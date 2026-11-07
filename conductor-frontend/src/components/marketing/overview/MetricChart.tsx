'use client'

// A larger line chart for one metric on the channel detail page: provisional days dashed, and the target as a
// reference line when it shares the daily scale (levels and ratios; a window-total target stays in the header).
// Optional `markers` draw a thin vertical line on the day each change went live.

import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { chartAxis, formatMetricValue, shortDate } from './format'
import { isSparse, splitSeries } from './Sparkline'
import type { OverviewChangeMarker, OverviewMetric, OverviewSeriesPoint } from './types'

/** A window-total target (SUM) is on a different scale from one day's value, so only LEVEL and ratio metrics draw it. */
export function targetBelongsOnDailyChart(metric: OverviewMetric): boolean {
  return !!metric.target && (metric.aggregation === 'LEVEL' || metric.aggregation === 'DERIVED')
}

/** A day to mark on the chart and what to say about it. */
export interface ChartMarker {
  day: string
  label: string
}

/** "RE-12 Title (live since 2026-10-01)". */
export function changeMarkerLabel(change: OverviewChangeMarker): string {
  return `${change.displayId} ${change.title} (live since ${change.day})`
}

export interface MarkerDay {
  day: string
  labels: string[]
}

/** Markers on days the series shows (a line needs a day on the axis), one entry per day, in series order. */
export function markersOnSeries(markers: ChartMarker[], series: OverviewSeriesPoint[]): MarkerDay[] {
  const byDay = new Map<string, string[]>()
  for (const m of markers) byDay.set(m.day, [...(byDay.get(m.day) ?? []), m.label])
  return series.filter((p) => byDay.has(p.day)).map((p) => ({ day: p.day, labels: byDay.get(p.day) as string[] }))
}

export function MetricChart({ metric, markers }: { metric: OverviewMetric; markers?: ChartMarker[] }) {
  const marked = markersOnSeries(markers ?? [], metric.series)
  const markedCount = marked.reduce((n, m) => n + m.labels.length, 0)
  const drawTarget = targetBelongsOnDailyChart(metric)
  const data = splitSeries(metric.series)
  const sparse = isSparse(metric.series)
  const axis = chartAxis(metric.series.map((p) => p.value), metric.unit, drawTarget ? metric.target?.value : undefined, metric.aggregation !== 'LEVEL' && metric.aggregation !== 'DERIVED')
  const points = metric.series.filter((p) => p.value !== null && p.value !== undefined).length
  const summary = `${metric.label} by day, ${points} days of data${drawTarget && metric.target ? `, target ${formatMetricValue(metric.target.value, metric.unit)}` : ''}${markedCount > 0 ? `, ${markedCount} ${markedCount === 1 ? 'change' : 'changes'} marked` : ''}`
  if (points === 0) {
    return <p className="py-8 text-center text-sm text-muted-foreground">No daily data in this window yet.</p>
  }
  const chart = (
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
            labelFormatter={(d) => {
              const day = shortDate(String(d)) ?? String(d)
              const labels = marked.find((m) => m.day === String(d))?.labels
              if (!labels) return day
              return (
                <span>
                  {day}
                  {labels.map((l) => (
                    <span key={l} className="block text-xs">
                      {l}
                    </span>
                  ))}
                </span>
              )
            }}
            contentStyle={{
              background: 'hsl(var(--popover))',
              border: '1px solid hsl(var(--border))',
              borderRadius: 7,
              fontSize: 12,
              color: 'hsl(var(--foreground))',
            }}
          />
          {marked.map((m) => (
            <ReferenceLine key={m.day} x={m.day} stroke="hsl(var(--muted-foreground))" strokeOpacity={0.6} strokeWidth={1} ifOverflow="hidden" />
          ))}
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
  if (marked.length === 0) return chart
  return (
    <>
      {chart}
      <p className="mt-1 text-xs text-muted-foreground" aria-hidden="true">
        Vertical lines mark when changes went live. Hover a day to see which.
      </p>
      <ul aria-label="Changes marked on this chart" className="sr-only">
        {marked.flatMap((m) => m.labels).map((label) => (
          <li key={label}>{label}</li>
        ))}
      </ul>
    </>
  )
}
