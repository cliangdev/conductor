import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { generateCreativeVideo } from '../mcp/tools/creative-videos.js'
import { VideoCostConfirmationRequired } from '../lib/video-common.js'
import type { GenerateVideosResult } from '../lib/video-generation.js'

function box(type: string, ...parts: Buffer[]): Buffer {
  const body = Buffer.concat(parts)
  const h = Buffer.alloc(8)
  h.writeUInt32BE(8 + body.length, 0)
  h.write(type, 4, 'latin1')
  return Buffer.concat([h, body])
}
const u32 = (n: number) => { const b = Buffer.alloc(4); b.writeUInt32BE(n, 0); return b }
const fx = (n: number) => u32(Math.round(n * 65536) >>> 0)
/** A minimal mp4: 4 s, 720x1280, 24 fps, with a sound track. */
function tinyMp4(width: number, height: number): Buffer {
  const mvhd = box('mvhd', u32(0), u32(0), u32(0), u32(1000), u32(4000), Buffer.alloc(80))
  const matrix = Buffer.concat([fx(1), fx(0), u32(0), fx(0), fx(1), u32(0), u32(0), u32(0), u32(0x40000000)])
  const tkhd = box('tkhd', u32(0), u32(0), u32(0), u32(1), u32(0), u32(0), Buffer.alloc(16), matrix, fx(width), fx(height))
  const hdlr = (t: string) => box('hdlr', u32(0), u32(0), Buffer.from(t, 'latin1'), Buffer.alloc(12))
  const mdhd = box('mdhd', u32(0), u32(0), u32(0), u32(24000), u32(0), Buffer.alloc(4))
  const stts = box('stts', u32(0), u32(1), u32(96), u32(1000))
  const video = box('trak', tkhd, box('mdia', mdhd, hdlr('vide'), box('minf', box('stbl', stts))))
  const audio = box('trak', box('mdia', hdlr('soun')))
  return Buffer.concat([box('ftyp', Buffer.from('isom'), u32(0)), box('moov', mvhd, video, audio), box('mdat', Buffer.alloc(8))])
}

let tmp: string
beforeEach(() => { tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'creative-vid-')) })
afterEach(() => fs.rmSync(tmp, { recursive: true, force: true }))

const gen = (width: number, height: number, extra: Partial<GenerateVideosResult> = {}) =>
  vi.fn().mockResolvedValue({
    videos: [tinyMp4(width, height), tinyMp4(width, height)],
    request: { prompt: 'A cat walks', aspect: '9:16', durationSeconds: 4, resolution: '1080p', tier: 'fast', generateAudio: true, count: 2, confirmCost: true },
    route: 'vertex',
    model: 'veo-3.1-fast-generate-001',
    estimateUsd: 1.6,
    perClipUsd: 0.8,
    billedUsd: 1.6,
    spentTodayUsd: 1.6,
    dailyCapUsd: 5,
    warnings: ['from the route'],
    sourceImagePath: '/frames/a.png',
    lastFramePath: '/frames/b.png',
    ...extra,
  } satisfies GenerateVideosResult)

describe('generateCreativeVideo', () => {
  it('writes clips, probes them, writes sidecars and returns the contract', async () => {
    const generate = gen(1080, 1920)
    const logs: string[] = []
    const res = await generateCreativeVideo({ prompt: 'A cat walks' }, {
      outDir: path.join(tmp, 'out'),
      generate,
      log: (m) => logs.push(m),
      contactSheet: async () => ({ data: Buffer.from('jpg'), mimeType: 'image/jpeg' }),
    })
    expect(generate).toHaveBeenCalledWith({ prompt: 'A cat walks' }, expect.objectContaining({ onProgress: expect.any(Function) }))
    expect(res.files.map((f) => path.basename(f.path))).toEqual(['clip-1.mp4', 'clip-2.mp4'])
    expect(res.files[0]).toMatchObject({ width: 1080, height: 1920, durationSeconds: 4, fps: 24, hasAudio: true })
    expect(res).toMatchObject({ estimatedCostUsd: 1.6, spentTodayUsd: 1.6, dailyCapUsd: 5, generator: { route: 'vertex', model: 'veo-3.1-fast-generate-001' } })
    expect(res.warnings).toEqual(['from the route'])
    expect(res.image).toEqual({ data: Buffer.from('jpg'), mimeType: 'image/jpeg' })
    expect(res.nextStep).toMatch(/realism check/)
    expect(res.nextStep).toContain('clipPath')
    expect(res.nextStep).toContain('isAigc')

    const sidecar = JSON.parse(fs.readFileSync(`${res.files[0]!.path}.provenance.json`, 'utf8'))
    expect(sidecar).toMatchObject({
      licence: 'Generated, house use',
      aiGenerated: true,
      prompt: 'A cat walks',
      model: 'veo-3.1-fast-generate-001',
      route: 'vertex',
      tier: 'fast',
      resolution: '1080p',
      generateAudio: true,
      estimatedCostUsd: 0.8,
      sourceImagePath: '/frames/a.png',
      lastFramePath: '/frames/b.png',
      synthId: true,
      durationSeconds: 4,
    })
    expect(sidecar.source).toMatch(/^Generated with veo-3\.1-fast-generate-001 via Vertex AI on \d{4}-\d{2}-\d{2}$/)
    expect(new Date(sidecar.generatedAt).toString()).not.toBe('Invalid Date')
  })

  it('reports what was billed, and the per-clip estimate in the sidecar, when Omni stopped early', async () => {
    const one = vi.fn().mockResolvedValue({
      videos: [tinyMp4(720, 1280)],
      request: { prompt: 'a', aspect: '9:16', durationSeconds: 5, resolution: '720p', tier: 'fast', generateAudio: false, count: 2, confirmCost: true },
      route: 'gemini',
      model: 'gemini-omni-1.1-flash',
      estimateUsd: 1,
      perClipUsd: 0.5,
      billedUsd: 0.5,
      spentTodayUsd: 0.5,
      dailyCapUsd: 5,
      warnings: ['Clip 2 of 2 failed'],
    } satisfies GenerateVideosResult)
    const res = await generateCreativeVideo({ prompt: 'a' }, { outDir: tmp, generate: one, contactSheet: async () => undefined })
    expect(res.estimatedCostUsd).toBe(0.5)
    expect(JSON.parse(fs.readFileSync(`${res.files[0]!.path}.provenance.json`, 'utf8')).estimatedCostUsd).toBe(0.5)
  })

  it('warns about 720p on 1080-wide placements', async () => {
    const res = await generateCreativeVideo({ prompt: 'a' }, { outDir: tmp, generate: gen(720, 1280), contactSheet: async () => undefined })
    expect(res.warnings.join(' ')).toMatch(/1\.5x/)
    expect(res.image).toBeUndefined()
    expect(res.placementFit?.['9x16']?.scale).toBe(1.5)
  })

  it('creates nothing on disk when the gate refuses', async () => {
    const generate = vi.fn().mockRejectedValue(new VideoCostConfirmationRequired({ estimateUsd: 0.6, breakdown: 'b', spentTodayUsd: 0, dailyCapUsd: 5, route: 'vertex', model: 'm' }))
    const out = path.join(tmp, 'never')
    await expect(generateCreativeVideo({ prompt: 'a' }, { outDir: out, generate })).rejects.toMatchObject({ code: 'VIDEO_COST_CONFIRMATION_REQUIRED' })
    expect(fs.existsSync(out)).toBe(false)
  })
})
