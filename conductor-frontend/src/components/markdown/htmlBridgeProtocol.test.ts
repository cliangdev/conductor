import { describe, it, expect, vi, afterEach } from 'vitest'
import { buildThemeMessage, openFrameLink, parseBridgeMessage, safeLinkUrl } from './htmlBridgeProtocol'

const msg = (data: Record<string, unknown>) => ({ source: 'conductor-bridge', ...data })

describe('parseBridgeMessage', () => {
  it('accepts openLink with a non-empty href and rejects the rest', () => {
    expect(parseBridgeMessage(msg({ type: 'openLink', href: 'https://example.com' }))).toEqual({
      type: 'openLink',
      href: 'https://example.com',
    })
    expect(parseBridgeMessage(msg({ type: 'openLink', href: '' }))).toBeNull()
    expect(parseBridgeMessage(msg({ type: 'openLink' }))).toBeNull()
    expect(parseBridgeMessage(msg({ type: 'openLink', href: 42 }))).toBeNull()
    expect(parseBridgeMessage(msg({ type: 'openLink', href: 'https://x.co/' + 'a'.repeat(3000) }))).toBeNull()
  })

  it('accepts a layout message and rejects malformed ones', () => {
    expect(
      parseBridgeMessage(
        msg({ type: 'layout', scrollY: 120, viewportH: 800, tops: [{ id: 'c1', top: 340 }, { id: 'c2', top: 900 }] })
      )
    ).toEqual({
      type: 'layout',
      scrollY: 120,
      viewportH: 800,
      tops: [{ id: 'c1', top: 340 }, { id: 'c2', top: 900 }],
    })
    expect(parseBridgeMessage(msg({ type: 'layout', scrollY: 0, viewportH: 600, tops: [] }))).toEqual({
      type: 'layout',
      scrollY: 0,
      viewportH: 600,
      tops: [],
    })
    expect(parseBridgeMessage(msg({ type: 'layout', viewportH: 600, tops: [] }))).toBeNull()
    expect(parseBridgeMessage(msg({ type: 'layout', scrollY: 0, viewportH: 600 }))).toBeNull()
    expect(parseBridgeMessage(msg({ type: 'layout', scrollY: NaN, viewportH: 600, tops: [] }))).toBeNull()
    expect(parseBridgeMessage(msg({ type: 'layout', scrollY: 0, viewportH: 600, tops: [{ id: 1, top: 2 }] }))).toBeNull()
    expect(parseBridgeMessage(msg({ type: 'layout', scrollY: 0, viewportH: 600, tops: [{ id: 'a', top: 'x' }] }))).toBeNull()
  })

  it('accepts only the thread-navigation keys', () => {
    expect(parseBridgeMessage(msg({ type: 'key', key: 'j' }))).toEqual({ type: 'key', key: 'j' })
    expect(parseBridgeMessage(msg({ type: 'key', key: 'Escape' }))).toEqual({ type: 'key', key: 'Escape' })
    expect(parseBridgeMessage(msg({ type: 'key', key: 'x' }))).toBeNull()
  })

  it('requires the bridge envelope', () => {
    expect(parseBridgeMessage({ type: 'openLink', href: 'https://example.com' })).toBeNull()
    expect(parseBridgeMessage(null)).toBeNull()
  })
})

describe('safeLinkUrl', () => {
  it('allows http, https, mailto and tel', () => {
    for (const href of ['http://a.co', 'https://a.co/x?y=1#z', 'mailto:a@b.co', 'tel:+15551234']) {
      expect(safeLinkUrl(href)).not.toBeNull()
    }
  })

  it('rejects everything else, including unparseable and relative URLs', () => {
    for (const href of [
      'javascript:alert(1)',
      'JaVaScRiPt:alert(1)',
      'data:text/html,<script>1</script>',
      'blob:https://a.co/uuid',
      'file:///etc/passwd',
      'about:srcdoc',
      '//evil.example/x',
      'other.html',
      '#hash',
      '',
    ]) {
      expect(safeLinkUrl(href), href).toBeNull()
    }
  })
})

describe('openFrameLink', () => {
  afterEach(() => vi.restoreAllMocks())

  it('opens http(s) in a new tab with noopener,noreferrer', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    expect(openFrameLink('https://example.com/a')).toBe(true)
    expect(open).toHaveBeenCalledWith('https://example.com/a', '_blank', 'noopener,noreferrer')
  })

  it('opens nothing for a disallowed URL', () => {
    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    expect(openFrameLink('javascript:alert(1)')).toBe(false)
    expect(openFrameLink('data:text/html,hi')).toBe(false)
    expect(open).not.toHaveBeenCalled()
  })
})

describe('theme message', () => {
  it('carries plain colours only', () => {
    expect(buildThemeMessage('hsl(45 90% 55% / 0.28)', 'rgba(255, 159, 10, .6)')).toEqual({
      type: 'theme',
      highlight: 'hsl(45 90% 55% / 0.28)',
      active: 'rgba(255, 159, 10, .6)',
    })
    expect(buildThemeMessage(' #fa0 ', 'hsl(1 2% 3%)')).toMatchObject({ highlight: '#fa0' })
  })

  it('refuses anything that could carry other CSS or script', () => {
    for (const bad of ['red; } body { display:none', 'url(x)}', '</style><script>', 'a{b}', '', 'x'.repeat(65), 'rgb(1,2,3);']) {
      expect(buildThemeMessage(bad, 'red')).toBeNull()
      expect(buildThemeMessage('red', bad)).toBeNull()
    }
  })
})
