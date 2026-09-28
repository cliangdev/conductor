import { Config } from '../config.js'
import { apiGet } from '../api.js'

/**
 * A structured reading of what is working across the project's published Posts: totals, engagement rate
 * by platform and format, best hours and weekdays, the best/worst destinations, and how the window
 * compares with the one before it — trimmed here for context (full detail lives in the backend's
 * `/marketing/insights` response; see `docs/api-guidelines.md` and the `MarketingInsightsResponse` schema
 * for the untrimmed shape).
 */

interface InsightsWindow {
  from: string
  to: string
  days: number
}

interface InsightsGroup {
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

interface InsightsPost {
  workItemId: string
  displayId?: string | null
  title?: string | null
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

interface InsightsMover {
  metric: string
  current: number
  previous: number
  deltaPct?: number | null
}

interface InsightsCoverage {
  platforms: string[]
  notes: string[]
}

interface CreativePerformancePlatform {
  platform: string
  posts: number
  views: number
  engagementRate?: number | null
}

interface CreativePerformanceEntry {
  creativeId: string
  label: string
  headline?: string | null
  posts: number
  views: number
  engagementRate?: number | null
  avgViewPct?: number | null
  views72h?: number | null
  byPlatform: CreativePerformancePlatform[]
}

interface MarketingInsightsResponse {
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
  creatives: CreativePerformanceEntry[]
}

/** Rounds a rate-like number to 4 decimals; leaves null/undefined alone. */
function round4(n: number | null | undefined): number | null | undefined {
  if (n === null || n === undefined) return n
  return Math.round(n * 10000) / 10000
}

/** Drops null/undefined fields from an object, keeping the key order of what remains. */
function omitNulls<T extends Record<string, unknown>>(obj: T): Partial<T> {
  const out: Partial<T> = {}
  for (const [key, value] of Object.entries(obj)) {
    if (value !== null && value !== undefined) out[key as keyof T] = value as T[keyof T]
  }
  return out
}

function trimGroup(group: InsightsGroup): Record<string, unknown> {
  return omitNulls({
    ...group,
    engagementRate: round4(group.engagementRate),
    avgViewPct: round4(group.avgViewPct),
  })
}

/** The fields worth an agent's context for one destination — a subset of the backend's InsightsPost. */
function trimPost(post: InsightsPost): Record<string, unknown> {
  return omitNulls({
    displayId: post.displayId,
    title: post.title,
    platform: post.platform,
    format: post.format,
    captionExcerpt: post.captionExcerpt,
    views: post.views,
    engagementRate: round4(post.engagementRate),
    permalink: post.permalink,
  })
}

/** Groups with at least two posts and a computed engagement rate, best rate first, capped at three. */
function topGroups(groups: InsightsGroup[]): Record<string, unknown>[] {
  return groups
    .filter((g) => g.posts >= 2 && g.engagementRate !== null && g.engagementRate !== undefined)
    .sort((a, b) => (b.engagementRate as number) - (a.engagementRate as number))
    .slice(0, 3)
    .map(trimGroup)
}

/** The fields worth an agent's context for one Creative variant's attributed performance. */
function trimCreative(entry: CreativePerformanceEntry): Record<string, unknown> {
  return omitNulls({
    creativeId: entry.creativeId,
    label: entry.label,
    headline: entry.headline,
    posts: entry.posts,
    views: entry.views,
    engagementRate: round4(entry.engagementRate),
    avgViewPct: round4(entry.avgViewPct),
    views72h: entry.views72h,
  })
}

/**
 * What is working across the project's published Posts, trimmed to what an agent needs to reason from:
 * totals and per-platform/per-format engagement, the best hours and weekdays to post, the best and worst
 * destinations, and movers vs. the prior window of the same length.
 */
export async function getMarketingInsights(
  config: Config,
  params: { window?: '7d' | '30d' | '90d'; platform?: string }
): Promise<Record<string, unknown>> {
  const query = new URLSearchParams()
  if (params.window) query.set('window', params.window)
  if (params.platform) query.set('platform', params.platform)
  const qs = query.toString()
  const data = await apiGet<MarketingInsightsResponse>(
    `/api/v2/projects/${config.projectId}/marketing/insights${qs ? `?${qs}` : ''}`,
    config
  )

  return {
    window: data.window,
    totals: trimGroup(data.totals),
    byPlatform: data.byPlatform.map(trimGroup),
    byFormat: data.byFormat.map(trimGroup),
    bestHoursUtc: topGroups(data.byHour),
    bestWeekdays: topGroups(data.byWeekday),
    topPosts: data.topPosts.slice(0, 5).map(trimPost),
    bottomPosts: data.bottomPosts.slice(0, 3).map(trimPost),
    movers: data.movers.map((m) =>
      omitNulls({ metric: m.metric, current: round4(m.current), previous: round4(m.previous), deltaPct: round4(m.deltaPct) })
    ),
    coverage: data.coverage,
    topCreatives: (data.creatives ?? []).slice(0, 5).map(trimCreative),
  }
}
