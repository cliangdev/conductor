// Wire shapes for GET /api/v2/projects/{projectId}/marketing/overview, …/marketing/channels/{channel}
// and PUT/DELETE …/marketing/targets/{channel}/{metric} (openapi-v2 `MarketingOverviewResponse` and friends).
// Percent values are fractions (0.12 = 12%).

import { apiDelete, apiGet, apiPut } from '@/lib/api'
import type { InsightsPost } from '@/components/marketing/insights/types'

export type OverviewWindowValue = '7d' | '28d' | '90d'
export type OverviewPageWindow = '7d' | '28d'

export const OVERVIEW_WINDOWS: OverviewPageWindow[] = ['7d', '28d']
export const DETAIL_WINDOWS: OverviewWindowValue[] = ['7d', '28d', '90d']
export const DEFAULT_OVERVIEW_WINDOW: OverviewPageWindow = '28d'

export type ChannelKey = 'WEB' | 'SEARCH' | 'SOCIAL' | 'STORE' | 'ADS' | 'PRODUCT'
/** Display order of the channel grid. */
export const CHANNEL_ORDER: ChannelKey[] = ['WEB', 'SEARCH', 'SOCIAL', 'STORE', 'ADS', 'PRODUCT']

export type ChannelStatus = 'LIVE' | 'STALE' | 'NOT_CONNECTED' | 'COMING_SOON' | 'MISMATCH'
export type MetricUnit = 'COUNT' | 'CURRENCY' | 'PERCENT' | 'POSITION' | 'RATING'
export type TargetDirection = 'UP' | 'DOWN'
/**
 * Where a value stands against its target. TOO_EARLY: no value, or a rate on fewer than 20 observations. WATCH:
 * a small miss, or a rate miss its sample is too small to call real. STOPPED: a snapshot more than a week behind
 * the rest of its channel, not judged until it arrives again (`note` says when it was last reported).
 */
export type TargetStatus = 'ON_TRACK' | 'WATCH' | 'OFF_TRACK' | 'TOO_EARLY' | 'STOPPED' | 'NO_TARGET'
/** COHORT: a value counts the people who first opened on that day (or in the window), followed to now. */
export type MetricBasis = 'EVENT' | 'COHORT'

export interface OverviewWindowRange {
  from: string
  /** Null when nothing is connected. */
  to: string | null
  /** The earliest channel's data-complete day; differs from `to` when channels are at different days. */
  dataCompleteThroughMin?: string | null
  previousFrom: string
  previousTo: string
}

export interface OverviewFunnelStage {
  key: string
  label: string
  value?: number | null
  previous?: number | null
  change?: number | null
  source: string
  /** WEB, STORE, ADS or PRODUCT: which path of the funnel the stage is on. */
  path: string
  /** The stage this one converts from; conversion is only shown when set. */
  conversionFromKey?: string | null
  available: boolean
  unavailableReason?: string | null
  dataCompleteThrough?: string | null
  /** COHORT for in-app stages counted by cohort; EVENT (or absent) otherwise. */
  basis?: MetricBasis
}

export interface OverviewConnection {
  id: string
  connectorId: string
  name: string
  status: 'LIVE' | 'STALE'
}

export interface MarketingTarget {
  value: number
  direction: TargetDirection
  /** True when a connector feed owns the target: it cannot be set or cleared here. */
  readOnly?: boolean
  /** Where a read-only target is set, e.g. "Set in Product metrics". */
  source?: string | null
  /** The period a count or currency total target is stated for, e.g. "28d"; absent means the target is compared as stated. */
  window?: string | null
  /** A miss within this percent of the target reads as Watch; absent means 10. */
  watchBandPct?: number | null
  /** The target the value was judged against in the requested window (prorated when the target has a window). */
  effectiveValue?: number | null
}

/** What a metric counts (openapi-v2 `OverviewMetricDefinition`); every field after `numerator` can be null. */
export interface OverviewMetricDefinition {
  /** One plain line naming the platform report or field the number comes from. */
  source: string
  /** A measure name from the API's closed list, or `product:<key>` for a metric a product backend declared. */
  numerator: string
  /** The measure divided by; null or absent for a plain count. */
  denominator?: string | null
  /** ALL or one traffic source type (APP_STORE_SEARCH ...); null or absent means ALL. */
  sourceType?: string | null
  /** One plain line naming what is left out; null when nothing is. */
  exclusions?: string | null
  /** The time zone that decides which day a row belongs to; null when the source does not say. */
  timeZone?: string | null
}

export interface OverviewSeriesPoint {
  day: string
  value?: number | null
  provisional?: boolean
}

export interface OverviewMetric {
  metric: string
  label: string
  unit: MetricUnit
  /** Which way a move reads as good news; also the default direction of a target. */
  goodDirection: TargetDirection
  value?: number | null
  previous?: number | null
  change?: number | null
  changePct?: number | null
  /** Set when `previous` is null because the history is shorter than the comparison window. */
  historyStartsOn?: string | null
  /** Snapshot day of a LEVEL metric (followers); null for metrics that sum a window. */
  asOf?: string | null
  noise: boolean
  /** SUM is a window total (its target is a total, not a daily level); LEVEL and DERIVED share one scale per day and per window. */
  aggregation?: 'SUM' | 'LEVEL' | 'DERIVED'
  /** PRODUCT only: the metric's place among the in-app funnel stages (1 first); null when it is not a stage. */
  funnelOrder?: number | null
  /** The feed reported a sample under 20 for the window; `noise` is then also true. */
  insufficientData?: boolean
  /** Standing against the target (the prorated target for a count or currency total); absent from older servers. */
  status?: TargetStatus
  /** How far short of the target, as a fraction of it (0.05 is 5%); 0 when met, null when not judged. */
  missPct?: number | null
  /** Observations behind the value, when known. */
  sampleSize?: number | null
  /** PRODUCT only: the part of the growth scorecard the metric belongs to. */
  area?: 'ACQUISITION' | 'ACTIVATION' | 'RETENTION' | 'REVENUE' | null
  /** PRODUCT only: what the metric counts, in the feed's words. */
  description?: string | null
  /** PRODUCT only: priority among the feed's headline metrics (1 first). */
  headline?: number | null
  /** PRODUCT only: COHORT when each day counts the people who first opened that day. */
  basis?: MetricBasis
  /** Where the number comes from and what it counts; absent from older servers. */
  definition?: OverviewMetricDefinition | null
  /** Set only when `value` is null because the stored data cannot answer (for example a unique-visitor window that is not stored). */
  note?: string | null
  /** Days after the channel's data-complete day that the series still shows (excluded from the value). */
  provisionalDays?: number
  target?: MarketingTarget | null
  series: OverviewSeriesPoint[]
}

/** One second source that disagrees with the channel's own figure: both figures cover the same settled days. */
export interface OverviewMismatch {
  /** BACKEND: the product metrics feed's reference. PLATFORM: a direct platform read from the ledger. Absent means BACKEND. */
  kind?: 'BACKEND' | 'PLATFORM'
  /** The channel metric compared, e.g. downloads or spend. */
  metric: string
  label: string
  /** What the second source reports: the product metrics feed (BACKEND) or the platform read (PLATFORM). */
  referenceValue: number
  /** What this channel's own connector reports. */
  channelValue: number
  /** Absolute gap as a fraction of the larger figure (0.05 is 5%). */
  gapPct: number
  from: string
  to: string
  /** Name of the connection(s) behind the channel's own figure, e.g. App Store Connect. */
  channelSource?: string
  /** Name of the second source: the product metrics connection(s), or what the platform read was called (Apple Ads console). */
  referenceSource?: string
}

export interface OverviewChannel {
  channel: ChannelKey
  label: string
  status: ChannelStatus
  lastFetchedAt?: string | null
  dataCompleteThrough?: string | null
  /** True for a connected channel with no complete day of data yet: `dataCompleteThrough` is then a stand-in. */
  noDataYet?: boolean
  /** False when the channel's connection owns its targets (In-app): they cannot be set or cleared here. */
  targetsEditable?: boolean
  /** The largest gap of `mismatches`, kept for older readers. */
  mismatch?: OverviewMismatch | null
  /** Every second source that disagrees, largest gap first; absent from older servers (read `mismatch` then). */
  mismatches?: OverviewMismatch[]
  /** True only in a fixed window when this channel's settled data ends before the window's last day. */
  partial?: boolean
  connections: OverviewConnection[]
  connectHint?: { /** Deprecated. */ connectorId?: string; connectors: { connectorId: string; name: string }[] } | null
  metrics: OverviewMetric[]
}

export interface MarketingOverviewResponse {
  window: OverviewWindowRange
  funnel: OverviewFunnelStage[]
  channels: OverviewChannel[]
  /** COHORT when the in-app stages count people who first opened in the window, followed to now. */
  funnelBasis?: MetricBasis
}

export interface OverviewDimensionColumn {
  key: string
  label: string
  unit: MetricUnit
}

export interface OverviewDimensionRow {
  key: string
  label: string
  values: (number | null)[]
}

export interface OverviewDimension {
  key: string
  label: string
  columns: OverviewDimensionColumn[]
  rows: OverviewDimensionRow[]
}

/** A change that went live (GET …/marketing/report `changeMarkers`): a chart marks its day. */
export interface OverviewChangeMarker {
  /** The change's liveSince date (YYYY-MM-DD). */
  day: string
  changeId: string
  displayId: string
  title: string
  /** LIVE or REVIEWED. */
  status: string
}

export interface MarketingChannelDetailResponse {
  window: OverviewWindowRange
  channel: OverviewChannel
  dimensions: OverviewDimension[]
  topPosts?: InsightsPost[] | null
}

const base = (projectId: string) => `/api/v2/projects/${projectId}/marketing`

export function getMarketingOverview(projectId: string, token: string, window: OverviewWindowValue) {
  return apiGet<MarketingOverviewResponse>(`${base(projectId)}/overview?window=${window}`, token)
}

export function getMarketingChannel(projectId: string, token: string, channel: string, window: OverviewWindowValue) {
  return apiGet<MarketingChannelDetailResponse>(
    `${base(projectId)}/channels/${encodeURIComponent(channel)}?window=${window}`,
    token,
  )
}

/** The report serves 7d and 28d only; the 90d detail window reads the 28d report (markers are not bounded below). */
export function reportWindowFor(window: OverviewWindowValue): '7d' | '28d' {
  return window === '7d' ? '7d' : '28d'
}

/** The days changes went live. Anything that is not a list of markers (an older server) reads as none. */
export async function getMarketingChangeMarkers(
  projectId: string,
  token: string,
  window: OverviewWindowValue,
): Promise<OverviewChangeMarker[]> {
  const report = await apiGet<{ changeMarkers?: OverviewChangeMarker[] | null } | null>(
    `${base(projectId)}/report?window=${reportWindowFor(window)}`,
    token,
  )
  return Array.isArray(report?.changeMarkers) ? report.changeMarkers : []
}

export function setMarketingTarget(
  projectId: string,
  token: string,
  channel: string,
  metric: string,
  body: { target: number; direction?: TargetDirection },
) {
  return apiPut<MarketingTarget>(`${base(projectId)}/targets/${channel}/${encodeURIComponent(metric)}`, body, token)
}

export function deleteMarketingTarget(projectId: string, token: string, channel: string, metric: string) {
  return apiDelete(`${base(projectId)}/targets/${channel}/${encodeURIComponent(metric)}`, token)
}

export function isChannelKey(value: string): value is ChannelKey {
  return (CHANNEL_ORDER as string[]).includes(value)
}
