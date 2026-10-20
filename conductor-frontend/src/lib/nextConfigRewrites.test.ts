/**
 * Pins the rewrite that backs the opt-in same-origin API proxy: it must forward /_backend/* to the
 * backend and never shadow the app's own /api/* routes (telemetry, health).
 */
import { afterEach, describe, expect, it, vi } from 'vitest'

async function rewrites(apiUrl: string) {
  vi.resetModules()
  vi.stubEnv('NEXT_PUBLIC_API_URL', apiUrl)
  const mod = await import('../../next.config.mjs')
  return (await mod.default.rewrites?.()) as { source: string; destination: string }[]
}

afterEach(() => vi.unstubAllEnvs())

describe('next.config rewrites', () => {
  it('proxies /_backend/* to the backend, trimming a trailing slash', async () => {
    const rules = await rewrites('https://api.example.com/')
    expect(rules).toContainEqual({ source: '/_backend/:path*', destination: 'https://api.example.com/:path*' })
  })

  it('adds no proxy rule without an absolute backend URL', async () => {
    const rules = await rewrites('')
    expect(rules.some((r) => r.source.startsWith('/_backend'))).toBe(false)
  })

  it('never shadows our own /api/telemetry or /api/health routes', async () => {
    const rules = await rewrites('https://api.example.com')
    for (const rule of rules) {
      expect(rule.source).not.toMatch(/^\/api\/(:|telemetry|health)/)
      expect(rule.source).not.toBe('/api/:path*')
    }
  })
})
