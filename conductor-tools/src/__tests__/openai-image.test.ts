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
    expect(body).toMatchObject({ model: 'gpt-image-2.5-sunburst', size: '2304x1536', quality: 'high', n: 1 })
    expect(body.prompt).toContain('a kitchen')
    expect(body.prompt).toMatch(/NO text/)
    expect(body.prompt).toContain('unretouched photo')
    expect(body.prompt).toContain('avoid the AI look')
    expect(body.prompt).toContain('real-world imperfections')
    expect(progress[0]).toBe('Calling OpenAI gpt-image-2.5-sunburst (high)')

    expect(res.files).toEqual([{ path: path.join(out, 'image-1.png'), width: 1, height: 1 }])
    expect(res.revisedPrompt).toBe('rp')
    expect(res.provenance.aiGenerated).toBe(true)
    expect(res.provenance.source).toMatch(/^Generated with OpenAI gpt-image-2.5-sunburst \(API\) on \d{4}-\d{2}-\d{2}$/)
    const sidecar = JSON.parse(fs.readFileSync(`${res.files[0]!.path}.provenance.json`, 'utf8'))
    expect(sidecar).toMatchObject({ source: res.provenance.source, aiGenerated: true, prompt: 'a kitchen', revisedPrompt: 'rp' })
  })

  it('sends params.quality over the config default and reports model and quality', async () => {
    fetchMock.mockResolvedValue(ok([{ b64_json: PNG_B64 }]))
    const res = await generateImagesWithOpenAi({ prompt: 'p', quality: 'low', outDir: path.join(tmp, 'q') }, cfg)
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body as string).quality).toBe('low')
    expect(res).toMatchObject({ quality: 'low', model: 'gpt-image-2.5-sunburst' })
    const sidecar = JSON.parse(fs.readFileSync(`${res.files[0]!.path}.provenance.json`, 'utf8'))
    expect(sidecar).toMatchObject({ model: 'gpt-image-2.5-sunburst', quality: 'low' })
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
    expect(form.get('size')).toBe('1440x2560')
    expect(form.get('quality')).toBe('high')
    expect(form.get('n')).toBe('1')
    expect(String(form.get('prompt'))).toContain('Use the attached images as references')
    const file = form.getAll('image[]')[0] as File
    expect(file.name).toBe('ref.jpg')
    expect(file.type).toBe('image/jpeg')
    expect(res.revisedPrompt).toBe('')
  })

  it('requests 1440x2560 by default and reports the size', async () => {
    fetchMock.mockResolvedValue(ok([{ b64_json: PNG_B64 }]))
    const res = await generateImagesWithOpenAi({ prompt: 'p', outDir: path.join(tmp, 'd') }, cfg)
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body as string).size).toBe('1440x2560')
    expect(res.size).toBe('1440x2560')
    expect(res.sizeNote).toBeUndefined()
  })

  it.each([
    ['9:16', '1440x2560'], ['4:5', '1664x2080'], ['1:1', '1920x1920'], ['2:3', '1536x2304'], ['3:2', '2304x1536'], ['16:9', '2560x1440'],
    ['portrait', '1536x2304'], ['square', '1920x1920'], ['landscape', '2304x1536'],
  ] as const)('maps aspect %s to %s', async (aspect, size) => {
    fetchMock.mockResolvedValue(ok([{ b64_json: PNG_B64 }]))
    await generateImagesWithOpenAi({ prompt: 'p', aspect, outDir: path.join(tmp, 'a') }, cfg)
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body as string).size).toBe(size)
  })

  it('sends an explicit size and flags an experimental one', async () => {
    fetchMock.mockImplementation(async () => ok([{ b64_json: PNG_B64 }]))
    const res = await generateImagesWithOpenAi({ prompt: 'p', size: '2160x3840', outDir: path.join(tmp, 'e') }, cfg)
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body as string).size).toBe('2160x3840')
    expect(res.sizeNote).toMatch(/experimental/)
    const plain = await generateImagesWithOpenAi({ prompt: 'p', size: '1280x1920', outDir: path.join(tmp, 'f') }, cfg)
    expect(plain.sizeNote).toBeUndefined()
  })

  it('falls back to a standard size with a note on a model without custom sizes', async () => {
    fetchMock.mockImplementation(async () => ok([{ b64_json: PNG_B64 }]))
    const res = await generateImagesWithOpenAi({ prompt: 'p', size: '1440x2560', outDir: path.join(tmp, 'g') }, { ...cfg, model: 'gpt-image-1' })
    expect(JSON.parse(fetchMock.mock.calls[0]![1].body as string).size).toBe('1024x1536')
    expect(res.size).toBe('1024x1536')
    expect(res.sizeNote).toMatch(/gpt-image-1.*fell back to 1024x1536/)
    await generateImagesWithOpenAi({ prompt: 'p', aspect: '16:9', outDir: path.join(tmp, 'h') }, { ...cfg, model: 'gpt-image-1' })
    expect(JSON.parse(fetchMock.mock.calls[1]![1].body as string).size).toBe('1536x1024')
  })

  it.each([
    ['1000x1600', /multiples of 16/],
    ['4000x1600', /over 3840/],
    ['3840x1024', /1:3 and 3:1/],
    ['512x512', /total pixels/],
    ['wide', /WIDTHxHEIGHT/],
  ])('rejects size %s', async (size, msg) => {
    await expect(generateImagesWithOpenAi({ prompt: 'p', size, outDir: path.join(tmp, 'x') }, cfg)).rejects.toThrow(msg)
    expect(fetchMock).not.toHaveBeenCalled()
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
