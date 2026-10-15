'use client'

import { Line, LineChart, ResponsiveContainer, YAxis } from 'recharts'
import type { OverviewSeriesPoint } from './types'

interface Datum {
  day: string
  solid: number | null
  provisional: number | null
}

/** Splits a series into a solid line and a dashed line for provisional days. The last complete point is
 *  repeated on the dashed line so the two segments join. */
export function splitSeries(series: OverviewSeriesPoint[]): Datum[] {
  return series.map((p, i) => {
    const value = p.value ?? null
    const prov = !!p.provisional
    const nextProv = !!series[i + 1]?.provisional
    return {
      day: p.day,
      solid: prov ? null : value,
      provisional: prov || (nextProv && !prov) ? value : null,
    }
  })
}

export function Sparkline({ series, label }: { series: OverviewSeriesPoint[]; label: string }) {
  const data = splitSeries(series)
  if (data.every((d) => d.solid === null && d.provisional === null)) return null
  return (
    <div role="img" aria-label={`${label} trend`} className="h-8 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 2, right: 2, bottom: 2, left: 2 }}>
          <YAxis hide domain={['auto', 'auto']} />
          <Line
            type="linear"
            dataKey="solid"
            stroke="hsl(var(--primary))"
            strokeWidth={1.5}
            dot={false}
            isAnimationActive={false}
          />
          <Line
            type="linear"
            dataKey="provisional"
            stroke="hsl(var(--primary))"
            strokeOpacity={0.5}
            strokeWidth={1.5}
            strokeDasharray="3 3"
            dot={false}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
