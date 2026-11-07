// Checks a "record a read" form the way the API will, so a mistake is caught before the request.

import type { MetricUnit } from '@/components/marketing/overview/types'
import { dayCount } from './format'
import type { PlatformReadRequest } from './types'

export const MAX_READ_DAYS = 92
export const MAX_SOURCE_NAME = 120
export const MAX_NOTE = 500

export interface ReadFormValues {
  channel: string
  metric: string
  from: string
  to: string
  /** As typed. */
  value: string
  sourceName: string
  note: string
}

export const EMPTY_READ_FORM: ReadFormValues = { channel: '', metric: '', from: '', to: '', value: '', sourceName: '', note: '' }

function isCalendarDay(text: string): boolean {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text)
  if (!m) return false
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
  return d.getUTCFullYear() === Number(m[1]) && d.getUTCMonth() === Number(m[2]) - 1 && d.getUTCDate() === Number(m[3])
}

/** Yesterday in UTC, the latest day a read may end on: today is still partial. */
export function yesterdayUtc(now: Date): string {
  return new Date(now.getTime() - 86_400_000).toISOString().slice(0, 10)
}

/** "1204", "1,204" and "162.86" are numbers; "1,5" is not guessed at. Null when it is not a number of zero or more. */
export function parseReadValue(text: string): number | null {
  const trimmed = text.trim()
  if (!trimmed) return null
  const plain = /^\d{1,3}(,\d{3})+(\.\d+)?$/.test(trimmed) ? trimmed.replace(/,/g, '') : trimmed
  if (!/^\d+(\.\d+)?$/.test(plain)) return null
  const n = Number(plain)
  return Number.isFinite(n) ? n : null
}

/** The first problem with the form, or null when it can be sent. */
export function validateRead(v: ReadFormValues, now: Date = new Date()): string | null {
  if (!v.channel || !v.metric) return 'Pick the metric that was read.'
  if (!isCalendarDay(v.from) || !isCalendarDay(v.to)) return 'Enter the first and last day the platform showed.'
  if (v.from > v.to) return 'The first day must be on or before the last day.'
  if (v.to > yesterdayUtc(now)) return `The last day must be yesterday or earlier (${yesterdayUtc(now)}): today is still partial.`
  if ((dayCount(v.from, v.to) ?? 0) > MAX_READ_DAYS) return `Read at most ${MAX_READ_DAYS} days at a time.`
  if (parseReadValue(v.value) === null) return "Enter the platform's figure as a number of zero or more."
  const source = v.sourceName.trim()
  if (!source) return 'Say where the figure was read, for example Apple Ads console.'
  if (source.length > MAX_SOURCE_NAME) return `The source is at most ${MAX_SOURCE_NAME} characters.`
  if (v.note.trim().length > MAX_NOTE) return `The note is at most ${MAX_NOTE} characters.`
  return null
}

/** The request body for a valid form. A percent metric is typed as 4 for 4% and sent as the fraction 0.04. */
export function readToRequest(v: ReadFormValues, unit: MetricUnit): PlatformReadRequest {
  const typed = parseReadValue(v.value) as number
  const note = v.note.trim()
  return {
    channel: v.channel,
    metric: v.metric,
    from: v.from,
    to: v.to,
    value: unit === 'PERCENT' ? Number((typed / 100).toPrecision(12)) : typed,
    sourceName: v.sourceName.trim(),
    ...(note ? { note } : {}),
  }
}
