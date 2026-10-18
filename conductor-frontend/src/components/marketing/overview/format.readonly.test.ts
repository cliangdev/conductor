import { describe, expect, it } from 'vitest'
import { channel } from './test-fixtures'
import { dataThroughSummary, mismatchCardLine, mismatchSummary, readOnlyTargetSource } from './format'

describe('read-only targets and mismatches', () => {
  it('names where a read-only target is set', () => {
    expect(readOnlyTargetSource({ value: 1, direction: 'UP', readOnly: true, source: 'Set in Rexipe' })).toBe('Set in Rexipe')
    expect(readOnlyTargetSource({ value: 1, direction: 'UP', readOnly: true, source: 'Rexipe' })).toBe('Set in Rexipe')
    expect(readOnlyTargetSource({ value: 1, direction: 'UP', readOnly: true })).toBe('Set in the connected source')
  })

  it('summarises a mismatch with both sources, both values, the gap and the days', () => {
    expect(
      mismatchSummary(
        { metric: 'downloads', label: 'Downloads', referenceValue: 3504, channelValue: 4999, gapPct: 0.299, from: '2026-09-15', to: '2026-10-12', channelSource: 'App Store Connect', referenceSource: 'Rexipe' },
        'App Store',
      ),
    ).toBe('App Store Connect reports 4,999 downloads; Product metrics (Rexipe) reports 3,504 — 29.9% apart for Sep 15 – Oct 12, the days both have finished reporting.')
    expect(
      mismatchSummary(
        { metric: 'spend', label: 'Spend', referenceValue: 480, channelValue: 520, gapPct: 0.077, from: '2026-09-05', to: '2026-09-05' },
        'Apple Ads',
      ),
    ).toBe('Apple Ads reports $520 in spend; Product metrics reports $480 — 7.7% apart for Sep 5, the days both have finished reporting.')
  })

  it('words the card line with the reference source', () => {
    expect(mismatchCardLine({ metric: 'downloads', label: 'Downloads', referenceValue: 1, channelValue: 2, gapPct: 0.299, from: 'a', to: 'b', referenceSource: 'Rexipe' })).toBe('Downloads differ from Rexipe by 29.9%')
  })
})

describe('dataThroughSummary', () => {
  it('names the earliest channel when channels end on different days', () => {
    const channels = [
      channel({ label: 'Web', dataCompleteThrough: '2026-10-15' }),
      channel({ label: 'App Store', dataCompleteThrough: '2026-10-11' }),
      channel({ label: 'Ads', status: 'NOT_CONNECTED', dataCompleteThrough: '2026-10-15' }),
    ]
    expect(dataThroughSummary(channels, '2026-10-15')).toBe('Oct 11 (App Store) – Oct 15')
  })

  it('shows one day when every reporting channel agrees, ignoring unconnected ones', () => {
    const channels = [
      channel({ dataCompleteThrough: '2026-10-15' }),
      channel({ status: 'NOT_CONNECTED', dataCompleteThrough: '2026-10-01' }),
    ]
    expect(dataThroughSummary(channels, null)).toBe('Oct 15')
  })

  it('leaves a stale channel out of the earliest day', () => {
    const channels = [
      channel({ label: 'Web', status: 'STALE', dataCompleteThrough: '2026-09-18' }),
      channel({ label: 'App Store', dataCompleteThrough: '2026-10-14' }),
      channel({ label: 'In-app', dataCompleteThrough: '2026-10-17' }),
    ]
    expect(dataThroughSummary(channels, '2026-10-14')).toBe('Oct 14 (App Store) – Oct 17')
  })

  it('keeps stale channels when every reporting channel is stale', () => {
    const channels = [channel({ label: 'Web', status: 'STALE', dataCompleteThrough: '2026-09-18' })]
    expect(dataThroughSummary(channels, null)).toBe('Sep 18')
  })

  it('falls back to the window end with nothing reporting', () => {
    expect(dataThroughSummary([], '2026-10-14')).toBe('Oct 14')
  })
})
