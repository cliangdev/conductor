import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MetricFlags, metricFlags } from './MetricFlags'

describe('MetricFlags', () => {
  it('shows nothing when the metric is settled and well sampled', () => {
    const { container } = render(<MetricFlags metric={{ insufficientData: false }} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('shows only the small-sample flag, never a provisional one', () => {
    const { rerender } = render(<MetricFlags metric={{ provisionalDays: 3 } as never} />)
    expect(screen.queryByText('Provisional')).not.toBeInTheDocument()
    expect(screen.queryByText('Small sample')).not.toBeInTheDocument()
    rerender(<MetricFlags metric={{ insufficientData: true }} />)
    expect(screen.getByText('Small sample')).toBeInTheDocument()
    expect(screen.queryByText('Provisional')).not.toBeInTheDocument()
  })

  it('drops the chip when the status already says Too early', () => {
    const { container } = render(<MetricFlags metric={{ insufficientData: true, status: 'TOO_EARLY' }} />)
    expect(container).toBeEmptyDOMElement()
    render(<MetricFlags metric={{ insufficientData: true, status: 'ON_TRACK' }} />)
    expect(screen.getByText('Small sample')).toBeInTheDocument()
  })

  it('reads the flag from a metric', () => {
    expect(metricFlags({ insufficientData: true })).toEqual({ smallSample: true })
    expect(metricFlags({})).toEqual({ smallSample: false })
  })
})
