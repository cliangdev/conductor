import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MismatchNotice } from './MismatchNotice'
import { channel, mismatch } from './test-fixtures'

describe('MismatchNotice', () => {
  it('shows both values, the gap and what to do', () => {
    render(
      <MismatchNotice
        projectId="proj-1"
        channel={channel({ channel: 'STORE', label: 'App Store', status: 'MISMATCH', mismatch: mismatch() })}
      />,
    )
    expect(screen.getByText(/Conductor 1,204 vs Product metrics 1,190 — 1\.2% apart over settled days \(Sep 1 – Sep 12\)/)).toBeInTheDocument()
    expect(screen.getByText(/Check that Product metrics and the App Store/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open product metrics' })).toHaveAttribute('href', '/app/projects/proj-1/integrations/product-metrics')
  })

  it('formats an ads spend mismatch as currency and names the source', () => {
    render(
      <MismatchNotice
        projectId="proj-1"
        sourceName="Rexipe backend"
        channel={channel({
          channel: 'ADS',
          label: 'Apple Ads',
          status: 'MISMATCH',
          mismatch: mismatch({ metric: 'spend', label: 'Spend', channelValue: 520, referenceValue: 480 }),
        })}
      />,
    )
    expect(screen.getByText(/Conductor \$520 vs Rexipe backend \$480/)).toBeInTheDocument()
  })

  it('renders nothing for a channel that matches', () => {
    const { container } = render(<MismatchNotice projectId="proj-1" channel={channel()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
