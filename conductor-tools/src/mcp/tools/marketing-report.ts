import { Config } from '../config.js'
import { apiGet, apiPut, apiDelete } from '../api.js'

/**
 * The whole-funnel marketing report (`/marketing/report`) and the industry benchmarks it compares against
 * (`/marketing/benchmarks`). The payload is already compact; this trims what an agent does not need
 * (nulls, float noise, long tails) per `docs/mcp-tool-guidelines.md`.
 */

export const MAX_REPORT_KEYWORDS = 25
export const MAX_REPORT_ADS = 20
export const MAX_REPORT_ADS_COVERAGE = 20
export const MAX_REPORT_CHANGE_MARKERS = 20
/** The backend lists up to 50 ASO keywords, best organic rank first; an agent needs the best 20. */
export const MAX_REPORT_ASO_KEYWORDS = 20
/**
 * Open changes and changes reviewed in the window, soonest due first. Each is listed by id, title, status and due
 * day only: their hypothesis, baseline and evidence fields made up over half of a live report. Changes due for
 * review keep every field, since judging them needs those.
 */
export const MAX_REPORT_CHANGES = 25
/** The change fields kept on an open or reviewed change: when it went live and, once reviewed, the verdict. */
const BRIEF_CHANGE_FIELDS = ['liveSince', 'verdict']

type Json = unknown

/** A change without its long fields: enough to name it, see where it stands and fetch it by displayId. */
function briefChange(change: Json): Json {
  if (!change || typeof change !== 'object') return change
  const { fields, ...rest } = change as Record<string, Json>
  const kept: Record<string, Json> = {}
  if (fields && typeof fields === 'object') {
    for (const k of BRIEF_CHANGE_FIELDS) {
      const v = (fields as Record<string, Json>)[k]
      if (v !== undefined) kept[k] = v
    }
  }
  return Object.keys(kept).length > 0 ? { ...rest, fields: kept } : rest
}

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
  adsCoverage?: Json[]
  changeMarkers?: Json[]
  asoKeywords?: Json[]
  changes?: { open?: Json[]; dueForReview?: Json[]; reviewedInWindow?: Json[] } | null
}

const ALWAYS_PRESENT = [
  'sources',
  'headline',
  'acquisition',
  'daily',
  'scorecard',
  'keywords',
  'ads',
  'benchmarks',
  'adsCoverage',
  'changeMarkers',
]

export async function getMarketingReport(
  config: Config,
  params: { window?: string }
): Promise<Record<string, unknown>> {
  const window = params.window ?? '28d'
  if (window !== '7d' && window !== '28d' && !/^\d{4}-\d{2}-\d{2}\.\.\d{4}-\d{2}-\d{2}$/.test(window)) {
    throw new Error(`Unknown window "${window}": use "7d", "28d" or a date range "YYYY-MM-DD..YYYY-MM-DD"`)
  }
  const report = await apiGet<ReportPayload>(
    `/api/v2/projects/${config.projectId}/marketing/report?window=${window}`,
    config
  )
  const keywords = report.keywords ?? []
  const ads = report.ads ?? []
  const adsCoverage = report.adsCoverage ?? []
  // Markers arrive oldest first; when there are too many the newest are the ones worth marking.
  const changeMarkers = report.changeMarkers ?? []
  // Arrive best organic rank first, so the head is the part to keep.
  const asoKeywords = report.asoKeywords ?? []
  const openChanges = report.changes?.open ?? []
  const reviewedChanges = report.changes?.reviewedInWindow ?? []
  const trimmed = trimReportValue({
    ...report,
    keywords: keywords.slice(0, MAX_REPORT_KEYWORDS),
    ads: ads.slice(0, MAX_REPORT_ADS),
    adsCoverage: adsCoverage.slice(0, MAX_REPORT_ADS_COVERAGE),
    changeMarkers: changeMarkers.slice(-MAX_REPORT_CHANGE_MARKERS),
    asoKeywords: asoKeywords.slice(0, MAX_REPORT_ASO_KEYWORDS),
    changes: report.changes
      ? {
          ...report.changes,
          open: openChanges.slice(0, MAX_REPORT_CHANGES).map(briefChange),
          reviewedInWindow: reviewedChanges.slice(0, MAX_REPORT_CHANGES).map(briefChange),
        }
      : report.changes,
  }) as Record<string, unknown>
  // An emptied section must still read as "none", not as a missing key.
  for (const k of ALWAYS_PRESENT) if (!(k in trimmed)) trimmed[k] = []
  if (!('definitions' in trimmed)) trimmed['definitions'] = {}
  // All three change buckets stay present even when empty: "nothing due" must not read as a missing key.
  const changes = (trimmed['changes'] ?? {}) as Record<string, unknown>
  trimmed['changes'] = {
    open: changes['open'] ?? [],
    dueForReview: changes['dueForReview'] ?? [],
    reviewedInWindow: changes['reviewedInWindow'] ?? [],
  }
  // window.to is null until a source has data; keep the key so "no data yet" is explicit.
  const w = report['window'] as { from?: string | null; to?: string | null } | undefined
  if (w) trimmed['window'] = { from: w.from ?? null, to: w.to ?? null }
  // The backend already caps keywords at MAX_REPORT_KEYWORDS, so only the other lists can run past their cap here.
  const totalRows: Record<string, number> = {}
  if (ads.length > MAX_REPORT_ADS) totalRows['ads'] = ads.length
  if (adsCoverage.length > MAX_REPORT_ADS_COVERAGE) totalRows['adsCoverage'] = adsCoverage.length
  if (changeMarkers.length > MAX_REPORT_CHANGE_MARKERS) totalRows['changeMarkers'] = changeMarkers.length
  if (asoKeywords.length > MAX_REPORT_ASO_KEYWORDS) totalRows['asoKeywords'] = asoKeywords.length
  if (openChanges.length > MAX_REPORT_CHANGES) totalRows['changes.open'] = openChanges.length
  if (reviewedChanges.length > MAX_REPORT_CHANGES) totalRows['changes.reviewedInWindow'] = reviewedChanges.length
  return Object.keys(totalRows).length > 0 ? { ...trimmed, totalRows } : trimmed
}

// the metric part mirrors the backend's METRIC_KEY ([A-Za-z0-9_.-]{1,64}, MarketingBenchmarkService)
const METRIC_REF = /^[A-Za-z0-9_]+\.[A-Za-z0-9_.-]{1,64}$/

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
  numerator?: string
  denominator?: string
  sourceType?: string
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
    numerator: p.numerator,
    denominator: p.denominator,
    sourceType: p.sourceType,
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
