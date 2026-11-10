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

interface InsightsTagGroup extends InsightsGroup {
  tagKey: string
  unsettledPosts?: number | null
  medianViewLift?: number | null
  beatBaselineShare?: number | null
  medianEngagementRate?: number | null
  medianRetentionLift?: number | null
  withBaselinePosts?: number | null
  beatBaselinePosts?: number | null
  withRetentionBaselinePosts?: number | null
  beatRetentionBaselinePosts?: number | null
  viewVerdict?: string | null
  retentionVerdict?: string | null
  verdict?: string | null
  verdictMetric?: string | null
}

interface PlaybookEffectSide {
  posts: number
  medianViewLift?: number | null
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
  settledPosts?: number | null
  medianViewLift?: number | null
  medianRetentionLift?: number | null
  beatBaselineShare?: number | null
  withBaselinePosts?: number | null
  beatBaselinePosts?: number | null
  viewVerdict?: string | null
  retentionVerdict?: string | null
  byPlatform: CreativePerformancePlatform[]
}

interface MarketingInsightsResponse {
  window: InsightsWindow
  totals: InsightsGroup
  byPlatform: InsightsGroup[]
  byFormat: InsightsGroup[]
  byHour: InsightsGroup[]
  byWeekday: InsightsGroup[]
  byTag?: InsightsTagGroup[]
  byTagWindow?: InsightsWindow
  playbookEffect?: { withLessons: PlaybookEffectSide; withoutLessons: PlaybookEffectSide }
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

/** A byTag row: the group plus its lift numbers (settled posts only), nulls dropped. */
function trimTagGroup(group: InsightsTagGroup): Record<string, unknown> {
  return omitNulls({
    ...trimGroup(group),
    medianViewLift: round4(group.medianViewLift),
    beatBaselineShare: round4(group.beatBaselineShare),
    medianEngagementRate: round4(group.medianEngagementRate),
    medianRetentionLift: round4(group.medianRetentionLift),
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
    settledPosts: entry.settledPosts,
    medianViewLift: round4(entry.medianViewLift),
    medianRetentionLift: round4(entry.medianRetentionLift),
    beatBaselineShare: round4(entry.beatBaselineShare),
    withBaselinePosts: entry.withBaselinePosts,
    beatBaselinePosts: entry.beatBaselinePosts,
    viewVerdict: entry.viewVerdict,
    retentionVerdict: entry.retentionVerdict,
  })
}

/**
 * What is working across the project's published Posts, trimmed to what an agent needs to reason from:
 * totals and per-platform/per-format engagement, the best hours and weekdays to post, the best and worst
 * destinations, and movers vs. the prior window of the same length.
 */
async function getPostInsights(
  config: Config,
  params: { window?: '7d' | '30d' | '90d'; platform?: string; brandKitId?: string; tags?: boolean }
): Promise<Record<string, unknown>> {
  const query = new URLSearchParams()
  if (params.window) query.set('window', params.window)
  if (params.platform) query.set('platform', params.platform)
  if (params.brandKitId) query.set('brandKitId', params.brandKitId)
  if (params.tags) query.set('include', 'tags')
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
    // Present only when "tags" was asked for: the output is otherwise exactly what it always was.
    ...(data.byTag ? { byTag: data.byTag.map(trimTagGroup) } : {}),
    ...(data.byTagWindow ? { byTagWindow: data.byTagWindow } : {}),
    ...(data.playbookEffect
      ? {
          playbookEffect: {
            withLessons: omitNulls({ posts: data.playbookEffect.withLessons.posts, medianViewLift: round4(data.playbookEffect.withLessons.medianViewLift) }),
            withoutLessons: omitNulls({ posts: data.playbookEffect.withoutLessons.posts, medianViewLift: round4(data.playbookEffect.withoutLessons.medianViewLift) }),
          },
        }
      : {}),
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

type MarketingChannel = 'WEB' | 'SEARCH' | 'SOCIAL' | 'ADS' | 'STORE' | 'PRODUCT'
const CHANNELS: MarketingChannel[] = ['WEB', 'SEARCH', 'SOCIAL', 'ADS', 'STORE', 'PRODUCT']
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
  insufficientData?: boolean | null
  provisionalDays?: number | null
  funnelOrder?: number | null
  status?: string | null
  missPct?: number | null
  sampleSize?: number | null
  area?: string | null
  basis?: string | null
  description?: string | null
  headline?: number | null
  note?: string | null
  definition?: {
    source: string
    numerator: string
    denominator?: string | null
    sourceType?: string | null
    exclusions?: string | null
    timeZone?: string | null
  } | null
  target?: {
    value: number
    direction: string
    readOnly?: boolean | null
    source?: string | null
    window?: string | null
    effectiveValue?: number | null
    watchBandPct?: number | null
  } | null
  series?: OverviewSeriesPoint[]
}

interface OverviewMismatch {
  metric: string
  label: string
  referenceValue: number
  channelValue: number
  gapPct: number
  from: string
  to: string
}

interface OverviewChannel {
  channel: string
  label: string
  status: string
  dataCompleteThrough?: string | null
  noDataYet?: boolean
  mismatch?: OverviewMismatch | null
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
  basis?: string | null
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
  funnelBasis?: string | null
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

/** A target a connector feed owns says so and where it is set: it cannot be changed through Conductor. */
function trimTarget(t: NonNullable<OverviewMetric['target']>): Record<string, unknown> {
  return omitNulls({
    value: t.value,
    direction: t.direction,
    window: t.window,
    effectiveValue: t.effectiveValue,
    watchBandPct: t.watchBandPct,
    readOnly: t.readOnly ? true : null,
    setIn: t.readOnly ? t.source : null,
  })
}

/**
 * True only when a day inside the metric's reported window is provisional. The backend's `provisionalDays`
 * counts the trailing days AFTER the window (past `dataCompleteThrough`), which never enter the value, so it
 * cannot be used on its own: a window with no provisional day must not read as provisional.
 */
export function windowHasProvisionalDay(m: OverviewMetric, dataCompleteThrough?: string | null): boolean {
  return (m.series ?? []).some((p) => p.provisional === true && (!dataCompleteThrough || p.day <= dataCompleteThrough))
}

function trimMetric(
  m: OverviewMetric,
  withSeries: boolean,
  flags = false,
  dataCompleteThrough?: string | null,
): Record<string, unknown> {
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
    // In-app data-quality flags (PRODUCT only, omitted when false): the newest days may still change, and the value
    // rests on too few users or events to read as a real change.
    provisional: flags && windowHasProvisionalDay(m, dataCompleteThrough) ? true : null,
    smallSample: flags && m.insufficientData ? true : null,
    target: m.target ? trimTarget(m.target) : null,
    // Standing against the target: ON_TRACK; WATCH (close, or a rate miss too small a sample to call real); OFF_TRACK;
    // TOO_EARLY; STOPPED (a snapshot the feed stopped sending, so it is not judged; `note` says when it was last reported).
    // Omitted without a target.
    // missPct is the shortfall as a fraction of the (prorated) target.
    status: m.status && m.status !== 'NO_TARGET' ? m.status : null,
    missPct: m.status && m.status !== 'NO_TARGET' ? round4(m.missPct) : null,
    sampleSize: m.sampleSize,
    area: m.area,
    description: m.description,
    headline: m.headline,
    // COHORT: each day counts the people who first opened that day, followed to now (EVENT is the default).
    basis: m.basis === 'COHORT' ? m.basis : null,
    // What the number counts (source, numerator, denominator, source type, exclusions, time zone), and, on a null
    // value, why the stored data cannot answer (e.g. unique visitors exist only for 7, 28 and 90 days).
    definition: m.definition ? omitNulls({ ...m.definition }) : null,
    note: m.note,
    series: withSeries ? bucketSeries(m.series ?? [], m.unit) : null,
  })
}

/**
 * The In-app channel splits into `stages` (the funnel stages, in funnel order, each with its flags and
 * read-only target) and `metrics` (the rest, e.g. cost per trial); every other channel has `metrics` only.
 */
function trimChannel(c: OverviewChannel, withSeries: boolean): Record<string, unknown> {
  const hasData = c.status === 'LIVE' || c.status === 'STALE' || c.status === 'MISMATCH'
  const isStage = (m: OverviewMetric) => c.channel === 'PRODUCT' && m.funnelOrder !== null && m.funnelOrder !== undefined
  const stages = hasData
    ? c.metrics.filter(isStage).sort((a, b) => (a.funnelOrder ?? 0) - (b.funnelOrder ?? 0))
    : []
  return omitNulls({
    channel: c.channel,
    label: c.label,
    status: c.status,
    // A channel with no complete day of data yet has a stand-in end date, so it carries noDataYet instead.
    dataCompleteThrough: c.noDataYet ? null : c.dataCompleteThrough,
    noDataYet: c.noDataYet ? true : null,
    connect: c.status === 'NOT_CONNECTED' ? c.connectHint?.connectors?.map((x) => x.name).join(', ') || null : null,
    // MISMATCH: a connector's own figure and a second source (the product backend's reference or a direct platform
    // read) differ outside the band: 1 unit for counts under 50, else 2%. get_verification_ledger lists every one.
    mismatch: c.mismatch
      ? omitNulls({
          metric: c.mismatch.metric,
          conductor: round4(c.mismatch.channelValue),
          reference: round4(c.mismatch.referenceValue),
          gapPct: round4(c.mismatch.gapPct),
          from: c.mismatch.from,
          to: c.mismatch.to,
        })
      : null,
    stages: stages.length > 0 ? stages.map((m) => trimMetric(m, withSeries, true, c.dataCompleteThrough)) : null,
    metrics: hasData ? c.metrics.filter((m) => !isStage(m)).map((m) => trimMetric(m, withSeries, c.channel === 'PRODUCT', c.dataCompleteThrough)) : null,
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
          basis: s.basis === 'COHORT' ? s.basis : null,
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
    // COHORT: the in-app stages count people who first opened in the window, followed to now.
    if (overview.funnelBasis === 'COHORT') out['funnelBasis'] = overview.funnelBasis
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
  params: { window?: '7d' | '30d' | '90d'; platform?: string; brandKitId?: string; include?: ('posts' | 'channels' | 'tags')[]; channel?: string }
): Promise<Record<string, unknown>> {
  const include = params.include && params.include.length > 0 ? params.include : ['posts']
  for (const i of include) {
    if (i !== 'posts' && i !== 'channels' && i !== 'tags') throw new Error(`Unknown include "${i}": use "posts", "channels" and/or "tags"`)
  }
  const wantTags = include.includes('tags')
  // The tag sections ride on the posts read, so "tags" alone still returns the posts.
  const wantPosts = include.includes('posts') || wantTags
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

  const postParams = { ...params, tags: wantTags }
  if (!wantChannels) return getPostInsights(config, postParams)
  const marketing = await getChannelInsights(config, { window: params.window, channel: params.channel as MarketingChannel | undefined })
  if (!wantPosts) return { marketing }
  return { ...(await getPostInsights(config, postParams)), marketing }
}
