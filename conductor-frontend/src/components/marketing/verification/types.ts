// Wire shapes for GET /api/v2/projects/{projectId}/marketing/verification and
// POST …/marketing/verification/reads and …/reads/{readId}/retract (openapi-v2 `MarketingVerificationLedger`, `VerificationRow`,
// `MarketingPlatformReadRequest`). Values are plain numbers; percent values are fractions.

import { apiGet, apiPost } from '@/lib/api'
import type { MetricUnit, OverviewMetricDefinition } from '@/components/marketing/overview/types'

export type LedgerWindow = '7d' | '28d' | '90d'
export const LEDGER_WINDOWS: LedgerWindow[] = ['7d', '28d', '90d']
export const DEFAULT_LEDGER_WINDOW: LedgerWindow = '28d'

/**
 * Which figure to trust for a metric. BOTH: every second source agrees. CONNECTOR: trust the connector. BACKEND:
 * backend and platform read agree, the connector does not. PLATFORM: the platform read disagrees with every other
 * source. UNCHECKED: no second source at all, never verified.
 */
export type LedgerVerdict = 'BOTH' | 'CONNECTOR' | 'BACKEND' | 'PLATFORM' | 'UNCHECKED'

export interface VerificationBackend {
  /** The product backend's sum over the days compared. */
  value: number
  /** Conductor's sum over the same days. */
  conductorValue: number
  daysCompared: number
  /** Name of the product metrics connection(s). */
  source: string
  from: string
  to: string
  matches: boolean
}

export interface VerificationPlatformRead {
  id: string
  value: number
  from: string
  to: string
  /** Where it was read, e.g. Apple Ads console. */
  sourceName: string
  note?: string | null
  recordedBy?: string | null
  recordedAt: string
  /** Conductor's own figure over exactly the read's days; null when that cannot be said. */
  conductorValueOverReadDays?: number | null
  /** Whether the read agrees with Conductor over its days; null when that cannot be compared. */
  matches?: boolean | null
}

export interface VerificationRow {
  channel: string
  metric: string
  /** CHANNEL.metric, e.g. ADS.installs. */
  metricRef: string
  label: string
  unit: MetricUnit
  definition?: OverviewMetricDefinition | null
  windowFrom?: string | null
  windowTo?: string | null
  /** Null when Conductor has none: unknown, never 0. */
  conductorValue?: number | null
  backend?: VerificationBackend | null
  platformRead?: VerificationPlatformRead | null
  verdict: LedgerVerdict
  /** True when any second source that could be compared disagrees with Conductor's figure. */
  mismatch: boolean
  /** True when the latest platform read was recorded more than 7 days ago. */
  readStale: boolean
  trustNote: string
}

export interface VerificationSummary {
  both: number
  connector: number
  backend: number
  platform: number
  unchecked: number
  mismatched: number
  staleReads: number
}

export interface MarketingVerificationLedger {
  window: { from: string; to?: string | null }
  rows: VerificationRow[]
  summary: VerificationSummary
}

export interface PlatformReadRequest {
  channel: string
  metric: string
  from: string
  to: string
  value: number
  sourceName: string
  note?: string
}

const base = (projectId: string) => `/api/v2/projects/${projectId}/marketing/verification`

export function getVerificationLedger(projectId: string, token: string, window: LedgerWindow) {
  return apiGet<MarketingVerificationLedger>(`${base(projectId)}?window=${window}`, token)
}

/** Records one direct platform read; the response is the ledger row recomputed for exactly the read's days. */
export function recordPlatformRead(projectId: string, token: string, body: PlatformReadRequest) {
  return apiPost<VerificationRow>(`${base(projectId)}/reads`, body, token)
}

/** Retracts a wrong read: it stays in the history but stops counting. Idempotent; requires ADMIN or CREATOR. */
export function retractPlatformRead(projectId: string, token: string, readId: string, reason: string) {
  return apiPost<unknown>(`${base(projectId)}/reads/${encodeURIComponent(readId)}/retract`, { reason }, token)
}
