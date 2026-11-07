import { describe, it, expect } from 'vitest'
import { estimateCost, httpFailure, normalizeVideoParams, rateFor, videoPlacementFit } from '../lib/video-common.js'

describe('normalizeVideoParams', () => {
  it('applies defaults', () => {
    const { request, warnings } = normalizeVideoParams({ prompt: ' a cat ' }, 'vertex')
    expect(request).toMatchObject({ prompt: 'a cat', aspect: '9:16', durationSeconds: 4, resolution: '1080p', tier: 'fast', generateAudio: false, count: 1, confirmCost: false })
    expect(warnings).toEqual([])
  })

  it('validates for Veo', () => {
    expect(() => normalizeVideoParams({ prompt: '' }, 'vertex')).toThrow(/prompt is required/)
    expect(() => normalizeVideoParams({ prompt: 'a', durationSeconds: 5 }, 'vertex')).toThrow(/4, 6 or 8/)
    expect(() => normalizeVideoParams({ prompt: 'a', aspect: '1:1' }, 'vertex')).toThrow(/aspect must be/)
    expect(() => normalizeVideoParams({ prompt: 'a', resolution: '4k' }, 'vertex')).toThrow(/resolution must be/)
    expect(() => normalizeVideoParams({ prompt: 'a', tier: 'pro' }, 'vertex')).toThrow(/tier must be/)
    expect(() => normalizeVideoParams({ prompt: 'a', count: 3 }, 'vertex')).toThrow(/count must be/)
    expect(() => normalizeVideoParams({ prompt: 'a', seed: 1.5 }, 'vertex')).toThrow(/seed must be/)
    expect(() => normalizeVideoParams({ prompt: 'a', lastFramePath: '/x.png' }, 'vertex')).toThrow(/needs imagePath/)
  })

  it('validates for Omni and reports ignored options', () => {
    expect(() => normalizeVideoParams({ prompt: 'a', durationSeconds: 11 }, 'gemini')).toThrow(/3 to 10/)
    expect(() => normalizeVideoParams({ prompt: 'a', durationSeconds: 2.5 }, 'gemini')).toThrow(/3 to 10/)
    const { request, warnings } = normalizeVideoParams({ prompt: 'a', durationSeconds: 7, tier: 'lite', seed: 3 }, 'gemini')
    expect(request.durationSeconds).toBe(7)
    expect(warnings.join(' ')).toMatch(/tier "lite" is ignored/)
    expect(warnings.join(' ')).toMatch(/seed is ignored/)
    expect(warnings.join(' ')).toMatch(/always makes audio/)
  })

  it('warns, without blocking, on a Vertex region other than us-central1', () => {
    const { warnings } = normalizeVideoParams({ prompt: 'a' }, 'vertex', 'europe-west4')
    expect(warnings[0]).toMatch(/us-central1 only/)
  })
})

describe('estimateCost', () => {
  const base = { durationSeconds: 4, resolution: '1080p', tier: 'fast', generateAudio: false, count: 1 } as const
  it('uses the plan rate table', () => {
    expect(estimateCost(base, 'vertex').estimateUsd).toBe(0.6)
    expect(estimateCost({ ...base, generateAudio: true }, 'vertex').estimateUsd).toBe(0.8)
    expect(estimateCost({ ...base, tier: 'lite', durationSeconds: 8 }, 'vertex').estimateUsd).toBe(0.4)
    expect(estimateCost({ ...base, tier: 'standard', generateAudio: true, count: 2 }, 'vertex').estimateUsd).toBe(4.8)
    expect(estimateCost({ ...base, resolution: '720p', durationSeconds: 5 }, 'gemini').estimateUsd).toBe(0.5)
    expect(estimateCost({ ...base, durationSeconds: 5 }, 'gemini').estimateUsd).toBe(0.75)
  })
  it('ignores generateAudio on Omni, which always makes sound', () => {
    expect(rateFor('gemini', 'fast', '720p', true).usdPerSecond).toBe(rateFor('gemini', 'fast', '720p', false).usdPerSecond)
  })
  it('lets config override part of the table', () => {
    expect(estimateCost(base, 'vertex', { fast: 0.1 }).estimateUsd).toBe(0.4)
    expect(estimateCost({ ...base, generateAudio: true }, 'vertex', { fast: 0.1 }).estimateUsd).toBe(0.8)
  })
  it('describes the maths as an estimate', () => {
    expect(estimateCost({ ...base, count: 2 }, 'vertex').breakdown).toBe('2 clips x 4 s x $0.15/s (fast) = $1.20, an estimate')
  })
})

describe('videoPlacementFit', () => {
  it('warns that 720p is upscaled 1.5x on a 1080-wide placement', () => {
    const { placementFit, warnings } = videoPlacementFit(720, 1280)
    expect(placementFit['9x16']!.scale).toBe(1.5)
    expect(warnings[0]).toMatch(/1\.5x/)
  })
  it('is quiet at 1080p', () => {
    expect(videoPlacementFit(1080, 1920).warnings).toEqual([])
  })
})

describe('httpFailure', () => {
  it('names the valid ids on 404', () => {
    const e = httpFailure('vertex', 404, 'nope', 'veo-x')
    expect(e.code).toBe('VIDEO_MODEL_NOT_FOUND')
    expect(e.message).toContain('veo-3.1-fast-generate-001')
  })
})
