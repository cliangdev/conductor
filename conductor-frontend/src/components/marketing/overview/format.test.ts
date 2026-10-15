import { describe, expect, it } from 'vitest'
import { changeTone, conversionRate, formatMetricValue, goodDirection, inputToTarget, targetLine, targetToInput } from './format'
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
    expect(goodDirection(metric({ metric: 'position', unit: 'POSITION' }))).toBe('DOWN')
    expect(changeTone(metric({ metric: 'position', unit: 'POSITION', change: -0.9 }))).toBe('good')
    expect(changeTone(metric({ metric: 'position', unit: 'POSITION', change: 0.9 }))).toBe('bad')
    expect(changeTone(metric({ metric: 'cost_per_install', unit: 'CURRENCY', change: 1 }))).toBe('bad')
    expect(changeTone(metric({ metric: 'cost_per_install', unit: 'CURRENCY', change: -1 }))).toBe('good')
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
