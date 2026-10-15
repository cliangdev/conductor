import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'

const lines: { type: string; dot: boolean }[] = []

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  LineChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  CartesianGrid: () => null,
  XAxis: () => null,
  YAxis: () => null,
  Tooltip: () => null,
  ReferenceLine: () => null,
  Line: ({ type, dot }: { type: string; dot: boolean }) => {
    lines.push({ type, dot })
    return null
  },
}))

import { MetricChart } from './MetricChart'
import { Sparkline, isSparse } from './Sparkline'
import { metric, series } from './test-fixtures'

describe('Sparkline', () => {
  it('draws daily data with straight segments, not a smoothed curve', () => {
    lines.length = 0
    render(<Sparkline series={series([10, 12, 11, 14, 15], 1)} label="Clicks" />)
    expect(lines.length).toBeGreaterThan(0)
    expect(lines.every((l) => l.type === 'linear')).toBe(true)
  })

  it('draws no dots on a full series', () => {
    lines.length = 0
    render(<Sparkline series={series([10, 12, 11])} label="Clicks" />)
    expect(lines.every((l) => l.dot === false)).toBe(true)
  })

  it('draws a dot on every point when the series has fewer than three', () => {
    lines.length = 0
    render(<Sparkline series={series([10831])} label="Followers" />)
    expect(lines.length).toBeGreaterThan(0)
    expect(lines.every((l) => l.dot === true)).toBe(true)
  })
})

describe('MetricChart', () => {
  it('draws a dot on a single-snapshot series, and none on a full one', () => {
    lines.length = 0
    render(<MetricChart metric={metric({ series: series([10831]) })} />)
    expect(lines.length).toBeGreaterThan(0)
    expect(lines.every((l) => l.dot === true)).toBe(true)

    lines.length = 0
    render(<MetricChart metric={metric({ series: series([1, 2, 3, 4]) })} />)
    expect(lines.every((l) => l.dot === false)).toBe(true)
  })
})

describe('isSparse', () => {
  it('counts only points that have a value', () => {
    expect(isSparse(series([null, 5, null, null]))).toBe(true)
    expect(isSparse(series([1, 2]))).toBe(true)
    expect(isSparse(series([1, 2, 3]))).toBe(false)
  })
})
