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
async function getPostInsights(
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

// --- Channels: the marketing overview (/marketing/overview) and one channel's detail ---

type MarketingChannel = 'WEB' | 'SEARCH' | 'SOCIAL' | 'ADS' | 'STORE'
const CHANNELS: MarketingChannel[] = ['WEB', 'SEARCH', 'SOCIAL', 'ADS', 'STORE']
const MAX_SERIES_POINTS = 14
const MAX_DIMENSION_ROWS = 10

interface OverviewSeriesPoint {
  day: string
  value?: number | null
  provisional?: boolean | null
}

interface OverviewMetric {
  metric: string
  label: string
  unit: string
  value?: number | null
  previous?: number | null
  changePct?: number | null
  goodDirection?: string
  asOf?: string | null
  historyStartsOn?: string | null
  noise: boolean
  target?: { value: number; direction: string } | null
  series?: OverviewSeriesPoint[]
}

interface OverviewChannel {
  channel: string
  label: string
  status: string
  dataCompleteThrough?: string | null
  connectHint?: { connectors?: { connectorId: string; name: string }[] } | null
  metrics: OverviewMetric[]
}

interface OverviewFunnelStage {
  key: string
  label: string
  value?: number | null
  previous?: number | null
  changePct?: number | null
  path?: string | null
  conversionFromKey?: string | null
  source: string
  available: boolean
  unavailableReason?: string | null
}

interface OverviewDimension {
  key: string
  label: string
  columns: { key: string; label: string; unit: string }[]
  rows: { key: string; label: string; values: (number | null)[] }[]
}

interface OverviewResponse {
  window: Record<string, string>
  funnel: OverviewFunnelStage[]
  channels: OverviewChannel[]
}

interface ChannelDetailResponse {
  window: Record<string, string>
  channel: OverviewChannel
  dimensions: OverviewDimension[]
  topPosts?: InsightsPost[]
}

const BUCKET_DAYS = 7

/**
 * Aggregates a daily series into equal 7-day buckets ending at the last day (a 7-day series stays daily).
 * COUNT and CURRENCY sum; PERCENT and POSITION average the days that have a value. A bucket is provisional
 * if any day in it is; buckets with no values are dropped.
 */
export function bucketSeries(points: OverviewSeriesPoint[], unit: string): Record<string, unknown>[] {
  const size = points.length <= MAX_SERIES_POINTS ? 1 : BUCKET_DAYS
  const sum = unit === 'COUNT' || unit === 'CURRENCY'
  const out: Record<string, unknown>[] = []
  for (let end = points.length; end > 0; end -= size) {
    const days = points.slice(Math.max(0, end - size), end)
    const values = days.map((d) => d.value).filter((v): v is number => v !== null && v !== undefined)
    if (values.length === 0) continue
    const total = values.reduce((x, y) => x + y, 0)
    out.unshift({
      from: days[0]!.day,
      to: days[days.length - 1]!.day,
      value: round4(sum ? total : total / values.length),
      provisional: days.some((d) => d.provisional),
    })
  }
  return out
}

function trimMetric(m: OverviewMetric, withSeries: boolean): Record<string, unknown> {
  return omitNulls({
    metric: m.metric,
    label: m.label,
    unit: m.unit,
    value: round4(m.value),
    previous: round4(m.previous),
    asOf: m.asOf,
    historyStartsOn: m.previous === null || m.previous === undefined ? m.historyStartsOn : null,
    changePct: round4(m.changePct),
    goodDirection: m.goodDirection,
    noise: m.noise,
    target: m.target,
    series: withSeries ? bucketSeries(m.series ?? [], m.unit) : null,
  })
}

function trimChannel(c: OverviewChannel, withSeries: boolean): Record<string, unknown> {
  const hasData = c.status === 'LIVE' || c.status === 'STALE'
  return omitNulls({
    channel: c.channel,
    label: c.label,
    status: c.status,
    dataCompleteThrough: c.dataCompleteThrough,
    connect: c.status === 'NOT_CONNECTED' ? c.connectHint?.connectors?.map((x) => x.name).join(', ') || null : null,
    metrics: hasData ? c.metrics.map((m) => trimMetric(m, withSeries)) : null,
  })
}

function trimDimension(d: OverviewDimension): Record<string, unknown> {
  return {
    key: d.key,
    label: d.label,
    columns: d.columns.map((c) => c.key),
    rows: d.rows.slice(0, MAX_DIMENSION_ROWS).map((r) => ({ label: r.label, values: r.values })),
    ...(d.rows.length > MAX_DIMENSION_ROWS ? { totalRows: d.rows.length } : {}),
  }
}

function trimFunnel(funnel: OverviewFunnelStage[]) {
  const byKey = new Map(funnel.map((s) => [s.key, s]))
  return {
    funnel: funnel
      .filter((s) => s.available)
      .map((s) => {
        const from = s.conversionFromKey ? byKey.get(s.conversionFromKey) : undefined
        const conversionPct =
          from && from.available && from.value && s.value !== null && s.value !== undefined ? s.value / from.value : null
        return omitNulls({
          key: s.key,
          label: s.label,
          path: s.path,
          value: round4(s.value),
          previous: round4(s.previous),
          changePct: round4(s.changePct ?? (s.previous && s.value != null ? (s.value - s.previous) / s.previous : null)),
          conversionFromKey: s.conversionFromKey,
          conversionPct: round4(conversionPct),
        })
      }),
    unavailableStages: funnel
      .filter((s) => !s.available)
      .map((s) => omitNulls({ key: s.key, reason: s.unavailableReason })),
  }
}

/** Per-channel totals, change, status and target; plus one channel's dimension tables when `channel` is set. */
async function getChannelInsights(
  config: Config,
  params: { window?: '7d' | '30d' | '90d'; channel?: MarketingChannel }
): Promise<Record<string, unknown>> {
  const window = params.window ?? '30d'
  // The backend's windows are 7d / 28d (overview) and 7d / 28d / 90d (detail).
  const backendWindow = window === '30d' ? '28d' : window
  const base = `/api/v2/projects/${config.projectId}/marketing`
  const out: Record<string, unknown> = {}

  if (window !== '90d') {
    const overview = await apiGet<OverviewResponse>(`${base}/overview?window=${backendWindow}`, config)
    out['window'] = overview.window
    Object.assign(out, trimFunnel(overview.funnel))
    out['channels'] = overview.channels.map((c) => trimChannel(c, false))
  }

  if (params.channel) {
    const detail = await apiGet<ChannelDetailResponse>(`${base}/channels/${params.channel}?window=${backendWindow}`, config)
    if (window === '90d') out['window'] = detail.window
    out['channelDetail'] = {
      ...trimChannel(detail.channel, true),
      dimensions: detail.dimensions.map(trimDimension),
      ...(detail.topPosts?.length ? { topPosts: detail.topPosts.slice(0, 5).map(trimPost) } : {}),
    }
  }
  return out
}

/**
 * What is working in marketing. `include` picks the sections: `posts` (default; today's output, unchanged)
 * and/or `channels` (the per-channel overview, plus one channel's dimension tables when `channel` is given).
 */
export async function getMarketingInsights(
  config: Config,
  params: { window?: '7d' | '30d' | '90d'; platform?: string; include?: ('posts' | 'channels')[]; channel?: string }
): Promise<Record<string, unknown>> {
  const include = params.include && params.include.length > 0 ? params.include : ['posts']
  for (const i of include) {
    if (i !== 'posts' && i !== 'channels') throw new Error(`Unknown include "${i}": use "posts" and/or "channels"`)
  }
  const wantPosts = include.includes('posts')
  const wantChannels = include.includes('channels')
  if (params.channel !== undefined && !CHANNELS.includes(params.channel as MarketingChannel)) {
    throw new Error(`Unknown channel "${params.channel}": use one of ${CHANNELS.join(', ')}`)
  }
  if (params.channel && !wantChannels) {
    throw new Error('`channel` needs include: ["channels"]')
  }
  if (wantChannels && params.window === '90d' && !params.channel) {
    throw new Error("window 90d is only available for one channel's detail: pass `channel`, or use 7d or 30d for the overview")
  }
  if (params.platform && !wantPosts) {
    throw new Error('`platform` only filters posts: add "posts" to include')
  }

  if (!wantChannels) return getPostInsights(config, params)
  const marketing = await getChannelInsights(config, { window: params.window, channel: params.channel as MarketingChannel | undefined })
  if (!wantPosts) return { marketing }
  return { ...(await getPostInsights(config, params)), marketing }
}
