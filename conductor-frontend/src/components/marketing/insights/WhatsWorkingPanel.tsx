'use client'

// "What's working" — a structured reading of every published Post's `post_metrics` snapshot: totals,
// engagement rate by platform/format/hour/weekday, the best and worst destinations, and how this
// window compares with the one before it. See openapi-v2 `getMarketingInsights`.

import Link from 'next/link'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { TrendingUpIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { EmptyState } from '@/components/ui/empty-state'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage } from '@/lib/api'
import { compactCount, count } from '@/components/marketing/destinations/DestinationMetrics'
import { humanizeId, useSidebarWorkNav, workItemDetailPath, workItemListPath } from '@/lib/workflows'
import { InsightsGroupTable } from './InsightsGroupTable'
import { InsightsBestTimes } from './InsightsBestTimes'
import { InsightsPostList } from './InsightsPostList'
import {
  DEFAULT_INSIGHTS_WINDOW,
  INSIGHTS_WINDOWS,
  engagementRateLabel,
  getMarketingInsights,
  moverDeltaLabel,
  type InsightsMover,
  type InsightsPost,
  type InsightsWindowValue,
  type MarketingInsightsResponse,
} from './types'

const MARKETING_AREA = 'MARKETING'

const WINDOW_TABS: TabItem[] = INSIGHTS_WINDOWS.map((w) => ({ value: w, label: w }))

function isInsightsWindow(value: string | null): value is InsightsWindowValue {
  return INSIGHTS_WINDOWS.includes(value as InsightsWindowValue)
}

function moverFor(movers: InsightsMover[], metric: string): InsightsMover | undefined {
  return movers.find((m) => m.metric === metric)
}

function StatTile({
  label,
  value,
  mover,
  windowLabel,
}: {
  label: string
  value: string
  mover?: InsightsMover
  windowLabel: string
}) {
  const delta = moverDeltaLabel(mover?.deltaPct)
  return (
    <div>
      <p className="text-xs font-medium text-muted-foreground">{label}</p>
      <p className="mt-1 text-2xl font-semibold tabular-nums text-foreground">{value}</p>
      {mover &&
        (delta === '—' ? (
          <p className="mt-0.5 text-xs text-muted-foreground">No prior {windowLabel} to compare</p>
        ) : (
          <p className="mt-0.5 text-xs text-muted-foreground">
            <span className={delta.startsWith('-') ? 'text-status-failed' : 'text-status-done'}>{delta}</span>
            {` vs prior ${windowLabel}`}
          </p>
        ))}
    </div>
  )
}

function PanelSkeleton() {
  return (
    <div className="space-y-4" aria-hidden>
      <Skeleton className="h-8 w-64" />
      <Skeleton className="h-32 w-full" />
      <Skeleton className="h-48 w-full" />
      <Skeleton className="h-48 w-full" />
    </div>
  )
}

export function WhatsWorkingPanel({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()

  const windowParam = searchParams.get('window')
  const activeWindow: InsightsWindowValue = isInsightsWindow(windowParam) ? windowParam : DEFAULT_INSIGHTS_WINDOW
  const activePlatform = searchParams.get('platform') ?? ''

  const [data, setData] = useState<MarketingInsightsResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const { entries } = useSidebarWorkNav(projectId, accessToken)
  const postWorkflow = useMemo(
    () => entries.find((e) => e.area.toUpperCase() === MARKETING_AREA),
    [entries],
  )

  const load = useCallback(() => {
    if (!accessToken) return
    setError(null)
    getMarketingInsights(projectId, accessToken, activeWindow, activePlatform || undefined)
      .then((res) => setData(res))
      .catch((err) => {
        setData(null)
        setError(apiErrorMessage(err, 'Could not load insights — please try again.'))
      })
  }, [projectId, accessToken, activeWindow, activePlatform])

  useEffect(() => {
    void load()
  }, [load])

  function updateQuery(next: { window?: InsightsWindowValue; platform?: string }) {
    const sp = new URLSearchParams(searchParams.toString())
    if (next.window !== undefined) {
      if (next.window === DEFAULT_INSIGHTS_WINDOW) sp.delete('window')
      else sp.set('window', next.window)
    }
    if (next.platform !== undefined) {
      if (!next.platform) sp.delete('platform')
      else sp.set('platform', next.platform)
    }
    const qs = sp.toString()
    router.replace(qs ? `${pathname}?${qs}` : pathname)
  }

  function hrefForPost(post: InsightsPost): string | null {
    if (!post.displayId) return null
    // Prefer the destination's own workflow (a second Marketing workflow would otherwise be
    // misrouted to whichever entry happens to be first). Fall back to the first-Marketing-workflow
    // guess only when the contract hasn't told us which workflow this post belongs to.
    const workflow = post.workflowSlug
      ? entries.find((e) => e.slug.toLowerCase() === post.workflowSlug!.toLowerCase())
      : undefined
    const resolved = workflow ?? postWorkflow
    if (!resolved) return null
    return workItemDetailPath(projectId, resolved.area, resolved.noun, post.displayId)
  }

  const windowLabel = activeWindow
  const loading = data === null && !error

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-3">
        <Tabs
          ariaLabel="Time window"
          items={WINDOW_TABS}
          value={activeWindow}
          onValueChange={(v) => updateQuery({ window: v as InsightsWindowValue })}
        />
        <Select
          aria-label="Platform"
          className="w-auto"
          value={activePlatform}
          onChange={(e) => updateQuery({ platform: e.target.value })}
        >
          <option value="">All platforms</option>
          {(data?.coverage.platforms ?? []).map((p) => (
            <option key={p} value={p}>
              {humanizeId(p)}
            </option>
          ))}
        </Select>
      </div>

      {error && <Alert variant="destructive">{error}</Alert>}

      {loading ? (
        <PanelSkeleton />
      ) : !data ? null : data.totals.posts === 0 ? (
        <EmptyState
          icon={TrendingUpIcon}
          title="No published Posts in this window yet"
          description="Once a Post publishes and Conductor reads its counters, its performance shows up here."
          action={
            postWorkflow && (
              <Link
                href={workItemListPath(projectId, postWorkflow.area, postWorkflow.noun)}
                className="text-sm font-medium text-primary hover:underline"
              >
                Go to Posts
              </Link>
            )
          }
        />
      ) : (
        <>
          <div className="grid grid-cols-2 gap-6 rounded-lg border border-border bg-surface p-4 sm:grid-cols-4">
            <StatTile
              label="Posts"
              value={count(data.totals.posts)}
              mover={moverFor(data.movers, 'posts')}
              windowLabel={windowLabel}
            />
            <StatTile
              label="Views"
              value={compactCount(data.totals.views)}
              mover={moverFor(data.movers, 'views')}
              windowLabel={windowLabel}
            />
            <StatTile
              label="Engagement rate"
              value={engagementRateLabel(data.totals.engagementRate)}
              mover={moverFor(data.movers, 'engagementRate')}
              windowLabel={windowLabel}
            />
            <StatTile
              label="Median views"
              value={data.totals.medianViews === null || data.totals.medianViews === undefined ? '—' : compactCount(data.totals.medianViews)}
              windowLabel={windowLabel}
            />
          </div>

          <div className="rounded-lg border border-border bg-surface p-4">
            <InsightsGroupTable
              title="By platform"
              groups={data.byPlatform}
              rowKind="platform"
              emptyMessage="No platform breakdown for this window."
            />
          </div>

          <div className="rounded-lg border border-border bg-surface p-4">
            <InsightsGroupTable
              title="By format"
              groups={data.byFormat}
              rowKind="format"
              emptyMessage="No format breakdown for this window."
            />
          </div>

          <div className="rounded-lg border border-border bg-surface p-4">
            <InsightsBestTimes byHour={data.byHour} byWeekday={data.byWeekday} />
          </div>

          {data.topPosts.length > 0 && (
            <div className="rounded-lg border border-border bg-surface p-4">
              <InsightsPostList title="Top posts" posts={data.topPosts} hrefFor={hrefForPost} />
            </div>
          )}

          {data.bottomPosts.length > 0 && (
            <div className="rounded-lg border border-border bg-surface p-4">
              <InsightsPostList title="Needs a rethink" posts={data.bottomPosts} hrefFor={hrefForPost} />
            </div>
          )}

          {data.coverage.notes.length > 0 && (
            <ul className="space-y-1 text-xs text-muted-foreground">
              {data.coverage.notes.map((note, i) => (
                <li key={i}>{note}</li>
              ))}
            </ul>
          )}
        </>
      )}
    </div>
  )
}
