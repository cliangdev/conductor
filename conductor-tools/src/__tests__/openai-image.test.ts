import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { generateImagesWithOpenAi } from '../lib/openai-image.js'

const PNG_B64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg=='
const cfg = { apiKey: 'sk-test', model: 'gpt-image-2.5-sunburst', quality: 'high', keySource: 'OPENAI_API_KEY' }

const ok = (data: unknown[]) => new Response(JSON.stringify({ data }), { status: 200 })
const fail = (status: number, error: Record<string, string>) => new Response(JSON.stringify({ error }), { status })

let tmp: string
const fetchMock = vi.fn()

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'openai-img-'))
})
afterEach(() => {
  vi.unstubAllGlobals()
  fs.rmSync(tmp, { recursive: true, force: true })
})

describe('generateImagesWithOpenAi', () => {
  it('posts a generations request and writes the image with its sidecar', async () => {
    fetchMock.mockResolvedValue(ok([{ b64_json: PNG_B64, revised_prompt: 'rp' }]))
    const out = path.join(tmp, 'out')
    const progress: string[] = []
    const res = await generateImagesWithOpenAi({ prompt: 'a kitchen', aspect: 'landscape', count: 1, outDir: out, onProgress: (m) => progress.push(m) }, cfg)

    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('https://api.openai.com/v1/images/generations')
    expect(init.headers.Authorization).toBe('Bearer sk-test')
    const body = JSON.parse(init.body as string)
    expect(body).toMatchObject({ model: 'gpt-image-2.5-sunburst', size: '1536x1024', quality: 'high', n: 1 })
    expect(body.prompt).toContain('a kitchen')
    expect(body.prompt).toMatch(/NO text/)
    expect(progress[0]).toBe('Calling OpenAI gpt-image-2.5-sunburst (high)')

    expect(res.files).toEqual([{ path: path.join(out, 'image-1.png'), width: 1, height: 1 }])
    expect(res.revisedPrompt).toBe('rp')
    expect(res.provenance.aiGenerated).toBe(true)
    expect(res.provenance.source).toMatch(/^Generated with OpenAI gpt-image-2.5-sunburst \(API\) on \d{4}-\d{2}-\d{2}$/)
    const sidecar = JSON.parse(fs.readFileSync(`${res.files[0]!.path}.provenance.json`, 'utf8'))
    expect(sidecar).toMatchObject({ source: res.provenance.source, aiGenerated: true, prompt: 'a kitchen', revisedPrompt: 'rp' })
  })

  it('sends references to the edits endpoint as multipart image[]', async () => {
    fetchMock.mockResolvedValue(ok([{ b64_json: PNG_B64 }]))
    const ref = path.join(tmp, 'ref.jpg')
    fs.writeFileSync(ref, 'jpgdata')
    const res = await generateImagesWithOpenAi({ prompt: 'p', referenceImagePaths: [ref], outDir: path.join(tmp, 'o') }, cfg)

    const [url, init] = fetchMock.mock.calls[0]!
    expect(url).toBe('https://api.openai.com/v1/images/edits')
    expect(init.headers['Content-Type']).toBeUndefined()
    const form = init.body as FormData
    expect(form.get('model')).toBe('gpt-image-2.5-sunburst')
    expect(form.get('size')).toBe('1024x1536')
    expect(form.get('quality')).toBe('high')
    expect(form.get('n')).toBe('1')
    expect(String(form.get('prompt'))).toContain('Use the attached images as references')
    const file = form.getAll('image[]')[0] as File
    expect(file.name).toBe('ref.jpg')
    expect(file.type).toBe('image/jpeg')
    expect(res.revisedPrompt).toBe('')
  })

  it('explains a rejected key', async () => {
    fetchMock.mockResolvedValue(fail(401, { message: 'Incorrect API key', code: 'invalid_api_key' }))
    await expect(generateImagesWithOpenAi({ prompt: 'p', outDir: tmp }, cfg)).rejects.toThrow(/key was rejected \(from OPENAI_API_KEY\).*set-openai-api-key/)
  })

  it('explains quota and rate limits with the OpenAI message', async () => {
    fetchMock.mockResolvedValue(fail(429, { message: 'You exceeded your current quota', code: 'insufficient_quota' }))
    await expect(generateImagesWithOpenAi({ prompt: 'p', outDir: tmp }, cfg)).rejects.toThrow(/quota.*You exceeded your current quota/)
    fetchMock.mockResolvedValue(fail(400, { message: 'billing hard limit reached', code: 'billing_hard_limit_reached' }))
    await expect(generateImagesWithOpenAi({ prompt: 'p', outDir: tmp }, cfg)).rejects.toThrow(/billing hard limit reached/)
  })

  it('surfaces other failures such as moderation', async () => {
    fetchMock.mockResolvedValue(fail(400, { message: 'Your request was rejected by the safety system', code: 'moderation_blocked' }))
    await expect(generateImagesWithOpenAi({ prompt: 'p', outDir: tmp }, cfg)).rejects.toThrow(
      'OpenAI image generation failed (400): Your request was rejected by the safety system'
    )
  })

  it('reports a timeout', async () => {
    fetchMock.mockImplementation((_url: string, init: RequestInit) =>
      new Promise((_resolve, reject) => {
        init.signal!.addEventListener('abort', () => reject(init.signal!.reason))
      })
    )
    await expect(generateImagesWithOpenAi({ prompt: 'p', outDir: tmp, timeoutMs: 20 }, cfg)).rejects.toThrow(/timed out after/)
  })

  it('fails when the response holds no valid image', async () => {
    fetchMock.mockResolvedValue(ok([{ b64_json: Buffer.from('nope').toString('base64') }]))
    await expect(generateImagesWithOpenAi({ prompt: 'p', outDir: tmp }, cfg)).rejects.toThrow(/no usable image/)
  })
})
