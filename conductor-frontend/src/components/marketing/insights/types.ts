// Wire shapes for GET /api/v2/projects/{projectId}/marketing/insights (openapi-v2
// `MarketingInsightsResponse` and friends) — "What's working" across every published Post.

import { apiGet } from '@/lib/api'

export type InsightsWindowValue = '7d' | '30d' | '90d'

export const INSIGHTS_WINDOWS: InsightsWindowValue[] = ['7d', '30d', '90d']
export const DEFAULT_INSIGHTS_WINDOW: InsightsWindowValue = '30d'

export interface InsightsWindow {
  from: string
  to: string
  days: number
}

/** One grouping's summed counters (a platform, a format, an hour, a weekday, or "all" for totals). */
export interface InsightsGroup {
  key: string
  label?: string | null
  posts: number
  views: number
  likes: number
  comments: number
  shares: number
  saves: number
  engagementRate?: number | null
  medianViews?: number | null
  avgViewPct?: number | null
}

export interface InsightsPost {
  workItemId: string
  displayId?: string | null
  title?: string | null
  workflowSlug?: string | null
  captionExcerpt?: string | null
  targetId: string
  platform: string
  format?: string | null
  accountLabel?: string | null
  permalink?: string | null
  firedAt?: string | null
  views: number
  likes?: number | null
  comments?: number | null
  shares?: number | null
  saves?: number | null
  engagementRate?: number | null
  avgViewPct?: number | null
  observedAt: string
}

export interface InsightsMover {
  metric: string
  current: number
  previous: number
  deltaPct?: number | null
}

export interface InsightsCoverage {
  platforms: string[]
  notes: string[]
}

export interface MarketingInsightsResponse {
  window: InsightsWindow
  totals: InsightsGroup
  byPlatform: InsightsGroup[]
  byFormat: InsightsGroup[]
  byHour: InsightsGroup[]
  byWeekday: InsightsGroup[]
  topPosts: InsightsPost[]
  bottomPosts: InsightsPost[]
  movers: InsightsMover[]
  coverage: InsightsCoverage
}

export function getMarketingInsights(
  projectId: string,
  token: string,
  window: InsightsWindowValue,
  platform?: string,
): Promise<MarketingInsightsResponse> {
  const params = new URLSearchParams({ window })
  if (platform) params.set('platform', platform)
  return apiGet<MarketingInsightsResponse>(
    `/api/v2/projects/${projectId}/marketing/insights?${params.toString()}`,
    token,
  )
}

/** "posts", "views", "engagementRate", … → "Views", "Engagement rate". */
export function moverLabel(metric: string): string {
  switch (metric) {
    case 'engagementRate':
      return 'Engagement rate'
    default:
      return metric.charAt(0).toUpperCase() + metric.slice(1)
  }
}

/** "+12.3% vs prior 30d" / "-4.0% vs prior 7d"; null delta (previous was zero) reads as muted "—". */
export function moverDeltaLabel(deltaPct: number | null | undefined): string {
  if (deltaPct === null || deltaPct === undefined) return '—'
  const sign = deltaPct > 0 ? '+' : ''
  return `${sign}${deltaPct.toFixed(1)}%`
}

/** Engagement rate is a 0..1 ratio; render as a one-decimal percentage, or a dash with no views. */
export function engagementRateLabel(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return '—'
  return `${(rate * 100).toFixed(1)}%`
}

/** avgViewPct already arrives as a 0..100 percentage (YouTube's average-view-percentage). */
export function avgViewPctLabel(pct: number | null | undefined): string {
  if (pct === null || pct === undefined) return '—'
  return `${pct.toFixed(1)}%`
}
