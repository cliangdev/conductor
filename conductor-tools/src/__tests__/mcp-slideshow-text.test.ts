import { describe, it, expect, vi, beforeEach } from 'vitest'
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
vi.mock('../lib/creative-render.js', () => ({ renderCreative: vi.fn(), renderViaApi: vi.fn() }))
vi.mock('../lib/media-probe.js', () => ({ probeMedia: vi.fn(), extractPoster: vi.fn() }))
vi.mock('../lib/poster-sheet.js', () => ({ composePosterSheet: vi.fn() }))

import { apiGet, apiPost, ApiError } from '../mcp/api.js'
import { getCreative } from '../mcp/tools/creatives.js'
import { updateCreativeSlideText } from '../mcp/tools/slideshow-text.js'

const config: Config = { apiKey: 'k', projectId: 'proj-1', projectName: 'P', email: 'e@x.test', apiUrl: 'https://api.test' }
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const BASE = '/api/v2/projects/proj-1/marketing/creatives'
const accepted = (over: Record<string, unknown> = {}) => ({
  revision: {
    id: 'rev-1',
    revisionNo: 3,
    status: 'PENDING',
    textDiff: [{ slide: 2, path: 'texts[0].t', old: 'Old', new: 'New' }, { slide: 2, path: 'texts[1].t', old: null, new: 'x' }, { slide: 5, path: 'title', old: 'a', new: 'b' }],
  },
  renderRun: { runId: 'run-1', status: 'RUNNING', runtime: 'target', targetName: 'byo' },
  ...over,
})

beforeEach(() => {
  vi.resetAllMocks()
  mocked(apiGet).mockResolvedValue({ version: 9 })
  mocked(apiPost).mockResolvedValue(accepted())
})

describe('update_creative_slide_text', () => {
  it('reads the version when omitted, posts path (from field) and returns the dispatch status', async () => {
    const r = await updateCreativeSlideText({ creativeId: 'c1', edits: [{ slide: 2, field: 'texts[0].t', text: 'New' }] }, config)
    expect(mocked(apiGet)).toHaveBeenCalledWith(`${BASE}/c1`, config)
    expect(mocked(apiPost)).toHaveBeenCalledWith(`${BASE}/c1/slide-text-edits`, { version: 9, edits: [{ slide: 2, path: 'texts[0].t', text: 'New' }] }, config)
    expect(r).toMatchObject({
      ok: true,
      revisionId: 'rev-1',
      status: 'PENDING',
      changedSlides: [2, 5],
      render: { runtime: 'target', targetName: 'byo', runId: 'run-1' },
    })
    expect(r['diff']).toHaveLength(3)
    expect(r['nextStep']).toMatch(/slides 2, 5 on the workspace's runtime target \(byo\)/)
    expect(r['nextStep']).toMatch(/get_creative/)
    expect(r['nextStep']).not.toMatch(/render-slides/)
  })

  it('uses the version it was given without reading the Creative', async () => {
    await updateCreativeSlideText({ creativeId: 'c1', version: 4, edits: [{ slide: 1, field: 'title', text: 'T' }] }, config)
    expect(mocked(apiGet)).not.toHaveBeenCalled()
    expect((mocked(apiPost).mock.calls[0]![1] as { version: number }).version).toBe(4)
  })

  it('on a self-hosted runtime adds the local render-slides command', async () => {
    mocked(apiPost).mockResolvedValue(accepted({ renderRun: { runId: 'run-1', status: 'QUEUED', runtime: 'self-hosted' } }))
    const r = await updateCreativeSlideText({ creativeId: 'c1', edits: [{ slide: 2, field: 'texts[0].t', text: 'New' }] }, config)
    expect(r['nextStep']).toMatch(/self-hosted daemon/)
    expect(r['nextStep']).toMatch(/No daemon running.*conductor creative render-slides rev-1 --creative c1/)
  })

  it('says so, with the local command, when no run was started', async () => {
    mocked(apiPost).mockResolvedValue(accepted({ renderRun: null }))
    const r = await updateCreativeSlideText({ creativeId: 'c1', edits: [{ slide: 2, field: 'texts[0].t', text: 'New' }] }, config)
    expect(r['render']).toBeUndefined()
    expect(r['nextStep']).toMatch(/No render run was started.*render-slides rev-1 --creative c1/)
  })

  it('clips long text and caps the diff it echoes', async () => {
    const textDiff = Array.from({ length: 25 }, (_, i) => ({ slide: 1, path: `items[${i}]`, old: 'o'.repeat(300), new: 'n' }))
    mocked(apiPost).mockResolvedValue(accepted({ revision: { id: 'rev-1', revisionNo: 1, status: 'PENDING', textDiff } }))
    const r = await updateCreativeSlideText({ creativeId: 'c1', edits: [{ slide: 1, field: 'items[0]', text: 'n' }] }, config)
    expect(r['diff']).toHaveLength(20)
    expect(r['diffOmitted']).toBe(5)
    expect(((r['diff'] as Array<{ old: string }>)[0]!.old).length).toBe(120)
  })

  it('names a stale version', async () => {
    mocked(apiPost).mockRejectedValue(new ApiError(409, 'This Creative changed since you read it'))
    await expect(updateCreativeSlideText({ creativeId: 'c1', version: 1, edits: [{ slide: 1, field: 'title', text: 'T' }] }, config)).rejects.toThrow(
      'This Creative changed since you read it — get_creative and retry'
    )
  })

  it('names an edit or render that is still running', async () => {
    mocked(apiPost).mockRejectedValue(new ApiError(409, 'A slide text edit is already rendering for this Creative.'))
    await expect(updateCreativeSlideText({ creativeId: 'c1', edits: [{ slide: 1, field: 'title', text: 'T' }] }, config)).rejects.toThrow(
      /already rendering.*pendingRevision.*Retry when it has finished/
    )
  })

  it('formats 422 violations as [field] message', async () => {
    mocked(apiPost).mockRejectedValue(
      new ApiError(422, 'invalid', { violations: [{ field: 'edits[0]', ruleId: 'slideTextEditInvalid', message: '"nope" is not an editable text field' }, { ruleId: 'slideTextEditNoop', message: 'no text changed' }] })
    )
    await expect(updateCreativeSlideText({ creativeId: 'c1', edits: [{ slide: 1, field: 'nope', text: 'T' }] }, config)).rejects.toThrow(
      '[edits[0]] "nope" is not an editable text field; [slideTextEditNoop] no text changed'
    )
  })

  it('rejects malformed edits before calling the API', async () => {
    const bad = (edits: unknown) => updateCreativeSlideText({ creativeId: 'c1', edits: edits as never }, config)
    await expect(bad([])).rejects.toThrow(/edits is required/)
    await expect(bad([{ slide: 0, field: 'title', text: 'x' }])).rejects.toThrow(/edits\[0\]\.slide/)
    await expect(bad([{ slide: 1, text: 'x' }])).rejects.toThrow(/edits\[0\]\.field/)
    await expect(bad([{ slide: 1, field: 'title', text: 3 }])).rejects.toThrow(/edits\[0\]\.text/)
    await expect(bad(Array.from({ length: 51 }, () => ({ slide: 1, field: 'title', text: 'x' })))).rejects.toThrow(/At most 50/)
    expect(mocked(apiPost)).not.toHaveBeenCalled()
  })

  it('accepts path where a model copied textFields', async () => {
    await updateCreativeSlideText({ creativeId: 'c1', version: 1, edits: [{ slide: 1, path: 'title', text: 'T' } as never] }, config)
    expect((mocked(apiPost).mock.calls[0]![1] as { edits: Array<{ path: string }> }).edits[0]!.path).toBe('title')
  })
})

describe('get_creative revisions', () => {
  const creative = (extra: Record<string, unknown>) => ({ id: 'c1', number: 1, kind: 'CAROUSEL', version: 5, ...extra })
  function mockReads(c: Record<string, unknown>) {
    mocked(apiGet).mockImplementation(async (url: string) => {
      if (url.endsWith('/readiness')) return { items: [] }
      if (url.endsWith('/performance')) return { family: [] }
      if (url.includes('/experiments')) return []
      if (url === BASE) return [c]
      return c
    })
  }

  it('names the failing checks of the latest revision by slide, ratio and rule and counts warnings', async () => {
    mockReads(
      creative({
        latestRevision: {
          id: 'rev-1',
          revisionNo: 2,
          status: 'FAILED',
          source: 'MCP',
          error: '1 layout check failed',
          createdAt: '2026-11-02T10:00:00Z',
          checks: [
            { slide: 3, ratio: '9x16', rule: 'safeZone', severity: 'error', message: 'text below the safe zone' },
            { slide: 1, ratio: '4x5', rule: 'wordsPerLine', severity: 'warning', message: 'long line' },
          ],
        },
        pendingRevision: null,
      })
    )
    const r = await getCreative({ creativeId: 'c1' }, config)
    expect(r['latestRevision']).toEqual({
      id: 'rev-1',
      revisionNo: 2,
      status: 'FAILED',
      source: 'MCP',
      error: '1 layout check failed',
      createdAt: '2026-11-02T10:00:00Z',
      failingChecks: ['slide 3 9x16 safeZone: text below the safe zone'],
      warningCount: 1,
    })
    expect(r['pendingRevision']).toBeNull()
  })

  it('points at a pending revision', async () => {
    mockReads(creative({ pendingRevision: { id: 'rev-2', status: 'PENDING', workflowRunId: 'run-9' }, latestRevision: { id: 'rev-2', revisionNo: 3, status: 'PENDING', checks: null } }))
    const r = await getCreative({ creativeId: 'c1' }, config)
    expect(r['pendingRevision']).toMatchObject({ id: 'rev-2', workflowRunId: 'run-9', note: expect.stringMatching(/rendering/) })
    expect(r['latestRevision']).toEqual({ id: 'rev-2', revisionNo: 3, status: 'PENDING' })
  })

  it('adds nothing for a Creative with no revision fields', async () => {
    mockReads(creative({}))
    const r = await getCreative({ creativeId: 'c1' }, config)
    expect('latestRevision' in r).toBe(false)
    expect('pendingRevision' in r).toBe(false)
  })
})
