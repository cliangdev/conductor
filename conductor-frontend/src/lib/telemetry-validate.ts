/**
 * Validation for the unauthenticated /api/telemetry route: unknown sample types are dropped, strings
 * are truncated and only scalar fields are kept. Lives outside route.ts because Next.js only allows
 * HTTP-method exports there.
 */

export const MAX_SAMPLES = 100
export const MAX_BODY_BYTES = 64 * 1024
export const MAX_STRING = 200
export const ALLOWED_TYPES = ['api_call', 'web_vital', 'page_ready'] as const

const MAX_FIELDS = 20
const FIELD_NAME = /^[A-Za-z][A-Za-z0-9_]{0,31}$/

/** Allowed values of an api_call sample's `via` field; any other value is dropped from the sample. */
export const ALLOWED_VIA = ['direct', 'proxy'] as const

export type CleanSample = Record<string, string | number | boolean>

/** Returns a cleaned sample, or null when it should be dropped. */
export function sanitizeSample(raw: unknown): CleanSample | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const input = raw as Record<string, unknown>
  if (typeof input.type !== 'string' || !(ALLOWED_TYPES as readonly string[]).includes(input.type)) return null
  const out: CleanSample = {}
  let fields = 0
  for (const [key, value] of Object.entries(input)) {
    if (fields >= MAX_FIELDS) break
    if (!FIELD_NAME.test(key) || key === 'severity' || key === 'message') continue
    if (key === 'via' && !(ALLOWED_VIA as readonly unknown[]).includes(value)) continue
    if (typeof value === 'string') out[key] = value.slice(0, MAX_STRING)
    else if (typeof value === 'number' && Number.isFinite(value)) out[key] = value
    else if (typeof value === 'boolean') out[key] = value
    else continue
    fields += 1
  }
  return out
}

/** Cleans a parsed request body; returns the samples worth logging (at most MAX_SAMPLES). */
export function sanitizeBatch(body: unknown): CleanSample[] {
  const samples = body && typeof body === 'object' ? (body as { samples?: unknown }).samples : undefined
  if (!Array.isArray(samples)) return []
  const clean: CleanSample[] = []
  for (const raw of samples.slice(0, MAX_SAMPLES)) {
    const s = sanitizeSample(raw)
    if (s) clean.push(s)
  }
  return clean
}
