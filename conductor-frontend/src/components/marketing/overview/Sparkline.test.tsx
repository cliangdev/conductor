import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'

const lineTypes: string[] = []

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  LineChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  YAxis: () => null,
  Line: ({ type }: { type: string }) => {
    lineTypes.push(type)
    return null
  },
}))

import { Sparkline } from './Sparkline'
import { series } from './test-fixtures'

describe('Sparkline', () => {
  it('draws daily data with straight segments, not a smoothed curve', () => {
    render(<Sparkline series={series([10, 12, 11, 14, 15], 1)} label="Clicks" />)
    expect(lineTypes.length).toBeGreaterThan(0)
    expect(lineTypes.every((t) => t === 'linear')).toBe(true)
  })
})
