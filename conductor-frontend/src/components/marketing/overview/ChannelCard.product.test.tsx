import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { ChannelCard } from './ChannelCard'
import { channel, mismatch, productChannel } from './test-fixtures'

function renderCard(ch = productChannel(), onSetTarget = vi.fn()) {
  render(<ChannelCard projectId="proj-1" channel={ch} canEditTargets onSetTarget={onSetTarget} />)
  return onSetTarget
}

describe('ChannelCard for the In-app channel', () => {
  it('shows the headline metrics and links to the detail page', () => {
    renderCard()
    expect(screen.getByRole('link', { name: 'In-app' })).toHaveAttribute('href', '/app/projects/proj-1/marketing/overview/product')
    expect(screen.getByText('Trials started')).toBeInTheDocument()
    expect(screen.getByText('140')).toBeInTheDocument()
    expect(screen.getByText('Cost per trial')).toBeInTheDocument()
    expect(screen.getByText('$4.20')).toBeInTheDocument()
  })

  it('shows only the first four metrics', () => {
    renderCard()
    expect(screen.queryByText('Onboarding started')).not.toBeInTheDocument()
  })

  it('shows the target read-only with where it is set and no edit button', () => {
    renderCard()
    expect(screen.getByText('Set in Rexipe backend')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit target for Trials started' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Set target for Trials started' })).not.toBeInTheDocument()
    // the connection owns every In-app target: a metric without one says where it would be set
    expect(screen.queryByRole('button', { name: /target for/ })).not.toBeInTheDocument()
    expect(screen.getAllByText(/No target set · set in Rexipe backend/).length).toBeGreaterThan(0)
  })

  it('flags provisional days and a small sample on the metrics that have them', () => {
    renderCard()
    expect(screen.getAllByText('Provisional')).toHaveLength(1)
    expect(screen.getAllByText('Small sample')).toHaveLength(1)
  })

  it('does not flag other channels', () => {
    render(
      <ChannelCard
        projectId="proj-1"
        channel={channel({ metrics: [{ ...channel().metrics[0], provisionalDays: 2, insufficientData: true }] })}
        canEditTargets
        onSetTarget={vi.fn()}
      />,
    )
    expect(screen.queryByText('Provisional')).not.toBeInTheDocument()
    expect(screen.queryByText('Small sample')).not.toBeInTheDocument()
  })

  it('still offers Set target on a channel whose targets are editable', () => {
    const onSetTarget = vi.fn()
    render(<ChannelCard projectId="proj-1" channel={channel()} canEditTargets onSetTarget={onSetTarget} />)
    fireEvent.click(screen.getByRole('button', { name: 'Set target for Clicks' }))
    expect(onSetTarget).toHaveBeenCalled()
  })

  it('links a not-connected card to the product metrics integration', () => {
    renderCard(productChannel({ status: 'NOT_CONNECTED', metrics: [], connections: [], lastFetchedAt: null }))
    expect(screen.getByText('Not connected')).toBeInTheDocument()
    expect(screen.getByText(/trials, paid and cost per trial/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Connect Product metrics' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/integrations/product-metrics',
    )
  })

  it('shows a mismatch pill and keeps the values visible', () => {
    renderCard(channel({ channel: 'STORE', label: 'App Store', status: 'MISMATCH', mismatch: mismatch() }))
    expect(screen.getByText('Mismatch')).toBeInTheDocument()
    expect(screen.getByText(/Downloads differ from Rexipe backend by 1\.2%/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Details' })).toHaveAttribute('href', '/app/projects/proj-1/marketing/overview/store#mismatch')
    expect(screen.getByText('Clicks')).toBeInTheDocument()
  })
})
