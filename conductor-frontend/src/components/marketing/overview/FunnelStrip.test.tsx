import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { FunnelStrip } from './FunnelStrip'
import { overviewResponse } from './test-fixtures'

describe('FunnelStrip', () => {
  it('shows step-to-step conversion between available stages', () => {
    render(<FunnelStrip stages={overviewResponse().funnel} />)
    expect(screen.getByText('5.0% of impressions')).toBeInTheDocument()
    expect(screen.getByText('80.0% of clicks')).toBeInTheDocument()
  })

  it('shows no conversion where the API names no source stage', () => {
    const stages = overviewResponse().funnel.map((st) => ({ ...st, conversionFromKey: null }))
    render(<FunnelStrip stages={stages} />)
    expect(screen.queryByText(/% of /)).not.toBeInTheDocument()
  })

  it('collapses consecutive unavailable stages with one reason into a single muted tile', () => {
    const reason = 'Connect the store (coming soon)'
    const gone = (key: string, label: string) => ({
      key, label, value: null, source: 'STORE', path: 'STORE', available: false, unavailableReason: reason,
    })
    render(
      <FunnelStrip
        stages={[
          ...overviewResponse().funnel.slice(0, 1),
          gone('store_impressions', 'Impressions'),
          gone('store_page_views', 'Page views'),
          gone('store_downloads', 'Downloads'),
        ]}
        channelLabels={{ STORE: 'App Store' }}
      />,
    )
    expect(screen.getByText('App Store: impressions → page views → downloads')).toBeInTheDocument()
    expect(screen.getAllByText(reason)).toHaveLength(1)
  })

  it('lays the strip out as rows on narrow screens and columns on wide ones', () => {
    render(<FunnelStrip stages={overviewResponse().funnel} />)
    const row = screen.getByText('Impressions').closest('li')
    expect(row).toHaveClass('flex', 'lg:block')
  })

  it('mutes an unavailable stage with its reason and shows no conversion for it', () => {
    render(<FunnelStrip stages={overviewResponse().funnel} />)
    expect(screen.getByText('No product data source yet')).toBeInTheDocument()
    expect(screen.queryByText(/of visitors/)).not.toBeInTheDocument()
  })

  it('explains complete and provisional days', () => {
    render(<FunnelStrip stages={overviewResponse().funnel} />)
    expect(screen.getByRole('img', { name: /may still be reported/ })).toBeInTheDocument()
  })
})
