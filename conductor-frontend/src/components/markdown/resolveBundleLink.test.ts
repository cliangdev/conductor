import { describe, it, expect } from 'vitest'
import { resolveBundleLink } from './resolveBundleLink'

describe('resolveBundleLink', () => {
  it('resolves a bundle-absolute link', () => {
    expect(resolveBundleLink('/engineering/architecture.md')).toBe('engineering/architecture.md')
  })

  it('resolves a same-directory relative link', () => {
    expect(resolveBundleLink('jane.md', 'people')).toBe('people/jane.md')
  })

  it('resolves a ./ relative link against the base directory', () => {
    expect(resolveBundleLink('./jane.md', 'people')).toBe('people/jane.md')
  })

  it('resolves a ../ relative link into a sibling directory', () => {
    expect(resolveBundleLink('../people/jane.md', 'engineering')).toBe('people/jane.md')
  })

  it('resolves relative links against the root when baseDir is empty', () => {
    expect(resolveBundleLink('engineering/architecture.md', '')).toBe('engineering/architecture.md')
  })

  it('strips a trailing fragment before resolving', () => {
    expect(resolveBundleLink('/engineering/architecture.md#overview')).toBe('engineering/architecture.md')
  })

  it('returns null for external URLs', () => {
    expect(resolveBundleLink('https://example.com/page.md')).toBeNull()
    expect(resolveBundleLink('http://example.com')).toBeNull()
  })

  it('returns null for mailto and doc: links', () => {
    expect(resolveBundleLink('mailto:a@b.com')).toBeNull()
    expect(resolveBundleLink('doc:some-doc-id')).toBeNull()
  })

  it('returns null for a fragment-only link', () => {
    expect(resolveBundleLink('#overview')).toBeNull()
  })

  it('returns null for a non-markdown target', () => {
    expect(resolveBundleLink('/engineering/diagram.png')).toBeNull()
  })

  it('returns null for an empty href', () => {
    expect(resolveBundleLink(undefined)).toBeNull()
    expect(resolveBundleLink('')).toBeNull()
  })

  it('collapses .. past the root without throwing', () => {
    expect(resolveBundleLink('../../jane.md', 'people')).toBe('jane.md')
  })
})
