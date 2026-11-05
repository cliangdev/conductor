import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const execFileMock = vi.hoisted(() => vi.fn())
vi.mock('node:child_process', async (orig) => ({ ...(await orig<typeof import('node:child_process')>()), execFile: execFileMock }))

import { buildVertexBody, generateWithVertex, getVertexToken, resetVertexTokenCache } from '../lib/vertex-veo.js'
import type { VideoRequest } from '../lib/video-common.js'

const req: VideoRequest = { prompt: 'a cat walks', aspect: '9:16', durationSeconds: 4, resolution: '1080p', tier: 'fast', generateAudio: false, count: 1, confirmCost: true }
const cfg = { project: 'proj-1', region: 'us-central1', model: 'veo-3.1-fast-generate-001' }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

/** A fake clock that the injected sleep advances. */
function clock() {
  let t = 0
  return { now: () => t, sleep: vi.fn(async (ms: number) => { t += ms }) }
}

describe('buildVertexBody', () => {
  it('always sends generateAudio, even when false', () => {
    const body = buildVertexBody(req, {}) as { parameters: Record<string, unknown>; instances: Array<Record<string, unknown>> }
    expect(body.parameters).toEqual({ aspectRatio: '9:16', durationSeconds: 4, sampleCount: 1, generateAudio: false, resolution: '1080p', personGeneration: 'allow_adult' })
    expect(body.instances).toEqual([{ prompt: 'a cat walks' }])
  })
  it('adds frames, seed and negativePrompt when given', () => {
    const body = buildVertexBody(
      { ...req, generateAudio: true, seed: 7, negativePrompt: 'text', count: 2 },
      { image: { base64: 'AAA', mimeType: 'image/png' }, lastFrame: { base64: 'BBB', mimeType: 'image/jpeg' } }
    ) as { parameters: Record<string, unknown>; instances: Array<Record<string, unknown>> }
    expect(body.instances[0]).toEqual({
      prompt: 'a cat walks',
      image: { bytesBase64Encoded: 'AAA', mimeType: 'image/png' },
      lastFrame: { bytesBase64Encoded: 'BBB', mimeType: 'image/jpeg' },
    })
    expect(body.parameters).toMatchObject({ generateAudio: true, seed: 7, negativePrompt: 'text', sampleCount: 2 })
  })
})

describe('generateWithVertex', () => {
  beforeEach(() => resetVertexTokenCache())

  it('starts the operation, polls until done and decodes base64 videos', async () => {
    const c = clock()
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ name: 'op-1' }))
      .mockResolvedValueOnce(json({ done: false }))
      .mockResolvedValueOnce(json({ done: false }))
      .mockResolvedValueOnce(json({ done: true, response: { videos: [{ bytesBase64Encoded: Buffer.from('mp4bytes').toString('base64') }] } }))
    const progress = vi.fn()
    const out = await generateWithVertex(req, {}, { ...cfg, onProgress: progress }, { fetch: fetchMock as unknown as typeof fetch, getToken: async () => 'tok', ...c })
    expect(out.videos[0]!.toString()).toBe('mp4bytes')
    const [startUrl, startInit] = fetchMock.mock.calls[0]!
    expect(startUrl).toBe('https://us-central1-aiplatform.googleapis.com/v1/projects/proj-1/locations/us-central1/publishers/google/models/veo-3.1-fast-generate-001:predictLongRunning')
    expect((startInit as RequestInit).headers).toMatchObject({ Authorization: 'Bearer tok' })
    const [pollUrl, pollInit] = fetchMock.mock.calls[1]!
    expect(String(pollUrl)).toMatch(/:fetchPredictOperation$/)
    expect(JSON.parse((pollInit as RequestInit).body as string)).toEqual({ operationName: 'op-1' })
    expect(c.sleep).toHaveBeenCalledTimes(2)
    expect(c.sleep).toHaveBeenCalledWith(10_000)
    expect(progress.mock.calls.length).toBeGreaterThanOrEqual(3)
  })

  it('times out after ten minutes of polling', async () => {
    const c = clock()
    const fetchMock = vi.fn().mockImplementation(async (url: string) => (String(url).endsWith('predictLongRunning') ? json({ name: 'op' }) : json({ done: false })))
    await expect(generateWithVertex(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, getToken: async () => 't', ...c })).rejects.toMatchObject({ code: 'VIDEO_TIMEOUT' })
    expect(c.sleep.mock.calls.length).toBe(60)
  })

  it('downloads a gs:// video with the same token', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ name: 'op' }))
      .mockResolvedValueOnce(json({ done: true, response: { videos: [{ gcsUri: 'gs://my-bucket/out dir/clip.mp4' }] } }))
      .mockResolvedValueOnce(new Response(Buffer.from('gcsbytes')))
    const out = await generateWithVertex(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, getToken: async () => 'secret-token', ...clock() })
    expect(out.videos[0]!.toString()).toBe('gcsbytes')
    const [url, init] = fetchMock.mock.calls[2]!
    expect(url).toBe('https://storage.googleapis.com/download/storage/v1/b/my-bucket/o/out%20dir%2Fclip.mp4?alt=media')
    expect((init as RequestInit).headers).toEqual({ Authorization: 'Bearer secret-token' })
  })

  it('reports an empty result as safety filtered, with reasons', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ name: 'op' }))
      .mockResolvedValueOnce(json({ done: true, response: { videos: [], raiMediaFilteredCount: 1, raiMediaFilteredReasons: ['Support code 58061214'] } }))
    const err = await generateWithVertex(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, getToken: async () => 't', ...clock() }).catch((e) => e)
    expect(err.code).toBe('VIDEO_SAFETY_FILTERED')
    expect(err.message).toContain('Support code 58061214')
    expect(err.message).toMatch(/bills/)
  })

  it('treats no videos and no counters as filtered too', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ name: 'op' })).mockResolvedValueOnce(json({ done: true, response: {} }))
    await expect(generateWithVertex(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, getToken: async () => 't', ...clock() })).rejects.toMatchObject({ code: 'VIDEO_SAFETY_FILTERED' })
  })

  it('keeps the clips that came back when only some were filtered, and warns', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json({ name: 'op' }))
      .mockResolvedValueOnce(json({ done: true, response: { videos: [{ bytesBase64Encoded: 'QQ==' }], raiMediaFilteredCount: 1 } }))
    const out = await generateWithVertex({ ...req, count: 2 }, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, getToken: async () => 't', ...clock() })
    expect(out.videos).toHaveLength(1)
    expect(out.warnings[0]).toMatch(/blocked by the safety filter/)
  })

  it.each([
    [404, 'VIDEO_MODEL_NOT_FOUND', /Valid ids/],
    [429, 'VIDEO_QUOTA', /quota/],
    [401, 'VIDEO_AUTH', /gcloud auth login/],
    [403, 'VIDEO_AUTH', /set-vertex-project/],
    [500, 'VIDEO_FAILED', /500/],
  ])('maps HTTP %i', async (status, code, text) => {
    const fetchMock = vi.fn().mockResolvedValueOnce(json({ error: { message: 'boom' } }, status))
    const err = await generateWithVertex(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, getToken: async () => 't', ...clock() }).catch((e) => e)
    expect(err.code).toBe(code)
    expect(err.message).toMatch(text)
  })

  it('maps an operation error', async () => {
    const mk = (error: object) => vi.fn().mockResolvedValueOnce(json({ name: 'op' })).mockResolvedValueOnce(json({ done: true, error }))
    const run = (error: object) => generateWithVertex(req, {}, cfg, { fetch: mk(error) as unknown as typeof fetch, getToken: async () => 't', ...clock() }).catch((e) => e)
    expect((await run({ code: 8, message: 'Quota exceeded', status: 'RESOURCE_EXHAUSTED' })).code).toBe('VIDEO_QUOTA')
    expect((await run({ code: 7, message: 'denied' })).code).toBe('VIDEO_AUTH')
    expect((await run({ code: 3, message: 'The prompt was blocked by Responsible AI' })).code).toBe('VIDEO_SAFETY_FILTERED')
    expect((await run({ code: 13, message: 'internal' })).code).toBe('VIDEO_FAILED')
  })
})

describe('getVertexToken', () => {
  const saved = process.env['CONDUCTOR_VERTEX_ACCESS_TOKEN']
  beforeEach(() => {
    resetVertexTokenCache()
    execFileMock.mockReset()
    delete process.env['CONDUCTOR_VERTEX_ACCESS_TOKEN']
  })
  afterEach(() => {
    if (saved === undefined) delete process.env['CONDUCTOR_VERTEX_ACCESS_TOKEN']
    else process.env['CONDUCTOR_VERTEX_ACCESS_TOKEN'] = saved
  })

  it('prefers the env token', async () => {
    process.env['CONDUCTOR_VERTEX_ACCESS_TOKEN'] = 'env-token'
    expect(await getVertexToken()).toBe('env-token')
    expect(execFileMock).not.toHaveBeenCalled()
  })

  it('asks gcloud and caches for about 50 minutes', async () => {
    execFileMock.mockImplementation((_cmd: string, _args: string[], _opts: unknown, cb: (e: Error | null, out: string) => void) => cb(null, 'gcloud-token\n'))
    let t = 1_000
    expect(await getVertexToken(() => t)).toBe('gcloud-token')
    expect(execFileMock.mock.calls[0]!.slice(0, 2)).toEqual(['gcloud', ['auth', 'print-access-token']])
    t += 49 * 60_000
    await getVertexToken(() => t)
    expect(execFileMock).toHaveBeenCalledTimes(1)
    t += 2 * 60_000
    await getVertexToken(() => t)
    expect(execFileMock).toHaveBeenCalledTimes(2)
  })

  it('explains how to log in when gcloud fails', async () => {
    execFileMock.mockImplementation((_c: string, _a: string[], _o: unknown, cb: (e: Error | null, out?: string) => void) => cb(new Error('ENOENT')))
    await expect(getVertexToken()).rejects.toMatchObject({ code: 'VIDEO_AUTH', message: expect.stringContaining('gcloud auth login') })
  })
})
