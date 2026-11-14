import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync, existsSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

vi.mock('../lib/media-probe.js', async (orig) => ({ ...(await orig<typeof import('../lib/media-probe.js')>()), probeMedia: vi.fn() }))

import { probeMedia } from '../lib/media-probe.js'
import { composeVideoTool, type ComposeVideoDeps } from '../mcp/tools/video-compose.js'
import { composePlan } from '../lib/video/render.js'
import { resolveTimeline } from '../lib/video/timeline.js'

const probe = vi.mocked(probeMedia)
let tmp: string

/** A PNG header with real dimensions (all readImageDimensions reads). */
function pngBytes(width: number, height: number): Buffer {
  const b = Buffer.alloc(33)
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(b)
  b.writeUInt32BE(13, 8)
  b.write('IHDR', 12)
  b.writeUInt32BE(width, 16)
  b.writeUInt32BE(height, 20)
  return b
}

const noOcr = { ocrEngine: { recognize: async () => [] } as never, sampler: async () => [] }
const goodDeps = (over: Partial<ComposeVideoDeps> = {}): ComposeVideoDeps => ({
  renderBeats: async (texts, dir) => ({
    beats: texts.map((_, i) => ({ index: i, png: join(dir, `b${i}.png`), top: 500 + i * 300, bottom: 600 + i * 300 })),
    checks: [],
  }),
  render: vi.fn(async () => ({ hasAudio: false })),
  motion: async () => 5,
  frame0: async (_v, out) => writeFileSync(out, 'png'),
  contactSheet: async (_v, out) => {
    writeFileSync(out, 'jpg')
    return { data: Buffer.from('jpg'), mimeType: 'image/jpeg' }
  },
  probeOutput: async () => ({ durationSeconds: 9.4 }),
  ...over,
})

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'compose-video-'))
  writeFileSync(join(tmp, 'a.png'), pngBytes(1080, 1920))
  writeFileSync(join(tmp, 'b.png'), pngBytes(1080, 1920))
  writeFileSync(join(tmp, 'hook.mp4'), 'x')
  writeFileSync(join(tmp, 'music.wav'), 'x')
  probe.mockReset()
  probe.mockImplementation(async (file: string) =>
    file.endsWith('.wav')
      ? ({ kind: 'AUDIO', contentType: 'audio/wav', durationSeconds: 10, hasAudio: true } as never)
      : ({ kind: 'VIDEO', contentType: 'video/mp4', durationSeconds: 5, hasAudio: true, width: 1080, height: 1920 } as never),
  )
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

const spec = () => ({
  id: 'My Video!',
  segments: [{ clip: 'hook.mp4', startSec: 0.4, endSec: 3.8 }, { image: 'a.png', durationSec: 3 }, { image: 'b.png', durationSec: 3, zoom: true }],
  texts: [{ fromSec: 0, toSec: 3.4, t: 'Hook', y: 30 }, { fromSec: 3.4, toSec: 9.4, t: 'Body', y: 45 }],
})

describe('composeVideoTool: validation', () => {
  it('needs a spec and returns every structural error with its path', async () => {
    expect(await composeVideoTool({}, { projectRoot: tmp })).toMatchObject({ ok: false, error: expect.stringMatching(/Pass spec/) })
    const res = await composeVideoTool({ spec: { segments: [{ clip: 'a.mp4', startSec: 2, endSec: 1 }, { image: 'b.png' }] } }, { projectRoot: tmp })
    expect(res).toMatchObject({ ok: false, error: 'The video spec is invalid.' })
    if (res.ok) return
    expect(res.errors?.[0]).toMatch(/^segments\[0\]\.endSec/)
    expect(res.errors?.[1]).toMatch(/^segments\[1\]\.durationSec/)
  })

  it('names a missing file, a non-video clip, a non-image and a non-audio track by path', async () => {
    probe.mockImplementation(async () => ({ kind: 'IMAGE', contentType: 'image/png' }) as never)
    writeFileSync(join(tmp, 'notimage.png'), 'plain text')
    const res = await composeVideoTool(
      {
        spec: {
          segments: [{ clip: 'nope.mp4' }, { clip: 'hook.mp4' }, { image: 'notimage.png', durationSec: 3 }],
          texts: [{ fromSec: 0, toSec: 2, t: 'x', y: 30 }],
          audio: { track: 'music.wav' },
        },
      },
      { projectRoot: tmp },
    )
    if (res.ok) throw new Error('expected a failure')
    expect(res.errors?.some((e) => e.startsWith('segments[0].clip: file not found'))).toBe(true)
    expect(res.errors?.some((e) => e.startsWith('segments[1].clip: not a video file'))).toBe(true)
    expect(res.errors?.some((e) => e.startsWith('segments[2].image: not a PNG, JPEG or WebP image'))).toBe(true)
    expect(res.errors?.some((e) => e.startsWith('audio.track: not an audio file'))).toBe(true)
  })

  it('reports a trim past the end of the clip and a total outside 3-90 s', async () => {
    const res = await composeVideoTool({ spec: { segments: [{ clip: 'hook.mp4', endSec: 9 }, { image: 'a.png', durationSec: 3 }] } }, { projectRoot: tmp })
    if (res.ok) throw new Error('expected a failure')
    expect(res.errors?.[0]).toMatch(/^segments\[0\]\.endSec: 9 is past the end of the clip \(5 s long\)/)
    const short = await composeVideoTool({ spec: { segments: [{ image: 'a.png', durationSec: 2 }] } }, { projectRoot: tmp })
    if (short.ok) throw new Error('expected a failure')
    expect(short.errors?.[0]).toMatch(/^segments: the video is 2 s long/)
  })

  it('refuses a source that would be upscaled more than 2x and warns below that', async () => {
    probe.mockImplementation(async () => ({ kind: 'VIDEO', contentType: 'video/mp4', durationSeconds: 5, hasAudio: false, width: 720, height: 1280 }) as never)
    const warn = await composeVideoTool({ spec: spec() }, { projectRoot: tmp, deps: goodDeps(), pixel: noOcr })
    if (!warn.ok) throw new Error(warn.error)
    expect(warn.checks.find((c) => c.rule === 'sourceResolution')).toMatchObject({ severity: 'warning', index: 0, message: expect.stringContaining('segments[0].clip is 720x1280, upscaled 1.5x') })
    probe.mockImplementation(async () => ({ kind: 'VIDEO', contentType: 'video/mp4', durationSeconds: 5, hasAudio: false, width: 360, height: 640 }) as never)
    const err = await composeVideoTool({ spec: spec() }, { projectRoot: tmp, deps: goodDeps(), pixel: noOcr })
    if (!err.ok) throw new Error(err.error)
    expect(err.checks.find((c) => c.rule === 'sourceResolution')?.severity).toBe('error')
    expect(err.rendered).toBe(false)
  })
})

describe('composeVideoTool: checks that stop the render', () => {
  it('does not encode when the first second has no text', async () => {
    const deps = goodDeps()
    const s = spec()
    s.texts = [{ fromSec: 2, toSec: 9, t: 'Late', y: 30 }]
    const res = await composeVideoTool({ spec: s }, { projectRoot: tmp, deps, pixel: noOcr })
    if (!res.ok) throw new Error(res.error)
    expect(res).toMatchObject({ rendered: false, passed: false })
    expect(res.checks.map((c) => c.rule)).toContain('firstSecondText')
    expect(res.nextStep).toMatch(/Not rendered/)
    expect(deps.render).not.toHaveBeenCalled()
    expect(res.videoPath).toBeUndefined()
  })

  it('does not encode when two beats on screen together share rows of the frame', async () => {
    const deps = goodDeps({ renderBeats: async (texts, dir) => ({ beats: texts.map((_, i) => ({ index: i, png: join(dir, `b${i}.png`), top: 500, bottom: 700 })), checks: [] }) })
    const s = spec()
    s.texts = [{ fromSec: 0, toSec: 5, t: 'A', y: 30 }, { fromSec: 2, toSec: 9, t: 'B', y: 31 }]
    const res = await composeVideoTool({ spec: s }, { projectRoot: tmp, deps, pixel: noOcr })
    if (!res.ok) throw new Error(res.error)
    expect(res.checks.find((c) => c.rule === 'textOverlap')).toBeTruthy()
    expect(res.rendered).toBe(false)
  })

  it('does not encode when the text renderer reports an error (safe zone, spill)', async () => {
    const deps = goodDeps({ renderBeats: async (texts, dir) => ({ beats: texts.map((_, i) => ({ index: i, png: join(dir, `b${i}.png`), top: 0, bottom: 10 })), checks: [{ placementKey: '9x16', index: 0, rule: 'safeZone', severity: 'error', message: 'texts[0]: intrudes into the top 290px overlay band' }] }) })
    const res = await composeVideoTool({ spec: spec() }, { projectRoot: tmp, deps, pixel: noOcr })
    if (!res.ok) throw new Error(res.error)
    expect(res.rendered).toBe(false)
    expect(res.checks[0]!.rule).toBe('safeZone')
  })
})

describe('composeVideoTool: a render', () => {
  it('encodes with resolved paths, writes spec.json, returns the preview frames and passes', async () => {
    const deps = goodDeps()
    const res = await composeVideoTool({ spec: { ...spec(), audio: { track: 'music.wav', duck: false } } }, { projectRoot: tmp, deps, pixel: noOcr, now: new Date(2026, 10, 14, 9, 30, 0) })
    if (!res.ok) throw new Error(res.error)
    expect(res.rendered).toBe(true)
    expect(res.passed).toBe(true)
    expect(res.outDir).toBe(join(tmp, '.conductor', 'videos', '20261114-093000-My-Video-'))
    expect(res.videoPath).toBe(join(res.outDir, 'My-Video-.mp4'))
    expect(existsSync(res.frame0Path!)).toBe(true)
    expect(res.contactSheetPath).toBe(join(res.outDir, 'contact-0-3s.jpg'))
    expect(res.image).toMatchObject({ mimeType: 'image/jpeg' })
    expect(res.durationSec).toBe(9.4)
    expect(res.timeline.map((s) => [s.fromSec, s.toSec])).toEqual([[0, 3.4], [3.4, 6.4], [6.4, 9.4]])
    expect(res.nextStep).toMatch(/preview_creative_draft\(\{kind: "CLIP"/)
    expect(res.nextStep).toMatch(/no commit step/)
    const saved = JSON.parse(readFileSync(res.specPath, 'utf8'))
    expect(saved.layout).toBe(2)
    expect(saved.segments[0].clip).toBe(join(tmp, 'hook.mp4'))
    const render = vi.mocked(deps.render!)
    const arg = render.mock.calls[0]![0]
    expect(arg).toMatchObject({ musicPath: join(tmp, 'music.wav'), duck: false, outPath: res.videoPath })
    expect(arg.textPngs).toHaveLength(2)
    expect(arg.timeline.totalSec).toBe(9.4)
  })

  it('adds the motion verdict and keeps going when the measurement fails', async () => {
    const stat = await composeVideoTool({ spec: spec() }, { projectRoot: tmp, deps: goodDeps({ motion: async () => 0.01 }), pixel: noOcr })
    if (!stat.ok) throw new Error(stat.error)
    expect(stat.passed).toBe(false)
    expect(stat.checks.find((c) => c.rule === 'firstSecondMotion')?.severity).toBe('error')
    const broken = await composeVideoTool({ spec: spec() }, { projectRoot: tmp, deps: goodDeps({ motion: async () => { throw new Error('boom') } }), pixel: noOcr })
    if (!broken.ok) throw new Error(broken.error)
    expect(broken.checks.find((c) => c.rule === 'firstSecondMotion')).toMatchObject({ severity: 'warning', message: expect.stringContaining('boom') })
  })

  it('folds the safe-zone OCR result into the checks', async () => {
    const frame = { atSeconds: 1, png: Buffer.alloc(0), isCut: false }
    const engine = { recognize: async () => [{ text: 'Hidden title', x: 100, y: 100, w: 500, h: 60 }] }
    const res = await composeVideoTool({ spec: spec() }, { projectRoot: tmp, deps: goodDeps(), pixel: { ocrEngine: engine as never, sampler: async () => [frame] } })
    if (!res.ok) throw new Error(res.error)
    expect(res.checks.some((c) => c.rule === 'safeZoneText' || c.rule === 'safeZoneUnchecked')).toBe(true)
  })

  it('records provenance when every visual input has a sidecar, and says what is missing otherwise', async () => {
    const side = { source: 'Own footage', licence: 'Own work', aiGenerated: false }
    for (const f of ['hook.mp4', 'a.png', 'b.png']) writeFileSync(join(tmp, `${f}.provenance.json`), JSON.stringify({ ...side, aiGenerated: f === 'a.png' }))
    const full = await composeVideoTool({ spec: spec() }, { projectRoot: tmp, deps: goodDeps(), pixel: noOcr })
    if (!full.ok) throw new Error(full.error)
    expect(full.provenance).toEqual({ recorded: true, needed: [], aiGenerated: true })
    const sidecar = JSON.parse(readFileSync(`${full.videoPath}.provenance.json`, 'utf8'))
    expect(sidecar).toMatchObject({ licence: 'Own work', aiGenerated: true, source: expect.stringContaining('Own footage') })
    rmSync(join(tmp, 'b.png.provenance.json'))
    const part = await composeVideoTool({ spec: { ...spec(), id: 'second' } }, { projectRoot: tmp, deps: goodDeps(), pixel: noOcr })
    if (!part.ok) throw new Error(part.error)
    expect(part.provenance.recorded).toBe(false)
    expect(part.provenance.needed).toEqual([join(tmp, 'b.png')])
    expect(existsSync(`${part.videoPath}.provenance.json`)).toBe(false)
    expect(part.nextStep).toMatch(/no \.provenance\.json/)
  })

  it('reports an encoder failure without a result', async () => {
    const res = await composeVideoTool({ spec: spec() }, { projectRoot: tmp, deps: goodDeps({ render: async () => { throw new Error('ffmpeg exited 1') } }), pixel: noOcr })
    expect(res).toMatchObject({ ok: false, error: expect.stringContaining('ffmpeg exited 1') })
  })
})

describe('composePlan', () => {
  it('hands media.mjs the timeline, text windows and inputs', () => {
    const { timeline } = resolveTimeline({ segments: [{ clip: '/c.mp4', startSec: 1, endSec: 4 }, { image: '/i.png', durationSec: 3, zoom: true, transition: 'fade' }] }, [{ durationSec: 5, hasAudio: true }, undefined])
    const { plan, inputs } = composePlan({ timeline: timeline!, texts: [{ fromSec: 0, toSec: 2 }], textPngs: ['/t.png'], voicePath: '/v.wav', outPath: '/o.mp4' })
    expect(plan).toMatchObject({ totalSec: 5.5, texts: [{ fromSec: 0, toSec: 2 }], audio: { duck: true } })
    expect((plan['segments'] as unknown[])[1]).toMatchObject({ kind: 'image', zoom: true, transitionIn: 'fade', useAudio: false })
    expect((plan['segments'] as unknown[])[0]).toMatchObject({ kind: 'clip', startSec: 1, durationSec: 3, useAudio: true })
    expect(inputs).toEqual({ segmentPaths: ['/c.mp4', '/i.png'], textPaths: ['/t.png'], voicePath: '/v.wav', outPath: '/o.mp4' })
  })
})
