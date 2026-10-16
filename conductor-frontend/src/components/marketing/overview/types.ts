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

export type ChannelKey = 'WEB' | 'SEARCH' | 'SOCIAL' | 'STORE' | 'ADS'
/** Display order of the channel grid. */
export const CHANNEL_ORDER: ChannelKey[] = ['WEB', 'SEARCH', 'SOCIAL', 'STORE', 'ADS']

export type ChannelStatus = 'LIVE' | 'STALE' | 'NOT_CONNECTED' | 'COMING_SOON'
export type MetricUnit = 'COUNT' | 'CURRENCY' | 'PERCENT' | 'POSITION'
export type TargetDirection = 'UP' | 'DOWN'

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
  target?: MarketingTarget | null
  series: OverviewSeriesPoint[]
}

export interface OverviewChannel {
  channel: ChannelKey
  label: string
  status: ChannelStatus
  lastFetchedAt?: string | null
  dataCompleteThrough?: string | null
  connections: OverviewConnection[]
  connectHint?: { /** Deprecated. */ connectorId?: string; connectors: { connectorId: string; name: string }[] } | null
  metrics: OverviewMetric[]
}

export interface MarketingOverviewResponse {
  window: OverviewWindowRange
  funnel: OverviewFunnelStage[]
  channels: OverviewChannel[]
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
