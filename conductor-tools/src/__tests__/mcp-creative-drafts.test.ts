import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  putBytes: vi.fn(),
  ApiError: class ApiError extends Error {
    status: number
    code?: string
    violations?: Array<{ field?: string; ruleId?: string; message: string }>
    constructor(status: number, message: string, opts: { code?: string; violations?: Array<{ field?: string; ruleId?: string; message: string }> } = {}) {
      super(message)
      this.name = 'ApiError'
      this.status = status
      this.code = opts.code
      this.violations = opts.violations
    }
  },
}))
vi.mock('../lib/creative-render.js', () => ({ renderCreative: vi.fn(), renderDraft: vi.fn() }))
vi.mock('../lib/media-probe.js', () => ({ probeMedia: vi.fn(), extractPoster: vi.fn() }))
vi.mock('../mcp/tools/creatives.js', () => ({
  createCreative: vi.fn(),
  updateCreative: vi.fn(),
  getCreative: vi.fn(),
  uploadCreativeMedia: vi.fn(),
}))

import { apiGet, apiPost, apiPatch, putBytes, ApiError } from '../mcp/api.js'
import { renderCreative, renderDraft } from '../lib/creative-render.js'
import { probeMedia } from '../lib/media-probe.js'
import { createCreative, updateCreative, getCreative, uploadCreativeMedia } from '../mcp/tools/creatives.js'
import { previewCreativeDraft, commitCreativeDraft, pruneDrafts } from '../mcp/tools/creative-drafts.js'

const config: Config = { apiKey: 'k', projectId: 'proj-1', projectName: 'P', email: 'e@x.test', apiUrl: 'https://api.test' }
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

// 1x1 PNG
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==',
  'base64'
)

let root: string
let photo: string

/** A render that "succeeds" by writing a sheet and a manifest the way the file transport does. */
function fakeDraftRender(opts: { sheetBytes?: Buffer; checks?: Array<Record<string, unknown>> } = {}) {
  mocked(renderDraft).mockImplementation(async ({ outDir }: { outDir: string }) => {
    fs.mkdirSync(outDir, { recursive: true })
    fs.writeFileSync(path.join(outDir, 'sheet.jpg'), opts.sheetBytes ?? Buffer.from([0xff, 0xd8, 0xff, 0xd9]))
    const manifest = {
      ok: true,
      frames: [{ placementKey: 'sheet', file: 'sheet.jpg', contentType: 'image/jpeg', width: 100, height: 50 }],
      warnings: [{ placementKey: '4x5', message: 'tight margin' }],
      ...(opts.checks ? { checks: opts.checks, passed: !opts.checks.some((c) => c.severity === 'error') } : {}),
    }
    fs.writeFileSync(path.join(outDir, 'manifest.json'), JSON.stringify(manifest))
    return { ok: true, manifest }
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'cond-drafts-'))
  photo = path.join(root, 'hero.png')
  fs.writeFileSync(photo, PNG)
})

afterEach(() => {
  fs.rmSync(root, { recursive: true, force: true })
})

describe('previewCreativeDraft', () => {
  it('calls only the draft-spec endpoint, writes nothing to the API, and returns the sheet inline', async () => {
    mocked(apiPost).mockResolvedValueOnce({ spec: { renderId: 'draft', previewOnly: true, creative: { photoUrl: 'local:photo' } }, readiness: { ready: false, blocking: ['caption'] } })
    fakeDraftRender()

    const result = await previewCreativeDraft({ headline: 'Plan the week in *one sentence*.', photoPath: photo }, config, { projectRoot: root })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(mocked(apiPost).mock.calls[0]![0]).toBe('/api/v2/projects/proj-1/marketing/creatives/draft-spec')
    expect(apiGet).not.toHaveBeenCalled()
    expect(apiPatch).not.toHaveBeenCalled()
    expect(putBytes).not.toHaveBeenCalled()
    expect(createCreative).not.toHaveBeenCalled()
    expect(updateCreative).not.toHaveBeenCalled()
    expect(uploadCreativeMedia).not.toHaveBeenCalled()
    expect(renderCreative).not.toHaveBeenCalled()

    expect(result.image?.mimeType).toBe('image/jpeg')
    expect(result.readiness).toEqual({ ready: false, blocking: ['caption'] })
    expect(result.warnings).toEqual([{ placementKey: '4x5', message: 'tight margin' }])
    expect(result.draftDir.startsWith(path.join(root, '.conductor', 'drafts'))).toBe(true)
    expect(path.basename(result.draftDir)).toMatch(/^\d{8}-\d{6}-plan-the-week-in-one-sentence$/)
    expect(result.nextStep).toMatch(/Nothing has been saved/)
  })

  it('maps local files to local:<key> media ids and sends their metadata as localMedia', async () => {
    const clip = path.join(root, 'bg.mp4')
    const music = path.join(root, 'bed.mp3')
    fs.writeFileSync(clip, 'x')
    fs.writeFileSync(music, 'y')
    const beatPhoto = path.join(root, 'beat.png')
    fs.writeFileSync(beatPhoto, PNG)
    mocked(probeMedia).mockImplementation(async (p: string) =>
      p === clip
        ? { kind: 'VIDEO', contentType: 'video/mp4', width: 1080, height: 1920, durationSeconds: 12.5, hasAudio: true }
        : { kind: 'AUDIO', contentType: 'audio/mpeg', durationSeconds: 30 }
    )
    mocked(apiPost).mockResolvedValueOnce({ spec: { creative: {} }, readiness: {} })
    fakeDraftRender()

    await previewCreativeDraft(
      {
        kind: 'MOTION',
        headline: 'H',
        photoPath: photo,
        clipPath: clip,
        audioPath: music,
        motion: { preset: 'fade-up', background: { clipStartSec: 2 } },
        audio: { volume: 0.5 },
        sequence: [{ headline: 'one', photoPath: beatPhoto }, { headline: 'two' }],
      },
      config,
      { projectRoot: root }
    )

    const body = mocked(apiPost).mock.calls[0]![1] as Record<string, any>
    expect(body.photoId).toBe('local:photo')
    expect(body.motion).toEqual({ preset: 'fade-up', background: { clipStartSec: 2, source: 'clip', clipMediaId: 'local:clip' } })
    expect(body.audio).toEqual({ source: 'track', volume: 0.5, trackId: 'local:audio' })
    expect(body.sequence).toEqual([{ headline: 'one', photoId: 'local:beat-1' }, { headline: 'two' }])
    expect(body.previewOnly).toBe(true)
    expect(body.photoPath).toBeUndefined()
    expect(body.localMedia).toEqual({
      photo: { kind: 'IMAGE', width: 1, height: 1 },
      clip: { kind: 'VIDEO', width: 1080, height: 1920, durationSeconds: 12.5, hasAudio: true },
      audio: { kind: 'AUDIO', durationSeconds: 30 },
      'beat-1': { kind: 'IMAGE', width: 1, height: 1 },
    })

    const renderArgs = mocked(renderDraft).mock.calls[0]![0] as { localFiles: Record<string, string> }
    expect(renderArgs.localFiles).toEqual({ photo, clip, audio: music, 'beat-1': beatPhoto })

    const draftDir = (mocked(renderDraft).mock.calls[0]![0] as { outDir: string }).outDir
    const draft = JSON.parse(fs.readFileSync(path.join(draftDir, 'draft.json'), 'utf8'))
    expect(draft.committed).toBe(false)
    expect(draft.localFiles).toEqual(renderArgs.localFiles)
    expect(draft.specSha256).toMatch(/^[0-9a-f]{64}$/)
    expect(draft.request.photoId).toBe('local:photo')
  })

  it('asks the renderer to check every placement for a contact-sheet preview, and reports checks and passed', async () => {
    mocked(apiPost).mockResolvedValueOnce({ spec: { previewOnly: true, creative: {} }, readiness: {} })
    const warning = { placementKey: '1x1', rule: 'photoResolution', message: 'photo is soft', severity: 'warning' }
    fakeDraftRender({ checks: [warning] })

    const result = await previewCreativeDraft({ headline: 'Plan *it*.', photoPath: photo }, config, { projectRoot: root })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.checks).toEqual([warning])
    expect(result.passed).toBe(true)
    expect(result.nextStep).toMatch(/All placement checks passed/)
    expect(result.nextStep).not.toMatch(/NOT ready/)
    // The renderer, not this tool, decides to run the per-placement checks: it gets the previewOnly spec.
    expect(mocked(renderDraft).mock.calls[0]![0].spec.previewOnly).toBe(true)
  })

  it('a failing safe-zone check makes passed false and tells the agent to fix and re-preview, not to ask for approval', async () => {
    mocked(apiPost).mockResolvedValueOnce({ spec: { previewOnly: true, creative: {} }, readiness: {} })
    const failure = {
      placementKey: '9x16',
      rule: 'safeZone',
      message: 'safe-zone intrusion: cc-cta intrudes 96px into the bottom safe zone (reserves 430px)',
      severity: 'error',
    }
    fakeDraftRender({ checks: [failure] })

    const result = await previewCreativeDraft({ headline: 'Plan *it*.', photoPath: photo }, config, { projectRoot: root })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.passed).toBe(false)
    expect(result.checks).toEqual([failure])
    expect(result.image).toBeDefined() // still returned inline, to look at while fixing
    expect(result.nextStep).toMatch(/NOT ready for approval/)
    expect(result.nextStep).toMatch(/9x16: safe-zone intrusion/)
    expect(result.nextStep).toMatch(/do not call commit_creative_draft/)
  })

  it('a manifest with no checks (a full render that already passed them) reports passed with an empty list', async () => {
    mocked(apiPost).mockResolvedValueOnce({ spec: { creative: {} }, readiness: {} })
    fakeDraftRender()

    const result = await previewCreativeDraft({ headline: 'Edited', photoPath: photo, full: true }, config, { projectRoot: root })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.checks).toEqual([])
    expect(result.passed).toBe(true)
  })

  it('passes typeOverrides through to draft-spec and keeps them in draft.json', async () => {
    mocked(apiPost).mockResolvedValueOnce({ spec: { creative: {} }, readiness: {} })
    fakeDraftRender()
    const typeOverrides = { '9x16': [96], '1x1': [88, 1.0, -2.6] }

    const result = await previewCreativeDraft({ headline: 'Plan *it*.', photoPath: photo, typeOverrides }, config, { projectRoot: root })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    const body = mocked(apiPost).mock.calls[0]![1] as Record<string, unknown>
    expect(body.typeOverrides).toEqual(typeOverrides)
    const draft = JSON.parse(fs.readFileSync(path.join(result.draftDir, 'draft.json'), 'utf8'))
    expect(draft.request.typeOverrides).toEqual(typeOverrides)
  })

  it('sends full:true as previewOnly:false and baseCreativeId through', async () => {
    mocked(apiPost).mockResolvedValueOnce({ spec: { creative: {} }, readiness: {} })
    fakeDraftRender()
    await previewCreativeDraft({ headline: 'Edited', baseCreativeId: 'c-9', full: true }, config, { projectRoot: root })
    const body = mocked(apiPost).mock.calls[0]![1] as Record<string, unknown>
    expect(body.previewOnly).toBe(false)
    expect(body.baseCreativeId).toBe('c-9')
    expect(body.full).toBeUndefined()
    expect(body.draftDir).toBeUndefined()
  })

  it('surfaces a 422 as a structured error and does not render or create a draft folder', async () => {
    mocked(apiPost).mockRejectedValueOnce(
      new ApiError(422, 'invalid', { violations: [{ field: 'headline', ruleId: 'accent', message: 'Mark exactly one *accent phrase*' }] })
    )

    const result = await previewCreativeDraft({ headline: 'plain', photoPath: photo }, config, { projectRoot: root })

    expect(result).toMatchObject({
      ok: false,
      rendered: false,
      status: 422,
      violations: [{ field: 'headline', ruleId: 'accent', message: 'Mark exactly one *accent phrase*' }],
    })
    expect((result as { error: string }).error).toBe('[headline] Mark exactly one *accent phrase*')
    expect(renderDraft).not.toHaveBeenCalled()
    expect(fs.existsSync(path.join(root, '.conductor', 'drafts'))).toBe(false)
  })

  it('refuses when a local file is missing, before calling the API', async () => {
    const result = await previewCreativeDraft({ headline: 'H', photoPath: path.join(root, 'nope.png') }, config, { projectRoot: root })
    expect(result.ok).toBe(false)
    expect((result as { error: string }).error).toMatch(/local:photo does not exist/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('returns the render error (with the draftDir) when the local render fails', async () => {
    mocked(apiPost).mockResolvedValueOnce({ spec: { creative: {} }, readiness: {} })
    mocked(renderDraft).mockResolvedValueOnce({ ok: false, error: 'no browser' })
    const result = await previewCreativeDraft({ headline: 'H' }, config, { projectRoot: root })
    expect(result).toMatchObject({ ok: false, error: 'no browser' })
    expect((result as { draftDir?: string }).draftDir).toBeTruthy()
  })

  it('does not inline an image over 1 MB and says where it is', async () => {
    mocked(apiPost).mockResolvedValueOnce({ spec: { creative: {} }, readiness: {} })
    fakeDraftRender({ sheetBytes: Buffer.alloc(1_100_000, 1) })
    const result = await previewCreativeDraft({ headline: 'H' }, config, { projectRoot: root })
    expect(result.ok && result.image).toBeFalsy()
    expect(result.ok && result.note).toMatch(/larger than this tool can inline/)
  })
})

describe('commitCreativeDraft', () => {
  async function makeDraft(extra: Record<string, unknown> = {}): Promise<string> {
    mocked(apiPost).mockResolvedValueOnce({ spec: { creative: {} }, readiness: {} })
    fakeDraftRender()
    const preview = await previewCreativeDraft({ headline: 'Plan *it*.', photoPath: photo, ...extra }, config, { projectRoot: root })
    if (!preview.ok) throw new Error(preview.error)
    mocked(apiPost).mockReset()
    return preview.draftDir
  }

  it('uploads each local file, then creates the Creative with the real media ids, then renders', async () => {
    const draftDir = await makeDraft()
    const order: string[] = []
    mocked(uploadCreativeMedia).mockImplementation(async () => {
      order.push('upload')
      return { media: { id: 'media-1' }, warnings: [] }
    })
    mocked(createCreative).mockImplementation(async () => {
      order.push('create')
      return { id: 'creative-1', displayId: '12', version: 1 }
    })
    mocked(renderCreative).mockImplementation(async () => {
      order.push('render')
      return { ok: true, renderId: 'r1', state: 'SUCCEEDED', frames: [{ placementKey: '4x5' }] }
    })
    mocked(getCreative).mockResolvedValue({ id: 'creative-1', displayId: '12', version: 1, readiness: { ready: false } })

    const result = await commitCreativeDraft({ draftDir, source: 'Own work', licence: 'Own work' }, config, { projectRoot: root })

    expect(order).toEqual(['upload', 'create', 'render'])
    expect(uploadCreativeMedia).toHaveBeenCalledWith({ filePath: photo, source: 'Own work', licence: 'Own work', aiGenerated: undefined }, config)
    const created = mocked(createCreative).mock.calls[0]![0] as Record<string, unknown>
    expect(created.photoId).toBe('media-1')
    expect(created.headline).toBe('Plan *it*.')
    expect(updateCreative).not.toHaveBeenCalled()
    expect(mocked(renderCreative).mock.calls[0]![0]).toMatchObject({ creativeId: 'creative-1', previewOnly: false })
    expect(result).toMatchObject({ ok: true, creativeId: 'creative-1', displayId: '12', readiness: { ready: false }, uploaded: { photo: 'media-1' } })

    const draft = JSON.parse(fs.readFileSync(path.join(draftDir, 'draft.json'), 'utf8'))
    expect(draft.committed).toBe(true)
    expect(draft.creativeId).toBe('creative-1')
  })

  it('with a baseCreativeId, updates that Creative at its current version instead of creating one', async () => {
    const draftDir = await makeDraft({ baseCreativeId: 'creative-7', photoPath: undefined })
    mocked(getCreative).mockResolvedValue({ id: 'creative-7', displayId: '7', version: 4, readiness: {} })
    mocked(updateCreative).mockResolvedValue({ id: 'creative-7', version: 5 })
    mocked(renderCreative).mockResolvedValue({ ok: true, renderId: 'r2', state: 'SUCCEEDED', frames: [] })

    const result = await commitCreativeDraft({ draftDir }, config, { projectRoot: root })

    expect(createCreative).not.toHaveBeenCalled()
    expect(uploadCreativeMedia).not.toHaveBeenCalled()
    expect(updateCreative).toHaveBeenCalledWith(expect.objectContaining({ creativeId: 'creative-7', version: 4, headline: 'Plan *it*.' }), config)
    expect(result.creativeId).toBe('creative-7')
  })

  it('creates the Creative with the draft\'s typeOverrides', async () => {
    const typeOverrides = { '9x16': [96], '1x1': [88] }
    const draftDir = await makeDraft({ typeOverrides })
    mocked(uploadCreativeMedia).mockResolvedValue({ media: { id: 'media-1' }, warnings: [] })
    mocked(createCreative).mockResolvedValue({ id: 'creative-1', version: 1 })
    mocked(renderCreative).mockResolvedValue({ ok: true, renderId: 'r1', state: 'SUCCEEDED', frames: [] })
    mocked(getCreative).mockResolvedValue({ id: 'creative-1', version: 1 })

    await commitCreativeDraft({ draftDir }, config, { projectRoot: root })

    expect(mocked(createCreative).mock.calls[0]![0]).toMatchObject({ typeOverrides })
  })

  it('updates the base Creative with the draft\'s typeOverrides', async () => {
    const typeOverrides = { '4x5': [90, 1.0, -2.7] }
    const draftDir = await makeDraft({ baseCreativeId: 'creative-7', photoPath: undefined, typeOverrides })
    mocked(getCreative).mockResolvedValue({ id: 'creative-7', version: 4 })
    mocked(updateCreative).mockResolvedValue({ id: 'creative-7', version: 5 })
    mocked(renderCreative).mockResolvedValue({ ok: true, renderId: 'r2', state: 'SUCCEEDED', frames: [] })

    await commitCreativeDraft({ draftDir }, config, { projectRoot: root })

    expect(mocked(updateCreative).mock.calls[0]![0]).toMatchObject({ creativeId: 'creative-7', version: 4, typeOverrides })
  })

  it('refuses a second commit of the same draft', async () => {
    const draftDir = await makeDraft()
    mocked(uploadCreativeMedia).mockResolvedValue({ media: { id: 'media-1' }, warnings: [] })
    mocked(createCreative).mockResolvedValue({ id: 'creative-1' })
    mocked(renderCreative).mockResolvedValue({ ok: true, renderId: 'r', state: 'SUCCEEDED', frames: [] })
    mocked(getCreative).mockResolvedValue({ id: 'creative-1', readiness: {} })

    await commitCreativeDraft({ draftDir }, config, { projectRoot: root })
    await expect(commitCreativeDraft({ draftDir }, config, { projectRoot: root })).rejects.toThrow(/already committed/)

    expect(createCreative).toHaveBeenCalledTimes(1)
    expect(uploadCreativeMedia).toHaveBeenCalledTimes(1)
  })

  it('keeps the draft committed (no duplicate on retry) when only the render fails', async () => {
    const draftDir = await makeDraft()
    mocked(uploadCreativeMedia).mockResolvedValue({ media: { id: 'media-1' }, warnings: [] })
    mocked(createCreative).mockResolvedValue({ id: 'creative-1' })
    mocked(renderCreative).mockResolvedValue({ ok: false, error: 'no browser', frames: [] })
    mocked(getCreative).mockResolvedValue({ id: 'creative-1', readiness: {} })

    const result = await commitCreativeDraft({ draftDir }, config, { projectRoot: root })
    expect(result.ok).toBe(false)
    expect(result.renderError).toBe('no browser')
    expect(result.nextStep).toMatch(/render_creative/)
    await expect(commitCreativeDraft({ draftDir }, config, { projectRoot: root })).rejects.toThrow(/already committed/)
  })

  it('does not re-upload a file a failed earlier commit already uploaded', async () => {
    const draftDir = await makeDraft()
    mocked(uploadCreativeMedia).mockResolvedValue({ media: { id: 'media-1' }, warnings: [] })
    mocked(createCreative).mockRejectedValueOnce(new Error('boom'))
    await expect(commitCreativeDraft({ draftDir }, config, { projectRoot: root })).rejects.toThrow('boom')

    mocked(createCreative).mockResolvedValue({ id: 'creative-1' })
    mocked(renderCreative).mockResolvedValue({ ok: true, renderId: 'r', state: 'SUCCEEDED', frames: [] })
    mocked(getCreative).mockResolvedValue({ id: 'creative-1', readiness: {} })
    await commitCreativeDraft({ draftDir }, config, { projectRoot: root })
    expect(uploadCreativeMedia).toHaveBeenCalledTimes(1)
  })

  it('errors clearly when the folder has no draft.json', async () => {
    await expect(commitCreativeDraft({ draftDir: root }, config, { projectRoot: root })).rejects.toThrow(/No draft\.json/)
  })
})

describe('pruneDrafts', () => {
  function makeDraftDir(name: string, draft: Record<string, unknown> | null): string {
    const dir = path.join(root, '.conductor', 'drafts', name)
    fs.mkdirSync(dir, { recursive: true })
    if (draft) fs.writeFileSync(path.join(dir, 'draft.json'), JSON.stringify(draft))
    return dir
  }

  it('removes committed and 14+ day old drafts, keeps fresh ones and folders without a draft.json', async () => {
    const now = new Date('2026-10-03T12:00:00Z')
    const committed = makeDraftDir('a-committed', { createdAt: now.toISOString(), committed: true })
    const old = makeDraftDir('b-old', { createdAt: '2026-09-10T00:00:00Z', committed: false })
    const fresh = makeDraftDir('c-fresh', { createdAt: '2026-10-01T00:00:00Z', committed: false })
    const foreign = makeDraftDir('d-foreign', null)

    const removed = await pruneDrafts(root, now)

    expect(removed.sort()).toEqual([committed, old].sort())
    expect(fs.existsSync(committed)).toBe(false)
    expect(fs.existsSync(old)).toBe(false)
    expect(fs.existsSync(fresh)).toBe(true)
    expect(fs.existsSync(foreign)).toBe(true)
  })

  it('never touches anything outside .conductor/drafts, including through a symlink', async () => {
    const outside = path.join(root, 'important')
    fs.mkdirSync(outside)
    fs.writeFileSync(path.join(outside, 'draft.json'), JSON.stringify({ createdAt: '2020-01-01T00:00:00Z', committed: true }))
    fs.writeFileSync(path.join(outside, 'keep.txt'), 'keep')
    const siblingDraft = path.join(root, '.conductor', 'other')
    fs.mkdirSync(siblingDraft, { recursive: true })
    fs.writeFileSync(path.join(siblingDraft, 'draft.json'), JSON.stringify({ createdAt: '2020-01-01T00:00:00Z', committed: true }))
    const drafts = path.join(root, '.conductor', 'drafts')
    fs.mkdirSync(drafts, { recursive: true })
    fs.symlinkSync(outside, path.join(drafts, 'link-to-outside'))

    const removed = await pruneDrafts(root)

    expect(removed).toEqual([])
    expect(fs.existsSync(path.join(outside, 'keep.txt'))).toBe(true)
    expect(fs.existsSync(siblingDraft)).toBe(true)
  })

  it('is a no-op when there is no drafts folder', async () => {
    expect(await pruneDrafts(path.join(root, 'nothing-here'))).toEqual([])
  })

  it('runs when either tool runs', async () => {
    const old = makeDraftDir('old', { createdAt: '2020-01-01T00:00:00Z', committed: false })
    mocked(apiPost).mockResolvedValueOnce({ spec: { creative: {} }, readiness: {} })
    fakeDraftRender()
    await previewCreativeDraft({ headline: 'H' }, config, { projectRoot: root })
    expect(fs.existsSync(old)).toBe(false)

    const old2 = makeDraftDir('old2', { createdAt: '2020-01-01T00:00:00Z', committed: false })
    await expect(commitCreativeDraft({ draftDir: root }, config, { projectRoot: root })).rejects.toThrow()
    expect(fs.existsSync(old2)).toBe(false)
  })
})
