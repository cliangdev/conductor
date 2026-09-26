// "Best times" — two small bar charts plotting engagement rate by UTC hour of day and by UTC
// weekday. One accent series, no legend (a single series needs none), posts count in the tooltip.

import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { humanizeId } from '@/lib/workflows'
import { engagementRateLabel, type InsightsGroup } from './types'

interface ChartRow {
  label: string
  engagementRatePct: number | null
  posts: number
}

function toHourLabel(key: string): string {
  return `${key}h`
}

function toWeekdayLabel(key: string): string {
  return humanizeId(key).slice(0, 3)
}

function toRows(groups: InsightsGroup[], labelFor: (key: string) => string): ChartRow[] {
  return groups.map((g) => ({
    label: labelFor(g.key),
    engagementRatePct: g.engagementRate === null || g.engagementRate === undefined ? null : g.engagementRate * 100,
    posts: g.posts,
  }))
}

function BestTimesChart({ title, rows, emptyMessage }: { title: string; rows: ChartRow[]; emptyMessage: string }) {
  return (
    <div className="min-w-0 flex-1">
      <h4 className="mb-2 text-xs font-medium text-muted-foreground">{title}</h4>
      {rows.length === 0 ? (
        <div className="flex h-40 items-center justify-center rounded-md border border-dashed border-border text-sm text-muted-foreground">
          {emptyMessage}
        </div>
      ) : (
        <div className="h-40">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} margin={{ top: 4, right: 4, bottom: 0, left: 0 }}>
              <CartesianGrid vertical={false} stroke="hsl(var(--border))" />
              <XAxis
                dataKey="label"
                tick={{ fontSize: 10, fill: 'hsl(var(--muted-foreground))' }}
                tickLine={false}
                axisLine={false}
                interval="preserveStartEnd"
              />
              <YAxis hide domain={[0, 'dataMax']} />
              <Tooltip
                cursor={{ fill: 'hsl(var(--muted))' }}
                contentStyle={{
                  background: 'hsl(var(--popover))',
                  border: '1px solid hsl(var(--border))',
                  borderRadius: 6,
                  fontSize: 12,
                }}
                formatter={(value, _name, item) => {
                  const posts = (item?.payload as ChartRow | undefined)?.posts ?? 0
                  return [
                    `${engagementRateLabel(Number(value) / 100)} · ${posts} post${posts === 1 ? '' : 's'}`,
                    'Engagement rate',
                  ]
                }}
              />
              <Bar dataKey="engagementRatePct" fill="hsl(var(--primary))" radius={[3, 3, 0, 0]} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </div>
  )
}

export function InsightsBestTimes({ byHour, byWeekday }: { byHour: InsightsGroup[]; byWeekday: InsightsGroup[] }) {
  return (
    <div>
      <div className="mb-2 flex items-baseline justify-between">
        <h3 className="text-sm font-semibold text-foreground">Best times</h3>
        <span className="text-xs text-muted-foreground">Hours are UTC</span>
      </div>
      <div className="flex flex-col gap-6 sm:flex-row">
        <BestTimesChart
          title="By hour of day"
          rows={toRows(byHour, toHourLabel)}
          emptyMessage="Not enough posts yet to break down by hour."
        />
        <BestTimesChart
          title="By weekday"
          rows={toRows(byWeekday, toWeekdayLabel)}
          emptyMessage="Not enough posts yet to break down by weekday."
        />
      </div>
    </div>
  )
}
