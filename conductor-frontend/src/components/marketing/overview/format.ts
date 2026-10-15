// Display helpers for the marketing overview: units, change direction, targets, windows.

import type { MarketingTarget, MetricUnit, OverviewMetric, TargetDirection } from './types'

const PLACEHOLDER = '—'

/** Currency falls back to USD: workspaces have no currency setting yet. */
export function formatMetricValue(value: number | null | undefined, unit: MetricUnit, currency = 'USD'): string {
  if (value === null || value === undefined || Number.isNaN(value)) return PLACEHOLDER
  switch (unit) {
    case 'PERCENT':
      return `${(value * 100).toFixed(1)}%`
    case 'POSITION':
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

export function targetLine(metric: OverviewMetric): string | null {
  if (!metric.target) return null
  const progress = targetProgress(metric.value, metric.target)
  const label = `Target ${formatMetricValue(metric.target.value, metric.unit)}`
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

/** Stage-to-stage conversion (this stage's value over the previous available stage's), or null. */
export function conversionRate(prev: number | null | undefined, current: number | null | undefined): number | null {
  if (prev === null || prev === undefined || current === null || current === undefined || prev <= 0) return null
  return current / prev
}
