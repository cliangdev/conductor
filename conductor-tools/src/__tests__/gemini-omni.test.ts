import { describe, it, expect, vi } from 'vitest'
import { buildOmniBody, buildOmniPrompt, generateOmniClip } from '../lib/gemini-omni.js'
import type { VideoRequest } from '../lib/video-common.js'

const req: VideoRequest = { prompt: 'a cat walks', aspect: '9:16', durationSeconds: 6, resolution: '720p', tier: 'fast', generateAudio: true, count: 1, confirmCost: true }
const img = { base64: 'IMG1', mimeType: 'image/png' as const }
const img2 = { base64: 'IMG2', mimeType: 'image/jpeg' as const }
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })
const cfg = { apiKey: 'AIza-secret', model: 'gemini-omni-1.1-flash' }
const modelOutput = (content: unknown[]) => ({ steps: [{ type: 'user_input' }, { type: 'model_output', content }] })

describe('Omni request', () => {
  it('puts the duration in the prompt and folds the negative prompt', () => {
    expect(buildOmniPrompt(req)).toBe('a cat walks\nThe clip is 6 seconds long.')
    expect(buildOmniPrompt({ ...req, negativePrompt: 'text, logos' })).toBe('a cat walks\nThe clip is 6 seconds long.\nAvoid: text, logos')
  })

  it('names the opening frame when only a first frame is sent', () => {
    expect(buildOmniPrompt(req, { image: true })).toBe('a cat walks\nThe clip is 6 seconds long.\nThe image is the opening frame: start exactly on it.')
  })
  it('sends first and last frame as images before the text', () => {
    const body = buildOmniBody(req, cfg.model, { image: img, lastFrame: img2 })
    expect(body).toEqual({
      model: 'gemini-omni-1.1-flash',
      input: [
        { type: 'image', data: 'IMG1', mime_type: 'image/png' },
        { type: 'image', data: 'IMG2', mime_type: 'image/jpeg' },
        { type: 'text', text: 'a cat walks\nThe clip is 6 seconds long.\nThe first image is the opening frame: start exactly on it. The second image is the closing frame: end exactly on it.' },
      ],
      response_format: { type: 'video', aspect_ratio: '9:16', resolution: '720p' },
      store: false,
    })
  })
})

describe('generateOmniClip', () => {
  it('posts with the key header and decodes inline data', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(modelOutput([{ type: 'text', text: 'ok' }, { type: 'video', data: Buffer.from('vid').toString('base64') }])))
    const out = await generateOmniClip(req, { image: img }, cfg, { fetch: fetchMock as unknown as typeof fetch })
    expect(out.video.toString()).toBe('vid')
    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('https://generativelanguage.googleapis.com/v1beta/interactions')
    expect((init as RequestInit).headers).toMatchObject({ 'x-goog-api-key': 'AIza-secret' })
    expect(String((init as RequestInit).body)).not.toContain('AIza-secret')
  })

  it('downloads a files/ uri with the key header', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(modelOutput([{ type: 'video', uri: 'files/abc123' }])))
      .mockResolvedValueOnce(new Response(Buffer.from('big')))
    const out = await generateOmniClip(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch })
    expect(out.video.toString()).toBe('big')
    const [url, init] = fetchMock.mock.calls[1]!
    expect(url).toBe('https://generativelanguage.googleapis.com/v1beta/files/abc123:download?alt=media')
    expect((init as RequestInit).headers).toEqual({ 'x-goog-api-key': 'AIza-secret' })
  })

  it('marks a failed download as billed, as a VideoError', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(json(modelOutput([{ type: 'video', uri: 'files/abc123' }])))
      .mockRejectedValueOnce(new TypeError('fetch failed: ECONNRESET'))
    const err = await generateOmniClip(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch }).catch((e) => e)
    expect(err).toMatchObject({ name: 'VideoError', code: 'VIDEO_FAILED', details: { billed: true } })
    expect(err.message).toContain('ECONNRESET')
    expect(err.message).not.toContain('AIza-secret')
    const err2 = await generateOmniClip(req, {}, cfg, {
      fetch: vi.fn().mockResolvedValueOnce(json(modelOutput([{ type: 'video', uri: 'files/x' }]))).mockResolvedValueOnce(json({ error: { message: 'gone' } }, 500)) as unknown as typeof fetch,
    }).catch((e) => e)
    expect(err2).toMatchObject({ code: 'VIDEO_FAILED', details: { billed: true } })
  })

  it('also reads a top-level outputs[] list', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json({ outputs: [{ type: 'video', data: Buffer.from('o').toString('base64') }] }))
    expect((await generateOmniClip(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch })).video.toString()).toBe('o')
  })

  it('reports a response with no video as blocked', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(modelOutput([{ type: 'text', text: 'I cannot' }])))
    await expect(generateOmniClip(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch })).rejects.toMatchObject({ code: 'VIDEO_SAFETY_FILTERED' })
  })

  it('maps HTTP errors without leaking the key', async () => {
    for (const [status, code] of [[404, 'VIDEO_MODEL_NOT_FOUND'], [429, 'VIDEO_QUOTA'], [403, 'VIDEO_AUTH']] as const) {
      const fetchMock = vi.fn().mockResolvedValue(json({ error: { message: 'bad' } }, status))
      const err = await generateOmniClip(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch }).catch((e) => e)
      expect(err.code).toBe(code)
      expect(err.message).not.toContain('AIza-secret')
    }
    const auth = await generateOmniClip(req, {}, cfg, { fetch: vi.fn().mockResolvedValue(json({}, 401)) as unknown as typeof fetch }).catch((e) => e)
    expect(auth.message).toContain('set-gemini-api-key')
  })

  it('keeps audio when asked', async () => {
    const strip = vi.fn()
    const fetchMock = vi.fn().mockResolvedValue(json(modelOutput([{ type: 'video', data: 'dmlk' }])))
    const out = await generateOmniClip(req, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, stripAudio: strip })
    expect(strip).not.toHaveBeenCalled()
    expect(out.warnings).toEqual([])
  })

  it('strips audio with ffmpeg when audio was not asked for', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(modelOutput([{ type: 'video', data: 'dmlk' }])))
    const out = await generateOmniClip({ ...req, generateAudio: false }, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, stripAudio: async () => Buffer.from('silent') })
    expect(out.video.toString()).toBe('silent')
    expect(out.warnings).toEqual([])
  })

  it('keeps the sound and warns when ffmpeg cannot strip it', async () => {
    const fetchMock = vi.fn().mockResolvedValue(json(modelOutput([{ type: 'video', data: Buffer.from('loud').toString('base64') }])))
    const out = await generateOmniClip({ ...req, generateAudio: false }, {}, cfg, { fetch: fetchMock as unknown as typeof fetch, stripAudio: async () => null })
    expect(out.video.toString()).toBe('loud')
    expect(out.warnings[0]).toMatch(/keeps its sound/)
  })
})
