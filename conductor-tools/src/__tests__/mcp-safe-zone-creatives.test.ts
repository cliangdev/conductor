import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'
import type { OcrEngine, OcrWord } from '../lib/safe-zone/ocr.js'
import type { OverlayWriter } from '../mcp/tools/safe-zone-pixels.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  putBytes: vi.fn(),
  ApiError: class ApiError extends Error {},
}))
vi.mock('../lib/creative-render.js', () => ({ renderCreative: vi.fn(), renderViaApi: vi.fn() }))
vi.mock('../lib/media-probe.js', () => ({ probeMedia: vi.fn(), extractPoster: vi.fn() }))
vi.mock('../lib/poster-sheet.js', () => ({ composePosterSheet: vi.fn() }))

import { apiGet, apiPost, apiPatch } from '../mcp/api.js'
import { renderCreative } from '../lib/creative-render.js'
import { attachCreativeToPost, createCreative, previewCreative, renderCreativeTool, updateCreative } from '../mcp/tools/creatives.js'

const config: Config = { apiKey: 'k', projectId: 'proj-1', projectName: 'P', email: 'e@x.test', apiUrl: 'https://api.test' }
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

function fakePng(w: number, h: number): Buffer {
  const b = Buffer.alloc(33)
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(b)
  b.writeUInt32BE(13, 8)
  b.write('IHDR', 12)
  b.writeUInt32BE(w, 16)
  b.writeUInt32BE(h, 20)
  b[24] = 8
  b[25] = 2
  return b
}
const word = (text: string, x: number, y: number): OcrWord => ({ text, confidence: 95, box: { x, y, w: 300, h: 50 } })
function fakeEngine(words: OcrWord[]): OcrEngine {
  return {
    async recognizeWords(_png, rect) {
      if (!rect) return words
      return words.filter((w) => {
        const cx = w.box.x + w.box.w / 2
        const cy = w.box.y + w.box.h / 2
        return cx >= rect.left && cx <= rect.left + rect.width && cy >= rect.top && cy <= rect.top + rect.height
      })
    },
  }
}
const fetchImpl = (async () => ({ ok: true, status: 200, arrayBuffer: async () => fakePng(1080, 1920) })) as unknown as typeof fetch
const overlay: OverlayWriter = async (_f, platforms, outDir, base) => platforms.map((platform) => ({ platform, file: `${outDir}/${base}-${platform}.jpg` }))

const renders = [
  {
    id: 'r1',
    state: 'SUCCEEDED',
    previewOnly: false,
    creativeVersion: 1,
    frames: [
      { placementKey: '9x16', sequenceIndex: 0, url: 'https://x/1.png', contentType: 'image/png' },
      { placementKey: '9x16', sequenceIndex: 1, url: 'https://x/2.png', contentType: 'image/png' },
    ],
  },
]
function stubCarousel(kind = 'CAROUSEL') {
  mocked(apiGet).mockImplementation(async (url: string) => (url.endsWith('/renders') ? renders : { kind, version: 1 }))
  mocked(apiPost).mockResolvedValue({ assets: [] })
}

beforeEach(() => vi.resetAllMocks())

describe('attach_creative_to_post pre-flight', () => {
  it('blocks a CAROUSEL with text under the top UI on slide 1, with a structured SAFE_ZONE_TEXT result', async () => {
    stubCarousel()
    const result = await attachCreativeToPost({ creativeId: 'c1', postId: 'w1' }, config, { ocrEngine: fakeEngine([word('HELLO', 100, 160)]), fetchImpl })
    expect(result).toMatchObject({ ok: false, code: 'SAFE_ZONE_TEXT' })
    expect(String(result['error'])).toMatch(/ignoreSafeZone: true/)
    expect((result['checks'] as Array<{ severity: string }>).some((c) => c.severity === 'error')).toBe(true)
    expect(apiPost).not.toHaveBeenCalled()
  })

  const targetsStub = (targets: unknown, creative: Record<string, unknown> = { kind: 'CAROUSEL', version: 1 }) =>
    mocked(apiGet).mockImplementation(async (url: string) =>
      url.endsWith('/renders') ? renders : url.endsWith('/publish-targets') ? targets : creative
    )

  it('does not refuse a Post whose destinations are only 4x5 and 1x1', async () => {
    targetsStub([{ platform: 'instagram', format: 'feed' }, { platform: 'facebook', format: 'feed' }])
    mocked(apiPost).mockResolvedValue({ assets: [] })
    const result = await attachCreativeToPost({ creativeId: 'c1', postId: 'w1' }, config, { ocrEngine: fakeEngine([word('HELLO', 100, 160)]), fetchImpl })
    expect(apiPost).toHaveBeenCalled()
    expect(result['code']).toBeUndefined()
    expect((result['safeZoneChecks'] as unknown[]).length).toBeGreaterThan(0)
  })

  it('still refuses a Post with a TikTok or Reels destination', async () => {
    for (const targets of [[{ platform: 'tiktok', format: 'feed' }], [{ platform: 'instagram', format: 'feed' }, { platform: 'instagram', format: 'reel' }]]) {
      mocked(apiPost).mockReset()
      targetsStub(targets)
      const result = await attachCreativeToPost({ creativeId: 'c1', postId: 'w1' }, config, { ocrEngine: fakeEngine([word('HELLO', 100, 160)]), fetchImpl })
      expect(result).toMatchObject({ ok: false, code: 'SAFE_ZONE_TEXT' })
      expect(apiPost).not.toHaveBeenCalled()
    }
  })

  it('refuses when the Post has no targets yet', async () => {
    targetsStub([])
    const result = await attachCreativeToPost({ creativeId: 'c1', postId: 'w1' }, config, { ocrEngine: fakeEngine([word('HELLO', 100, 160)]), fetchImpl })
    expect(result).toMatchObject({ code: 'SAFE_ZONE_TEXT' })
  })

  it('attaches with ignoreSafeZone: true and still returns the checks', async () => {
    stubCarousel()
    const result = await attachCreativeToPost({ creativeId: 'c1', postId: 'w1', ignoreSafeZone: true }, config, { ocrEngine: fakeEngine([word('HELLO', 100, 160)]), fetchImpl })
    expect(apiPost).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/creatives/c1/attach', { renderId: 'r1', workItemId: 'w1' }, config)
    expect(result['safeZoneChecks']).toBeDefined()
  })

  it('lets warnings through on a successful attach', async () => {
    stubCarousel()
    // Bottom text on slide 2 only is a warning anywhere.
    const result = await attachCreativeToPost({ creativeId: 'c1', postId: 'w1' }, config, { ocrEngine: fakeEngine([word('CAPTION', 100, 1600)]), fetchImpl })
    expect(apiPost).toHaveBeenCalled()
    expect((result['safeZoneChecks'] as Array<{ severity: string }>).every((c) => c.severity === 'warning')).toBe(true)
  })

  it('attaches a clean CAROUSEL with nothing extra', async () => {
    stubCarousel()
    const result = await attachCreativeToPost({ creativeId: 'c1', postId: 'w1' }, config, { ocrEngine: fakeEngine([word('HELLO', 100, 400)]), fetchImpl })
    expect(apiPost).toHaveBeenCalled()
    expect(result).toEqual({ assets: [] })
  })

  it('checks a CLIP video the same way', async () => {
    mocked(apiGet).mockImplementation(async (url: string) =>
      url.endsWith('/renders')
        ? [{ id: 'r2', state: 'SUCCEEDED', previewOnly: false, creativeVersion: 1, frames: [{ placementKey: '9x16', url: 'https://x/c.mp4', durationSeconds: 8, contentType: 'video/mp4' }] }]
        : { kind: 'CLIP', version: 1 }
    )
    const sampler = async () => [{ atSeconds: 0.5, png: fakePng(1080, 1920), isCut: false }]
    const result = await attachCreativeToPost({ creativeId: 'c1', postId: 'w1' }, config, { ocrEngine: fakeEngine([word('HELLO', 100, 170)]), fetchImpl, sampler })
    expect(result).toMatchObject({ ok: false, code: 'SAFE_ZONE_TEXT' })
  })

  it('does not look at a STILL', async () => {
    mocked(apiGet).mockResolvedValue({ kind: 'STILL' })
    mocked(apiPost).mockResolvedValue({ assets: [] })
    await attachCreativeToPost({ creativeId: 'c1', postId: 'w1', renderId: 'r9' }, config, { fetchImpl })
    expect(apiGet).not.toHaveBeenCalledWith(expect.stringContaining('/renders'), expect.anything())
  })
})

describe('preview_creative and render_creative on a saved CAROUSEL', () => {
  it('preview_creative returns checks, passed and overlay sheet paths for the 9x16 slides', async () => {
    stubCarousel()
    mocked(apiGet).mockImplementation(async (url: string) =>
      url.endsWith('/renders')
        ? [...renders, { id: 'r0', state: 'SUCCEEDED', previewOnly: true, creativeVersion: 1, frames: [{ placementKey: 'sheet', url: 'https://x/s.jpg', sizeBytes: 10 }] }]
        : { kind: 'CAROUSEL', version: 1 }
    )
    vi.stubGlobal('fetch', async () => ({ ok: true, status: 200, headers: new Headers({ 'content-type': 'image/jpeg' }), arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer }))
    const result = await previewCreative({ creativeId: 'c1' }, config, { ocrEngine: fakeEngine([word('HELLO', 100, 160)]), fetchImpl, overlay })
    vi.unstubAllGlobals()
    expect(result.passed).toBe(false)
    expect(result.checks?.[0]).toMatchObject({ rule: 'safeZoneText', index: 0, severity: 'error' })
    expect(result.overlaySheets?.map((s) => s.platform)).toEqual(['instagram', 'tiktok'])
    expect(result.image).toBeDefined()
  })

  it('render_creative reports the checks but is never blocked by them', async () => {
    mocked(renderCreative).mockResolvedValue({ ok: true, renderId: 'r1', state: 'SUCCEEDED', frames: renders[0]!.frames })
    mocked(apiGet).mockResolvedValue({ kind: 'CAROUSEL', version: 1 })
    const result = await renderCreativeTool({ creativeId: 'c1' }, config, undefined, { ocrEngine: fakeEngine([word('HELLO', 100, 160)]), fetchImpl })
    expect(result['ok']).toBe(true)
    expect((result['checks'] as unknown[]).length).toBeGreaterThan(0)
    expect(result['passed']).toBe(false)
  })
})

describe('create_creative / update_creative pacing', () => {
  const alt = ['A turkey. Text: "Thanksgiving in 20 min"', 'Plain. Text: "No stress"']

  it('computes durations from slideAltText for a CAROUSEL with a track and reports the total', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'c1' })
    const result = await createCreative({ kind: 'CAROUSEL', slides: { '9x16': ['m1', 'm2'] }, slideAltText: alt, audio: { source: 'track', trackId: 't' } }, config)
    const body = mocked(apiPost).mock.calls[0]![1] as any
    expect(body.slideshow.slideDurations).toEqual([3.5, 3])
    expect(result).toMatchObject({ id: 'c1', slideDurations: [3.5, 3], slideshowDurationSeconds: 6 })
  })

  it('keeps durations that were passed, and does nothing without a track', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'c1' })
    await createCreative({ kind: 'CAROUSEL', slides: { '9x16': ['m1', 'm2'] }, slideAltText: alt, audio: { source: 'track' }, slideshow: { slideDurations: [5, 5] } }, config)
    expect((mocked(apiPost).mock.calls[0]![1] as any).slideshow.slideDurations).toEqual([5, 5])
    await createCreative({ kind: 'CAROUSEL', slides: { '9x16': ['m1', 'm2'] }, slideAltText: alt }, config)
    expect((mocked(apiPost).mock.calls[1]![1] as any).slideshow).toBeUndefined()
  })

  it('update_creative does the same for a patch with slides, alt text and a track', async () => {
    mocked(apiPatch).mockResolvedValue({ id: 'c1' })
    await updateCreative({ creativeId: 'c1', version: 2, slides: { '9x16': ['m1', 'm2'] }, slideAltText: alt, audio: { source: 'track' } }, config)
    expect((mocked(apiPatch).mock.calls[0]![1] as any).slideshow.slideDurations).toEqual([3.5, 3])
  })
})

describe('update_creative pacing keeps the saved slideshow', () => {
  const url = '/api/v2/projects/proj-1/marketing/creatives/c1'
  const alt = ['Photo. Text: "one two"', 'Photo. Text: "three"']
  it('does not recompute saved durations or drop zoom:false', async () => {
    mocked(apiGet).mockResolvedValue({ kind: 'CAROUSEL', version: 3, slides: { '9x16': ['m1', 'm2'] }, slideAltText: alt, audio: { source: 'track' }, slideshow: { zoom: false, slideDurations: [5, 6] } })
    mocked(apiPatch).mockResolvedValue({ id: 'c1', version: 4 })
    await updateCreative({ creativeId: 'c1', version: 3, slideAltText: alt }, config)
    const body = mocked(apiPatch).mock.calls[0]![1] as Record<string, unknown>
    expect(body['slideshow']).toBeUndefined()
  })

  it('keeps zoom:false when it does compute durations', async () => {
    mocked(apiGet).mockResolvedValue({ kind: 'CAROUSEL', version: 3, slides: { '9x16': ['m1', 'm2'] }, slideAltText: alt, audio: { source: 'track' }, slideshow: { zoom: false } })
    mocked(apiPatch).mockResolvedValue({ id: 'c1', version: 4 })
    const result = await updateCreative({ creativeId: 'c1', version: 3, slideAltText: alt }, config)
    const body = mocked(apiPatch).mock.calls[0]![1] as { slideshow: { zoom: boolean; slideDurations: number[] } }
    expect(body.slideshow.zoom).toBe(false)
    expect(body.slideshow.slideDurations).toHaveLength(2)
    expect(result['slideDurations']).toHaveLength(2)
    expect(apiGet).toHaveBeenCalledWith(url, config)
  })

  it('recomputes when the slide count no longer matches the saved durations, keeping zoom', async () => {
    mocked(apiGet).mockResolvedValue({ kind: 'CAROUSEL', version: 3, slides: { '9x16': ['m1', 'm2'] }, slideAltText: alt, audio: { source: 'track' }, slideshow: { zoom: false, slideDurations: [5, 6] } })
    mocked(apiPatch).mockResolvedValue({ id: 'c1', version: 4 })
    await updateCreative({ creativeId: 'c1', version: 3, slides: { '9x16': ['m1', 'm2', 'm3'] }, slideAltText: [...alt, 'Photo. Text: "four"'] }, config)
    const body = mocked(apiPatch).mock.calls[0]![1] as { slideshow: { zoom: boolean; slideDurations: number[] } }
    expect(body.slideshow.zoom).toBe(false)
    expect(body.slideshow.slideDurations).toHaveLength(3)
  })
})
