import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({ apiGet: vi.fn(), apiPost: vi.fn(), apiPatch: vi.fn(), putBytes: vi.fn(), ApiError: class ApiError extends Error {} }))
vi.mock('../lib/creative-render.js', () => ({ renderCreative: vi.fn(), renderDraft: vi.fn(), renderViaApi: vi.fn() }))

import { apiGet, apiPost, putBytes } from '../mcp/api.js'
import { renderSlides, uploadRevisionSlide } from '../mcp/tools/slideshow-render.js'

const config: Config = { apiKey: 'run-key', projectId: 'proj-1', projectName: '', email: '', apiUrl: 'https://api.test' }
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const ID = (n: number) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
const REV = '11111111-1111-1111-1111-111111111111'
const CREATIVE = '22222222-2222-2222-2222-222222222222'
const base = `/api/v2/projects/proj-1/marketing/creatives/${CREATIVE}`

/** Three slides: 1 and 3 use background media A and B, 2 is text only. Slides 1 and 3 changed. */
function detail(over: Record<string, unknown> = {}) {
  return {
    id: REV,
    revisionNo: 4,
    status: 'PENDING',
    displayId: '12a',
    changedSlides: [1, 3],
    spec: {
      id: 'ss',
      slides: [
        { bg: `media:${ID(1)}`, texts: [{ t: 'New hook', y: 30 }] },
        { type: 'messages', bubbles: [{ from: 'me', t: 'hi' }] },
        { bg: `media:${ID(2)}`, texts: [{ t: 'New end', y: 30 }] },
      ],
    },
    slides: { '9x16': [ID(11), ID(12), ID(13)], '4x5': [ID(21), ID(22), ID(23)] },
    renderLists: [
      { list: '9x16', ratio: '9x16' },
      { list: '4x5', ratio: '4x5' },
    ],
    slideStyle: { boxBg: '#ffffff', accent: '#ff2d55' },
    // The revision detail carries every Media the job reads: a run-scoped key may not call the photo endpoints.
    media: Object.values(media),
    ...over,
  }
}

const media: Record<string, Record<string, unknown>> = {
  [ID(1)]: { id: ID(1), url: 'https://cdn/a' },
  [ID(2)]: { id: ID(2), url: 'https://cdn/b' },
  [ID(11)]: { id: ID(11), width: 1080, height: 1920, source: 'src 11', licence: 'Own work', aiGenerated: false },
  [ID(13)]: { id: ID(13), width: 1080, height: 1920, source: 'src 13', licence: 'Licensed', aiGenerated: true },
  [ID(31)]: { id: ID(31), width: 720, height: 1280, source: 'src 31', licence: 'Own work', aiGenerated: false },
  [ID(33)]: { id: ID(33), width: 720, height: 1280, source: 'src 33', licence: 'Own work', aiGenerated: false },
  [ID(21)]: { id: ID(21), width: 1080, height: 1350, source: 'src 21', licence: 'Own work', aiGenerated: false },
  [ID(23)]: { id: ID(23), width: 1080, height: 1350, source: 'src 23', licence: 'Licensed', aiGenerated: true },
}

let current: Record<string, unknown>
let calls: Array<{ spec: any; outDir: string; options: any }>
let composeChecks: any[]
const compose = vi.fn(async (spec: { slides: unknown[] }, outDir: string, options: { ratios?: string[] }) => {
  calls.push({ spec, outDir, options })
  return {
    slidePaths: Object.fromEntries((options.ratios ?? []).map((r) => [r, spec.slides.map((_, i) => path.join(outDir, `${r}-${i + 1}.png`))])),
    checks: composeChecks,
  }
})
const upload = vi.fn()
const download = vi.fn(async (m: { id: string }, into: string) => path.join(into, `${m.id}.png`))

beforeEach(() => {
  vi.resetAllMocks()
  current = detail()
  calls = []
  composeChecks = []
  compose.mockClear()
  download.mockClear()
  mocked(apiGet).mockImplementation(async (url: string) => {
    if (url === `${base}/revisions/${REV}`) return current
    // A run-scoped key cannot read the photo endpoints, so the job must never call them.
    throw new Error(`unexpected GET ${url}`)
  })
  mocked(apiPost).mockResolvedValue({})
  upload.mockReset()
  upload.mockImplementation(async (p: { label: string }) => ({ media: { id: `new:${p.label}` } }))
})

const run = (o: Record<string, unknown> = {}) =>
  renderSlides({ revisionId: REV, creativeId: CREATIVE }, config, { compose: compose as never, download: download as never, upload: upload as never, ...o })
const posts = (suffix: string) => mocked(apiPost).mock.calls.filter((c) => (c[0] as string).endsWith(suffix))

describe('renderSlides', () => {
  it('composes only the changed slides at both ratios, uploads them with the replaced media provenance and completes', async () => {
    const r = await run()
    expect(r).toMatchObject({ ok: true, outcome: 'completed', slides: [1, 3], ratios: ['9x16', '4x5'], uploaded: 4 })

    expect(compose).toHaveBeenCalledTimes(1)
    const { spec, options } = calls[0]!
    expect(spec.slides).toHaveLength(2)
    expect(spec.slides[0].texts[0].t).toBe('New hook')
    expect(spec.slides[1].texts[0].t).toBe('New end')
    // media refs became the downloaded files; the unchanged middle slide was not composed
    expect(spec.slides[0].bg).toMatch(new RegExp(`${ID(1)}\\.png$`))
    expect(spec.slides[1].bg).toMatch(new RegExp(`${ID(2)}\\.png$`))
    expect(download.mock.calls.map((c) => (c[0] as { id: string }).id).sort()).toEqual([ID(1), ID(2)])
    expect(options.ratios).toEqual(['9x16', '4x5'])
    expect(options.style).toEqual({ boxBg: '#ffffff', accent: '#ff2d55' })
    // slides are stored as JPEG at quality 92 (Instagram and TikTok photo posts refuse PNG)
    expect(options).toMatchObject({ format: 'jpeg', jpegQuality: 92 })
    // nothing is read from the photo endpoints
    expect(mocked(apiGet).mock.calls.map((c) => c[0] as string).filter((u) => u.includes('/photos/'))).toEqual([])

    const uploads = mocked(upload).mock.calls.map((c) => c[0] as Record<string, unknown>)
    expect(uploads.map((u) => u['label'])).toEqual(['12a slide 1 9x16 r4', '12a slide 3 9x16 r4', '12a slide 1 4x5 r4', '12a slide 3 4x5 r4'])
    expect(uploads[0]).toMatchObject({ source: 'src 11', licence: 'Own work', aiGenerated: false })
    expect(uploads[1]).toMatchObject({ source: 'src 13', licence: 'Licensed', aiGenerated: true })
    expect(uploads[3]).toMatchObject({ source: 'src 23', aiGenerated: true })

    expect(posts('/fail')).toHaveLength(0)
    const [url, body] = posts('/complete')[0]!
    expect(url).toBe(`${base}/revisions/${REV}/complete`)
    expect(body).toEqual({
      slideMedia: {
        '9x16': { '1': 'new:12a slide 1 9x16 r4', '3': 'new:12a slide 3 9x16 r4' },
        '4x5': { '1': 'new:12a slide 1 4x5 r4', '3': 'new:12a slide 3 4x5 r4' },
      },
      checks: [],
    })
  })

  it('reports a revision the backend settled as FAILED on complete instead of success', async () => {
    mocked(apiPost).mockImplementation(async (url: string) =>
      url.endsWith('/complete') ? { status: 'FAILED', error: 'The Creative changed while the slides were rendering.' } : {}
    )
    const r = await run()
    expect(r).toEqual({ ok: false, outcome: 'rejected', revisionId: REV, error: 'The Creative changed while the slides were rendering.' })
    expect(posts('/fail')).toHaveLength(0)
  })

  it('renders a default list at the ratio the backend names, whatever size its images are', async () => {
    // 720x1280 is not 1080x1920: a job that read pixel sizes would refuse it; the backend's renderLists decides.
    current = detail({ slides: { default: [ID(31), ID(32), ID(33)] }, renderLists: [{ list: 'default', ratio: '9x16' }] })
    const r = await run()
    expect(r).toMatchObject({ ok: true, ratios: ['9x16'], uploaded: 2 })
    expect(mocked(upload).mock.calls[0]![0]).toMatchObject({ source: 'src 31' })
  })

  it('renders a default list once per ratio', async () => {
    current = detail({ slides: { default: [ID(11), ID(12), ID(13)] }, renderLists: [{ list: 'default', ratio: '9x16' }] })
    const r = await run()
    expect(r).toMatchObject({ ok: true, ratios: ['9x16'], uploaded: 2 })
    expect(calls[0]!.options.ratios).toEqual(['9x16'])
    const body = posts('/complete')[0]![1] as { slideMedia: Record<string, unknown> }
    expect(Object.keys(body.slideMedia)).toEqual(['9x16'])
  })

  it('adds the ratio of a default list next to an explicit 9x16 list', async () => {
    current = detail({
      slides: { '9x16': [ID(11), ID(12), ID(13)], default: [ID(21), ID(22), ID(23)] },
      renderLists: [{ list: '9x16', ratio: '9x16' }, { list: 'default', ratio: '4x5' }],
    })
    // the backend says the default list is the 4x5 list
    const r = await run()
    expect(r).toMatchObject({ ok: true, ratios: ['9x16', '4x5'] })
    // an explicit list is where provenance comes from
    current = detail({
      slides: { default: [ID(13), ID(12), ID(13)], '9x16': [ID(11), ID(12), ID(13)] },
      renderLists: [{ list: 'default', ratio: '9x16' }, { list: '9x16', ratio: '9x16' }],
    })
    mocked(upload).mockClear()
    await run()
    expect((mocked(upload).mock.calls[0]![0] as Record<string, unknown>)['source']).toBe('src 11')
  })

  it('fails when the backend names nothing to render (the Creative cannot be text-edited)', async () => {
    current = detail({ slides: { '1x1': [ID(11), ID(12), ID(13)] }, renderLists: [] })
    await expect(run()).rejects.toThrow(/no 9x16 or 4x5 slides/)
    expect(compose).not.toHaveBeenCalled()
    expect(posts('/fail')[0]![1]).toMatchObject({ error: expect.stringMatching(/no 9x16 or 4x5/) })
  })

  it('does not guess the lists from pixel sizes when an older backend sends no renderLists', async () => {
    current = detail({ renderLists: undefined })
    await expect(run()).rejects.toThrow(/did not say which slide lists to re-render/)
    expect(compose).not.toHaveBeenCalled()
    expect(mocked(upload)).not.toHaveBeenCalled()
  })

  it('refuses a ratio it cannot compose instead of skipping the list', async () => {
    current = detail({ slides: { '1x1': [ID(11), ID(12), ID(13)] }, renderLists: [{ list: '1x1', ratio: '1x1' }] })
    await expect(run()).rejects.toThrow(/cannot make/)
    expect(compose).not.toHaveBeenCalled()
  })

  it('posts /fail with the checks, mapped to real slide numbers, and uploads nothing on an error check', async () => {
    composeChecks = [
      { slide: 2, ratio: '9x16', rule: 'safeZone', severity: 'error', message: 'text below the safe zone' },
      { slide: 1, ratio: '4x5', rule: 'wordsPerLine', severity: 'warning', message: 'long line' },
      { slide: 1, ratio: '9x16', rule: 'provenance', severity: 'warning', message: 'no sidecar' },
    ]
    const r = await run()
    expect(r).toMatchObject({ ok: false, outcome: 'checks-failed' })
    expect(mocked(upload)).not.toHaveBeenCalled()
    expect(posts('/complete')).toHaveLength(0)
    const fail = posts('/fail')
    expect(fail).toHaveLength(1)
    const body = fail[0]![1] as { error: string; checks: any[] }
    expect(body.error).toMatch(/1 layout check failed.*slide 3 9x16 safeZone/)
    // compose slide 2 is real slide 3; the provenance note is not about this job and is dropped
    expect(body.checks).toEqual([
      { slide: 3, ratio: '9x16', rule: 'safeZone', severity: 'error', message: 'text below the safe zone' },
      { slide: 1, ratio: '4x5', rule: 'wordsPerLine', severity: 'warning', message: 'long line' },
    ])
  })

  it('sends warnings with /complete', async () => {
    composeChecks = [{ slide: 2, ratio: '4x5', rule: 'textSize', severity: 'warning', message: 'small' }]
    const r = await run()
    expect(r).toMatchObject({ ok: true, warnings: 1 })
    expect((posts('/complete')[0]![1] as { checks: unknown[] }).checks).toEqual([{ slide: 3, ratio: '4x5', rule: 'textSize', severity: 'warning', message: 'small' }])
  })

  it('on a crash tries POST /fail with the error and rethrows', async () => {
    compose.mockRejectedValueOnce(new Error('browser exploded'))
    await expect(run()).rejects.toThrow('browser exploded')
    expect(mocked(upload)).not.toHaveBeenCalled()
    expect(posts('/fail')).toHaveLength(1)
    expect(posts('/fail')[0]![1]).toEqual({ error: 'browser exploded' })
  })

  it('still rethrows the original error when /fail itself is refused', async () => {
    compose.mockRejectedValueOnce(new Error('boom'))
    mocked(apiPost).mockRejectedValue(new Error('409'))
    await expect(run()).rejects.toThrow('boom')
  })

  it('does nothing for a revision that is no longer pending', async () => {
    current = detail({ status: 'FAILED' })
    expect(await run()).toMatchObject({ ok: false, outcome: 'not-pending' })
    expect(compose).not.toHaveBeenCalled()
    expect(mocked(apiPost)).not.toHaveBeenCalled()
  })

  it('reads the display id from the Creative when the revision detail lacks it', async () => {
    current = detail({ displayId: undefined })
    mocked(apiGet).mockImplementation(async (url: string) => {
      if (url === `${base}/revisions/${REV}`) return current
      if (url === base) return { displayId: '7b' }
      throw new Error(`unexpected GET ${url}`)
    })
    await run()
    expect((mocked(upload).mock.calls[0]![0] as { label: string }).label).toBe('7b slide 1 9x16 r4')
  })
})

it('fails clearly when an older backend sends no media in the revision detail', async () => {
  current = detail({ media: undefined })
  await expect(run()).rejects.toThrow(/did not send the revision's media/)
  expect(compose).not.toHaveBeenCalled()
})

/** A minimal JPEG: SOI, a SOF0 segment carrying the size, EOI. */
function jpegBytes(width: number, height: number): Buffer {
  return Buffer.from([0xff, 0xd8, 0xff, 0xc0, 0x00, 0x11, 0x08, height >> 8, height & 255, width >> 8, width & 255, 0x03, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1, 0xff, 0xd9])
}
const PNG_BYTES = Buffer.from('89504e470d0a1a0a0000000d49484452000004380000078008060000', 'hex')

describe('uploadRevisionSlide', () => {
  const revisionBase = `${base}/revisions/${REV}`
  const file = (name: string, bytes: Buffer): string => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rs-test-'))
    const f = path.join(dir, name)
    fs.writeFileSync(f, bytes)
    return f
  }

  it('mints through the revision, uploads image/jpeg, confirms, and never touches the photo endpoints', async () => {
    mocked(apiPost).mockImplementation(async (url: string) =>
      url.endsWith('/media') ? { id: 'm-1', uploadUrl: 'https://upload.test/m-1' } : { id: 'm-1', uploadStatus: 'UPLOADED' }
    )
    const result = await uploadRevisionSlide(
      revisionBase,
      { filePath: file('s.jpg', jpegBytes(1080, 1920)), label: '12a slide 1 9x16 r4', source: 'src', licence: 'Own work', aiGenerated: true },
      config
    )

    expect(result.media).toMatchObject({ id: 'm-1' })
    const [mintUrl, mintBody] = mocked(apiPost).mock.calls[0]!
    expect(mintUrl).toBe(`${revisionBase}/media`)
    expect(mintBody).toMatchObject({ contentType: 'image/jpeg', width: 1080, height: 1920, source: 'src', licence: 'Own work', aiGenerated: true })
    expect(mocked(putBytes)).toHaveBeenCalledWith('https://upload.test/m-1', 'image/jpeg', expect.anything())
    expect(mocked(apiPost).mock.calls[1]![0]).toBe(`${revisionBase}/media/m-1/confirm`)
    expect(mocked(apiPost).mock.calls.map((c) => c[0] as string).filter((u) => u.includes('/marketing/photos'))).toEqual([])
  })

  it('refuses a PNG slide instead of storing a format TikTok photo posts reject', async () => {
    await expect(uploadRevisionSlide(revisionBase, { filePath: file('s.png', PNG_BYTES), label: 'x' }, config)).rejects.toThrow(/image\/png, not image\/jpeg/)
    expect(mocked(apiPost)).not.toHaveBeenCalled()
    expect(mocked(putBytes)).not.toHaveBeenCalled()
  })
})

describe('renderSlides default upload', () => {
  it('uploads the composed JPEGs as image/jpeg through the revision media endpoints', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'rs-test-'))
    const composeJpeg = vi.fn(async (spec: { slides: unknown[] }, outDir: string, options: { ratios?: string[] }) => ({
      slidePaths: Object.fromEntries(
        (options.ratios ?? []).map((r) => [
          r,
          spec.slides.map((_, i) => {
            const f = path.join(dir, `${r}-${i + 1}.jpg`)
            fs.writeFileSync(f, jpegBytes(1080, r === '9x16' ? 1920 : 1350))
            return f
          }),
        ])
      ),
      checks: [],
    }))
    let n = 0
    mocked(apiPost).mockImplementation(async (url: string) => {
      if (url.endsWith('/media')) return { id: `m-${++n}`, uploadUrl: `https://upload.test/m-${n}` }
      if (url.endsWith('/confirm')) return { id: url.split('/').slice(-2)[0] }
      return {}
    })
    const r = await renderSlides({ revisionId: REV, creativeId: CREATIVE }, config, { compose: composeJpeg as never, download: download as never })

    expect(r).toMatchObject({ ok: true, uploaded: 4 })
    const contentTypes = mocked(putBytes).mock.calls.map((c) => c[1])
    expect(contentTypes).toEqual(['image/jpeg', 'image/jpeg', 'image/jpeg', 'image/jpeg'])
    const mints = posts('/media').map((c) => c[1] as { contentType: string })
    expect(mints.map((m) => m.contentType)).toEqual(['image/jpeg', 'image/jpeg', 'image/jpeg', 'image/jpeg'])
    expect(posts('/complete')).toHaveLength(1)
    expect(mocked(apiGet).mock.calls.map((c) => c[0] as string).filter((u) => u.includes('/photos/'))).toEqual([])
  })
})

describe('temp files', () => {
  it('removes its working folder', async () => {
    const before = fs.readdirSync(os.tmpdir()).filter((f) => f.startsWith('conductor-render-slides-')).length
    await run()
    expect(fs.readdirSync(os.tmpdir()).filter((f) => f.startsWith('conductor-render-slides-')).length).toBe(before)
  })
})
