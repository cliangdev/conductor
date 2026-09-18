import { describe, it, expect } from 'vitest'
import { resolveSiteUrl } from './site-url'

const FALLBACK = 'http://localhost:3000'

describe('resolveSiteUrl', () => {
  it('uses a configured https origin', () => {
    expect(resolveSiteUrl('https://conductor.rexipe.io')).toBe('https://conductor.rexipe.io')
  })

  it('drops a trailing slash so canonical links do not double up', () => {
    expect(resolveSiteUrl('https://conductor.rexipe.io/')).toBe('https://conductor.rexipe.io')
  })

  it('keeps only the origin when a path is configured by mistake', () => {
    expect(resolveSiteUrl('https://conductor.rexipe.io/app/projects')).toBe(
      'https://conductor.rexipe.io',
    )
  })

  // The regression this module exists for: an unset GitHub variable reaches the Docker build as an
  // empty --build-arg, not as undefined, and `new URL('')` threw during `next build`.
  it.each(['', '   ', undefined])('falls back when the value is blank (%p)', (raw) => {
    expect(resolveSiteUrl(raw)).toBe(FALLBACK)
  })

  it('falls back rather than throwing on an unparseable value', () => {
    expect(resolveSiteUrl('conductor.rexipe.io')).toBe(FALLBACK)
    expect(resolveSiteUrl('https://')).toBe(FALLBACK)
  })

  it('falls back on a non-http protocol, which would break absolute Open Graph URLs', () => {
    expect(resolveSiteUrl('ftp://conductor.rexipe.io')).toBe(FALLBACK)
    expect(resolveSiteUrl('javascript:alert(1)')).toBe(FALLBACK)
  })

  it('never returns something new URL() would reject', () => {
    for (const raw of ['', ' ', 'nope', 'https://', 'ftp://x.io', 'https://conductor.rexipe.io']) {
      expect(() => new URL(resolveSiteUrl(raw))).not.toThrow()
    }
  })
})
