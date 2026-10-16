import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MetricChart, targetBelongsOnDailyChart } from './MetricChart'
import { metric } from './test-fixtures'

const target = { value: 200, direction: 'UP' as const }

describe('MetricChart target line', () => {
  it('leaves a window-total target off a per-day SUM chart', () => {
    const m = metric({ aggregation: 'SUM', target })
    expect(targetBelongsOnDailyChart(m)).toBe(false)
    render(<MetricChart metric={m} />)
    expect(screen.getByRole('img').getAttribute('aria-label')).not.toMatch(/target/)
  })

  it.each(['LEVEL', 'DERIVED'] as const)('keeps the target for a %s metric', (aggregation) => {
    const m = metric({ aggregation, target })
    expect(targetBelongsOnDailyChart(m)).toBe(true)
    render(<MetricChart metric={m} />)
    expect(screen.getByRole('img').getAttribute('aria-label')).toMatch(/target 200/)
  })

  it('draws no target line without a target', () => {
    expect(targetBelongsOnDailyChart(metric({ aggregation: 'LEVEL', target: null }))).toBe(false)
  })

  it('treats a metric with no aggregation as a total', () => {
    expect(targetBelongsOnDailyChart(metric({ target }))).toBe(false)
  })
})
