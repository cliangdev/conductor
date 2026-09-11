'use client'

// What a published Post did, per destination: the counters the connection's post-metrics feed files
// (views, likes, comments, shares where the platform reports them). Renders once there is something to
// show or the Post has finished publishing; before that there is nothing a person could act on.

import { useCallback, useEffect, useState } from 'react'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { humanizeId, statusMeta } from '@/lib/workflows'
import { timeAgo } from '@/lib/format'
import type { WorkflowView } from '@/types/workItem'

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

const COLUMNS: { key: keyof PublishMetricSnapshot; label: string }[] = [
  { key: 'views', label: 'Views' },
  { key: 'likes', label: 'Likes' },
  { key: 'comments', label: 'Comments' },
  { key: 'shares', label: 'Shares' },
]

function count(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return new Intl.NumberFormat().format(value)
}

export function PostPerformanceCard({
  projectId,
  workItemId,
  token,
  status,
  workflowView,
  refreshKey,
}: {
  projectId: string
  workItemId: string
  token: string
  status: string
  workflowView?: WorkflowView
  /** Change it to re-read — after a publish, a retry, or a recorded link. */
  refreshKey?: number | string
}) {
  const [metrics, setMetrics] = useState<PublishMetricsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const data = await apiGet<PublishMetricsResponse>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-metrics`,
        token
      )
      setMetrics(data)
      setError(null)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not read the performance numbers'))
    }
  }, [projectId, workItemId, token])

  useEffect(() => {
    void load()
  }, [load, status, refreshKey])

  const finished = statusMeta(workflowView, status).category === 'terminal'
  const targets = metrics?.targets ?? []
  // Before anything has published there is nothing to count; the card would only say "nothing yet".
  if (!finished && targets.length === 0 && !error) return null

  const newest = targets
    .map((t) => t.latest?.observedAt)
    .filter((v): v is string => Boolean(v))
    .sort()
    .at(-1)

  return (
    <Card data-testid="post-performance">
      <CardHeader>
        <h3 className="text-sm font-semibold text-foreground">Performance</h3>
        {newest && <span className="text-xs text-muted-foreground">as of {timeAgo(newest)}</span>}
      </CardHeader>
      <CardContent className="divide-y-0 px-4 py-3">
        {error ? (
          <p className="text-sm text-muted-foreground">{error}</p>
        ) : targets.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No numbers yet. Conductor reads each destination&rsquo;s counters a few hours after it publishes;
            Sync now on the account&rsquo;s Integrations page reads them sooner.
          </p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <th className="py-1 pr-3 font-medium">Destination</th>
                  {COLUMNS.map((c) => (
                    <th key={c.key} className="py-1 pr-3 text-right font-medium">
                      {c.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {targets.map((t) => (
                  <tr key={t.targetId} className="border-t border-border">
                    <td className="py-1.5 pr-3">
                      <span className="text-foreground">{humanizeId(t.platform)}</span>
                      {t.accountLabel && <span className="text-muted-foreground"> ({t.accountLabel})</span>}
                      {t.latest?.unavailable && (
                        <span className="block text-xs text-muted-foreground">No longer on the platform</span>
                      )}
                    </td>
                    {COLUMNS.map((c) => (
                      <td key={c.key} className="py-1.5 pr-3 text-right tabular-nums text-foreground">
                        {t.latest?.unavailable ? '—' : count(t.latest?.[c.key] as number | null | undefined)}
                      </td>
                    ))}
                  </tr>
                ))}
                {targets.length > 1 && metrics?.totals && (
                  <tr className="border-t border-border font-medium">
                    <td className="py-1.5 pr-3 text-foreground">All destinations</td>
                    {COLUMNS.map((c) => (
                      <td key={c.key} className="py-1.5 pr-3 text-right tabular-nums text-foreground">
                        {count(metrics.totals?.[c.key] as number | null | undefined)}
                      </td>
                    ))}
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
