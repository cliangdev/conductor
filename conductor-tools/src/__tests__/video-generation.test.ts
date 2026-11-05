import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

const readConfigMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/config.js', () => ({ readConfig: readConfigMock, CONFIG_PATH: '/x/config.json' }))
const vertexMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/vertex-veo.js', () => ({ generateWithVertex: vertexMock }))
const omniMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/gemini-omni.js', async (orig) => ({ ...(await orig<typeof import('../lib/gemini-omni.js')>()), generateOmniClip: omniMock }))

import { generateVideos, pickVideoRoute, videoModel } from '../lib/video-generation.js'
import { VideoError, VideoSafetyFiltered } from '../lib/video-common.js'
import { record, spentToday } from '../lib/video-spend.js'

let tmp: string
let spend: { path: string; now: Date }
const png = () => {
  const p = path.join(tmp, 'frame.png')
  fs.writeFileSync(p, 'png')
  return p
}
const ENV = ['CONDUCTOR_GEMINI_API_KEY', 'GEMINI_API_KEY', 'CONDUCTOR_VIDEO_MODEL']

beforeEach(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'video-gen-'))
  spend = { path: path.join(tmp, 'spend.json'), now: new Date(2026, 10, 4, 12) }
  for (const k of ENV) delete process.env[k]
  readConfigMock.mockReset()
  vertexMock.mockReset().mockResolvedValue({ videos: [Buffer.from('v')], warnings: [] })
  omniMock.mockReset().mockResolvedValue({ video: Buffer.from('o'), warnings: [] })
})
afterEach(() => fs.rmSync(tmp, { recursive: true, force: true }))

const gate = (code: string) => expect.objectContaining({ code })

describe('route selection', () => {
  it('uses vertex when a project is set', () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p', geminiApiKey: 'k' })
    expect(pickVideoRoute()).toMatchObject({ route: 'vertex', project: 'p', region: 'us-central1' })
  })
  it('falls back to gemini when only a key resolves (env too)', () => {
    readConfigMock.mockReturnValue({})
    process.env['GEMINI_API_KEY'] = 'envkey'
    expect(pickVideoRoute()).toMatchObject({ route: 'gemini', apiKey: 'envkey' })
  })
  it('honours config videoRoute, and is not configured when that route lacks its setting', () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p', geminiApiKey: 'k', videoRoute: 'gemini', vertexRegion: 'europe-west4' })
    expect(pickVideoRoute()?.route).toBe('gemini')
    readConfigMock.mockReturnValue({ geminiApiKey: 'k', videoRoute: 'vertex' })
    expect(pickVideoRoute()).toBeNull()
  })
  it('is null with nothing set', () => {
    readConfigMock.mockReturnValue(null)
    expect(pickVideoRoute()).toBeNull()
  })
  it('maps tier to the Veo model id and lets the env override it', () => {
    expect(videoModel('vertex', 'lite')).toBe('veo-3.1-lite-generate-001')
    expect(videoModel('vertex', 'standard')).toBe('veo-3.1-generate-001')
    expect(videoModel('gemini', 'fast')).toBe('gemini-omni-1.1-flash')
    process.env['CONDUCTOR_VIDEO_MODEL'] = 'custom'
    expect(videoModel('vertex', 'fast')).toBe('custom')
  })
})

describe('gate order', () => {
  it('1. not configured comes first, with both setup paths, even for bad input', async () => {
    readConfigMock.mockReturnValue(null)
    const err = await generateVideos({ prompt: '' }, { spend }).catch((e) => e)
    expect(err).toEqual(gate('VIDEO_ROUTE_NOT_CONFIGURED'))
    expect(err.message).toContain('conductor config set-vertex-project <id>')
    expect(err.message).toContain('gcloud auth login')
    expect(err.message).toContain('conductor config set-gemini-api-key <key>')
  })

  it('2. validation runs before the caps', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p', videoPerCallCapUsd: 0.01 })
    await expect(generateVideos({ prompt: 'a', durationSeconds: 5 }, { spend })).rejects.toThrow(/4, 6 or 8/)
    await expect(generateVideos({ prompt: 'a', imagePath: path.join(tmp, 'nope.png') }, { spend })).rejects.toThrow(/imagePath not found/)
    fs.writeFileSync(path.join(tmp, 'x.gif'), 'g')
    await expect(generateVideos({ prompt: 'a', imagePath: path.join(tmp, 'x.gif') }, { spend })).rejects.toThrow(/JPEG or PNG/)
    expect(vertexMock).not.toHaveBeenCalled()
  })

  it('3. per-call cap, before the daily cap and confirmation', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p', videoPerCallCapUsd: 0.5, videoDailyCapUsd: 0.1 })
    await expect(generateVideos({ prompt: 'a' }, { spend })).rejects.toEqual(gate('VIDEO_COST_CAP_EXCEEDED')) // 4 s fast = $0.60
  })

  it('4. daily cap counts what is already spent today', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p' })
    record(4.7, spend)
    await expect(generateVideos({ prompt: 'a', confirmCost: true }, { spend })).rejects.toEqual(gate('VIDEO_DAILY_CAP_EXCEEDED'))
    expect(vertexMock).not.toHaveBeenCalled()
  })

  it('5. asks for confirmation with the estimate details, and spends nothing', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p' })
    record(1, spend)
    const err = await generateVideos({ prompt: 'a' }, { spend }).catch((e) => e)
    expect(err.code).toBe('VIDEO_COST_CONFIRMATION_REQUIRED')
    expect(err.details).toMatchObject({ estimateUsd: 0.6, spentTodayUsd: 1, dailyCapUsd: 5, route: 'vertex', model: 'veo-3.1-fast-generate-001' })
    expect(err.details.breakdown).toContain('$0.60')
    expect(vertexMock).not.toHaveBeenCalled()
    expect(spentToday(spend)).toBe(1)
  })

  it('config rate overrides change the estimate', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p', videoRates: { fast: 0.1 } })
    const err = await generateVideos({ prompt: 'a' }, { spend }).catch((e) => e)
    expect(err.details.estimateUsd).toBe(0.4)
  })
})

describe('running', () => {
  it('calls Vertex once with the loaded frames, records spend and reports totals', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p', videoDailyCapUsd: 10 })
    const res = await generateVideos({ prompt: 'a', imagePath: png(), count: 2, confirmCost: true, tier: 'lite' }, { spend })
    expect(vertexMock).toHaveBeenCalledTimes(1)
    const [req, frames, cfg] = vertexMock.mock.calls[0]!
    expect(req).toMatchObject({ count: 2, tier: 'lite' })
    expect(frames.image).toEqual({ base64: Buffer.from('png').toString('base64'), mimeType: 'image/png' })
    expect(frames.lastFrame).toBeUndefined()
    expect(cfg).toMatchObject({ project: 'p', region: 'us-central1', model: 'veo-3.1-lite-generate-001' })
    expect(res).toMatchObject({ route: 'vertex', estimateUsd: 0.4, spentTodayUsd: 0.4, dailyCapUsd: 10 })
    expect(spentToday(spend)).toBe(0.4)
  })

  it('makes one Omni call per clip and records each', async () => {
    readConfigMock.mockReturnValue({ geminiApiKey: 'k' })
    const res = await generateVideos({ prompt: 'a', count: 2, durationSeconds: 5, resolution: '720p', confirmCost: true }, { spend })
    expect(omniMock).toHaveBeenCalledTimes(2)
    expect(res.videos).toHaveLength(2)
    expect(res.estimateUsd).toBe(1)
    expect(spentToday(spend)).toBe(1)
  })

  it('does not record spend on a plain failure, but does when the result was safety filtered', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p' })
    vertexMock.mockRejectedValueOnce(new Error('network'))
    await expect(generateVideos({ prompt: 'a', confirmCost: true }, { spend })).rejects.toThrow('network')
    expect(spentToday(spend)).toBe(0)
    vertexMock.mockRejectedValueOnce(new VideoSafetyFiltered('x'))
    await expect(generateVideos({ prompt: 'a', confirmCost: true }, { spend })).rejects.toMatchObject({ code: 'VIDEO_SAFETY_FILTERED' })
    expect(spentToday(spend)).toBe(0.6)
  })

  it('keeps a finished Omni clip when the next one fails', async () => {
    readConfigMock.mockReturnValue({ geminiApiKey: 'k' })
    omniMock.mockResolvedValueOnce({ video: Buffer.from('o'), warnings: [] }).mockRejectedValueOnce(new Error('network'))
    const res = await generateVideos({ prompt: 'a', count: 2, durationSeconds: 5, resolution: '720p', confirmCost: true }, { spend })
    expect(res.videos).toHaveLength(1)
    expect(res.warnings.join(' ')).toContain('Clip 2 of 2 failed')
    expect(spentToday(spend)).toBe(0.5)
  })

  it('records spend on a timeout, since the job may still be billed', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p' })
    vertexMock.mockRejectedValueOnce(new VideoError('VIDEO_TIMEOUT', 'slow'))
    await expect(generateVideos({ prompt: 'a', confirmCost: true }, { spend })).rejects.toMatchObject({ code: 'VIDEO_TIMEOUT' })
    expect(spentToday(spend)).toBe(0.6)
  })

  it('the daily cap resets on a new local date', async () => {
    readConfigMock.mockReturnValue({ vertexProject: 'p' })
    record(4.9, spend)
    await expect(generateVideos({ prompt: 'a', confirmCost: true }, { spend })).rejects.toEqual(gate('VIDEO_DAILY_CAP_EXCEEDED'))
    const tomorrow = { ...spend, now: new Date(2026, 10, 5, 8) }
    await expect(generateVideos({ prompt: 'a', confirmCost: true }, { spend: tomorrow })).resolves.toMatchObject({ spentTodayUsd: 0.6 })
  })
})
