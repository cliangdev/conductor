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

  it('lists every mismatch with both figures, the days and the source, and links to the ledger', () => {
    const backendOne = mismatch()
    const platformOne = mismatch({
      kind: 'PLATFORM',
      metric: 'spend',
      label: 'Spend',
      channelValue: 162.86,
      referenceValue: 180,
      gapPct: 0.0953,
      referenceSource: 'Apple Ads console',
      channelSource: 'Apple Search Ads',
      from: '2026-09-29',
      to: '2026-10-26',
    })
    render(
      <MismatchNotice
        projectId="proj-1"
        channel={channel({ channel: 'ADS', label: 'Ads', status: 'MISMATCH', mismatch: backendOne, mismatches: [backendOne, platformOne] })}
      />,
    )
    const items = screen.getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(items[0]).toHaveTextContent('App Store Connect reports 1,204 downloads; Product metrics (Rexipe backend) reports 1,190')
    expect(items[1]).toHaveTextContent(
      'Apple Search Ads reports $163 in spend; the platform read from Apple Ads console reports $180 — 9.5% apart for Sep 29 – Oct 26',
    )
    expect(screen.getByRole('link', { name: /verification ledger/i })).toHaveAttribute('href', '/app/projects/proj-1/marketing/verification')
  })

  it('names a platform read without a source as the platform read', () => {
    const platformOne = mismatch({ kind: 'PLATFORM', referenceSource: undefined })
    render(<MismatchNotice channel={channel({ status: 'MISMATCH', mismatch: platformOne, mismatches: [platformOne] })} />)
    expect(screen.getByText(/the platform read reports 1,190/)).toBeInTheDocument()
  })

  it('falls back to the single mismatch when the API sends no list, and when it sends an empty one', () => {
    const one = mismatch()
    const { rerender } = render(<MismatchNotice channel={channel({ status: 'MISMATCH', mismatch: one })} />)
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
    expect(screen.getByText(/App Store Connect reports 1,204 downloads/)).toBeInTheDocument()
    rerender(<MismatchNotice channel={channel({ status: 'MISMATCH', mismatch: one, mismatches: [] })} />)
    expect(screen.getByText(/App Store Connect reports 1,204 downloads/)).toBeInTheDocument()
  })

  it('has no ledger link without a project', () => {
    render(<MismatchNotice channel={channel({ status: 'MISMATCH', mismatch: mismatch() })} />)
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('renders nothing for a channel that matches', () => {
    const { container } = render(<MismatchNotice channel={channel()} />)
    expect(container).toBeEmptyDOMElement()
  })
})
