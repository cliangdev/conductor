import { describe, expect, it } from 'vitest'
import { chartAxis, changeTone, conversionRate, dataThroughLabel, formatMetricValue, inputToTarget, targetLine, targetToInput } from './format'
import { metric } from './test-fixtures'

describe('formatMetricValue', () => {
  it('formats each unit', () => {
    expect(formatMetricValue(0.031, 'PERCENT')).toBe('3.1%')
    expect(formatMetricValue(8.24, 'POSITION')).toBe('8.2')
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

describe('target met', () => {
  it('says "Target met" once an UP target is reached or passed', () => {
    const m = metric({ unit: 'PERCENT', value: 0.05, target: { value: 0.04, direction: 'UP' } })
    expect(targetLine(m)).toBe('Target met (4.0%)')
    expect(targetLine({ ...m, value: 0.04 })).toBe('Target met (4.0%)')
  })

  it('says "Target met" once a DOWN target is reached, and not before', () => {
    const m = metric({ unit: 'POSITION', goodDirection: 'DOWN', value: 3.5, target: { value: 4, direction: 'DOWN' } })
    expect(targetLine(m)).toBe('Target met (4.0)')
    expect(targetLine({ ...m, value: 8 })).toBe('Target 4.0 · 50% there')
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

  it('includes the target in the range', () => {
    const axis = chartAxis([1, 2, 3], 'COUNT', 50)
    expect(axis.domain[1]).toBeGreaterThanOrEqual(50)
  })
})
