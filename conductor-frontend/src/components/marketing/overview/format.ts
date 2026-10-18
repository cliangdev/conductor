// Display helpers for the marketing overview: units, change direction, targets, windows.

import type { MarketingTarget, MetricUnit, OverviewChannel, OverviewMetric, OverviewMismatch, TargetDirection } from './types'

const PLACEHOLDER = '—'

/** Currency falls back to USD: workspaces have no currency setting yet. */
export function formatMetricValue(value: number | null | undefined, unit: MetricUnit, currency = 'USD'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return PLACEHOLDER
  switch (unit) {
    case 'PERCENT':
      return `${(value * 100).toFixed(1)}%`
    case 'POSITION':
    case 'RATING':
      return value.toFixed(1)
    case 'CURRENCY':
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency,
        maximumFractionDigits: Math.abs(value) >= 100 ? 0 : 2,
      }).format(value)
    case 'COUNT':
    default:
      return Math.abs(value) >= 100000
        ? new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(value)
        : new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(value)
  }
}

export type ChangeTone = 'good' | 'bad' | 'neutral'

export function changeTone(metric: OverviewMetric): ChangeTone {
  const change = metric.change
  if (change === null || change === undefined || change === 0 || metric.noise) return 'neutral'
  const up = change > 0
  return up === (metric.goodDirection === 'UP') ? 'good' : 'bad'
}

export const TONE_CLASS: Record<ChangeTone, string> = {
  good: 'text-status-done',
  bad: 'text-status-failed',
  neutral: 'text-muted-foreground',
}

/** "+12.3%" / "-4.0%"; null when there is no prior period to compare with. */
export function changeLabel(changePct: number | null | undefined): string | null {
  if (changePct === null || changePct === undefined || Number.isNaN(changePct)) return null
  const pct = changePct * 100
  const sign = pct > 0 ? '+' : ''
  return `${sign}${pct.toFixed(1)}%`
}

/** Fraction of the target reached, 0..n. For a DOWN target it is target / value (reaching it is 100%). */
export function targetProgress(value: number | null | undefined, target: MarketingTarget): number | null {
  if (value === null || value === undefined || target.value === 0) return null
  if (target.direction === 'UP') return value / target.value
  return value === 0 ? null : target.value / value
}

export function targetMet(value: number | null | undefined, target: MarketingTarget): boolean {
  if (value === null || value === undefined) return false
  return target.direction === 'UP' ? value >= target.value : value <= target.value
}

export function targetLine(metric: OverviewMetric): string | null {
  if (!metric.target) return null
  const shown = formatMetricValue(metric.target.value, metric.unit)
  if (targetMet(metric.value, metric.target)) return `Target met (${shown})`
  const progress = targetProgress(metric.value, metric.target)
  const label = `Target ${shown}`
  return progress === null ? label : `${label} · ${Math.round(progress * 100)}% there`
}

/** What a person types for a target: PERCENT is entered as 4 meaning 4%. */
export function targetToInput(value: number, unit: MetricUnit): string {
  const shown = unit === 'PERCENT' ? value * 100 : value
  return String(Number(shown.toFixed(4)))
}

/** Parses the typed target; null when not a finite non-negative number. PERCENT 4 → 0.04. */
export function inputToTarget(text: string, unit: MetricUnit): number | null {
  const trimmed = text.trim()
  if (!trimmed) return null
  const n = Number(trimmed)
  if (!Number.isFinite(n) || n < 0) return null
  return unit === 'PERCENT' ? Number((n / 100).toPrecision(12)) : n
}

/** "Sep 3" from an ISO date or timestamp. */
export function shortDate(iso: string | null | undefined): string | null {
  if (!iso) return null
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso)
  if (Number.isNaN(d.getTime())) return null
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })
}

/** "Oct 11" for one day; "Oct 11–12" (same month) or "Oct 30 – Nov 2" when channels are at different days. */
export function dataThroughLabel(min: string | null | undefined, to: string | null | undefined): string | null {
  if (!to) return null
  const end = shortDate(to)
  const start = shortDate(min)
  if (!end) return null
  if (!start || min === to) return end
  const [startMonth] = start.split(' ')
  const [, endDay] = end.split(' ')
  return startMonth === end.split(' ')[0] ? `${start}–${endDay}` : `${start} – ${end}`
}

/**
 * "Oct 15" when every channel with data is complete through the same day; otherwise the earliest channel is
 * named so a range explains itself: "Oct 11 (App Store) – Oct 15".
 */
export function dataThroughSummary(channels: OverviewChannel[], fallbackTo: string | null | undefined): string | null {
  const reportingAll = channels
    .filter((c) => (c.status === 'LIVE' || c.status === 'STALE' || c.status === 'MISMATCH') && c.dataCompleteThrough)
  // A stale channel is left out of the funnel window, so it must not set the header's earliest day either
  // (unless every reporting channel is stale).
  const current = reportingAll.filter((c) => c.status !== 'STALE')
  const reporting = (current.length > 0 ? current : reportingAll)
    .sort((a, b) => (a.dataCompleteThrough as string).localeCompare(b.dataCompleteThrough as string))
  if (reporting.length === 0) return dataThroughLabel(null, fallbackTo)
  const first = reporting[0]
  const last = reporting[reporting.length - 1]
  if (first.dataCompleteThrough === last.dataCompleteThrough) return shortDate(last.dataCompleteThrough)
  return `${shortDate(first.dataCompleteThrough)} (${first.label}) – ${shortDate(last.dataCompleteThrough)}`
}

// ── Chart axes ────────────────────────────────────────────────────────────────

function formatWithDecimals(value: number, unit: MetricUnit, decimals: number): string {
  switch (unit) {
    case 'PERCENT':
      return `${(value * 100).toFixed(decimals)}%`
    case 'POSITION':
    case 'RATING':
      return value.toFixed(decimals)
    case 'CURRENCY':
      return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: 'USD',
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals,
      }).format(value)
    default:
      return formatMetricValue(value, 'COUNT')
  }
}

function niceStep(raw: number): number {
  const pow = 10 ** Math.floor(Math.log10(raw))
  const f = raw / pow
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 5 ? 5 : 10) * pow
}

export interface ChartAxis {
  domain: [number, number]
  ticks: number[]
  format: (value: number) => string
}

/** Y axis for a metric's daily series (and its target): counts start at zero; other units pad the
 *  range to at least +-10% of the mean so a tiny wobble does not look dramatic; tick labels get enough
 *  precision that neighbouring ticks never read the same. */
export function chartAxis(
  values: (number | null | undefined)[],
  unit: MetricUnit,
  target?: number | null,
  /** A summed flow (spend, revenue) reads honestly from zero; ratios and levels (rating, position) do not. */
  includeZero = false,
): ChartAxis {
  const nums = values.filter((v): v is number => v !== null && v !== undefined && Number.isFinite(v))
  if (target !== null && target !== undefined) nums.push(target)
  if (includeZero && nums.length > 0) nums.push(0)
  if (nums.length === 0) return { domain: [0, 1], ticks: [0, 1], format: (v) => formatMetricValue(v, unit) }
  const min = Math.min(...nums)
  const max = Math.max(...nums)

  if (unit === 'COUNT') {
    // Whole numbers only: a 0-1 series must read 0, 1, not 0, 0.5, 1 rounded to "0, 1, 1".
    const step = Math.max(1, niceStep(Math.max(max, 1) / 4))
    const top = Math.ceil(Math.max(max, 1) / step) * step
    const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step)
    return { domain: [0, top], ticks, format: (v) => formatMetricValue(v, 'COUNT') }
  }

  const mean = nums.reduce((a, b) => a + b, 0) / nums.length
  const minSpan = Math.max(Math.abs(mean) * 0.2, Number.EPSILON)
  const mid = (min + max) / 2
  const span = Math.max(max - min, minSpan)
  const rawLo = mid - span / 2 - span * 0.05
  const rawHi = mid + span / 2 + span * 0.05
  // Round the domain outward to a step of 1, 2 or 5 x 10^n so ticks read as round numbers.
  const step = niceStep((rawHi - rawLo) / 4)
  let lo = Math.floor(rawLo / step) * step
  if (min >= 0 && lo < 0) lo = 0
  let hi = Math.ceil(rawHi / step) * step
  // A star rating tops out at 5: never pad the axis beyond it.
  if (unit === 'RATING' && hi > 5) hi = Math.max(5, lo + step)
  const count = Math.round((hi - lo) / step) + 1
  let ticks = Array.from({ length: count }, (_, i) => Number((lo + step * i).toPrecision(12)))
  if (unit === 'RATING') {
    ticks = ticks.filter((t) => t <= hi)
    if (ticks[ticks.length - 1] < hi) ticks.push(hi)
  }
  let decimals = unit === 'POSITION' || unit === 'RATING' ? 1 : 0
  for (; decimals < 6; decimals++) {
    const labels = ticks.map((t) => formatWithDecimals(t, unit, decimals))
    if (new Set(labels).size === labels.length) break
  }
  return { domain: [lo, hi], ticks, format: (v) => formatWithDecimals(v, unit, decimals) }
}

/** Stage-to-stage conversion (this stage's value over the previous available stage's), or null. */
export function conversionRate(prev: number | null | undefined, current: number | null | undefined): number | null {
  if (prev === null || prev === undefined || current === null || current === undefined || prev <= 0) return null
  return current / prev
}

/** "Set in Product metrics": where a read-only (feed-owned) target is changed. */
export function readOnlyTargetSource(target: MarketingTarget): string {
  const source = target.source?.trim()
  if (!source) return 'Set in the connected source'
  return /^set in /i.test(source) ? source : `Set in ${source}`
}

/** What the channel's own connection and the product feed each say, in words a non-engineer can compare. */
export function mismatchSummary(m: OverviewMismatch, channelLabel: string): string {
  const currency = m.metric === 'spend'
  const unit: MetricUnit = currency ? 'CURRENCY' : 'COUNT'
  const noun = m.label.toLowerCase()
  const amount = (v: number) => (currency ? `${formatMetricValue(v, unit)} in ${noun}` : `${formatMetricValue(v, unit)} ${noun}`)
  const own = m.channelSource || channelLabel
  const reference = `Product metrics${m.referenceSource ? ` (${m.referenceSource})` : ''}`
  const range = m.from === m.to ? shortDate(m.from) : `${shortDate(m.from)} – ${shortDate(m.to)}`
  return `${own} reports ${amount(m.channelValue)}; ${reference} reports ${formatMetricValue(m.referenceValue, unit)} — ${(m.gapPct * 100).toFixed(1)}% apart for ${range}, the days both have finished reporting.`
}

/** The short card line: "Downloads differ from Rexipe backend by 29.9%". */
export function mismatchCardLine(m: OverviewMismatch): string {
  return `${m.label} differ from ${m.referenceSource || 'Product metrics'} by ${(m.gapPct * 100).toFixed(1)}%`
}
