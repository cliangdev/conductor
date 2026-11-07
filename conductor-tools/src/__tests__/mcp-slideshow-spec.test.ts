import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { pathToFileURL } from 'node:url'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  putBytes: vi.fn(),
  ApiError: class ApiError extends Error {},
}))
vi.mock('../lib/creative-render.js', () => ({ renderCreative: vi.fn(), renderDraft: vi.fn(), renderViaApi: vi.fn() }))
vi.mock('../lib/poster-sheet.js', () => ({ composePosterSheet: vi.fn() }))
vi.mock('../mcp/tools/creatives.js', () => ({
  createCreative: vi.fn(),
  updateCreative: vi.fn(),
  getCreative: vi.fn(),
  uploadCreativeMedia: vi.fn(),
}))

import { apiGet, apiPost } from '../mcp/api.js'
import { renderViaApi } from '../lib/creative-render.js'
import { composePosterSheet } from '../lib/poster-sheet.js'
import { createCreative, updateCreative, getCreative, uploadCreativeMedia } from '../mcp/tools/creatives.js'
import { previewCreativeDraft, commitCreativeDraft } from '../mcp/tools/creative-drafts.js'
import { attachSlideshowSpec, chooseDraftSpec, imageSimilarity, SIMILARITY_THRESHOLD, similarityFilter, uploadSpecImages } from '../mcp/tools/slideshow-spec.js'
import { prepareSpecForCreative, slideshowSpecView, specImages, SPEC_INLINE_LIMIT } from '../lib/slideshow/index.js'
import { resolveFfmpegPath } from '../lib/media-probe.js'

const config: Config = { apiKey: 'k', projectId: 'proj-1', projectName: 'P', email: 'e@x.test', apiUrl: 'https://api.test' }
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==', 'base64')
/** A PNG header that claims the given size: all the dimension sniffing reads. */
const pngHeader = (w: number, h: number): Buffer => {
  const b = Buffer.alloc(33)
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(b)
  b.writeUInt32BE(13, 8)
  b.write('IHDR', 12)
  b.writeUInt32BE(w, 16)
  b.writeUInt32BE(h, 20)
  return b
}

let root: string
let out: string
/** The files of a composed slideshow "ss": two slides, a background photo used twice and a phone screenshot. */
function composedFolder() {
  out = path.join(root, 'out', 'ss')
  fs.mkdirSync(out, { recursive: true })
  const bg = path.join(root, 'photos', 'bg.png')
  const shot = path.join(root, 'photos', 'shot.png')
  fs.mkdirSync(path.dirname(bg), { recursive: true })
  fs.writeFileSync(bg, PNG)
  fs.writeFileSync(shot, PNG)
  const slides = [path.join(out, 'ss-9x16-01.png'), path.join(out, 'ss-9x16-02.png')]
  for (const f of slides) fs.writeFileSync(f, PNG)
  const spec = {
    id: 'ss',
    slides: [
      { type: 'photo', bg, texts: [{ t: 'Hook line', y: 30 }] },
      { type: 'screen', bg, shot, callouts: [{ x: 20, y: 40, label: 'Tap here' }] },
    ],
    resolvedStyle: { fontFamily: null, fontUrl: null, boxBg: '#ffffff', accent: '#ff2d55' },
  }
  fs.writeFileSync(path.join(out, 'spec.json'), JSON.stringify(spec))
  return { bg, shot, slides, spec }
}

beforeEach(() => {
  vi.resetAllMocks()
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'cond-spec-'))
  mocked(composePosterSheet).mockResolvedValue(Buffer.from([0xff, 0xd8, 0xff, 0xd9]))
})
afterEach(() => fs.rmSync(root, { recursive: true, force: true }))

describe('prepareSpecForCreative', () => {
  it('stamps the current layout version, and keeps one the spec already names', () => {
    const r = prepareSpecForCreative({ id: 'a', slides: [{ type: 'photo', bg: 'img/a.png' }] }, '/work/specs')
    expect(r.ok && r.spec.layout).toBe(2)
    const old = prepareSpecForCreative({ id: 'a', layout: 1, slides: [{ type: 'photo', bg: 'img/a.png' }] }, '/work/specs')
    expect(old.ok && old.spec.layout).toBe(1)
    expect(prepareSpecForCreative({ id: 'a', layout: 'x', slides: [{ type: 'photo', bg: 'img/a.png' }] }, '/work/specs').ok).toBe(false)
  })

  it('resolves relative images against the spec folder and splits off resolvedStyle', () => {
    const r = prepareSpecForCreative({ id: 'a', slides: [{ type: 'photo', bg: 'img/a.png' }], resolvedStyle: { boxBg: '#fff' } }, '/work/specs')
    expect(r).toMatchObject({ ok: true, resolvedStyle: { boxBg: '#fff' } })
    if (!r.ok) return
    expect(r.spec.slides[0]!.bg).toBe(path.resolve('/work/specs', 'img/a.png'))
    expect('resolvedStyle' in r.spec).toBe(false)
  })

  it('converts file: URLs, keeps absolute paths and existing media refs, covers every image field', () => {
    const id = '123e4567-e89b-12d3-a456-426614174000'
    const r = prepareSpecForCreative(
      {
        id: 'a',
        slides: [
          { type: 'screen', shot: pathToFileURL('/x/s.png').href },
          { type: 'grid', photos: [{ src: '/x/p.png' }, { src: `media:${id}` }] },
          { type: 'week', days: [{ d: 'Mon', t: 'x', img: 'd.png' }] },
        ],
      },
      '/spec'
    )
    if (!r.ok) throw new Error(r.errors.join())
    expect(specImages(r.spec)).toEqual(['/x/s.png', '/x/p.png', `media:${id}`, path.resolve('/spec', 'd.png')])
  })

  it('refuses web URLs, inline data images and invalid specs without throwing', () => {
    expect(prepareSpecForCreative({ id: 'a', slides: [{ bg: 'https://x.test/a.png' }] }, '/s')).toMatchObject({ ok: false, errors: [expect.stringMatching(/web URL/)] })
    expect(prepareSpecForCreative({ id: 'a', slides: [{ bg: 'data:image/png;base64,AA' }] }, '/s')).toMatchObject({ ok: false, errors: [expect.stringMatching(/data: image/)] })
    expect(prepareSpecForCreative({ id: '', slides: [] }, '/s')).toMatchObject({ ok: false })
    expect(prepareSpecForCreative(null, '/s')).toMatchObject({ ok: false })
    expect(prepareSpecForCreative([], '/s')).toMatchObject({ ok: false })
  })
})

describe('slideshowSpecView', () => {
  it('shows a small spec with its text fields', () => {
    const spec = { id: 'a', slides: [{ type: 'photo', texts: [{ t: 'Hello', y: 30 }] }] }
    expect(slideshowSpecView(spec)).toEqual({ slideshowSpec: spec, textFields: [{ slide: 1, path: 'texts[0].t', text: 'Hello' }] })
  })
  it('summarises a large spec instead of repeating it', () => {
    const spec = { id: 'big', slides: Array.from({ length: 30 }, (_, i) => ({ type: 'photo', bg: `media:${'0'.repeat(8)}-0000-0000-0000-${String(i).padStart(12, '0')}`, texts: [{ t: `Line ${i} `.repeat(25), y: 30 }] })) }
    expect(JSON.stringify(spec).length).toBeGreaterThan(SPEC_INLINE_LIMIT)
    const view = slideshowSpecView(spec)!
    expect(view.textFields).toHaveLength(30)
    expect(view.slideshowSpec).toMatchObject({ id: 'big', slides: 30, omitted: expect.stringMatching(/textFields/) })
    expect(JSON.stringify(view.slideshowSpec).length).toBeLessThan(600)
  })
  it('flags text as not editable when a 1x1 list exists', () => {
    const spec = { id: 'a', slides: [{ type: 'photo', texts: [{ t: 'Hello', y: 30 }] }] }
    expect(slideshowSpecView(spec, { '9x16': ['a'], '1x1': ['b'] })).toMatchObject({ textEditable: false, textEditNote: expect.stringMatching(/1x1 slides/) })
    expect(slideshowSpecView(spec, { '9x16': ['a'], '1x1': [] })).not.toHaveProperty('textEditable')
    expect(slideshowSpecView(spec, null)).not.toHaveProperty('textEditable')
  })
  it('has nothing for no spec', () => {
    expect(slideshowSpecView(null)).toBeUndefined()
    expect(slideshowSpecView(undefined)).toBeUndefined()
  })
})

describe('chooseDraftSpec', () => {
  it('finds the spec.json compose_slideshow wrote beside the slides', async () => {
    const { slides } = composedFolder()
    const c = await chooseDraftSpec({ slideFiles: slides, slideLists: { '9x16': ['local:slide-1', 'local:slide-2'] }, projectRoot: root })
    expect(c.error).toBeUndefined()
    expect(c.path).toBe(path.join(out, 'spec.json'))
    expect(c.spec?.slides).toHaveLength(2)
    expect('resolvedStyle' in c.spec!).toBe(false)
  })

  it('ignores a spec.json that is not the one these slides were composed from', async () => {
    const { slides } = composedFolder()
    const other = path.join(out, 'other-01.png')
    fs.writeFileSync(other, PNG)
    expect(await chooseDraftSpec({ slideFiles: [other, slides[1]!], slideLists: {}, projectRoot: root })).toEqual({ notes: [] })
  })

  it('drops the spec with a note when its slide count differs from a list, without failing', async () => {
    const { slides } = composedFolder()
    const c = await chooseDraftSpec({ slideFiles: slides, slideLists: { '9x16': ['a', 'b'], '4x5': ['a', 'b', 'c'] }, projectRoot: root })
    expect(c.spec).toBeUndefined()
    expect(c.error).toBeUndefined()
    expect(c.notes[0]).toMatch(/2 slides but 4x5 has 3/)
  })

  it('an explicit path that cannot be used is an error; an auto-detected one is only a note', async () => {
    const { slides } = composedFolder()
    const explicit = await chooseDraftSpec({ slideshowSpecPath: path.join(root, 'nope.json'), slideFiles: slides, slideLists: {}, projectRoot: root })
    expect(explicit.error).toMatch(/Could not read/)
    fs.writeFileSync(path.join(out, 'spec.json'), '{ not json')
    const auto = await chooseDraftSpec({ slideFiles: slides, slideLists: {}, projectRoot: root })
    expect(auto.error).toBeUndefined()
    expect(auto.spec).toBeUndefined()
  })

  it('refuses (explicit) or drops (auto) a spec whose image files are gone', async () => {
    const { slides, bg } = composedFolder()
    fs.rmSync(bg)
    expect((await chooseDraftSpec({ slideshowSpecPath: path.join(out, 'spec.json'), slideFiles: slides, slideLists: {}, projectRoot: root })).error).toMatch(/not on this machine/)
    const auto = await chooseDraftSpec({ slideFiles: slides, slideLists: {}, projectRoot: root })
    expect(auto.spec).toBeUndefined()
    expect(auto.notes[0]).toMatch(/not on this machine/)
  })

  it('takes a source spec by relative path, with images relative to the spec', async () => {
    fs.mkdirSync(path.join(root, 'specs'), { recursive: true })
    fs.writeFileSync(path.join(root, 'specs', 'nov02.json'), JSON.stringify({ id: 'nov02', slides: [{ bg: '../photos/bg.png', texts: [{ t: 'x', y: 20 }] }] }))
    fs.mkdirSync(path.join(root, 'photos'))
    fs.writeFileSync(path.join(root, 'photos', 'bg.png'), PNG)
    const c = await chooseDraftSpec({ slideshowSpecPath: 'specs/nov02.json', slideFiles: [], slideLists: { default: ['id'] }, projectRoot: root })
    expect(c.spec?.slides[0]!.bg).toBe(path.join(root, 'photos', 'bg.png'))
  })
})

describe('preview and commit carry the spec', () => {
  it('keeps the spec in draft.json (not in the draft-spec call) and reports it', async () => {
    const { slides } = composedFolder()
    mocked(apiPost).mockResolvedValueOnce({ spec: {}, readiness: {} })
    const r = await previewCreativeDraft({ kind: 'CAROUSEL', slidePaths: { '9x16': slides } }, config, { projectRoot: root })
    if (!r.ok) throw new Error(r.error)
    expect(r.slideshowSpec).toEqual({ path: path.join(out, 'spec.json'), slides: 2, textFields: 2 })
    expect(r.nextStep).toMatch(/slideshow spec.*stored on the Creative at commit/)
    const body = mocked(apiPost).mock.calls[0]![1] as Record<string, unknown>
    expect(body['slideshowSpec']).toBeUndefined()
    expect(body['slideshowSpecPath']).toBeUndefined()
    const draft = JSON.parse(fs.readFileSync(path.join(r.draftDir, 'draft.json'), 'utf8'))
    expect(draft.slideshowSpecPath).toBe(path.join(out, 'spec.json'))
    expect(draft.slideshowSpec.slides[0].bg).toBe(path.join(root, 'photos', 'bg.png'))
    expect(draft.slideshowSpec.resolvedStyle).toBeUndefined()
    expect(draft.request.slideshowSpec).toBeUndefined()
  })

  it('a slide-count mismatch warns and drops the spec but still previews', async () => {
    const { slides } = composedFolder()
    mocked(apiPost).mockResolvedValueOnce({ spec: {}, readiness: {} })
    const r = await previewCreativeDraft({ kind: 'CAROUSEL', slidePaths: { '9x16': [slides[0]!] } }, config, { projectRoot: root })
    if (!r.ok) throw new Error(r.error)
    expect(r.slideshowSpec).toBeUndefined()
    expect(r.note).toMatch(/spec was not kept/)
    expect(JSON.parse(fs.readFileSync(path.join(r.draftDir, 'draft.json'), 'utf8')).slideshowSpec).toBeUndefined()
  })

  it('an explicit slideshowSpecPath that is unusable fails before any API call', async () => {
    const { slides } = composedFolder()
    const r = await previewCreativeDraft({ kind: 'CAROUSEL', slidePaths: { '9x16': slides }, slideshowSpecPath: path.join(root, 'missing.json') }, config, { projectRoot: root })
    expect(r).toMatchObject({ ok: false, error: expect.stringMatching(/Could not read/) })
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('a slideshowSpecPath on a non-carousel is refused', async () => {
    const r = await previewCreativeDraft({ kind: 'STILL', slideshowSpecPath: 'x.json' }, config, { projectRoot: root })
    expect(r).toMatchObject({ ok: false, error: expect.stringMatching(/CAROUSEL/) })
  })

  it('without a spec.json nothing changes', async () => {
    const f = path.join(root, '01.png')
    fs.writeFileSync(f, PNG)
    mocked(apiPost).mockResolvedValueOnce({ spec: {}, readiness: {} })
    const r = await previewCreativeDraft({ kind: 'CAROUSEL', slidePaths: { '9x16': [f] } }, config, { projectRoot: root })
    if (!r.ok) throw new Error(r.error)
    expect(r.slideshowSpec).toBeUndefined()
  })

  it('warns when new slides without a spec will clear the base Creative\'s spec', async () => {
    const f = path.join(root, '01.png')
    fs.writeFileSync(f, PNG)
    mocked(apiGet).mockResolvedValueOnce({ kind: 'CAROUSEL', slideshowSpec: { id: 'old', slides: [{}] } })
    mocked(apiPost).mockResolvedValueOnce({ spec: {}, readiness: {} })
    const r = await previewCreativeDraft({ baseCreativeId: 'c0', slidePaths: { '9x16': [f] } }, config, { projectRoot: root })
    if (!r.ok) throw new Error(r.error)
    expect(r.note).toMatch(/clear that spec/)
  })

  async function preview() {
    const folder = composedFolder()
    mocked(apiPost).mockResolvedValueOnce({ spec: {}, readiness: {} })
    const r = await previewCreativeDraft({ kind: 'CAROUSEL', slidePaths: { '9x16': folder.slides } }, config, { projectRoot: root })
    if (!r.ok) throw new Error(r.error)
    mocked(apiPost).mockReset()
    return { ...folder, draftDir: r.draftDir }
  }
  const uploads: string[] = []
  function stubUploads() {
    uploads.length = 0
    mocked(uploadCreativeMedia).mockImplementation(async (p: { filePath: string }) => {
      uploads.push(path.basename(p.filePath))
      return { media: { id: `m-${path.basename(p.filePath, '.png')}` }, warnings: [] }
    })
    mocked(renderViaApi).mockResolvedValue({ ok: true, frames: [] })
    mocked(getCreative).mockResolvedValue({ id: 'c1', version: 1, readiness: {} })
  }

  it('commit uploads each spec image once, rewrites the refs to media:<id> and sends the spec with the Creative', async () => {
    const { draftDir } = await preview()
    stubUploads()
    mocked(createCreative).mockResolvedValue({ id: 'c1', kind: 'CAROUSEL', version: 1, slideshowSpec: { id: 'ss' } })
    const result = await commitCreativeDraft({ draftDir, licence: 'Own work' }, config, { projectRoot: root })

    // the two slides, then bg.png (used on both slides) once, then shot.png
    expect(uploads).toEqual(['ss-9x16-01.png', 'ss-9x16-02.png', 'bg.png', 'shot.png'])
    const sent = mocked(createCreative).mock.calls[0]![0] as Record<string, any>
    expect(sent.slides).toEqual({ '9x16': ['m-ss-9x16-01', 'm-ss-9x16-02'] })
    expect(sent.slideshowSpec.slides[0].bg).toBe('media:m-bg')
    expect(sent.slideshowSpec.slides[1]).toMatchObject({ bg: 'media:m-bg', shot: 'media:m-shot' })
    expect(sent.slideshowSpec.resolvedStyle).toBeUndefined()
    expect(sent.slideshowSpec.slides[1].callouts[0].label).toBe('Tap here')
    expect(result.slideshowSpec).toEqual({ attached: true, images: 2 })
    expect(result.warnings).toEqual([])
  })

  it('a spec image with its own sidecar uploads with it; the rest use the call params; photoChecked never marks spec images', async () => {
    const { draftDir, shot } = await preview()
    fs.writeFileSync(`${shot}.provenance.json`, JSON.stringify({ source: 'Screenshot', licence: 'Own work', aiGenerated: false }))
    stubUploads()
    mocked(createCreative).mockResolvedValue({ id: 'c1', kind: 'CAROUSEL', version: 1, slideshowSpec: {} })
    await commitCreativeDraft({ draftDir, source: 'Stock', licence: 'CC0', aiGenerated: true, photoChecked: true }, config, { projectRoot: root })
    const calls = mocked(uploadCreativeMedia).mock.calls.map((c) => c[0] as Record<string, unknown>)
    expect(calls.find((c) => String(c['filePath']).endsWith('shot.png'))).toMatchObject({ source: 'Screenshot', licence: 'Own work', aiGenerated: false })
    expect(calls.find((c) => String(c['filePath']).endsWith('bg.png'))).toMatchObject({ source: 'Stock', licence: 'CC0', aiGenerated: true })
    const { apiPatch } = await import('../mcp/api.js')
    const checked = mocked(apiPatch).mock.calls.map((c) => c[0] as string)
    expect(checked.filter((u) => /photos\/m-(bg|shot)/.test(u))).toEqual([])
  })

  it('a retry after a failed upload does not re-upload what landed', async () => {
    const { draftDir } = await preview()
    stubUploads()
    mocked(uploadCreativeMedia).mockImplementation(async (p: { filePath: string }) => {
      const name = path.basename(p.filePath)
      if (name === 'shot.png' && !uploads.includes('failed-once')) {
        uploads.push('failed-once')
        throw new Error('network')
      }
      uploads.push(name)
      return { media: { id: `m-${path.basename(name, '.png')}` }, warnings: [] }
    })
    mocked(createCreative).mockResolvedValue({ id: 'c1', kind: 'CAROUSEL', version: 1, slideshowSpec: {} })
    await expect(commitCreativeDraft({ draftDir }, config, { projectRoot: root })).rejects.toThrow('network')
    uploads.length = 0
    uploads.push('failed-once')
    await commitCreativeDraft({ draftDir }, config, { projectRoot: root })
    expect(uploads.filter((u) => u.endsWith('.png'))).toEqual(['shot.png'])
  })

  it('refuses before uploading anything when a spec image is gone', async () => {
    const { draftDir, bg } = await preview()
    stubUploads()
    fs.rmSync(bg)
    await expect(commitCreativeDraft({ draftDir }, config, { projectRoot: root })).rejects.toThrow(/slideshow spec's image no longer exists/)
    expect(uploadCreativeMedia).not.toHaveBeenCalled()
  })

  it('warns when the server does not echo the spec back', async () => {
    const { draftDir } = await preview()
    stubUploads()
    mocked(createCreative).mockResolvedValue({ id: 'c1', kind: 'CAROUSEL', version: 1 })
    const result = await commitCreativeDraft({ draftDir }, config, { projectRoot: root })
    expect(result.slideshowSpec).toEqual({ attached: false, images: 2 })
    expect(result.warnings.join()).toMatch(/did not return slideshowSpec/)
  })

  it('updates a base Creative with the spec too', async () => {
    const folder = composedFolder()
    mocked(apiGet).mockResolvedValueOnce({ kind: 'CAROUSEL' })
    mocked(apiPost).mockResolvedValueOnce({ spec: {}, readiness: {} })
    const r = await previewCreativeDraft({ baseCreativeId: 'c0', slidePaths: { '9x16': folder.slides } }, config, { projectRoot: root })
    if (!r.ok) throw new Error(r.error)
    stubUploads()
    mocked(getCreative).mockResolvedValue({ id: 'c0', version: 4, readiness: {} })
    mocked(updateCreative).mockResolvedValue({ id: 'c0', kind: 'CAROUSEL', version: 5, slideshowSpec: {} })
    await commitCreativeDraft({ draftDir: r.draftDir }, config, { projectRoot: root })
    const sent = mocked(updateCreative).mock.calls[0]![0] as Record<string, any>
    expect(sent).toMatchObject({ creativeId: 'c0', version: 4 })
    expect(sent.slideshowSpec.slides[1].shot).toBe('media:m-shot')
  })
})

describe('uploadSpecImages', () => {
  it('passes existing media refs through untouched and uploads nothing for them', async () => {
    const id = '123e4567-e89b-12d3-a456-426614174000'
    const out = await uploadSpecImages({ id: 'a', slides: [{ bg: `media:${id}` }] }, config, { fallback: {}, done: {} })
    expect(out).toMatchObject({ uploaded: 0, spec: { slides: [{ bg: `media:${id}` }] } })
    expect(uploadCreativeMedia).not.toHaveBeenCalled()
  })
})

describe('attachSlideshowSpec', () => {
  const ID = (n: number) => `00000000-0000-0000-0000-${String(n).padStart(12, '0')}`
  let specFile: string
  let creative: Record<string, unknown>
  const similar = (ssim: number | ((n: number) => number)) => vi.fn(async (_a: string, _b: string) => (typeof ssim === 'number' ? ssim : ssim(++calls)))
  let calls = 0
  const composeStub = vi.fn(async (spec: { slides: unknown[] }, outDir: string, o: { ratios?: string[] }) => ({
    slidePaths: Object.fromEntries((o.ratios ?? []).map((r) => [r, spec.slides.map((_, i) => path.join(outDir, `${r}-${i}.png`))])),
  }))
  const download = vi.fn(async (id: string, into: string) => {
    const f = path.join(into, `${id}.img`)
    fs.writeFileSync(f, pngDims[id] ?? pngHeader(1080, 1920))
    return f
  })
  let pngDims: Record<string, Buffer>

  beforeEach(() => {
    calls = 0
    pngDims = {}
    composeStub.mockClear()
    download.mockClear()
    const { spec } = composedFolder()
    specFile = path.join(out, 'spec.json')
    void spec
    creative = { id: 'c1', kind: 'CAROUSEL', version: 7, slides: { '9x16': [ID(1), ID(2)] }, slideStyle: null }
    mocked(apiGet).mockImplementation(async () => creative)
    mocked(updateCreative).mockResolvedValue({ id: 'c1', version: 8, slideshowSpec: { id: 'ss' } })
    mocked(uploadCreativeMedia).mockImplementation(async (p: { filePath: string }) => ({ media: { id: `m-${path.basename(p.filePath, '.png')}` }, warnings: [] }))
  })
  const run = (extra: Record<string, unknown> = {}, o: Record<string, unknown> = {}) =>
    attachSlideshowSpec({ creativeId: 'c1', specPath: specFile, ...extra }, config, { compose: composeStub as never, download, similarity: similar(0.999) as never, ...o })

  it('re-composes, compares, uploads the images and patches the spec with the Creative\'s version', async () => {
    const r = await run()
    expect(r).toMatchObject({ ok: true, attached: true, images: 2, compared: 2, minSimilarity: 0.999, forced: false, textFields: 2, slideStyleSet: true })
    expect(composeStub).toHaveBeenCalledTimes(1)
    const [spec, , options] = composeStub.mock.calls[0]! as unknown as [any, string, any]
    expect(options.ratios).toEqual(['9x16'])
    expect(options.style).toEqual({ fontFamily: null, fontUrl: null, boxBg: '#ffffff', accent: '#ff2d55' })
    expect(spec.slides).toHaveLength(2)
    const patch = mocked(updateCreative).mock.calls[0]![0] as Record<string, any>
    expect(patch).toMatchObject({ creativeId: 'c1', version: 7, slideStyle: { boxBg: '#ffffff' } })
    expect(patch.slideshowSpec.slides[1]).toMatchObject({ bg: 'media:m-bg', shot: 'media:m-shot' })
    expect(patch.slideshowSpec.resolvedStyle).toBeUndefined()
    expect(mocked(uploadCreativeMedia)).toHaveBeenCalledTimes(2)
    expect((r as any).nextStep).toMatch(/get_creative/)
  })

  it('keeps the Creative\'s own slideStyle', async () => {
    creative['slideStyle'] = { accent: '#000000' }
    await run()
    expect((mocked(updateCreative).mock.calls[0]![0] as Record<string, unknown>)['slideStyle']).toBeUndefined()
  })

  it('refuses a Creative that is not a CAROUSEL, before composing anything', async () => {
    creative['kind'] = 'STILL'
    expect(await run()).toMatchObject({ ok: false, error: expect.stringMatching(/only a CAROUSEL/) })
    expect(composeStub).not.toHaveBeenCalled()
  })

  it('refuses a slide-count mismatch even with force', async () => {
    creative['slides'] = { '9x16': [ID(1), ID(2), ID(3)] }
    expect(await run({ force: true })).toMatchObject({ ok: false, error: expect.stringMatching(/2 slides but 9x16 has 3.*force cannot override/) })
    expect(updateCreative).not.toHaveBeenCalled()
  })

  it('refuses to replace an existing spec unless forced', async () => {
    creative['slideshowSpec'] = { id: 'old', slides: [{}, {}] }
    expect(await run()).toMatchObject({ ok: false, error: expect.stringMatching(/already has a slideshow spec/) })
    expect(await run({ force: true })).toMatchObject({ ok: true })
  })

  it('refuses when any slide is below the threshold, naming it, and uploads and patches nothing', async () => {
    const r = await run({}, { similarity: similar((n) => (n === 2 ? 0.81 : 0.998)) })
    expect(r.ok).toBe(false)
    if (r.ok) return
    expect(r.error).toMatch(/1 of 2 slides.*below 0\.95.*9x16 slide 2 = 0\.81/)
    expect(r.similarity).toMatchObject({ threshold: SIMILARITY_THRESHOLD, minSimilarity: 0.81, below: [{ placement: '9x16', slide: 2, ssim: 0.81 }] })
    expect(uploadCreativeMedia).not.toHaveBeenCalled()
    expect(updateCreative).not.toHaveBeenCalled()
  })

  it('force attaches anyway and says so', async () => {
    const r = await run({ force: true }, { similarity: similar(0.6) })
    expect(r).toMatchObject({ ok: true, forced: true, minSimilarity: 0.6 })
    expect(updateCreative).toHaveBeenCalledTimes(1)
  })

  it('compares each placement the Creative has, and treats default by the shape of its images', async () => {
    creative['slides'] = { default: [ID(1), ID(2)], '4x5': [ID(3), ID(4)] }
    pngDims = { [ID(1)]: pngHeader(1080, 1920), [ID(3)]: pngHeader(1080, 1350) }
    const r = await run()
    expect(r).toMatchObject({ ok: true, compared: 4 })
    expect((composeStub.mock.calls[0]![2] as { ratios: string[] }).ratios.sort()).toEqual(['4x5', '9x16'])
  })

  it('skips a list it cannot re-compose (1x1) and notes it; nothing comparable needs force', async () => {
    creative['slides'] = { '9x16': [ID(1), ID(2)], '1x1': [ID(3), ID(4)] }
    pngDims = { [ID(3)]: pngHeader(1080, 1080) }
    const r = await run()
    expect(r).toMatchObject({ ok: true, compared: 2, skipped: [expect.stringMatching(/^1x1/)] })

    creative['slides'] = { '1x1': [ID(3), ID(4)] }
    const none = await run()
    expect(none).toMatchObject({ ok: false, error: expect.stringMatching(/force: true/) })
    expect(await run({ force: true })).toMatchObject({ ok: true, compared: 0, forced: true })
  })

  it('refuses a spec that already names media, a missing image, and a bad spec', async () => {
    fs.writeFileSync(path.join(root, 'media.json'), JSON.stringify({ id: 'x', slides: [{ bg: `media:${ID(9)}` }, {}] }))
    expect(await run({ specPath: path.join(root, 'media.json') })).toMatchObject({ ok: false, error: expect.stringMatching(/already names uploaded images/) })
    fs.rmSync(path.join(root, 'photos', 'bg.png'))
    expect(await run()).toMatchObject({ ok: false, error: expect.stringMatching(/not on this machine/) })
    fs.writeFileSync(path.join(root, 'bad.json'), JSON.stringify({ id: 'x', slides: [] }))
    expect(await run({ specPath: path.join(root, 'bad.json') })).toMatchObject({ ok: false, error: expect.stringMatching(/not a usable slideshow spec/) })
  })

  it('warns when the server does not echo the spec back', async () => {
    mocked(updateCreative).mockResolvedValue({ id: 'c1', version: 8 })
    const r = await run()
    expect(r).toMatchObject({ ok: true, warnings: [expect.stringMatching(/did not return slideshowSpec/)] })
  })

  it('accepts a source spec with relative image paths', async () => {
    fs.mkdirSync(path.join(root, 'specs'))
    fs.writeFileSync(path.join(root, 'specs', 'nov02.json'), JSON.stringify({ id: 'nov02', slides: [{ bg: '../photos/bg.png', texts: [{ t: 'a', y: 20 }] }, { bg: '../photos/shot.png', texts: [{ t: 'b', y: 20 }] }] }))
    const r = await run({ specPath: path.join(root, 'specs', 'nov02.json') })
    expect(r).toMatchObject({ ok: true, slideStyleSet: false })
    expect((composeStub.mock.calls[0]![2] as { style?: unknown }).style).toBeUndefined()
  })
})

// The similarity measure itself, with the real ffmpeg (skipped when none can be found).
const hasFfmpeg = await resolveFfmpegPath().then(() => true, () => false)
describe.skipIf(!hasFfmpeg)('imageSimilarity (real ffmpeg)', () => {
  it('is 1 for identical images, catches a local change a whole-frame score would average away, and survives rescaling', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ssim-'))
    try {
      const { spawnSync } = await import('node:child_process')
      const ffmpeg = await resolveFfmpegPath()
      const make = (name: string, args: string[]) => {
        const f = path.join(dir, name)
        const r = spawnSync(ffmpeg, ['-y', '-loglevel', 'error', ...args, '-frames:v', '1', f])
        if (r.status !== 0) throw new Error(String(r.stderr))
        return f
      }
      const size = { width: 270, height: 480 }
      const one = make('one.png', ['-f', 'lavfi', '-i', 'testsrc=size=270x480'])
      const same = make('same.png', ['-f', 'lavfi', '-i', 'testsrc=size=270x480'])
      const other = make('other.png', ['-f', 'lavfi', '-i', 'mandelbrot=size=270x480'])
      expect(await imageSimilarity(one, same, size)).toBeGreaterThan(0.999)
      expect(await imageSimilarity(one, other, size)).toBeLessThan(0.9)
      // a small box drawn on one corner: whole-frame SSIM stays high, the tile under it does not
      const edited = make('edited.png', ['-i', one, '-vf', 'drawbox=x=10:y=10:w=70:h=40:color=black:t=fill'])
      expect(await imageSimilarity(one, edited, size)).toBeLessThan(SIMILARITY_THRESHOLD)
      // a different size is scaled first: the same picture at twice the size is still close (a fine test card loses some detail resizing)
      const big = make('big.png', ['-i', one, '-vf', 'scale=540:960:flags=bicubic'])
      expect(await imageSimilarity(one, big, size)).toBeGreaterThan(0.7)
    } finally {
      fs.rmSync(dir, { recursive: true, force: true })
    }
  })

  it('builds one scored tile per grid cell, the last as the graph output', () => {
    const graph = similarityFilter({ width: 1080, height: 1920 })
    expect(graph.match(/ssim/g)).toHaveLength(48)
    expect(graph.match(/nullsink/g)).toHaveLength(47)
    expect(graph).toContain('crop=180:240:900:1680')
  })
})
