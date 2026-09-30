import { describe, it, expect } from 'vitest'
import { isBrandKitUnconfigured } from './brandKitStatus'
import type { BrandKit } from './types'

function kit(overrides: Partial<BrandKit> = {}): BrandKit {
  return {
    id: 'kit-1',
    projectId: 'proj-1',
    slug: 'default',
    name: 'Default',
    isDefault: true,
    tokens: {},
    fontFamily: null,
    fontUrl: null,
    markUrl: null,
    wordmarkDarkUrl: null,
    wordmarkLightUrl: null,
    badgeUrl: null,
    ctaClaim: null,
    accentPhraseRequired: false,
    copyRules: [],
    approvedLines: [],
    enabledPlacements: ['9x16', '4x5', '1x1'],
    knowledgePagePath: '',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    configured: false,
    ...overrides,
  }
}

describe('isBrandKitUnconfigured', () => {
  it('is true when the server says configured: false', () => {
    expect(isBrandKitUnconfigured(kit({ configured: false }))).toBe(true)
  })

  it('is false when the server says configured: true', () => {
    expect(isBrandKitUnconfigured(kit({ configured: true }))).toBe(false)
  })

  it('is false when `configured` is missing (older backend) — never a false notice', () => {
    const { configured, ...withoutConfigured } = kit()
    expect(isBrandKitUnconfigured(withoutConfigured as BrandKit)).toBe(false)
  })

  it('is false for null/undefined (nothing to nudge about yet)', () => {
    expect(isBrandKitUnconfigured(null)).toBe(false)
    expect(isBrandKitUnconfigured(undefined)).toBe(false)
  })
})
