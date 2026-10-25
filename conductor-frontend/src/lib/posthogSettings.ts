/** Pure helpers behind the PostHog connect form, edit dialog, header state and chart axis. */

export type PostHogRegion = 'us' | 'eu' | 'custom'

export interface PostHogFormValues {
  projectId: string
  region: PostHogRegion
  /** Only read when region is 'custom'. */
  customHost: string
  eventFilter: string
  conversionEvent: string
}

export const EMPTY_POSTHOG_FORM: PostHogFormValues = {
  projectId: '',
  region: 'us',
  customHost: '',
  eventFilter: '',
  conversionEvent: '',
}

const FILTER = /^\s*[A-Za-z0-9_$.:-]{1,100}\s*=\s*'[^'\\\r\n]{0,200}'\s*$/
const EVENT_NAME = /^[A-Za-z0-9_$.:-]{1,100}$/

/** The stored `host` setting for a form: absent for US cloud (the default), `eu`, or the typed URL. */
export function hostForRegion(region: PostHogRegion, customHost: string): string {
  if (region === 'eu') return 'eu'
  if (region === 'custom') return customHost.trim()
  return ''
}

export function regionFromHost(host: unknown): { region: PostHogRegion; customHost: string } {
  const h = typeof host === 'string' ? host.trim() : ''
  if (!h || h.toLowerCase() === 'us') return { region: 'us', customHost: '' }
  if (h.toLowerCase() === 'eu') return { region: 'eu', customHost: '' }
  return { region: 'custom', customHost: h }
}

export function formFromConfig(config: Record<string, unknown> | null | undefined): PostHogFormValues {
  const c = config ?? {}
  const str = (k: string) => (typeof c[k] === 'string' ? (c[k] as string) : c[k] != null ? String(c[k]) : '')
  return {
    projectId: str('projectId'),
    ...regionFromHost(c.host),
    eventFilter: str('eventFilter'),
    conversionEvent: str('conversionEvent'),
  }
}

/** Client-side check that mirrors the backend's parsing; returns a message or null. */
export function validatePostHogForm(v: PostHogFormValues): string | null {
  if (!v.projectId.trim()) return 'Project ID is required'
  if (v.region === 'custom' && !v.customHost.trim()) return 'Enter your self-hosted PostHog URL'
  if (v.eventFilter.trim() && !FILTER.test(v.eventFilter)) {
    return "Event filter must look like  property = 'value'  (for example $host = 'rexipe.io')"
  }
  if (v.conversionEvent.trim() && !EVENT_NAME.test(v.conversionEvent.trim())) {
    return 'Conversion event may only contain letters, digits and _ $ . : -'
  }
  return null
}

/** Settings for a new connection: empty optional fields are left out, US cloud sends no host. */
export function buildPostHogConfig(v: PostHogFormValues): Record<string, string> {
  const config: Record<string, string> = { projectId: v.projectId.trim() }
  const host = hostForRegion(v.region, v.customHost)
  if (host) config.host = host
  if (v.eventFilter.trim()) config.eventFilter = v.eventFilter.trim()
  if (v.conversionEvent.trim()) config.conversionEvent = v.conversionEvent.trim()
  return config
}

/** Settings for an edit: every field is sent so a cleared one is cleared server-side too. */
export function buildPostHogConfigPatch(v: PostHogFormValues): Record<string, string> {
  return {
    projectId: v.projectId.trim(),
    host: hostForRegion(v.region, v.customHost),
    eventFilter: v.eventFilter.trim(),
    conversionEvent: v.conversionEvent.trim(),
  }
}

/** "2026-10-03" (or an ISO timestamp) to "Oct 3"; anything unparseable is returned as is. */
export function formatChartTick(raw: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(raw)
  if (!m) return raw
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])))
  if (Number.isNaN(d.getTime())) return raw
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', timeZone: 'UTC' })
}

export type EffectiveHealth = 'HEALTHY' | 'DEGRADED' | 'SETUP_REQUIRED' | 'UNHEALTHY' | null

/**
 * The badge state of a connection: the worst of the last data fetch and the connection's own health
 * (stalled feed, rejected key). A HEALTHY fetch never hides a DEGRADED / UNHEALTHY connection.
 */
export function effectiveHealth(
  connection: { healthStatus?: string | null; healthMessage?: string | null } | null | undefined,
  fetchHealth: string | null | undefined,
  fetchMessage?: string | null,
): { status: EffectiveHealth; message: string | null } {
  const rank = { HEALTHY: 0, DEGRADED: 1, SETUP_REQUIRED: 2, UNHEALTHY: 3 } as const
  const known = (s?: string | null) => (s && s in rank ? (s as keyof typeof rank) : null)
  const a = known(connection?.healthStatus)
  const b = known(fetchHealth)
  if (!a && !b) return { status: null, message: null }
  const connWins = (a ? rank[a] : -1) >= (b ? rank[b] : -1)
  const status = connWins ? a : b
  const message = connWins
    ? (connection?.healthMessage ?? (b && rank[b] === rank[status!] ? fetchMessage : null) ?? null)
    : (fetchMessage ?? null)
  return { status, message: status === 'HEALTHY' ? null : message }
}
