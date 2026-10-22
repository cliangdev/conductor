import { describe, expect, it } from 'vitest'
import { chartAxis, changeTone, conversionRate, dataThroughLabel, formatMetricValue, inputToTarget, targetLine, targetToInput } from './format'
import { metric } from './test-fixtures'

describe('formatMetricValue', () => {
  it('formats each unit', () => {
    expect(formatMetricValue(0.031, 'PERCENT')).toBe('3.1%')
    expect(formatMetricValue(8.24, 'POSITION')).toBe('8.2')
    expect(formatMetricValue(4.66, 'RATING')).toBe('4.7')
    expect(formatMetricValue(12.5, 'CURRENCY')).toBe('$12.50')
    expect(formatMetricValue(1234, 'COUNT')).toBe('1,234')
    expect(formatMetricValue(null, 'COUNT')).toBe('—')
  })
})

describe('changeTone', () => {
  it('colours a rise good and a fall bad for higher-is-better metrics', () => {
    expect(changeTone(metric({ change: 5 }))).toBe('good')
    expect(changeTone(metric({ change: -5 }))).toBe('bad')
  })

  it('flips for position and cost metrics where lower is better', () => {
    expect(changeTone(metric({ unit: 'POSITION', goodDirection: 'DOWN', change: -0.9 }))).toBe('good')
    expect(changeTone(metric({ unit: 'POSITION', goodDirection: 'DOWN', change: 0.9 }))).toBe('bad')
    expect(changeTone(metric({ unit: 'CURRENCY', goodDirection: 'DOWN', change: 1 }))).toBe('bad')
    expect(changeTone(metric({ unit: 'CURRENCY', goodDirection: 'DOWN', change: -1 }))).toBe('good')
  })

  it('stays neutral inside normal variation or with no change', () => {
    expect(changeTone(metric({ change: 50, noise: true }))).toBe('neutral')
    expect(changeTone(metric({ change: null }))).toBe('neutral')
  })
})

describe('targets', () => {
  it('converts a typed percentage to a fraction and back', () => {
    expect(inputToTarget('4', 'PERCENT')).toBe(0.04)
    expect(inputToTarget('4.5', 'PERCENT')).toBe(0.045)
    expect(targetToInput(0.04, 'PERCENT')).toBe('4')
    expect(inputToTarget('1000', 'COUNT')).toBe(1000)
  })

  it('rejects empty, negative and non-numeric input', () => {
    expect(inputToTarget('', 'COUNT')).toBeNull()
    expect(inputToTarget('-1', 'COUNT')).toBeNull()
    expect(inputToTarget('abc', 'COUNT')).toBeNull()
  })

  it('describes progress toward the target in either direction', () => {
    const up = metric({ unit: 'PERCENT', value: 0.031, target: { value: 0.04, direction: 'UP' } })
    expect(targetLine(up)).toBe('Target 4.0% · 78% there')
    const down = metric({ unit: 'POSITION', value: 8, target: { value: 4, direction: 'DOWN' } })
    expect(targetLine(down)).toBe('Target 4.0 · 50% there')
    expect(targetLine(metric())).toBeNull()
  })
})

describe('conversionRate', () => {
  it('divides stage by previous stage and guards zero or missing', () => {
    expect(conversionRate(10000, 500)).toBe(0.05)
    expect(conversionRate(0, 5)).toBeNull()
    expect(conversionRate(null, 5)).toBeNull()
  })
})

describe('target met (server without a status)', () => {
  it('says "On target" once an UP target is reached or passed', () => {
    const m = metric({ unit: 'PERCENT', value: 0.05, target: { value: 0.04, direction: 'UP' } })
    expect(targetLine(m)).toBe('On target · Target 4.0%')
    expect(targetLine({ ...m, value: 0.04 })).toBe('On target · Target 4.0%')
  })

  it('says "On target" once a DOWN target is reached, and not before', () => {
    const m = metric({ unit: 'POSITION', goodDirection: 'DOWN', value: 3.5, target: { value: 4, direction: 'DOWN' } })
    expect(targetLine(m)).toBe('On target · Target 4.0')
    expect(targetLine({ ...m, value: 8 })).toBe('Target 4.0 · 50% there')
  })
})

describe('target status', () => {
  const target = { value: 100, direction: 'UP' as const }

  it('labels each standing', () => {
    expect(targetLine(metric({ value: 100, status: 'ON_TRACK', missPct: 0, target }))).toBe('On target · Target 100')
    expect(targetLine(metric({ value: 92, status: 'WATCH', missPct: 0.08, target }))).toBe('Close · 8% short of 100')
    expect(targetLine(metric({ value: 40, status: 'OFF_TRACK', missPct: 0.6, target }))).toBe('Off target · 60% short of 100')
    expect(targetLine(metric({ value: 5, status: 'TOO_EARLY', target }))).toBe('Too early · Target 100')
  })

  it('says "over" for a DOWN target that is missed', () => {
    const down = { value: 10, direction: 'DOWN' as const }
    expect(targetLine(metric({ goodDirection: 'DOWN', value: 11, status: 'WATCH', missPct: 0.1, target: down }))).toBe(
      'Close · 10% over 10',
    )
  })

  it('names the period of a count total target', () => {
    const m = metric({ aggregation: 'SUM', value: 50, status: 'ON_TRACK', missPct: 0, target: { ...target, window: '28d' } })
    expect(targetLine(m)).toBe('On target · Target 100 per 28d')
    expect(targetLine({ ...m, unit: 'PERCENT', aggregation: 'DERIVED', value: 0.5, target: { value: 0.25, direction: 'UP', window: '28d' } })).toBe(
      'On target · Target 25.0%',
    )
  })

  it('has no line without a target', () => {
    expect(targetLine(metric({ status: 'NO_TARGET' }))).toBeNull()
  })
})

describe('dataThroughLabel', () => {
  it('is empty when nothing is connected', () => {
    expect(dataThroughLabel(null, null)).toBeNull()
  })

  it('shows one day, or a range when channels differ', () => {
    expect(dataThroughLabel('2026-10-12', '2026-10-12')).toBe('Oct 12')
    expect(dataThroughLabel('2026-10-11', '2026-10-12')).toBe('Oct 11–12')
    expect(dataThroughLabel('2026-10-30', '2026-11-02')).toBe('Oct 30 – Nov 2')
  })
})

describe('chartAxis', () => {
  it('starts a summed currency axis at zero when asked', () => {
    const padded = chartAxis([80, 100, 120, 140], 'CURRENCY')
    expect(padded.domain[0]).toBeGreaterThan(0)
    const axis = chartAxis([80, 100, 120, 140], 'CURRENCY', null, true)
    expect(axis.domain[0]).toBe(0)
    expect(axis.ticks[0]).toBe(0)
    expect(axis.domain[1]).toBeGreaterThanOrEqual(140)
  })

  it('never pads a rating axis beyond 5 stars', () => {
    const axis = chartAxis([4.6, 4.7, 4.8, 4.75], 'RATING')
    expect(axis.domain[1]).toBe(5)
    expect(Math.max(...axis.ticks)).toBe(5)
    const wide = chartAxis([1, 5], 'RATING')
    expect(wide.domain[1]).toBe(5)
    expect(wide.ticks.every((t) => t <= 5)).toBe(true)
  })

  it('uses whole-number ticks for a small count series', () => {
    const axis = chartAxis([0, 1, 0, 1], 'COUNT')
    expect(axis.ticks).toEqual([0, 1])
    const labels = axis.ticks.map(axis.format)
    expect(new Set(labels).size).toBe(labels.length)
    expect(chartAxis([0, 2], 'COUNT').ticks.every(Number.isInteger)).toBe(true)
  })

  it('gives every tick of a flat percent series a distinct label', () => {
    const axis = chartAxis([0.07, 0.0701, 0.07, 0.0699], 'PERCENT')
    const labels = axis.ticks.map(axis.format)
    expect(new Set(labels).size).toBe(labels.length)
  })

  it('pads a tight cost series to at least +-10% of the mean', () => {
    const axis = chartAxis([2.55, 2.57, 2.59, 2.56], 'CURRENCY')
    const mean = 2.5675
    expect(axis.domain[1] - axis.domain[0]).toBeGreaterThanOrEqual(mean * 0.2)
    const labels = axis.ticks.map(axis.format)
    expect(new Set(labels).size).toBe(labels.length)
  })

  it('starts counts at zero with whole-number ticks', () => {
    const axis = chartAxis([120, 340, 280], 'COUNT')
    expect(axis.domain[0]).toBe(0)
    expect(axis.domain[1]).toBeGreaterThanOrEqual(340)
    expect(axis.ticks.every(Number.isInteger)).toBe(true)
  })

  it('rounds the domain and step to 1, 2 or 5 x 10^n', () => {
    const axis = chartAxis([135, 190, 247, 160], 'CURRENCY')
    const step = axis.ticks[1] - axis.ticks[0]
    const mantissa = step / 10 ** Math.floor(Math.log10(step))
    expect([1, 2, 5]).toContain(Math.round(mantissa))
    expect(axis.ticks.every((t) => Math.abs(t / step - Math.round(t / step)) < 1e-9)).toBe(true)
    expect(axis.domain[0]).toBeLessThanOrEqual(135)
    expect(axis.domain[1]).toBeGreaterThanOrEqual(247)
    const labels = axis.ticks.map(axis.format)
    expect(new Set(labels).size).toBe(labels.length)
    expect(labels.every((l) => /^\$\d+$/.test(l))).toBe(true)
  })

  it('includes the target in the range', () => {
    const axis = chartAxis([1, 2, 3], 'COUNT', 50)
    expect(axis.domain[1]).toBeGreaterThanOrEqual(50)
  })
})
