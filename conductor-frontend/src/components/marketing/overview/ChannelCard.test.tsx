import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ChannelCard } from './ChannelCard'
import { channel, metric } from './test-fixtures'

function renderCard(ch = channel(), canEditTargets = true, onSetTarget = vi.fn()) {
  render(<ChannelCard projectId="proj-1" channel={ch} canEditTargets={canEditTargets} onSetTarget={onSetTarget} />)
  return onSetTarget
}

describe('ChannelCard', () => {
  it('links the whole card to the detail page and shows status and freshness', () => {
    renderCard()
    expect(screen.getByRole('link', { name: 'Search' })).toHaveAttribute('href', '/app/projects/proj-1/marketing/overview/search')
    expect(screen.getByText('Live')).toBeInTheDocument()
    expect(screen.getByText('Last updated 2h ago')).toBeInTheDocument()
  })

  it('shows a stale pill', () => {
    renderCard(channel({ status: 'STALE' }))
    expect(screen.getByText('Stale')).toBeInTheDocument()
  })

  it('offers a connect button for a not-connected channel', () => {
    renderCard(channel({ channel: 'ADS', label: 'Apple Ads', status: 'NOT_CONNECTED', metrics: [], connectHint: { connectorId: 'apple-search-ads' } }))
    expect(screen.getByText('Not connected')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Connect Apple Search Ads' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/integrations/apple-search-ads',
    )
  })

  it('shows a plain note when coming soon', () => {
    renderCard(channel({ channel: 'STORE', label: 'App Store', status: 'COMING_SOON', metrics: [], connectHint: null }))
    expect(screen.getByText('Coming soon')).toBeInTheDocument()
    expect(screen.getByText(/not available yet/)).toBeInTheDocument()
  })

  it('colours a good change green and a bad change red', () => {
    renderCard(
      channel({
        metrics: [
          metric({ metric: 'clicks', label: 'Clicks', change: 10, changePct: 0.1 }),
          metric({ metric: 'impressions', label: 'Impressions', change: -10, changePct: -0.1 }),
        ],
      }),
    )
    expect(screen.getByText('+10.0%').closest('span')).toHaveClass('text-status-done')
    expect(screen.getByText('-10.0%').closest('span')).toHaveClass('text-status-failed')
  })

  it('treats a falling position as good', () => {
    renderCard(channel({ metrics: [metric({ metric: 'position', label: 'Average position', unit: 'POSITION', goodDirection: 'DOWN', value: 8.2, change: -0.9, changePct: -0.099 })] }))
    expect(screen.getByText('8.2')).toBeInTheDocument()
    expect(screen.getByText('-9.9%').closest('span')).toHaveClass('text-status-done')
  })

  it('treats a rising cost per install as bad', () => {
    renderCard(channel({ metrics: [metric({ metric: 'cost_per_install', label: 'Cost per install', unit: 'CURRENCY', goodDirection: 'DOWN', value: 2.5, change: 0.5, changePct: 0.25 })] }))
    expect(screen.getByText('$2.50')).toBeInTheDocument()
    expect(screen.getByText('+25.0%').closest('span')).toHaveClass('text-status-failed')
  })

  it('mutes the change and notes a move within normal range', () => {
    renderCard(channel({ metrics: [metric({ noise: true, change: 5, changePct: 0.05 })] }))
    expect(screen.getByText('within normal range')).toBeInTheDocument()
    expect(screen.getByText('+5.0%').closest('span')).toHaveClass('text-muted-foreground')
  })

  it('shows the target line, or none with a Set target action', () => {
    const onSet = renderCard(
      channel({
        metrics: [
          metric({ metric: 'ctr', label: 'Click-through rate', unit: 'PERCENT', value: 0.031, target: { value: 0.04, direction: 'UP' } }),
          metric({ metric: 'clicks', label: 'Clicks' }),
        ],
      }),
    )
    expect(screen.getByText(/Target 4.0% · 78% there/)).toBeInTheDocument()
    expect(screen.getByText(/No target set/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set target for Clicks' }))
    expect(onSet).toHaveBeenCalledWith('SEARCH', expect.objectContaining({ metric: 'clicks' }))
  })

  it('hides target actions for people who cannot edit', () => {
    renderCard(channel(), false)
    expect(screen.queryByRole('button', { name: /target/i })).not.toBeInTheDocument()
    expect(screen.getByText(/No target set/)).toBeInTheDocument()
  })
})
