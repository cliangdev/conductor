import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { FunnelStrip } from './FunnelStrip'
import { productChannel, productFunnelStages } from './test-fixtures'

function renderFunnel(stages = productFunnelStages()) {
  render(<FunnelStrip stages={stages} productMetrics={productChannel().metrics} />)
}

describe('FunnelStrip with in-app stages', () => {
  it('shows the in-app stages after downloads, each converting from the stage before', () => {
    renderFunnel()
    const labels = screen.getAllByRole('listitem').map((li) => li.querySelector('p')?.textContent)
    expect(labels).toEqual(['Downloads', 'First opens', 'Onboarding started', 'Trials started', 'Paid'])
    expect(screen.getByText('90.0% of downloads')).toBeInTheDocument()
    expect(screen.getByText('25.0% of onboarding started')).toBeInTheDocument()
  })

  it('flags provisional and small-sample stages', () => {
    renderFunnel()
    expect(screen.getByText('Provisional')).toBeInTheDocument()
    expect(screen.getByText('Small sample')).toBeInTheDocument()
  })

  it('shows a read-only target with where it is set', () => {
    renderFunnel()
    expect(screen.getByText(/Target 200/)).toBeInTheDocument()
    expect(screen.getByText(/Set in Rexipe backend/)).toBeInTheDocument()
  })

  it('shows no flags without in-app metrics', () => {
    render(<FunnelStrip stages={productFunnelStages()} />)
    expect(screen.queryByText('Provisional')).not.toBeInTheDocument()
    expect(screen.queryByText('Small sample')).not.toBeInTheDocument()
  })

  it('keeps the placeholder tile when the in-app source is not connected', () => {
    render(
      <FunnelStrip
        stages={[
          ...productFunnelStages().slice(0, 1),
          {
            key: 'product_stages',
            label: 'In-app stages (first open → paid)',
            source: 'PRODUCT',
            path: 'PRODUCT',
            available: false,
            unavailableReason: 'Connect product metrics',
          },
        ]}
      />,
    )
    expect(screen.getByText('Connect product metrics')).toBeInTheDocument()
  })
})
