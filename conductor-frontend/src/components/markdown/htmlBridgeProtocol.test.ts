import { describe, it, expect, vi, afterEach } from 'vitest'
import { openFrameLink, parseBridgeMessage, safeLinkUrl } from './htmlBridgeProtocol'

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
