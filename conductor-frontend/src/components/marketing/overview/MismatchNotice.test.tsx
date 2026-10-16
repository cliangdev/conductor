import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MismatchNotice } from './MismatchNotice'
import { channel, mismatch } from './test-fixtures'

describe('MismatchNotice', () => {
  it('names both sources, shows both figures and what to check', () => {
    render(<MismatchNotice channel={channel({ channel: 'STORE', label: 'App Store', status: 'MISMATCH', mismatch: mismatch() })} />)
    expect(
      screen.getByText(
        'App Store Connect reports 1,204 downloads; Product metrics (Rexipe backend) reports 1,190 — 1.2% apart for Sep 1 – Sep 12, the days both have finished reporting.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(/one side is missing days or counts days in a different time zone/)).toBeInTheDocument()
    expect(screen.getByText(/Check both connections.*last sync, then compare a single day/)).toBeInTheDocument()
    expect(document.getElementById('mismatch')).not.toBeNull()
    expect(screen.queryByText(/settled/)).not.toBeInTheDocument()
  })

  it('formats an ads spend mismatch as currency', () => {
    render(
      <MismatchNotice
        channel={channel({
          channel: 'ADS',
          label: 'Apple Ads',
          status: 'MISMATCH',
          mismatch: mismatch({ metric: 'spend', label: 'Spend', channelValue: 520, referenceValue: 480, channelSource: 'Apple Search Ads' }),
        })}
      />,
    )
    expect(screen.getByText(/Apple Search Ads reports \$520 in spend; Product metrics \(Rexipe backend\) reports \$480/)).toBeInTheDocument()
  })

  it('falls back to the channel name when the API names no source', () => {
    render(
      <MismatchNotice
        channel={channel({ label: 'App Store', status: 'MISMATCH', mismatch: mismatch({ channelSource: undefined, referenceSource: undefined }) })}
      />,
    )
    expect(screen.getByText(/^App Store reports 1,204 downloads; Product metrics reports 1,190/)).toBeInTheDocument()
  })

  it('renders nothing for a channel that matches', () => {
    const { container } = render(<MismatchNotice channel={channel()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
