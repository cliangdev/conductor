import { describe, it, expect, vi } from 'vitest'
import {
  compareVersions,
  isOutdated,
  isUpdateCheckDisabled,
  readCache,
  writeCache,
  clearCache,
  fetchLatestVersion,
  getLatestVersion,
  checkForUpdate,
  cachedUpdateStatus,
  startUpdateNotifier,
  withUpdateNotice,
  buildUpdateMessage,
  CACHE_TTL_MS,
  type UpdateCheckDeps,
} from '../lib/update-check.js'

function memFs(initial: Record<string, string> = {}, opts: { readOnly?: boolean } = {}) {
  const files = new Map(Object.entries(initial))
  const fsLike = {
    readFileSync: vi.fn((p: string) => {
      if (!files.has(p)) throw new Error('ENOENT')
      return files.get(p)!
    }),
    writeFileSync: vi.fn((p: string, c: string) => {
      if (opts.readOnly) throw new Error('EROFS')
      files.set(p, c)
    }),
    renameSync: vi.fn((a: string, b: string) => {
      files.set(b, files.get(a)!)
      files.delete(a)
    }),
    mkdirSync: vi.fn(() => {
      if (opts.readOnly) throw new Error('EROFS')
    }),
    rmSync: vi.fn((p: string) => void files.delete(p)),
  }
  return { files, fsLike: fsLike as unknown as UpdateCheckDeps['fs'] }
}

const CACHE = '/home/.conductor/update-check.json'
const NOW = 1_000_000_000

function deps(over: Partial<UpdateCheckDeps> & { latest?: string | null; files?: Record<string, string> } = {}) {
  const { fsLike, files } = memFs(over.files)
  const fetchMock = vi.fn(async () => {
    if (over.latest === null) throw new Error('offline')
    return { ok: true, json: async () => ({ latest: over.latest ?? '2.0.0' }) } as Response
  })
  const d: Partial<UpdateCheckDeps> = {
    fetch: (over.fetch ?? fetchMock) as unknown as typeof fetch,
    now: over.now ?? (() => NOW),
    env: over.env ?? {},
    dir: '/home/.conductor',
    fs: over.fs ?? fsLike,
  }
  return { d, files, fetchMock }
}

describe('compareVersions / isOutdated', () => {
  it('compares major.minor.patch numerically', () => {
    expect(compareVersions('0.54.0', '0.60.0')).toBeLessThan(0)
    expect(compareVersions('0.9.0', '0.10.0')).toBeLessThan(0)
    expect(compareVersions('1.0.0', '0.99.99')).toBeGreaterThan(0)
    expect(compareVersions('1.2.3', '1.2.3')).toBe(0)
    expect(compareVersions('nope', '1.0.0')).toBeNull()
  })

  it('sorts a prerelease below its release', () => {
    expect(compareVersions('1.0.0-beta.1', '1.0.0')).toBeLessThan(0)
    expect(compareVersions('1.0.0-beta.1', '1.0.0-beta.2')).toBeLessThan(0)
  })

  it('flags only stable newer versions', () => {
    expect(isOutdated('0.54.0', '0.60.0')).toBe(true)
    expect(isOutdated('0.60.0', '0.60.0')).toBe(false)
    expect(isOutdated('0.61.0', '0.60.0')).toBe(false)
    expect(isOutdated('1.0.0-beta.1', '1.0.0')).toBe(true)
  })

  it('never nags a prerelease current toward a lower stable, nor toward a prerelease', () => {
    expect(isOutdated('1.1.0-beta.1', '1.0.0')).toBe(false)
    expect(isOutdated('1.0.0', '1.1.0-beta.1')).toBe(false)
    expect(isOutdated('garbage', '1.0.0')).toBe(false)
  })
})

describe('isUpdateCheckDisabled', () => {
  it('honours the opt-outs', () => {
    expect(isUpdateCheckDisabled({})).toBe(false)
    expect(isUpdateCheckDisabled({ CONDUCTOR_NO_UPDATE_CHECK: '1' })).toBe(true)
    expect(isUpdateCheckDisabled({ NO_UPDATE_NOTIFIER: '1' })).toBe(true)
    expect(isUpdateCheckDisabled({ CI: 'true' })).toBe(true)
    expect(isUpdateCheckDisabled({ CI: '1' })).toBe(true)
    expect(isUpdateCheckDisabled({ CI: 'false' })).toBe(false)
    expect(isUpdateCheckDisabled({ CI: '' })).toBe(false)
  })
})

describe('cache', () => {
  it('round-trips through a temp file + rename', () => {
    const { d, files } = deps()
    writeCache({ checkedAt: 5, latest: '1.2.3' }, d)
    expect(readCache(d)).toEqual({ checkedAt: 5, latest: '1.2.3' })
    expect(files.size).toBe(1)
    expect(files.has(CACHE)).toBe(true)
    clearCache(d)
    expect(readCache(d)).toBeNull()
  })

  it('treats missing, corrupt and malformed caches as a miss', () => {
    expect(readCache(deps().d)).toBeNull()
    expect(readCache(deps({ files: { [CACHE]: '{not json' } }).d)).toBeNull()
    expect(readCache(deps({ files: { [CACHE]: '{"checkedAt":"x","latest":"1.0.0"}' } }).d)).toBeNull()
    expect(readCache(deps({ files: { [CACHE]: '{"checkedAt":1,"latest":"junk"}' } }).d)).toBeNull()
  })

  it('tolerates a read-only home', () => {
    const { fsLike } = memFs({}, { readOnly: true })
    expect(() => writeCache({ checkedAt: 1, latest: '1.0.0' }, { dir: '/ro', fs: fsLike })).not.toThrow()
  })

  it('serves a fresh cache without fetching, refetches after 24h', async () => {
    const fresh = deps({ files: { [CACHE]: JSON.stringify({ checkedAt: NOW - 1000, latest: '3.0.0' }) } })
    expect(await getLatestVersion({}, fresh.d)).toBe('3.0.0')
    expect(fresh.fetchMock).not.toHaveBeenCalled()

    const stale = deps({ files: { [CACHE]: JSON.stringify({ checkedAt: NOW - CACHE_TTL_MS - 1, latest: '3.0.0' }) }, latest: '4.0.0' })
    expect(await getLatestVersion({}, stale.d)).toBe('4.0.0')
    expect(stale.fetchMock).toHaveBeenCalledTimes(1)
    expect(readCache(stale.d)).toEqual({ checkedAt: NOW, latest: '4.0.0' })
  })

  it('force bypasses a fresh cache', async () => {
    const x = deps({ files: { [CACHE]: JSON.stringify({ checkedAt: NOW, latest: '3.0.0' }) }, latest: '5.0.0' })
    expect(await getLatestVersion({ force: true }, x.d)).toBe('5.0.0')
  })
})

describe('fetchLatestVersion', () => {
  it('returns null (never throws) on network error, bad status, bad body, timeout', async () => {
    expect(await fetchLatestVersion(deps({ latest: null }).d)).toBeNull()
    expect(await fetchLatestVersion({ fetch: (async () => ({ ok: false })) as unknown as typeof fetch })).toBeNull()
    expect(await fetchLatestVersion({ fetch: (async () => ({ ok: true, json: async () => ({ latest: 5 }) })) as unknown as typeof fetch })).toBeNull()
    expect(await fetchLatestVersion({ fetch: (async () => ({ ok: true, json: async () => { throw new Error('bad') } })) as unknown as typeof fetch })).toBeNull()
    const hanging = ((_u: string, init: RequestInit) =>
      new Promise((_r, rej) => init.signal!.addEventListener('abort', () => rej(new Error('aborted'))))) as unknown as typeof fetch
    expect(await fetchLatestVersion({ fetch: hanging })).toBeNull()
  }, 5000)

  it('passes an abort signal (timeout)', async () => {
    const x = deps()
    await fetchLatestVersion(x.d)
    const init = (x.fetchMock.mock.calls[0] as unknown as [string, RequestInit])[1]
    expect(init.signal).toBeInstanceOf(AbortSignal)
  })

  it('falls back to a stale cache when the fetch fails (non-forced)', async () => {
    const x = deps({ files: { [CACHE]: JSON.stringify({ checkedAt: 1, latest: '3.0.0' }) }, latest: null })
    expect(await getLatestVersion({}, x.d)).toBe('3.0.0')
    expect(await getLatestVersion({ force: true }, x.d)).toBeNull()
  })
})

describe('checkForUpdate', () => {
  it('is disabled by env and does not fetch', async () => {
    for (const env of [{ CONDUCTOR_NO_UPDATE_CHECK: '1' }, { NO_UPDATE_NOTIFIER: '1' }, { CI: 'true' }]) {
      const x = deps({ env })
      expect(await checkForUpdate('1.0.0', {}, x.d)).toBeNull()
      expect(x.fetchMock).not.toHaveBeenCalled()
    }
  })

  it('ignoreDisabled still checks (doctor)', async () => {
    const x = deps({ env: { CI: 'true' } })
    expect(await checkForUpdate('1.0.0', { ignoreDisabled: true, force: true }, x.d)).toEqual({ current: '1.0.0', latest: '2.0.0', outdated: true })
  })

  it('returns null offline', async () => {
    expect(await checkForUpdate('1.0.0', {}, deps({ latest: null }).d)).toBeNull()
  })

  it('cachedUpdateStatus is cache-only', () => {
    const x = deps({ files: { [CACHE]: JSON.stringify({ checkedAt: 1, latest: '2.0.0' }) } })
    expect(cachedUpdateStatus('1.0.0', x.d)).toEqual({ current: '1.0.0', latest: '2.0.0', outdated: true })
    expect(cachedUpdateStatus('3.0.0', x.d)?.outdated).toBe(false)
    expect(cachedUpdateStatus('1.0.0', deps().d)).toBeNull()
    expect(x.fetchMock).not.toHaveBeenCalled()
  })
})

type Block = { type: string; text: string }
type Result = { content: Block[]; isError?: boolean }

describe('MCP notifier', () => {
  const ok = (): Result => ({ content: [{ type: 'text', text: '{"a":1}' }] })
  const settle = () => new Promise((r) => setTimeout(r, 20))

  it('adds nothing before the check resolves, then adds it', async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => (release = r))
    const f = (async () => {
      await gate
      return { ok: true, json: async () => ({ latest: '9.0.0' }) } as Response
    }) as unknown as typeof fetch
    const n = startUpdateNotifier('1.0.0', { ...deps().d, fetch: f })
    const before = ok()
    expect(withUpdateNotice(before, n)).toBe(before)
    release()
    await settle()
    expect(withUpdateNotice(ok(), n).content).toHaveLength(2)
  })

  it('appends a separate block once to the first successful result', async () => {
    const n = startUpdateNotifier('1.0.0', deps({ latest: '9.0.0' }).d)
    await settle()
    const input = ok()
    const first = withUpdateNotice(input, n)
    expect(first.content).toHaveLength(2)
    expect(first.content[0]).toEqual(input.content[0])
    expect(first.content[1]!.text).toMatch(/^Note for the user \(mention once\): Conductor CLI 1\.0\.0 is installed; 9\.0\.0 is available/)
    expect(input.content).toHaveLength(1)
    const second = ok()
    expect(withUpdateNotice(second, n)).toBe(second)
  })

  it('skips error results and keeps the notice for the next success', async () => {
    const n = startUpdateNotifier('1.0.0', deps({ latest: '9.0.0' }).d)
    await settle()
    const err: Result = { content: [{ type: 'text', text: 'x' }], isError: true }
    expect(withUpdateNotice(err, n)).toBe(err)
    const next = withUpdateNotice(ok(), n)
    expect(next.content).toHaveLength(2)
    expect(next.isError).toBeUndefined()
  })

  it('adds nothing when up to date, offline, or disabled', async () => {
    for (const d of [deps({ latest: '1.0.0' }).d, deps({ latest: null }).d, deps({ env: { CI: '1' } }).d]) {
      const n = startUpdateNotifier('1.0.0', d)
      await settle()
      const r = ok()
      expect(withUpdateNotice(r, n)).toBe(r)
    }
  })

  it('never writes to stdout', async () => {
    const out = vi.spyOn(process.stdout, 'write')
    const n = startUpdateNotifier('1.0.0', deps({ latest: '9.0.0' }).d)
    await settle()
    withUpdateNotice(ok(), n)
    expect(out).not.toHaveBeenCalled()
    out.mockRestore()
  })

  it('message names both versions, the command and the restart', () => {
    const m = buildUpdateMessage('0.54.0', '0.60.0')
    expect(m).toContain('0.54.0')
    expect(m).toContain('0.60.0')
    expect(m).toContain('conductor upgrade')
    expect(m).toContain('restart Claude Code')
  })
})
