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
