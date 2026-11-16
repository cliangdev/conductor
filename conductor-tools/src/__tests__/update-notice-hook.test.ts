import { describe, it, expect, vi } from 'vitest'
import { maybePrintNotice } from '../lib/update-notice-hook.js'
import type { UpdateCheckDeps } from '../lib/update-check.js'

const NOW = 1_000_000_000
function cacheDeps(latest: string | null, checkedAt = NOW, env: NodeJS.ProcessEnv = {}): Partial<UpdateCheckDeps> {
  return {
    now: () => NOW,
    env,
    dir: '/h',
    fs: {
      readFileSync: () => {
        if (latest === null) throw new Error('ENOENT')
        return JSON.stringify({ checkedAt, latest })
      },
    } as unknown as UpdateCheckDeps['fs'],
  }
}

function env(isTTY: boolean) {
  return { isTTY, write: vi.fn(), refreshInBackground: vi.fn() }
}

describe('CLI update notice hook', () => {
  it('prints the one-liner when outdated and stderr is a TTY', () => {
    const e = env(true)
    maybePrintNotice('status', {}, '1.0.0', e, cacheDeps('2.0.0'))
    expect(e.write).toHaveBeenCalledTimes(1)
    expect(e.write.mock.calls[0]![0]).toContain('2.0.0 is available')
    expect(e.refreshInBackground).not.toHaveBeenCalled()
  })

  it('is silent when not a TTY, up to date, --json, or no cache', () => {
    const a = env(false)
    maybePrintNotice('status', {}, '1.0.0', a, cacheDeps('2.0.0'))
    expect(a.write).not.toHaveBeenCalled()
    const b = env(true)
    maybePrintNotice('status', {}, '2.0.0', b, cacheDeps('2.0.0'))
    expect(b.write).not.toHaveBeenCalled()
    const c = env(true)
    maybePrintNotice('lint', { json: true }, '1.0.0', c, cacheDeps('2.0.0'))
    expect(c.write).not.toHaveBeenCalled()
    const d = env(true)
    maybePrintNotice('status', {}, '1.0.0', d, cacheDeps(null))
    expect(d.write).not.toHaveBeenCalled()
  })

  it('is silent for mcp, doctor, upgrade and the hidden refresher, and starts no refresh for them', () => {
    for (const name of ['mcp', 'doctor', 'upgrade', '__update-check']) {
      const e = env(true)
      maybePrintNotice(name, {}, '1.0.0', e, cacheDeps('2.0.0', 0))
      expect(e.write).not.toHaveBeenCalled()
      expect(e.refreshInBackground).not.toHaveBeenCalled()
    }
  })

  it('refreshes in the background only when the cache is missing or stale', () => {
    const missing = env(true)
    maybePrintNotice('status', {}, '1.0.0', missing, cacheDeps(null))
    expect(missing.refreshInBackground).toHaveBeenCalledTimes(1)
    const stale = env(false)
    maybePrintNotice('status', {}, '1.0.0', stale, cacheDeps('2.0.0', 0))
    expect(stale.refreshInBackground).toHaveBeenCalledTimes(1)
  })

  it('does nothing when disabled by env', () => {
    const e = env(true)
    maybePrintNotice('status', {}, '1.0.0', e, cacheDeps('2.0.0', 0, { CONDUCTOR_NO_UPDATE_CHECK: '1' }))
    expect(e.write).not.toHaveBeenCalled()
    expect(e.refreshInBackground).not.toHaveBeenCalled()
  })
})
