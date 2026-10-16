import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MetricFlags, metricFlags } from './MetricFlags'

describe('MetricFlags', () => {
  it('shows nothing when the metric is settled and well sampled', () => {
    const { container } = render(<MetricFlags metric={{ provisionalDays: 0, insufficientData: false }} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows each flag on its own', () => {
    const { rerender } = render(<MetricFlags metric={{ provisionalDays: 3 }} />)
    expect(screen.getByText('Provisional')).toBeInTheDocument()
    expect(screen.queryByText('Small sample')).not.toBeInTheDocument()
    rerender(<MetricFlags metric={{ insufficientData: true }} />)
    expect(screen.getByText('Small sample')).toBeInTheDocument()
    expect(screen.queryByText('Provisional')).not.toBeInTheDocument()
  })

  it('reads both flags from a metric', () => {
    expect(metricFlags({ provisionalDays: 1, insufficientData: true })).toEqual({ provisional: true, smallSample: true })
    expect(metricFlags({})).toEqual({ provisional: false, smallSample: false })
  })
})
