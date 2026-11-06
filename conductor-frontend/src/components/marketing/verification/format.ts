// Display helpers for the verification ledger. Figures here are compared by eye, so counts are never compacted
// ("100K" would hide a gap of 40) and an unknown figure is a dash, never 0.

import { shortDate } from '@/components/marketing/overview/format'
import type { MetricUnit } from '@/components/marketing/overview/types'

const PLACEHOLDER = '—'

export function formatLedgerValue(value: number | null | undefined, unit: MetricUnit): string {
  if (value === null || value === undefined || Number.isNaN(value)) return PLACEHOLDER
  switch (unit) {
    case 'CURRENCY':
      return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value)
    case 'PERCENT':
      return `${(value * 100).toFixed(2)}%`
    case 'POSITION':
    case 'RATING':
      return value.toFixed(2)
    case 'COUNT':
    default:
      return new Intl.NumberFormat('en-US', { maximumFractionDigits: Number.isInteger(value) ? 0 : 2 }).format(value)
  }
}

const DAY_MS = 86_400_000

function utcDay(iso: string): number | null {
  const t = Date.parse(`${iso.slice(0, 10)}T00:00:00Z`)
  return Number.isNaN(t) ? null : t
}

/** Days from `from` to `to`, counting both ends; null when either is missing. */
export function dayCount(from: string | null | undefined, to: string | null | undefined): number | null {
  if (!from || !to) return null
  const a = utcDay(from)
  const b = utcDay(to)
  return a === null || b === null ? null : Math.round((b - a) / DAY_MS) + 1
}

/** "Sep 1 – Sep 28", or "Sep 3" for a single day; null when a day is missing. */
export function dayRange(from: string | null | undefined, to: string | null | undefined): string | null {
  const start = shortDate(from)
  const end = shortDate(to)
  if (!start || !end) return null
  return from === to ? start : `${start} – ${end}`
}

export function daysLabel(count: number): string {
  return `${count} ${count === 1 ? 'day' : 'days'}`
}
