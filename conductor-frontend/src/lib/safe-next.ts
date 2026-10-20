// Where to send someone after login. Only same-origin relative paths are allowed: `//evil.example` and
// `/\evil.example` both start with "/" but browsers resolve them to another host.

export const DEFAULT_NEXT = '/app/projects'

export function safeNext(next: string | null | undefined): string {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\')) return DEFAULT_NEXT
  return next
}

/** The `/login?next=…` URL that returns to `pathWithSearch` (path and query string) after sign-in. */
export function loginUrlFor(pathWithSearch: string): string {
  return `/login?next=${encodeURIComponent(safeNext(pathWithSearch))}`
}
