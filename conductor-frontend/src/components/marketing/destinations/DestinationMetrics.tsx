// What a published destination did, on the row itself: the counters the connection's post-metrics feed
// files (views, likes, comments, shares where the platform reports them), plus the totals line and the
// "X doesn't report Y" footnote the panel shows under every row.

import { humanizeId } from '@/lib/workflows'
import { timeAgo } from '@/lib/format'

export interface PublishMetricSnapshot {
  observedAt: string
  views?: number | null
  likes?: number | null
  comments?: number | null
  shares?: number | null
  saves?: number | null
  reach?: number | null
  impressions?: number | null
  watchTimeSeconds?: number | null
  unavailable?: boolean
}

export interface PublishMetricsTarget {
  targetId: string
  platform: string
  accountLabel?: string | null
  permalink?: string | null
  latest: PublishMetricSnapshot
  series: PublishMetricSnapshot[]
}

export interface PublishMetricsResponse {
  workItemId: string
  targets: PublishMetricsTarget[]
  totals?: PublishMetricSnapshot | null
}

export const COLUMNS: { key: keyof PublishMetricSnapshot; label: string }[] = [
  { key: 'views', label: 'Views' },
  { key: 'likes', label: 'Likes' },
  { key: 'comments', label: 'Comments' },
  { key: 'shares', label: 'Shares' },
]

/**
 * What each platform hands out through its public API without extra permissions. A dash is the
 * platform not reporting it, never a zero, and this is what lets the footnote say so in words.
 */
export const REPORTED: Record<string, ReadonlySet<keyof PublishMetricSnapshot>> = {
  tiktok: new Set(['views', 'likes', 'comments', 'shares']),
  youtube: new Set(['views', 'likes', 'comments']),
  facebook: new Set(['likes', 'comments', 'shares']),
  instagram: new Set(['likes', 'comments']),
}

/** "Facebook doesn't report views; Instagram doesn't report views or shares." for the platforms present. */
export function unreportedNote(platforms: string[]): string | null {
  const parts: string[] = []
  for (const platform of [...new Set(platforms.map((p) => p.toLowerCase()))]) {
    const reported = REPORTED[platform]
    if (!reported) continue
    const missing = COLUMNS.filter((c) => !reported.has(c.key)).map((c) => c.label.toLowerCase())
    if (missing.length === 0) continue
    const list = missing.length === 1 ? missing[0] : `${missing.slice(0, -1).join(', ')} or ${missing.at(-1)}`
    parts.push(`${humanizeId(platform)} doesn't report ${list}`)
  }
  return parts.length === 0 ? null : parts.join('; ') + '.'
}

export function count(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return new Intl.NumberFormat().format(value)
}

/** 1234 → "1.2K", 1200000 → "1.2M"; small numbers as they are. For the row, where space is short. */
export function compactCount(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  try {
    return new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }).format(value)
  } catch {
    return String(value)
  }
}

/** "1.2K views · 84 likes · 12 comments" — only the counters this platform reports, dashes for gaps. */
export function DestinationMetrics({ metrics }: { metrics: PublishMetricsTarget }) {
  if (metrics.latest?.unavailable) {
    return <span className="text-xs text-muted-foreground">No longer on the platform</span>
  }
  const reported = REPORTED[metrics.platform.toLowerCase()]
  const parts = COLUMNS.filter((c) => !reported || reported.has(c.key)).map(
    (c) => `${compactCount(metrics.latest?.[c.key] as number | null | undefined)} ${c.label.toLowerCase()}`
  )
  if (parts.length === 0) return null
  return (
    <span className="whitespace-nowrap text-xs tabular-nums text-muted-foreground" data-testid="destination-metrics">
      {parts.join(' · ')}
    </span>
  )
}

/** The panel's footer once more than one destination has numbers: the totals, then the footnote. */
export function DestinationTotals({
  totals,
  observedAt,
  note,
}: {
  totals: PublishMetricSnapshot | null | undefined
  observedAt: string | null
  note: string | null
}) {
  if (!totals && !note) return null
  return (
    <div className="space-y-1 px-4 py-2.5 text-xs text-muted-foreground" data-testid="post-performance">
      {totals && (
        <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1">
          <span className="font-medium text-foreground">
            All destinations{observedAt ? <span className="font-normal text-muted-foreground"> · as of {timeAgo(observedAt)}</span> : null}
          </span>
          <span className="tabular-nums text-foreground">
            {COLUMNS.map((c) => `${compactCount(totals[c.key] as number | null | undefined)} ${c.label.toLowerCase()}`).join(' · ')}
          </span>
        </div>
      )}
      {note && <p>{note} A dash means the platform doesn&rsquo;t report that number.</p>}
    </div>
  )
}
