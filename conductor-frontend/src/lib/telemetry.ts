/**
 * Lightweight client telemetry: API call timings, web vitals and page-ready marks are buffered in
 * memory and beaconed to the same-origin `/api/telemetry` route, which logs them as JSON lines
 * (see docs/performance.md). Everything here is best-effort and must never throw into app code.
 */

export type TelemetrySample = {
  type: 'api_call' | 'web_vital' | 'page_ready'
  [key: string]: string | number | boolean | undefined
}

export const TELEMETRY_ENDPOINT = '/api/telemetry'
export const FLUSH_INTERVAL_MS = 5000
/** Matches the server route's per-request cap. */
export const MAX_SAMPLES_PER_BEACON = 100
const MAX_BUFFERED = 300

let forcedEnabled: boolean | null = null
let buffer: TelemetrySample[] = []
let timer: ReturnType<typeof setInterval> | null = null
let listenersAttached = false

/** On in the browser outside tests; tests opt in explicitly. Set NEXT_PUBLIC_TELEMETRY=off to disable. */
export function isTelemetryEnabled(): boolean {
  if (forcedEnabled !== null) return forcedEnabled
  if (typeof window === 'undefined') return false
  if (process.env.NODE_ENV === 'test') return false
  return process.env.NEXT_PUBLIC_TELEMETRY !== 'off'
}

/** Force telemetry on or off (tests). Pass null to restore the default. */
export function setTelemetryEnabled(value: boolean | null) {
  forcedEnabled = value
}

/** Drop buffered samples and timers (tests). */
export function resetTelemetry() {
  buffer = []
  if (timer) clearInterval(timer)
  timer = null
  navigation.id = 0
  navigation.startedAt = 0
  navigation.apiCalls = 0
  navigation.readyMarked = false
}

// ── navigation bookkeeping (shared by api.ts and perf.ts) ───────────────────

export const navigation = {
  id: 0,
  /** performance.now() value at navigation start; 0 = the document load (timeOrigin). */
  startedAt: 0,
  apiCalls: 0,
  readyMarked: false,
}

export function startNavigation(now: number = nowMs()) {
  navigation.id += 1
  navigation.startedAt = now
  navigation.apiCalls = 0
  navigation.readyMarked = false
}

export function nowMs(): number {
  return typeof performance !== 'undefined' ? performance.now() : Date.now()
}

// ── normalisation ───────────────────────────────────────────────────────────

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DISPLAY_KEY = /^[A-Z][A-Z0-9]{1,9}-\d+$/
const NUMERIC = /^\d+$/

/** Strip origin and query/hash; replace UUIDs, display keys (ABC-123) and numeric ids with `:id`. */
export function normalizePath(input: string): string {
  let path = input ?? ''
  try {
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(path)) path = new URL(path).pathname
  } catch { /* keep as is */ }
  path = path.split('#')[0].split('?')[0]
  return path
    .split('/')
    .map((seg) => (UUID.test(seg) || DISPLAY_KEY.test(seg) || NUMERIC.test(seg) ? ':id' : seg))
    .join('/')
}

export function currentPage(): string {
  if (typeof window === 'undefined') return ''
  return normalizePath(window.location.pathname)
}

export type ServerTiming = { dbMs?: number; dbQueries?: number; serverMs?: number }

/** Parse `db;dur=5;desc="2 queries", pool;dur=1, app;dur=12`. Missing or malformed parts are omitted. */
export function parseServerTiming(header: string | null | undefined): ServerTiming {
  const out: ServerTiming = {}
  if (!header) return out
  for (const part of header.split(',')) {
    const [rawName, ...params] = part.trim().split(';')
    const name = rawName.trim()
    let dur: number | undefined
    let desc: string | undefined
    for (const p of params) {
      const [k, ...v] = p.trim().split('=')
      const val = v.join('=').trim().replace(/^"|"$/g, '')
      if (k === 'dur') {
        const n = Number(val)
        if (Number.isFinite(n)) dur = n
      } else if (k === 'desc') desc = val
    }
    if (name === 'db') {
      if (dur !== undefined) out.dbMs = dur
      const m = desc?.match(/(\d+)/)
      if (m) out.dbQueries = Number(m[1])
    } else if (name === 'app' && dur !== undefined) {
      out.serverMs = dur
    }
  }
  return out
}

// ── buffer and flush ────────────────────────────────────────────────────────

export function enqueue(sample: TelemetrySample) {
  try {
    if (!isTelemetryEnabled()) return
    buffer.push(sample)
    if (buffer.length > MAX_BUFFERED) buffer.splice(0, buffer.length - MAX_BUFFERED)
    ensureFlushing()
  } catch { /* telemetry must never throw */ }
}

export function bufferedCount(): number {
  return buffer.length
}

function ensureFlushing() {
  if (typeof window === 'undefined') return
  if (!timer) timer = setInterval(flush, FLUSH_INTERVAL_MS)
  if (!listenersAttached) {
    listenersAttached = true
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'hidden') flush()
    })
    window.addEventListener('pagehide', flush)
  }
}

export function flush() {
  try {
    while (buffer.length > 0) {
      const batch = buffer.splice(0, MAX_SAMPLES_PER_BEACON)
      send(JSON.stringify({ samples: batch }))
    }
  } catch { /* telemetry must never throw */ }
}

function send(body: string) {
  if (typeof navigator !== 'undefined' && typeof navigator.sendBeacon === 'function') {
    navigator.sendBeacon(TELEMETRY_ENDPOINT, new Blob([body], { type: 'application/json' }))
  } else if (typeof fetch === 'function') {
    void fetch(TELEMETRY_ENDPOINT, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true,
    }).catch(() => {})
  }
}

// ── api_call recording ──────────────────────────────────────────────────────

export function recordApiCall(args: {
  path: string
  method: string
  status: number
  clientMs: number
  serverTiming: string | null | undefined
  /** Whether the call went cross-origin to the backend or through the same-origin proxy. */
  via?: 'direct' | 'proxy'
}) {
  try {
    if (!isTelemetryEnabled()) return
    // Don't let telemetry measure itself.
    if (args.path.startsWith(TELEMETRY_ENDPOINT)) return
    const st = parseServerTiming(args.serverTiming)
    navigation.apiCalls += 1
    enqueue({
      type: 'api_call',
      path: normalizePath(args.path),
      method: args.method,
      status: args.status,
      clientMs: Math.round(args.clientMs),
      serverMs: st.serverMs,
      dbMs: st.dbMs,
      dbQueries: st.dbQueries,
      via: args.via ?? 'direct',
      page: currentPage(),
    })
  } catch { /* telemetry must never throw */ }
}
