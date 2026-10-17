'use client'

// A larger line chart for one metric on the channel detail page: provisional days dashed, and the target as a
// reference line when it shares the daily scale (levels and ratios; a window-total target stays in the header).

import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { chartAxis, formatMetricValue, shortDate } from './format'
import { isSparse, splitSeries } from './Sparkline'
import type { OverviewMetric } from './types'

/** A window-total target (SUM) is on a different scale from one day's value, so only LEVEL and ratio metrics draw it. */
export function targetBelongsOnDailyChart(metric: OverviewMetric): boolean {
  return !!metric.target && (metric.aggregation === 'LEVEL' || metric.aggregation === 'DERIVED')
}

export function MetricChart({ metric }: { metric: OverviewMetric }) {
  const drawTarget = targetBelongsOnDailyChart(metric)
  const data = splitSeries(metric.series)
  const sparse = isSparse(metric.series)
  const axis = chartAxis(metric.series.map((p) => p.value), metric.unit, drawTarget ? metric.target?.value : undefined, metric.aggregation !== 'LEVEL' && metric.aggregation !== 'DERIVED')
  const points = metric.series.filter((p) => p.value !== null && p.value !== undefined).length
  const summary = `${metric.label} by day, ${points} days of data${drawTarget && metric.target ? `, target ${formatMetricValue(metric.target.value, metric.unit)}` : ''}`
  if (points === 0) {
    return <p className="py-8 text-center text-sm text-muted-foreground">No daily data in this window yet.</p>
  }
  return (
    <div role="img" aria-label={summary} className="h-52 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid stroke="hsl(var(--border))" strokeDasharray="3 3" vertical={false} />
          <XAxis
            dataKey="day"
            tickFormatter={(d: string) => shortDate(d) ?? d}
            tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
            tickLine={false}
            axisLine={false}
            interval="preserveStartEnd"
            minTickGap={24}
          />
          <YAxis
            width={56}
            ticks={axis.ticks}
            tickFormatter={axis.format}
            tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }}
            tickLine={false}
            axisLine={false}
            domain={axis.domain}
            allowDataOverflow
          />
          <Tooltip
            formatter={(v) => formatMetricValue(typeof v === 'number' ? v : null, metric.unit)}
            labelFormatter={(d) => shortDate(String(d)) ?? String(d)}
            contentStyle={{
              background: 'hsl(var(--popover))',
              border: '1px solid hsl(var(--border))',
              borderRadius: 7,
              fontSize: 12,
              color: 'hsl(var(--foreground))',
            }}
          />
          {drawTarget && metric.target && (
            <ReferenceLine
              y={metric.target.value}
              stroke="hsl(var(--status-progress))"
              strokeDasharray="4 4"
              label={{ value: 'Target', position: 'insideTopRight', fontSize: 11, fill: 'hsl(var(--status-progress))' }}
            />
          )}
          <Line
            type="linear"
            dataKey="solid"
            name={metric.label}
            stroke="hsl(var(--primary))"
            strokeWidth={2}
            dot={sparse}
            isAnimationActive={false}
          />
          <Line
            type="linear"
            dataKey="provisional"
            name={`${metric.label} (still updating)`}
            stroke="hsl(var(--primary))"
            strokeOpacity={0.5}
            strokeWidth={2}
            strokeDasharray="4 4"
            dot={sparse}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}
