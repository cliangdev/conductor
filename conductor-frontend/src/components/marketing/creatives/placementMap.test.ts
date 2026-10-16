import { describe, expect, it } from 'vitest'
import {
  carouselPlacementFor,
  groupFrames,
  matches,
  matchesVideo,
  placementChip,
  placementFor,
} from './placementMap'
import type { CreativeRenderFrame } from './types'

const d = (platform: string, format = 'FEED') => ({ platform, format })
const S = (...k: string[]) => new Set(k)

function frame(placementKey: string, sequenceIndex: number | null = null, video = false): CreativeRenderFrame {
  return {
    id: `${placementKey}-${sequenceIndex}`,
    placementKey,
    sequenceIndex,
    url: `https://x/${placementKey}-${sequenceIndex}`,
    width: 10,
    height: 10,
    sizeBytes: 1,
    warnings: [],
    contentType: video ? 'video/mp4' : 'image/png',
  }
}

describe('matches (stills) — mirrors CreativePlacementTargetMapperTest', () => {
  it.each([
    ['9x16', 'tiktok', 'FEED', true],
    ['9x16', 'instagram', 'REEL', true],
    ['9x16', 'instagram', 'FEED', false],
    ['4x5', 'instagram', 'FEED', true],
    ['4x5', 'instagram', 'REEL', false],
    ['4x5', 'instagram', 'STORY', false],
    ['4x5', 'facebook', 'FEED', false],
    ['1x1', 'facebook', 'FEED', true],
    ['1x1', 'facebook', 'STORY', false],
    ['1x1', 'instagram', 'FEED', false],
    ['story', 'facebook', 'STORY', true],
    ['story', 'instagram', 'STORY', true],
    ['story', 'facebook', 'FEED', false],
    ['9x16', 'facebook', 'REEL', false],
    ['4x5', 'facebook', 'REEL', false],
    ['1x1', 'facebook', 'REEL', false],
    ['story', 'facebook', 'REEL', false],
    ['2x3', 'facebook', 'FEED', false],
    ['1x1', 'FACEBOOK', 'FEED', true],
  ])('%s on %s %s -> %s', (key, platform, format, expected) => {
    expect(matches(key as string, d(platform as string, format as string))).toBe(expected)
  })
})

describe('matchesVideo — mirrors the backend video cases', () => {
  it.each([
    ['9x16', 'tiktok', 'FEED', ['9x16'], true],
    ['9x16', 'instagram', 'REEL', ['9x16'], true],
    ['9x16', 'instagram', 'FEED', ['9x16'], false],
    ['9x16', 'facebook', 'REEL', ['9x16'], true],
    ['9x16', 'facebook', 'FEED', ['9x16'], true],
    ['9x16', 'facebook', 'FEED', ['9x16', '1x1'], false],
    ['9x16', 'youtube', 'FEED', ['9x16'], true],
    ['9x16', 'facebook', 'STORY', ['9x16'], true],
    ['9x16', 'facebook', 'STORY', ['9x16', 'story'], false],
    ['1x1', 'facebook', 'FEED', ['1x1', '9x16', '16x9'], true],
    ['9x16', 'facebook', 'FEED', ['9x16', '16x9'], true],
    ['16x9', 'facebook', 'FEED', ['9x16', '16x9'], false],
    ['16x9', 'facebook', 'FEED', ['16x9'], true],
    ['9x16', 'youtube', 'FEED', ['9x16', '16x9'], false],
    ['16x9', 'youtube', 'FEED', ['9x16', '16x9'], true],
    ['16x9', 'youtube', 'FEED', ['16x9', '1x1'], true],
    ['16x9', 'facebook', 'FEED', ['16x9', '1x1'], false],
    ['4x5', 'instagram', 'FEED', ['4x5'], true],
    ['4x5', 'instagram', 'REEL', ['4x5'], false],
    ['1x1', 'instagram', 'FEED', ['1x1'], false],
    ['story', 'instagram', 'STORY', ['story'], true],
    ['story', 'facebook', 'FEED', ['story'], false],
  ])('%s on %s %s present %j -> %s', (key, platform, format, present, expected) => {
    expect(matchesVideo(key as string, d(platform as string, format as string), new Set(present as string[]))).toBe(expected)
  })
})

describe('carouselPlacementFor — mirrors the backend carousel cases', () => {
  it('Instagram feed prefers 4x5 then 1x1', () => {
    expect(carouselPlacementFor(d('instagram'), S('4x5', '1x1', '9x16'))).toBe('4x5')
    expect(carouselPlacementFor(d('instagram'), S('1x1', '9x16'))).toBe('1x1')
    expect(carouselPlacementFor(d('instagram'), S('9x16'))).toBeNull()
  })
  it('Facebook feed prefers 1x1 then 4x5', () => {
    expect(carouselPlacementFor(d('facebook'), S('4x5', '1x1'))).toBe('1x1')
    expect(carouselPlacementFor(d('facebook'), S('4x5'))).toBe('4x5')
    expect(carouselPlacementFor(d('facebook'), S('9x16'))).toBeNull()
  })
  it('TikTok prefers 9x16 then 4x5', () => {
    expect(carouselPlacementFor(d('tiktok'), S('4x5', '9x16'))).toBe('9x16')
    expect(carouselPlacementFor(d('tiktok'), S('4x5'))).toBe('4x5')
    expect(carouselPlacementFor(d('tiktok'), S('1x1'))).toBeNull()
  })
  it('reels, stories and YouTube take none', () => {
    const all = S('4x5', '1x1', '9x16')
    for (const [p, f] of [['instagram', 'REEL'], ['facebook', 'REEL'], ['instagram', 'STORY'], ['facebook', 'STORY'], ['youtube', 'FEED']]) {
      expect(carouselPlacementFor(d(p, f), all)).toBeNull()
    }
  })
})

describe('groupFrames', () => {
  it('orders by sequence index then placement, groups by placement, drops the sheet', () => {
    const groups = groupFrames([frame('4x5', 1), frame('9x16', 0), frame('4x5', 0), frame('sheet'), frame('9x16', 1)])
    expect(groups.map((g) => g.placementKey)).toEqual(['4x5', '9x16'])
    expect(groups[0].frames.map((f) => f.sequenceIndex)).toEqual([0, 1])
  })
})

describe('placementFor', () => {
  const stills = groupFrames([frame('9x16'), frame('4x5'), frame('1x1')])

  it('gives each destination its still size', () => {
    expect(placementFor('STILL', stills, d('instagram'))).toMatchObject({ placementKey: '4x5' })
    expect(placementFor('STILL', stills, d('facebook'))).toMatchObject({ placementKey: '1x1' })
    expect(placementFor('STILL', stills, d('tiktok'))).toMatchObject({ placementKey: '9x16' })
  })

  it('YouTube takes video only', () => {
    expect(placementFor('STILL', stills, d('youtube'))).toEqual({ reason: 'YouTube takes video only — this Creative is a still image.' })
    const carousel = groupFrames([frame('4x5', 0), frame('4x5', 1)])
    expect(placementFor('CAROUSEL', carousel, d('youtube'))).toEqual({ reason: 'YouTube takes video only — this Creative is a carousel.' })
  })

  it('a carousel goes to the one preferred placement with all its slides', () => {
    const carousel = groupFrames([frame('4x5', 0), frame('4x5', 1), frame('1x1', 0), frame('1x1', 1)])
    const r = placementFor('CAROUSEL', carousel, d('instagram'))
    expect(r).toMatchObject({ placementKey: '4x5' })
    expect(placementChip(r)).toBe('4:5 ×2')
    expect(placementFor('CAROUSEL', carousel, d('facebook'))).toMatchObject({ placementKey: '1x1' })
  })

  it('a carousel cannot publish to a reel or a story', () => {
    const carousel = groupFrames([frame('4x5', 0), frame('4x5', 1)])
    expect('reason' in placementFor('CAROUSEL', carousel, d('instagram', 'REEL'))).toBe(true)
    expect('reason' in placementFor('CAROUSEL', carousel, d('instagram', 'STORY'))).toBe(true)
  })

  it('names the missing size', () => {
    const only45 = groupFrames([frame('4x5')])
    expect(placementFor('STILL', only45, d('tiktok'))).toEqual({ reason: 'No 9:16 size (has 4:5); add one in the Creative.' })
  })

  it('uses the video rule for video frames and picks exactly one size', () => {
    const video = groupFrames([frame('9x16', null, true), frame('16x9', null, true)])
    expect(placementFor('CLIP', video, d('youtube'))).toMatchObject({ placementKey: '16x9' })
    expect(placementFor('CLIP', video, d('tiktok'))).toMatchObject({ placementKey: '9x16' })
    expect(placementFor('CLIP', video, d('facebook'))).toMatchObject({ placementKey: '9x16' })
  })

  it('an unknown format reads as feed', () => {
    expect(placementFor('STILL', stills, { platform: 'instagram', format: null })).toMatchObject({ placementKey: '4x5' })
  })

  it('no frames means no render', () => {
    expect('reason' in placementFor('STILL', [], d('instagram'))).toBe(true)
  })
})
