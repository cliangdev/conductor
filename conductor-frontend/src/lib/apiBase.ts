/**
 * Per-browser choice of how the browser reaches the backend:
 *  - `direct` (default): cross-origin to NEXT_PUBLIC_API_URL, which costs a CORS preflight per new URL.
 *  - `proxy`: same-origin `/_backend/*`, rewritten to the backend by next.config.mjs, so no preflight.
 *
 * Opt in with `?api=proxy`, back out with `?api=direct`; the choice is kept in localStorage so it
 * survives navigation. Storage access is always wrapped: when it is unavailable we stay direct.
 * See docs/performance.md. Server-side code never uses this; it always talks to the backend directly.
 */

export type ApiVia = 'direct' | 'proxy'

export const API_VIA_STORAGE_KEY = 'conductor.apiVia'
export const API_VIA_QUERY_PARAM = 'api'
/** Same-origin prefix; must not collide with the app's own /api/* routes. Keep in sync with next.config.mjs. */
export const PROXY_PREFIX = '/_backend'

function parse(value: string | null | undefined): ApiVia | null {
  return value === 'proxy' || value === 'direct' ? value : null
}

function readStored(): ApiVia | null {
  try {
    return parse(window.localStorage.getItem(API_VIA_STORAGE_KEY))
  } catch {
    return null
  }
}

function writeStored(via: ApiVia) {
  try {
    window.localStorage.setItem(API_VIA_STORAGE_KEY, via)
  } catch { /* storage unavailable: the query param still applies for this page load */ }
}

/** The proxy only exists when the build knows an absolute backend URL (see next.config.mjs). */
function proxyAvailable(): boolean {
  return /^https?:\/\//.test(process.env.NEXT_PUBLIC_API_URL ?? '')
}

export function getApiVia(): ApiVia {
  if (typeof window === 'undefined' || !proxyAvailable()) return 'direct'
  let fromQuery: ApiVia | null = null
  try {
    fromQuery = parse(new URLSearchParams(window.location.search).get(API_VIA_QUERY_PARAM))
  } catch { /* ignore */ }
  if (fromQuery) {
    if (readStored() !== fromQuery) writeStored(fromQuery)
    return fromQuery
  }
  return readStored() ?? 'direct'
}

/** Base URL (no trailing slash) to prefix API paths with in browser fetches. */
export function getApiBase(via: ApiVia = getApiVia()): string {
  return via === 'proxy' ? PROXY_PREFIX : (process.env.NEXT_PUBLIC_API_URL ?? '')
}
