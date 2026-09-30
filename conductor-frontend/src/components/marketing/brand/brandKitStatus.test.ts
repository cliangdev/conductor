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
    ...overrides,
  }
}

describe('isBrandKitUnconfigured', () => {
  it('is true for a freshly lazily-created kit (never edited, no font, no logo)', () => {
    expect(isBrandKitUnconfigured(kit())).toBe(true)
  })

  it('is false once the kit has been edited (updatedAt moved past createdAt)', () => {
    expect(isBrandKitUnconfigured(kit({ updatedAt: '2026-01-02T00:00:00Z' }))).toBe(false)
  })

  it('is false when a font family is set, even with updatedAt === createdAt', () => {
    expect(isBrandKitUnconfigured(kit({ fontFamily: 'Poppins' }))).toBe(false)
  })

  it('is false when any logo slot is set', () => {
    expect(isBrandKitUnconfigured(kit({ markUrl: 'https://cdn.example/mark.png' }))).toBe(false)
    expect(isBrandKitUnconfigured(kit({ wordmarkDarkUrl: 'https://cdn.example/w-dark.png' }))).toBe(false)
    expect(isBrandKitUnconfigured(kit({ wordmarkLightUrl: 'https://cdn.example/w-light.png' }))).toBe(false)
    expect(isBrandKitUnconfigured(kit({ badgeUrl: 'https://cdn.example/badge.png' }))).toBe(false)
  })

  it('is false for null/undefined (nothing to nudge about yet)', () => {
    expect(isBrandKitUnconfigured(null)).toBe(false)
    expect(isBrandKitUnconfigured(undefined)).toBe(false)
  })
})
