import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'

export const PACKAGE_NAME = '@cliangdev/conductor'
export const DIST_TAGS_URL = 'https://registry.npmjs.org/-/package/@cliangdev/conductor/dist-tags'
export const FETCH_TIMEOUT_MS = 1500
export const CACHE_TTL_MS = 24 * 60 * 60 * 1000
export const UPGRADE_COMMAND = 'conductor upgrade'

export interface UpdateCache {
  checkedAt: number
  latest: string
}

/** Everything the check touches, injectable so tests need no network, clock, or real home dir. */
export interface UpdateCheckDeps {
  fetch: typeof fetch
  now: () => number
  env: NodeJS.ProcessEnv
  /** Directory holding update-check.json (default ~/.conductor). */
  dir: string
  fs: Pick<typeof fs, 'readFileSync' | 'writeFileSync' | 'renameSync' | 'mkdirSync' | 'rmSync'>
}

export function defaultDeps(): UpdateCheckDeps {
  return {
    fetch: (...args) => fetch(...args),
    now: () => Date.now(),
    env: process.env,
    dir: path.join(os.homedir(), '.conductor'),
    fs,
  }
}

function resolveDeps(d?: Partial<UpdateCheckDeps>): UpdateCheckDeps {
  return { ...defaultDeps(), ...d }
}

export function cachePath(deps: Pick<UpdateCheckDeps, 'dir'>): string {
  return path.join(deps.dir, 'update-check.json')
}

interface Parsed {
  major: number
  minor: number
  patch: number
  pre: string | null
}

function parse(v: string): Parsed | null {
  const m = /^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+.*)?$/.exec(v.trim())
  if (!m) return null
  return { major: +m[1]!, minor: +m[2]!, patch: +m[3]!, pre: m[4] ?? null }
}

/** Returns >0 when a is newer than b, <0 when older, 0 when equal; null if either is unparseable.
 * A prerelease sorts below its own release. */
export function compareVersions(a: string, b: string): number | null {
  const pa = parse(a)
  const pb = parse(b)
  if (!pa || !pb) return null
  for (const k of ['major', 'minor', 'patch'] as const) {
    if (pa[k] !== pb[k]) return pa[k] - pb[k]
  }
  if (pa.pre === pb.pre) return 0
  if (pa.pre === null) return 1
  if (pb.pre === null) return -1
  return pa.pre < pb.pre ? -1 : 1
}

/** True when `latest` is a stable release newer than `current`. A prerelease `latest` never nags, and a
 * prerelease `current` is never nagged toward a lower stable. */
export function isOutdated(current: string, latest: string): boolean {
  const pl = parse(latest)
  if (!pl || pl.pre !== null) return false
  const cmp = compareVersions(current, latest)
  return cmp !== null && cmp < 0
}

export function isUpdateCheckDisabled(env: NodeJS.ProcessEnv = process.env): boolean {
  if (env['CONDUCTOR_NO_UPDATE_CHECK'] === '1') return true
  if (env['NO_UPDATE_NOTIFIER'] !== undefined && env['NO_UPDATE_NOTIFIER'] !== '') return true
  const ci = env['CI']
  return ci !== undefined && ci !== '' && ci !== '0' && ci.toLowerCase() !== 'false'
}

export function buildUpdateMessage(current: string, latest: string): string {
  return (
    `Conductor CLI ${current} is installed; ${latest} is available. ` +
    `Update with: ${UPGRADE_COMMAND} (or: npm i -g ${PACKAGE_NAME}@latest && conductor init) — then restart Claude Code.`
  )
}

export function buildAgentNotice(current: string, latest: string): string {
  return `Note for the user (mention once): ${buildUpdateMessage(current, latest)}`
}

export function readCache(d?: Partial<UpdateCheckDeps>): UpdateCache | null {
  const deps = resolveDeps(d)
  try {
    const parsed = JSON.parse(String(deps.fs.readFileSync(cachePath(deps), 'utf8'))) as Partial<UpdateCache>
    if (typeof parsed.checkedAt !== 'number' || typeof parsed.latest !== 'string' || !parse(parsed.latest)) return null
    return { checkedAt: parsed.checkedAt, latest: parsed.latest }
  } catch {
    return null
  }
}

export function writeCache(cache: UpdateCache, d?: Partial<UpdateCheckDeps>): void {
  const deps = resolveDeps(d)
  const target = cachePath(deps)
  const tmp = `${target}.${process.pid}.tmp`
  try {
    deps.fs.mkdirSync(deps.dir, { recursive: true })
    deps.fs.writeFileSync(tmp, JSON.stringify(cache), 'utf8')
    deps.fs.renameSync(tmp, target)
  } catch {
    try {
      deps.fs.rmSync(tmp, { force: true })
    } catch { /* read-only home: nothing to do */ }
  }
}

export function clearCache(d?: Partial<UpdateCheckDeps>): void {
  const deps = resolveDeps(d)
  try {
    deps.fs.rmSync(cachePath(deps), { force: true })
  } catch { /* ignore */ }
}

/** Latest published version from the registry, or null on any failure. Never throws. */
export async function fetchLatestVersion(d?: Partial<UpdateCheckDeps>): Promise<string | null> {
  const deps = resolveDeps(d)
  try {
    const res = await deps.fetch(DIST_TAGS_URL, {
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      headers: { accept: 'application/json' },
    })
    if (!res.ok) return null
    const body = (await res.json()) as { latest?: unknown }
    return typeof body.latest === 'string' && parse(body.latest) ? body.latest : null
  } catch {
    return null
  }
}

function isFresh(cached: UpdateCache | null, now: number): cached is UpdateCache {
  return !!cached && cached.checkedAt <= now && now - cached.checkedAt < CACHE_TTL_MS
}

export function cacheIsFresh(d?: Partial<UpdateCheckDeps>): boolean {
  const deps = resolveDeps(d)
  return isFresh(readCache(deps), deps.now())
}

/** Latest version, using the cache when it is under 24h old unless `force`. Updates the cache on a fetch.
 * Falls back to a stale cache entry when a non-forced fetch fails. Null when nothing is known. */
export async function getLatestVersion(opts: { force?: boolean } = {}, d?: Partial<UpdateCheckDeps>): Promise<string | null> {
  const deps = resolveDeps(d)
  const cached = readCache(deps)
  if (!opts.force && isFresh(cached, deps.now())) return cached.latest
  const latest = await fetchLatestVersion(deps)
  if (latest) {
    writeCache({ checkedAt: deps.now(), latest }, deps)
    return latest
  }
  return opts.force ? null : (cached?.latest ?? null)
}

export interface UpdateStatus {
  current: string
  latest: string
  outdated: boolean
}

/** Full check (cache-aware unless `force`). Null when disabled (unless `ignoreDisabled`) or unknown. */
export async function checkForUpdate(
  current: string,
  opts: { force?: boolean; ignoreDisabled?: boolean } = {},
  d?: Partial<UpdateCheckDeps>,
): Promise<UpdateStatus | null> {
  const deps = resolveDeps(d)
  if (!opts.ignoreDisabled && isUpdateCheckDisabled(deps.env)) return null
  try {
    const latest = await getLatestVersion({ force: opts.force }, deps)
    if (!latest) return null
    return { current, latest, outdated: isOutdated(current, latest) }
  } catch {
    return null
  }
}

/** Synchronous, cache-only answer: no network. Used by CLI commands so they add no latency. */
export function cachedUpdateStatus(current: string, d?: Partial<UpdateCheckDeps>): UpdateStatus | null {
  const deps = resolveDeps(d)
  if (isUpdateCheckDisabled(deps.env)) return null
  const cached = readCache(deps)
  if (!cached) return null
  return { current, latest: cached.latest, outdated: isOutdated(current, cached.latest) }
}

export interface UpdateNotifier {
  /** The agent-facing notice, returned at most once and only after the background check resolved as outdated. */
  takeNotice(): string | null
}

/** Starts the check in the background (never awaited by the caller). */
export function startUpdateNotifier(current: string, d?: Partial<UpdateCheckDeps>): UpdateNotifier {
  let notice: string | null = null
  void checkForUpdate(current, {}, d)
    .then((status) => {
      if (status?.outdated) notice = buildAgentNotice(status.current, status.latest)
    })
    .catch(() => {})
  return {
    takeNotice() {
      const n = notice
      notice = null
      return n
    },
  }
}

/** Appends the notice as a separate text block to a successful tool result; errors pass through untouched. */
export function withUpdateNotice<R>(result: R, notifier: UpdateNotifier): R {
  const r = result as unknown as { isError?: boolean; content?: unknown[] }
  if (!r || r.isError || !Array.isArray(r.content)) return result
  const text = notifier.takeNotice()
  if (!text) return result
  return { ...r, content: [...r.content, { type: 'text', text }] } as unknown as R
}
