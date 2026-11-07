import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import type { Config } from '../mcp/config.js'
import type { OcrEngine, OcrWord } from '../lib/safe-zone/ocr.js'
import type { SampledFrame } from '../lib/safe-zone/frames.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  putBytes: vi.fn(),
  ApiError: class ApiError extends Error {},
}))
vi.mock('../lib/creative-render.js', () => ({ renderCreative: vi.fn(), renderDraft: vi.fn(), renderViaApi: vi.fn() }))
vi.mock('../lib/poster-sheet.js', () => ({ composePosterSheet: vi.fn() }))
vi.mock('../lib/safe-zone/frames.js', async (orig) => ({ ...(await orig<typeof import('../lib/safe-zone/frames.js')>()), sampleVideoFrames: vi.fn() }))
vi.mock('../lib/media-probe.js', () => ({ probeMedia: vi.fn(), extractPoster: vi.fn() }))
vi.mock('../mcp/tools/creatives.js', () => ({
  createCreative: vi.fn(),
  updateCreative: vi.fn(),
  getCreative: vi.fn(),
  uploadCreativeMedia: vi.fn(),
}))

import { apiPost } from '../mcp/api.js'
import { renderDraft } from '../lib/creative-render.js'
import { composePosterSheet } from '../lib/poster-sheet.js'
import { probeMedia } from '../lib/media-probe.js'
import { sampleVideoFrames } from '../lib/safe-zone/frames.js'
import { createCreative, uploadCreativeMedia } from '../mcp/tools/creatives.js'
import { previewCreativeDraft, commitCreativeDraft, pruneDrafts } from '../mcp/tools/creative-drafts.js'
import { checkSavedCreativePixels, clipOverlayFrames, type OverlayWriter } from '../mcp/tools/safe-zone-pixels.js'

const config: Config = { apiKey: 'k', projectId: 'proj-1', projectName: 'P', email: 'e@x.test', apiUrl: 'https://api.test' }
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

/** A PNG whose header says w x h. The fake engines never decode it. */
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
const word = (text: string, x: number, y: number, w = 300, h = 50): OcrWord => ({ text, confidence: 95, box: { x, y, w, h } })
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
const IG_BYTES = Buffer.from([0xff, 0xd8, 0xff, 0xaa, 0xff, 0xd9])
const TT_BYTES = Buffer.from([0xff, 0xd8, 0xff, 0xbb, 0xff, 0xd9])
/** Writes one fake sheet per platform, like overlaySheetsFor, and records what it was asked to draw. */
function fakeOverlay(calls: Array<{ labels: string[]; platforms: string[] }> = []): OverlayWriter {
  return async (frames, platforms, outDir, baseName) => {
    calls.push({ labels: frames.map((f) => f.label), platforms })
    return platforms.map((platform) => {
      const file = path.join(outDir, `${baseName}-ui-${platform}.jpg`)
      fs.writeFileSync(file, platform === 'instagram' ? IG_BYTES : TT_BYTES)
      return { platform, file, bytes: 6 }
    })
  }
}

let root: string
const slide = (name: string): string => {
  const p = path.join(root, name)
  fs.writeFileSync(p, fakePng(1080, 1920))
  return p
}

beforeEach(() => {
  vi.resetAllMocks()
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'cond-sz-'))
  mocked(composePosterSheet).mockResolvedValue(Buffer.from([0xff, 0xd8, 0xff, 0xd9]))
})
afterEach(() => fs.rmSync(root, { recursive: true, force: true }))

describe('CAROUSEL pixel check', () => {
  it('fails a 9x16 carousel whose slide 1 has text at y=160, names slide 1 and the platform top UI, and says fix first', async () => {
    mocked(apiPost).mockResolvedValue({ spec: {}, readiness: {} })
    const result = await previewCreativeDraft(
      { kind: 'CAROUSEL', caption: 'C', slidePaths: { '9x16': [slide('01.png'), slide('02.png')] } },
      config,
      { projectRoot: root, ocrEngine: fakeEngine([word('THANKSGIVING', 100, 160)]), overlay: fakeOverlay() }
    )
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.passed).toBe(false)
    const hit = result.checks.find((c) => c.rule === 'safeZoneText' && c.index === 0)!
    expect(hit.severity).toBe('error')
    expect(hit.message).toMatch(/Slide 1/)
    expect(hit.platforms).toEqual(expect.arrayContaining([expect.stringMatching(/Instagram Reels/), expect.stringMatching(/TikTok/)]))
    // Slide 2 has the same text, but the top zone is only an error on slide 1.
    expect(result.checks.find((c) => c.index === 1)?.severity).toBe('warning')
    expect(result.nextStep).toMatch(/NOT ready for approval/)
    expect(result.nextStep).toMatch(/y=290/)
    expect(result.nextStep).toMatch(/BEFORE showing/)
  })

  it('passes with checks [] when the text is at y >= 290, inlines the Instagram overlay and keeps both sheets on disk', async () => {
    mocked(apiPost).mockResolvedValue({ spec: {}, readiness: {} })
    const calls: Array<{ labels: string[]; platforms: string[] }> = []
    const result = await previewCreativeDraft(
      { kind: 'CAROUSEL', caption: 'C', slidePaths: { '9x16': [slide('01.png'), slide('02.png')] } },
      config,
      { projectRoot: root, ocrEngine: fakeEngine([word('THANKSGIVING', 100, 400)]), overlay: fakeOverlay(calls) }
    )
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.passed).toBe(true)
    expect(result.checks).toEqual([])
    expect(result.image?.data.equals(IG_BYTES)).toBe(true)
    expect(result.overlaySheets?.map((s) => s.platform)).toEqual(['instagram', 'tiktok'])
    for (const s of result.overlaySheets!) expect(fs.existsSync(s.file)).toBe(true)
    // The clean contact sheet stays on disk, with its path returned.
    expect(result.sheets?.[0]?.file).toMatch(/sheet-9x16\.jpg$/)
    expect(fs.existsSync(result.sheets![0]!.file)).toBe(true)
    expect(calls[0]!.labels).toEqual(['Slide 1', 'Slide 2'])
  })

  it('uiOverlay: none draws nothing and keeps the clean sheet as the image', async () => {
    mocked(apiPost).mockResolvedValue({ spec: {}, readiness: {} })
    const calls: Array<{ labels: string[]; platforms: string[] }> = []
    const result = await previewCreativeDraft(
      { kind: 'CAROUSEL', caption: 'C', uiOverlay: 'none', slidePaths: { '9x16': [slide('01.png')] } },
      config,
      { projectRoot: root, ocrEngine: fakeEngine([]), overlay: fakeOverlay(calls) }
    )
    expect(result.ok && result.overlaySheets).toBeFalsy()
    expect(calls).toEqual([])
    // uiOverlay is a tool option, not a Creative field: it is never sent to the backend.
    expect(mocked(apiPost).mock.calls[0]![1]).not.toHaveProperty('uiOverlay')
  })

  it('reports an unchecked warning (not an empty list) for slides that are existing media', async () => {
    mocked(apiPost).mockResolvedValue({ spec: {}, readiness: {} })
    const result = await previewCreativeDraft(
      { kind: 'CAROUSEL', caption: 'C', slidePaths: { '9x16': [slide('01.png'), '11111111-1111-1111-1111-111111111111'] } },
      config,
      { projectRoot: root, ocrEngine: fakeEngine([]), overlay: fakeOverlay() }
    )
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.passed).toBe(true)
    expect(result.checks.map((c) => c.rule)).toEqual(['safeZoneUnchecked'])
    expect(result.checks[0]!.message).toMatch(/slide 2/)
  })
})

describe('slideshow pacing in the preview', () => {
  const alt = ['A turkey. Text: "Thanksgiving in 20 min"', 'The app screen. Text: "No stress"']
  function fakeSlideshowRender(durationSeconds = 6) {
    mocked(renderDraft).mockImplementation(async ({ outDir }: { outDir: string }) => {
      fs.writeFileSync(path.join(outDir, 'slideshow-9x16.mp4'), Buffer.from([0, 0, 0, 1]))
      return {
        ok: true,
        manifest: { ok: true, frames: [{ placementKey: '9x16', file: 'slideshow-9x16.mp4', contentType: 'video/mp4', durationSeconds }], warnings: [] },
      }
    })
  }
  const base = () => ({ kind: 'CAROUSEL', caption: 'C', audioPath: path.join(root, 'song.mp3'), slideAltText: alt })

  beforeEach(() => {
    fs.writeFileSync(path.join(root, 'song.mp3'), 'ID3')
    mocked(probeMedia).mockResolvedValue({ kind: 'AUDIO', durationSeconds: 30 })
  })

  it('times the slides from their text, sends and saves the durations, and reports the total', async () => {
    mocked(apiPost).mockResolvedValue({ spec: { creative: { kind: 'CAROUSEL' } }, readiness: {} })
    fakeSlideshowRender(6)
    const result = await previewCreativeDraft(
      { ...base(), slidePaths: { '9x16': [slide('01.png'), slide('02.png')] } },
      config,
      { projectRoot: root, ocrEngine: fakeEngine([]), overlay: fakeOverlay() }
    )
    expect(result.ok).toBe(true)
    if (!result.ok) return
    // 4 words: 3.0 + 0.5 first; 2 words on an app screen: 3.0 + 0.5.
    expect(result.slideDurations).toEqual([3.5, 3.5])
    expect(result.pacing).toEqual({ basis: 'altText', computed: true })
    expect(result.slideshowDurationSeconds).toBe(6)
    expect(result.nextStep).toMatch(/say the total length \(6 s\)/)
    // Sent to the backend (so the MP4 uses it) and saved in draft.json (so commit stores it).
    expect((mocked(apiPost).mock.calls[0]![1] as any).slideshow.slideDurations).toEqual([3.5, 3.5])
    const draft = JSON.parse(fs.readFileSync(path.join(result.draftDir, 'draft.json'), 'utf8'))
    expect(draft.request.slideshow.slideDurations).toEqual([3.5, 3.5])
  })

  it('commit_creative_draft sends the computed durations to the created Creative', async () => {
    mocked(apiPost).mockResolvedValue({ spec: { creative: { kind: 'CAROUSEL' } }, readiness: {} })
    fakeSlideshowRender()
    const preview = await previewCreativeDraft(
      { ...base(), slidePaths: { '9x16': [slide('01.png'), slide('02.png')] } },
      config,
      { projectRoot: root, ocrEngine: fakeEngine([]), overlay: fakeOverlay() }
    )
    if (!preview.ok) throw new Error('preview failed')
    let n = 0
    mocked(uploadCreativeMedia).mockImplementation(async () => ({ media: { id: `m${++n}` }, warnings: [] }))
    mocked(createCreative).mockResolvedValue({ id: 'c1', kind: 'CAROUSEL', audio: { source: 'track' } })
    mocked((await import('../mcp/tools/creatives.js')).getCreative).mockResolvedValue({})
    mocked((await import('../lib/creative-render.js')).renderCreative).mockResolvedValue({ ok: true, frames: [] })
    await commitCreativeDraft({ draftDir: preview.draftDir }, config, { projectRoot: root })
    expect((mocked(createCreative).mock.calls[0]![0] as any).slideshow.slideDurations).toEqual([3.5, 3.5])
  })

  it('keeps durations that were passed and adds pacing warnings for slides held too briefly', async () => {
    mocked(apiPost).mockResolvedValue({ spec: { creative: { kind: 'CAROUSEL' } }, readiness: {} })
    fakeSlideshowRender()
    const result = await previewCreativeDraft(
      { ...base(), slideshow: { slideDurations: [0.9, 3.5] }, slidePaths: { '9x16': [slide('01.png'), slide('02.png')] } },
      config,
      { projectRoot: root, ocrEngine: fakeEngine([]), overlay: fakeOverlay() }
    )
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect((mocked(apiPost).mock.calls[0]![1] as any).slideshow.slideDurations).toEqual([0.9, 3.5])
    expect(result.pacing).toEqual({ basis: 'altText', computed: false })
    expect(result.slideDurations).toEqual([0.9, 3.5])
    expect(result.warnings.some((w) => /Slide 1/.test(w.message))).toBe(true)
  })

  it('reads the words off the slide with OCR when its alt text has no Text: part', async () => {
    mocked(apiPost).mockResolvedValue({ spec: { creative: { kind: 'CAROUSEL' } }, readiness: {} })
    fakeSlideshowRender()
    // 12 words of text at y=500: 2.4 + 0.14 x 12 = 4.08 s (+ 0.5 for the first slide).
    const words = Array.from({ length: 12 }, (_, i) => word(`word${i}`, 40 + (i % 4) * 250, 500 + Math.floor(i / 4) * 80, 200))
    const result = await previewCreativeDraft(
      { kind: 'CAROUSEL', caption: 'C', audioPath: path.join(root, 'song.mp3'), slidePaths: { '9x16': [slide('01.png')] } },
      config,
      { projectRoot: root, ocrEngine: fakeEngine(words), overlay: fakeOverlay() }
    )
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.pacing).toEqual({ basis: 'ocr', computed: true })
    expect(result.slideDurations).toEqual([4.6])
  })
})

describe('CLIP preview', () => {
  const clip = () => {
    const p = path.join(root, 'clip.mp4')
    fs.writeFileSync(p, 'mp4')
    return p
  }
  const sampler = async (): Promise<SampledFrame[]> =>
    [0.5, 1.5, 2.5, 4.2, 6].map((atSeconds) => ({ atSeconds, png: fakePng(1080, 1920), isCut: atSeconds === 4.2 }))

  beforeEach(() => {
    mocked(probeMedia).mockResolvedValue({ kind: 'VIDEO', width: 1080, height: 1920, durationSeconds: 8, hasAudio: true })
  })

  it('fails a clip whose first-second captions sit at y=170, and sends nothing to Conductor', async () => {
    const result = await previewCreativeDraft({ kind: 'CLIP', clipPath: clip() }, config, {
      projectRoot: root,
      ocrEngine: fakeEngine([word('SAVE MORE TODAY', 100, 170)]),
      sampler,
      overlay: fakeOverlay(),
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.passed).toBe(false)
    expect(result.checks[0]).toMatchObject({ rule: 'safeZoneText', severity: 'error', placementKey: '9x16', atSeconds: 0.5 })
    expect(result.checks[0]!.platforms).toEqual(expect.arrayContaining([expect.stringMatching(/Instagram Reels/), expect.stringMatching(/TikTok/)]))
    expect(result.nextStep).toMatch(/NOT ready/)
    expect(apiPost).not.toHaveBeenCalled()
    expect(uploadCreativeMedia).not.toHaveBeenCalled()
  })

  it('passes when the captions are at y >= 290; the overlay uses 0.5/1.5/2.5 s and each cut, Instagram inlined, both on disk', async () => {
    const calls: Array<{ labels: string[]; platforms: string[] }> = []
    const result = await previewCreativeDraft({ kind: 'CLIP', clipPath: clip() }, config, {
      projectRoot: root,
      ocrEngine: fakeEngine([word('SAVE MORE TODAY', 100, 400)]),
      sampler,
      overlay: fakeOverlay(calls),
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.passed).toBe(true)
    expect(result.checks).toEqual([])
    expect(calls[0]!.labels).toEqual(['0.5 s', '1.5 s', '2.5 s', 'Cut at 4.2 s'])
    expect(result.image?.data.equals(IG_BYTES)).toBe(true)
    expect(result.overlaySheets?.map((s) => s.platform)).toEqual(['instagram', 'tiktok'])
    expect(result.overlaySheets!.every((s) => fs.existsSync(s.file))).toBe(true)
    expect(result.nextStep).toMatch(/upload_creative_media/)
    expect(result.nextStep).toMatch(/kind CLIP/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('accepts clipPaths per placement; a non-9:16 clip is not checked and says so', async () => {
    mocked(probeMedia).mockResolvedValue({ kind: 'VIDEO', width: 1920, height: 1080, durationSeconds: 8 })
    const result = await previewCreativeDraft({ kind: 'CLIP', clipPaths: { '16x9': clip() } }, config, {
      projectRoot: root,
      ocrEngine: fakeEngine([word('TEXT', 100, 170)]),
      sampler,
      overlay: fakeOverlay(),
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.checks).toEqual([])
    expect(result.note).toMatch(/not 9:16/)
    expect(result.image).toBeUndefined()
  })

  it('a single clipPath whose aspect is not 9:16 is filed under its own placement', async () => {
    mocked(probeMedia).mockResolvedValue({ kind: 'VIDEO', width: 1080, height: 1350, durationSeconds: 8 })
    const result = await previewCreativeDraft({ kind: 'CLIP', clipPath: clip() }, config, { projectRoot: root, ocrEngine: fakeEngine([]), sampler, overlay: fakeOverlay() })
    expect(result.ok && result.files[0]!.placementKey).toBe('4x5')
  })

  it('writes a draft.json so pruning removes the folder by age, and refuses to commit it', async () => {
    const result = await previewCreativeDraft({ kind: 'CLIP', clipPath: clip() }, config, { projectRoot: root, ocrEngine: fakeEngine([]), sampler, overlay: fakeOverlay() })
    const dir = result.draftDir!
    expect(JSON.parse(fs.readFileSync(path.join(dir, 'draft.json'), 'utf8'))).toMatchObject({ committed: false, request: { kind: 'CLIP' } })
    await expect(commitCreativeDraft({ draftDir: dir }, config, { projectRoot: root })).rejects.toThrow(/no commit step/)
    expect(await pruneDrafts(root, new Date(Date.now() + 15 * 24 * 3600 * 1000))).toEqual([path.resolve(dir)])
    expect(fs.existsSync(dir)).toBe(false)
  })

  it('asks for a clip path when none is given', async () => {
    const result = await previewCreativeDraft({ kind: 'CLIP' }, config, { projectRoot: root })
    expect(result.ok).toBe(false)
  })

  it('caps the overlay sheet at 10 frames', () => {
    const frames = Array.from({ length: 30 }, (_, i) => ({ atSeconds: 0.5 + i, png: fakePng(1080, 1920), isCut: i % 2 === 1 }))
    expect(clipOverlayFrames(frames).length).toBeLessThanOrEqual(10)
  })
})

describe('STILL previews', () => {
  it('writes an overlay sheet for a 9x16 frame image on disk, and keeps the contact sheet as the image', async () => {
    const photo = path.join(root, 'hero.png')
    fs.writeFileSync(photo, fakePng(1080, 1920))
    mocked(probeMedia).mockResolvedValue({ kind: 'IMAGE' })
    mocked(apiPost).mockResolvedValue({ spec: { creative: { kind: 'STILL' } }, readiness: {} })
    mocked(renderDraft).mockImplementation(async ({ outDir }: { outDir: string }) => {
      fs.writeFileSync(path.join(outDir, 'sheet.jpg'), Buffer.from([0xff, 0xd8, 0xff, 0xd9]))
      fs.writeFileSync(path.join(outDir, '9x16.png'), fakePng(1080, 1920))
      return {
        ok: true,
        manifest: {
          ok: true,
          frames: [
            { placementKey: 'sheet', file: 'sheet.jpg', contentType: 'image/jpeg' },
            { placementKey: '9x16', file: '9x16.png', contentType: 'image/png', width: 1080, height: 1920 },
          ],
          warnings: [],
          checks: [],
          passed: true,
        },
      }
    })
    const result = await previewCreativeDraft({ headline: 'H', photoPath: photo, full: true }, config, { projectRoot: root, overlay: fakeOverlay() })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.overlaySheets?.map((s) => s.platform)).toEqual(['instagram', 'tiktok'])
    expect(result.overlaySheets![0]!.file).toMatch(/overlay-9x16-ui-instagram\.jpg$/)
    expect(result.image?.data.equals(IG_BYTES)).toBe(false)
  })

  it('skips silently when only a contact sheet exists', async () => {
    const photo = path.join(root, 'hero.png')
    fs.writeFileSync(photo, fakePng(1080, 1920))
    mocked(probeMedia).mockResolvedValue({ kind: 'IMAGE' })
    mocked(apiPost).mockResolvedValue({ spec: { creative: { kind: 'STILL' } }, readiness: {} })
    mocked(renderDraft).mockImplementation(async ({ outDir }: { outDir: string }) => {
      fs.writeFileSync(path.join(outDir, 'sheet.jpg'), Buffer.from([0xff, 0xd8, 0xff, 0xd9]))
      return { ok: true, manifest: { ok: true, frames: [{ placementKey: 'sheet', file: 'sheet.jpg', contentType: 'image/jpeg' }], warnings: [] } }
    })
    const result = await previewCreativeDraft({ headline: 'H', photoPath: photo }, config, { projectRoot: root, overlay: fakeOverlay() })
    expect(result.ok && result.overlaySheets).toBeUndefined()
  })
})

describe('clip duration and overlay folders', () => {
  it('passes the probed duration to the frame sampler', async () => {
    mocked(probeMedia).mockResolvedValue({ kind: 'VIDEO', width: 1080, height: 1920, durationSeconds: 8, hasAudio: true })
    mocked(sampleVideoFrames).mockResolvedValue([{ atSeconds: 0.5, png: fakePng(1080, 1920), isCut: false }])
    const clip = path.join(root, 'clip.mp4')
    fs.writeFileSync(clip, 'mp4')
    await previewCreativeDraft({ kind: 'CLIP', clipPath: clip }, config, { projectRoot: root, ocrEngine: fakeEngine([]), overlay: fakeOverlay() })
    expect(sampleVideoFrames).toHaveBeenCalledWith(clip, { durationSeconds: 8 })
  })

  const savedCarousel = { kind: 'CAROUSEL' as const, audio: null }
  const render = { id: 'r', state: 'SUCCEEDED', frames: [{ placementKey: '9x16', sequenceIndex: 0, url: 'https://x.test/a.png', contentType: 'image/png' }] }
  const fetchImpl = (async () => new Response(fakePng(1080, 1920))) as unknown as typeof fetch

  it('removes the folder it made when no overlay was written, and keeps it when sheets point into it', async () => {
    let dirNone = ''
    const none: OverlayWriter = async (_f, _p, outDir) => {
      dirNone = outDir
      return []
    }
    await checkSavedCreativePixels(savedCarousel, render, { ocrEngine: fakeEngine([]), fetchImpl, writeOverlay: true, uiOverlay: 'instagram', overlay: none })
    expect(dirNone).not.toBe('')
    expect(fs.existsSync(dirNone)).toBe(false)

    const out = await checkSavedCreativePixels(savedCarousel, render, { ocrEngine: fakeEngine([]), fetchImpl, writeOverlay: true, uiOverlay: 'instagram', overlay: fakeOverlay() })
    const sheets = out.result!.overlaySheets
    expect(sheets.length).toBeGreaterThan(0)
    for (const s of sheets) expect(fs.existsSync(s.file)).toBe(true)
    for (const s of sheets) fs.rmSync(path.dirname(s.file), { recursive: true, force: true })
  })

  it('reuses one folder per render, so repeated previews do not pile up', async () => {
    const r = { ...render, id: `reuse-${process.pid}` }
    const first = await checkSavedCreativePixels(savedCarousel, r, { ocrEngine: fakeEngine([]), fetchImpl, writeOverlay: true, uiOverlay: 'instagram', overlay: fakeOverlay() })
    const second = await checkSavedCreativePixels(savedCarousel, r, { ocrEngine: fakeEngine([]), fetchImpl, writeOverlay: true, uiOverlay: 'instagram', overlay: fakeOverlay() })
    const dir1 = path.dirname(first.result!.overlaySheets[0]!.file)
    const dir2 = path.dirname(second.result!.overlaySheets[0]!.file)
    expect(dir2).toBe(dir1)
    expect(fs.readdirSync(dir2).length).toBe(second.result!.overlaySheets.length)
    fs.rmSync(dir2, { recursive: true, force: true })
  })
})
