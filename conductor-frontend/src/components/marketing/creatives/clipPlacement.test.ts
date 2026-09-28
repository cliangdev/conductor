import { describe, it, expect } from 'vitest'
import { nearestAspectPlacement } from './clipPlacement'
import type { CreativeRegistryPlacement } from './types'

const PLACEMENTS: CreativeRegistryPlacement[] = [
  { key: '9x16', label: '9:16 TikTok and Reels', platform: 'tiktok', width: 1080, height: 1920, default: true },
  { key: '4x5', label: '4:5 Instagram feed', platform: 'instagram', width: 1080, height: 1350, default: true },
  { key: '1x1', label: '1:1 Facebook feed', platform: 'facebook', width: 1080, height: 1080, default: true },
  { key: 'story', label: '9:16 Stories', platform: 'story', width: 1080, height: 1920, default: false },
  { key: '1.91x1', label: '1.91:1 link ad', platform: 'link', width: 1200, height: 628, default: false },
  { key: '2x3', label: '2:3 Pinterest', platform: 'pinterest', width: 1000, height: 1500, default: false },
  { key: '16x9', label: '16:9 YouTube', platform: 'youtube', width: 1920, height: 1080, default: false },
]

describe('nearestAspectPlacement', () => {
  it('picks 9x16 for a portrait video', () => {
    expect(nearestAspectPlacement(PLACEMENTS, 1080, 1920)).toBe('9x16')
  })

  it('picks 1x1 for a square video', () => {
    expect(nearestAspectPlacement(PLACEMENTS, 1000, 1000)).toBe('1x1')
  })

  it('picks 16x9 for a landscape video', () => {
    expect(nearestAspectPlacement(PLACEMENTS, 1920, 1080)).toBe('16x9')
  })

  it('picks 4x5 for a video whose ratio sits between 1x1 and 9x16, closer to 4x5', () => {
    // 4:5 is 0.8; 9x16 is 0.5625. 0.75 is closer to 0.8 than to 0.5625.
    expect(nearestAspectPlacement(PLACEMENTS, 900, 1200)).toBe('4x5')
  })

  it('never returns story — a story-nearest video resolves to 9x16 instead', () => {
    // story and 9x16 share the exact same ratio, so a tie always resolves to whichever is earlier —
    // 9x16 is listed first, and story is excluded from winning outright regardless.
    expect(nearestAspectPlacement(PLACEMENTS, 540, 960)).toBe('9x16')
  })

  it('returns null when the video has no measured dimensions', () => {
    expect(nearestAspectPlacement(PLACEMENTS, null, null)).toBeNull()
    expect(nearestAspectPlacement(PLACEMENTS, 1080, undefined)).toBeNull()
  })

  it('returns null when there are no placements to compare', () => {
    expect(nearestAspectPlacement([], 1080, 1920)).toBeNull()
  })

  it('ignores a placement missing its own dimensions', () => {
    const withGap: CreativeRegistryPlacement[] = [
      { key: 'broken', label: 'Broken', platform: null, width: 0, height: 0, default: false },
      ...PLACEMENTS,
    ]
    expect(nearestAspectPlacement(withGap, 1080, 1920)).toBe('9x16')
  })
})
