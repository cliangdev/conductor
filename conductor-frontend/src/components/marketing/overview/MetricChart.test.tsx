import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MetricChart, changeMarkerLabel, markersOnSeries, targetBelongsOnDailyChart } from './MetricChart'
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

describe('MetricChart change markers', () => {
  const live = { day: '2026-09-03', label: 'RE-12 New onboarding (live since 2026-09-03)' }

  it('lists a marker inside the series with its accessible label and counts it in the chart summary', () => {
    render(<MetricChart metric={metric()} markers={[live]} />)
    const list = screen.getByRole('list', { name: 'Changes marked on this chart' })
    expect(list).toHaveTextContent('RE-12 New onboarding (live since 2026-09-03)')
    expect(screen.getByRole('img').getAttribute('aria-label')).toMatch(/, 1 change marked$/)
  })

  it('leaves out a marker for a day the chart does not show', () => {
    render(<MetricChart metric={metric()} markers={[{ day: '2026-08-01', label: 'RE-1 Old (live since 2026-08-01)' }]} />)
    expect(screen.queryByRole('list')).not.toBeInTheDocument()
    expect(screen.getByRole('img').getAttribute('aria-label')).not.toMatch(/marked/)
  })

  it('puts two changes on the same day into one marker', () => {
    const groups = markersOnSeries(
      [live, { day: '2026-09-03', label: 'RE-13 Other (live since 2026-09-03)' }],
      metric().series,
    )
    expect(groups).toEqual([{ day: '2026-09-03', labels: [live.label, 'RE-13 Other (live since 2026-09-03)'] }])
    render(<MetricChart metric={metric()} markers={[live, { day: '2026-09-03', label: 'RE-13 Other (live since 2026-09-03)' }]} />)
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getByRole('img').getAttribute('aria-label')).toMatch(/, 2 changes marked$/)
  })

  it('renders exactly the same without the markers prop, with none, or with none in range', () => {
    const plain = render(<MetricChart metric={metric()} />).container.innerHTML
    const empty = render(<MetricChart metric={metric()} markers={[]} />).container.innerHTML
    const outside = render(<MetricChart metric={metric()} markers={[{ day: '2025-01-01', label: 'x' }]} />).container.innerHTML
    expect(empty).toBe(plain)
    expect(outside).toBe(plain)
  })

  it('writes the label as "RE-12 Title (live since 2026-10-01)"', () => {
    expect(changeMarkerLabel({ day: '2026-10-01', changeId: 'c1', displayId: 'RE-12', title: 'Title', status: 'LIVE' })).toBe(
      'RE-12 Title (live since 2026-10-01)',
    )
  })
})
