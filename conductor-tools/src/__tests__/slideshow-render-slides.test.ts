import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({ apiGet: vi.fn(), apiPost: vi.fn(), apiPatch: vi.fn(), putBytes: vi.fn(), ApiError: class ApiError extends Error {} }))
vi.mock('../lib/creative-render.js', () => ({ renderCreative: vi.fn(), renderDraft: vi.fn(), renderViaApi: vi.fn() }))
vi.mock('../mcp/tools/creatives.js', () => ({ uploadCreativeMedia: vi.fn() }))

import { apiGet, apiPost } from '../mcp/api.js'
import { uploadCreativeMedia } from '../mcp/tools/creatives.js'
import { ratioOfSize, renderSlides } from '../mcp/tools/slideshow-render.js'

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
    slideStyle: { boxBg: '#ffffff', accent: '#ff2d55' },
    ...over,
  }
}

const media: Record<string, Record<string, unknown>> = {
  [ID(1)]: { id: ID(1), url: 'https://cdn/a' },
  [ID(2)]: { id: ID(2), url: 'https://cdn/b' },
  [ID(11)]: { id: ID(11), width: 1080, height: 1920, source: 'src 11', licence: 'Own work', aiGenerated: false },
  [ID(13)]: { id: ID(13), width: 1080, height: 1920, source: 'src 13', licence: 'Licensed', aiGenerated: true },
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
    const m = /photos\/(.+)$/.exec(url)
    if (m) return media[m[1]!] ?? { id: m[1] }
    throw new Error(`unexpected GET ${url}`)
  })
  mocked(apiPost).mockResolvedValue({})
  mocked(uploadCreativeMedia).mockImplementation(async (p: { label: string }) => ({ media: { id: `new:${p.label}` }, warnings: [] }))
})

const run = (o: Record<string, unknown> = {}) => renderSlides({ revisionId: REV, creativeId: CREATIVE }, config, { compose: compose as never, download: download as never, ...o })
const posts = (suffix: string) => mocked(apiPost).mock.calls.filter((c) => (c[0] as string).endsWith(suffix))

describe('ratioOfSize', () => {
  it('names the two composed sizes only', () => {
    expect(ratioOfSize(1080, 1920)).toBe('9x16')
    expect(ratioOfSize(1080, 1350)).toBe('4x5')
    expect(ratioOfSize(1080, 1080)).toBeUndefined()
    expect(ratioOfSize(null, undefined)).toBeUndefined()
  })
})

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

    const uploads = mocked(uploadCreativeMedia).mock.calls.map((c) => c[0] as Record<string, unknown>)
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

  it('treats a default list as the ratio of its images and renders it once per ratio', async () => {
    current = detail({ slides: { default: [ID(11), ID(12), ID(13)] } })
    const r = await run()
    expect(r).toMatchObject({ ok: true, ratios: ['9x16'], uploaded: 2 })
    expect(calls[0]!.options.ratios).toEqual(['9x16'])
    const body = posts('/complete')[0]![1] as { slideMedia: Record<string, unknown> }
    expect(Object.keys(body.slideMedia)).toEqual(['9x16'])
  })

  it('adds the ratio of a default list next to an explicit 9x16 list', async () => {
    current = detail({ slides: { '9x16': [ID(11), ID(12), ID(13)], default: [ID(21), ID(22), ID(23)] } })
    // the default list's images are 1080x1350, so it is the 4x5 list
    const r = await run()
    expect(r).toMatchObject({ ok: true, ratios: ['9x16', '4x5'] })
    // an explicit list is where provenance comes from
    current = detail({ slides: { default: [ID(13), ID(12), ID(13)], '9x16': [ID(11), ID(12), ID(13)] } })
    mocked(uploadCreativeMedia).mockClear()
    await run()
    expect((mocked(uploadCreativeMedia).mock.calls[0]![0] as Record<string, unknown>)['source']).toBe('src 11')
  })

  it('leaves a 1x1 list alone and fails when nothing else is left to render', async () => {
    current = detail({ slides: { '1x1': [ID(11), ID(12), ID(13)] } })
    await expect(run()).rejects.toThrow(/no 9x16 or 4x5 slides/)
    expect(compose).not.toHaveBeenCalled()
    expect(posts('/fail')[0]![1]).toMatchObject({ error: expect.stringMatching(/no 9x16 or 4x5/) })
  })

  it('posts /fail with the checks, mapped to real slide numbers, and uploads nothing on an error check', async () => {
    composeChecks = [
      { slide: 2, ratio: '9x16', rule: 'safeZone', severity: 'error', message: 'text below the safe zone' },
      { slide: 1, ratio: '4x5', rule: 'wordsPerLine', severity: 'warning', message: 'long line' },
      { slide: 1, ratio: '9x16', rule: 'provenance', severity: 'warning', message: 'no sidecar' },
    ]
    const r = await run()
    expect(r).toMatchObject({ ok: false, outcome: 'checks-failed' })
    expect(mocked(uploadCreativeMedia)).not.toHaveBeenCalled()
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
    expect(mocked(uploadCreativeMedia)).not.toHaveBeenCalled()
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
      return media[/photos\/(.+)$/.exec(url)![1]!] ?? {}
    })
    await run()
    expect((mocked(uploadCreativeMedia).mock.calls[0]![0] as { label: string }).label).toBe('7b slide 1 9x16 r4')
  })
})

describe('temp files', () => {
  it('removes its working folder', async () => {
    const before = fs.readdirSync(os.tmpdir()).filter((f) => f.startsWith('conductor-render-slides-')).length
    await run()
    expect(fs.readdirSync(os.tmpdir()).filter((f) => f.startsWith('conductor-render-slides-')).length).toBe(before)
  })
})
