import { describe, expect, it } from 'vitest'
import { mismatchSummary, readOnlyTargetSource } from './format'

describe('read-only targets and mismatches', () => {
  it('names where a read-only target is set', () => {
    expect(readOnlyTargetSource({ value: 1, direction: 'UP', readOnly: true, source: 'Set in Rexipe' })).toBe('Set in Rexipe')
    expect(readOnlyTargetSource({ value: 1, direction: 'UP', readOnly: true, source: 'Rexipe' })).toBe('Set in Rexipe')
    expect(readOnlyTargetSource({ value: 1, direction: 'UP', readOnly: true })).toBe('Set in the connected source')
  })

  it('summarises a mismatch with both values, the gap and the days', () => {
    expect(
      mismatchSummary(
        { metric: 'downloads', label: 'Downloads', referenceValue: 1190, channelValue: 1204, gapPct: 0.0116, from: '2026-09-01', to: '2026-09-12' },
        'Rexipe',
      ),
    ).toBe('Conductor 1,204 vs Rexipe 1,190 — 1.2% apart over settled days (Sep 1 – Sep 12)')
    expect(
      mismatchSummary(
        { metric: 'spend', label: 'Spend', referenceValue: 480, channelValue: 520, gapPct: 0.077, from: '2026-09-05', to: '2026-09-05' },
        'Rexipe',
      ),
    ).toBe('Conductor $520 vs Rexipe $480 — 7.7% apart over settled days (Sep 5)')
  })
})
