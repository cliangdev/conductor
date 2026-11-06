// Wire shapes for GET /api/v2/projects/{projectId}/marketing/definitions (openapi-v2 `MarketingDefinitionsResponse`).

import { apiGet } from '@/lib/api'
import type { MetricUnit, OverviewMetricDefinition } from '@/components/marketing/overview/types'

/** The names the overview cards use; a channel the API adds later shows under its own key. */
export const CHANNEL_LABELS: Record<string, string> = {
  WEB: 'Web',
  SEARCH: 'Search',
  SOCIAL: 'Social',
  STORE: 'App Store',
  ADS: 'Ads',
  PRODUCT: 'In-app',
}

export interface MarketingDefinitionEntry {
  /** CHANNEL.key, the reference a benchmark or target uses (ADS.installs). */
  metricRef: string
  /** WEB, SEARCH, SOCIAL, STORE, ADS or PRODUCT. */
  channel: string
  key: string
  label: string
  unit: MetricUnit
  /** SUM, LEVEL, DERIVED or WINDOW_UNIQUE. */
  aggregation: string
  definition: OverviewMetricDefinition
}

export interface MarketingDefinitionsResponse {
  definitions: MarketingDefinitionEntry[]
}

export function getMarketingDefinitions(projectId: string, token: string) {
  return apiGet<MarketingDefinitionsResponse>(`/api/v2/projects/${projectId}/marketing/definitions`, token)
}
