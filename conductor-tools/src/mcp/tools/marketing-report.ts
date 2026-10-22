import { Config } from '../config.js'
import { apiGet, apiPut, apiDelete } from '../api.js'

/**
 * The whole-funnel marketing report (`/marketing/report`) and the industry benchmarks it compares against
 * (`/marketing/benchmarks`). The payload is already compact; this trims what an agent does not need
 * (nulls, float noise, long tails) per `docs/mcp-tool-guidelines.md`.
 */

export const MAX_REPORT_KEYWORDS = 25
export const MAX_REPORT_ADS = 20

type Json = unknown

/** Rounds to 4 decimals, or to 2 once the magnitude is 100 or more (money and counts need no more). */
function roundNumber(n: number): number {
  if (!Number.isFinite(n)) return n
  const places = Math.abs(n) >= 100 ? 2 : 4
  const f = 10 ** places
  return Math.round(n * f) / f
}

/** Drops null/undefined fields and empty arrays/objects, recursively, and rounds numbers. */
export function trimReportValue(value: Json): Json {
  if (typeof value === 'number') return roundNumber(value)
  if (Array.isArray(value)) return value.map(trimReportValue)
  if (value && typeof value === 'object') {
    const out: Record<string, Json> = {}
    for (const [k, v] of Object.entries(value as Record<string, Json>)) {
      if (v === null || v === undefined) continue
      const t = trimReportValue(v)
      if (Array.isArray(t) && t.length === 0) continue
      if (t && typeof t === 'object' && !Array.isArray(t) && Object.keys(t as object).length === 0) continue
      out[k] = t
    }
    return out
  }
  return value
}

interface ReportPayload extends Record<string, Json> {
  keywords?: Json[]
  ads?: Json[]
}

const ALWAYS_PRESENT = ['sources', 'headline', 'acquisition', 'daily', 'scorecard', 'keywords', 'ads', 'benchmarks']

export async function getMarketingReport(
  config: Config,
  params: { window?: string }
): Promise<Record<string, unknown>> {
  const window = params.window ?? '28d'
  if (window !== '7d' && window !== '28d') throw new Error(`Unknown window "${window}": use "7d" or "28d"`)
  const report = await apiGet<ReportPayload>(
    `/api/v2/projects/${config.projectId}/marketing/report?window=${window}`,
    config
  )
  const keywords = report.keywords ?? []
  const ads = report.ads ?? []
  const trimmed = trimReportValue({
    ...report,
    keywords: keywords.slice(0, MAX_REPORT_KEYWORDS),
    ads: ads.slice(0, MAX_REPORT_ADS),
  }) as Record<string, unknown>
  // An emptied section must still read as "none", not as a missing key.
  for (const k of ALWAYS_PRESENT) if (!(k in trimmed)) trimmed[k] = []
  const totalRows: Record<string, number> = {}
  if (keywords.length > MAX_REPORT_KEYWORDS) totalRows['keywords'] = keywords.length
  if (ads.length > MAX_REPORT_ADS) totalRows['ads'] = ads.length
  return Object.keys(totalRows).length > 0 ? { ...trimmed, totalRows } : trimmed
}

const METRIC_REF = /^[A-Za-z0-9_]+\.[A-Za-z0-9_:-]+$/

function checkMetricRef(metricRef: string): void {
  if (!metricRef || !METRIC_REF.test(metricRef)) {
    throw new Error('metricRef must be CHANNEL.metric (e.g. STORE.conversion_rate) or PRODUCT.<key>')
  }
}

export interface SetBenchmarkParams {
  metricRef: string
  label?: string
  value: number
  rangeText?: string
  segment?: string
  sourceName: string
  sourceUrl: string
  checkedOn: string
  notes?: string
}

export async function setMarketingBenchmark(config: Config, p: SetBenchmarkParams): Promise<Record<string, unknown>> {
  checkMetricRef(p.metricRef)
  if (typeof p.value !== 'number' || !Number.isFinite(p.value)) throw new Error('value must be a number')
  if (!p.sourceName?.trim()) throw new Error('sourceName is required: name the source you read')
  if (!/^https?:\/\//i.test(p.sourceUrl ?? '')) {
    throw new Error('sourceUrl must be the http(s) URL of the page you read this session; a benchmark is never recorded from memory')
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(p.checkedOn ?? '')) throw new Error('checkedOn must be the day you read the source, YYYY-MM-DD')
  const body = {
    label: p.label,
    value: p.value,
    rangeText: p.rangeText,
    segment: p.segment,
    sourceName: p.sourceName,
    sourceUrl: p.sourceUrl,
    checkedOn: p.checkedOn,
    notes: p.notes,
  }
  const saved = await apiPut<Record<string, unknown>>(
    `/api/v2/projects/${config.projectId}/marketing/benchmarks/${encodeURIComponent(p.metricRef)}`,
    body,
    config
  )
  return trimReportValue(saved) as Record<string, unknown>
}

export async function deleteMarketingBenchmark(config: Config, p: { metricRef: string }): Promise<Record<string, unknown>> {
  checkMetricRef(p.metricRef)
  await apiDelete(`/api/v2/projects/${config.projectId}/marketing/benchmarks/${encodeURIComponent(p.metricRef)}`, config)
  return { deleted: p.metricRef }
}
