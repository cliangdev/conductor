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
    title?: string
    constructor(status: number, message: string, opts: { code?: string; title?: string } = {}) {
      super(message)
      this.name = 'ApiError'
      this.status = status
      this.code = opts.code
      this.title = opts.title
    }
  },
}))
vi.mock('node:fs/promises', () => ({ readFile: vi.fn() }))
vi.mock('../lib/creative-render.js', () => ({ renderCreative: vi.fn() }))

import { readFile } from 'node:fs/promises'
import { apiGet, apiPost, apiPatch, putBytes, ApiError } from '../mcp/api.js'
import { renderCreative } from '../lib/creative-render.js'
import {
  getBrandKit,
  listCreatives,
  getCreative,
  createCreative,
  updateCreative,
  uploadCreativePhoto,
  renderCreativeTool,
  previewCreative,
  attachCreativeToPost,
  createExperiment,
} from '../mcp/tools/creatives.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
}

const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

// A minimal 1x1 PNG (valid IHDR header: width=1, height=1).
const PNG_1X1 = Buffer.from([
  0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
  0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
  0, 0, 0, 1, 0, 0, 0, 1,
  8, 6, 0, 0, 0, 0, 0, 0, 0,
])

beforeEach(() => {
  vi.clearAllMocks()
})

describe('get_brand_kit', () => {
  it('fetches a specific kit by id', async () => {
    mocked(apiGet).mockResolvedValue({ id: 'kit1', isDefault: true })
    const result = await getBrandKit({ kitId: 'kit1' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/brand-kits/kit1', config)
    expect(result).toEqual({ id: 'kit1', isDefault: true })
  })

  it('lists kits and picks the default when no kitId is given', async () => {
    mocked(apiGet).mockResolvedValue([{ id: 'a', isDefault: false }, { id: 'b', isDefault: true }])
    const result = await getBrandKit({}, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/brand-kits', config)
    expect(result).toEqual({ id: 'b', isDefault: true })
  })

  it('throws a clear error when the project has no kits at all', async () => {
    mocked(apiGet).mockResolvedValue([])
    await expect(getBrandKit({}, config)).rejects.toThrow(/no Brand Kit/)
  })
})

describe('list_creatives', () => {
  it('builds the query string from state and brandKitId', async () => {
    mocked(apiGet).mockResolvedValue([])
    await listCreatives({ state: 'READY', brandKitId: 'kit1' }, config)
    expect(apiGet).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives?state=READY&brandKitId=kit1',
      config
    )
  })

  it('omits the query string when no filters are given', async () => {
    mocked(apiGet).mockResolvedValue([])
    await listCreatives({}, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/creatives', config)
  })
})

describe('get_creative', () => {
  it('merges the creative, its readiness, sibling variants, performance and active experiment', async () => {
    mocked(apiGet)
      .mockResolvedValueOnce({ id: 'c12a', number: 12, displayId: '12a', activeExperimentId: 'exp-1' })
      .mockResolvedValueOnce({ ready: false, items: [] })
      .mockResolvedValueOnce([
        { id: 'c12a', number: 12, displayId: '12a' },
        { id: 'c12b', number: 12, displayId: '12b', state: 'DRAFT', headline: 'h' },
        { id: 'c9a', number: 9, displayId: '9a' },
      ])
      .mockResolvedValueOnce({
        creativeId: 'c12a',
        family: [
          { creativeId: 'c12a', label: '12a', headline: 'A', posts: 2, views: 100, engagementRate: 0.123456, avgViewPct: null, views72h: 90, byPlatform: [] },
          { creativeId: 'c12b', label: '12b', headline: 'B', posts: 1, views: 50, engagementRate: null, avgViewPct: null, views72h: null, byPlatform: [] },
        ],
      })
      .mockResolvedValueOnce({ id: 'exp-1', state: 'RUNNING', metric: 'views', windowHours: 72, summary: null })

    const result = await getCreative({ creativeId: 'c12a' }, config)

    expect(result['readiness']).toEqual({ ready: false, items: [] })
    expect(result['variants']).toEqual([{ id: 'c12b', displayId: '12b', state: 'DRAFT', headline: 'h' }])
    expect(apiGet).toHaveBeenNthCalledWith(5, '/api/v2/projects/proj-1/marketing/experiments/exp-1', config)
    expect(result['experiment']).toMatchObject({ id: 'exp-1', state: 'RUNNING' })
    const performance = result['performance'] as Array<Record<string, unknown>>
    expect(performance).toHaveLength(2)
    expect(performance[0]!['engagementRate']).toBe(0.1235)
    expect(performance[0]).not.toHaveProperty('byPlatform')
  })

  it('falls back to listing experiments by creativeId when there is no activeExperimentId', async () => {
    mocked(apiGet)
      .mockResolvedValueOnce({ id: 'c12a', number: 12, displayId: '12a', activeExperimentId: null })
      .mockResolvedValueOnce({ ready: true, items: [] })
      .mockResolvedValueOnce([{ id: 'c12a', number: 12, displayId: '12a' }])
      .mockResolvedValueOnce({ creativeId: 'c12a', family: [] })
      .mockResolvedValueOnce([{ id: 'exp-old', state: 'DECIDED', winnerCreativeId: 'c12a' }])

    const result = await getCreative({ creativeId: 'c12a' }, config)

    expect(apiGet).toHaveBeenNthCalledWith(5, '/api/v2/projects/proj-1/marketing/experiments?creativeId=c12a', config)
    expect(result['experiment']).toMatchObject({ id: 'exp-old', state: 'DECIDED', winnerCreativeId: 'c12a' })
  })

  it('reports a null experiment when the family has never run one', async () => {
    mocked(apiGet)
      .mockResolvedValueOnce({ id: 'c12a', number: 12, displayId: '12a' })
      .mockResolvedValueOnce({ ready: true, items: [] })
      .mockResolvedValueOnce([{ id: 'c12a', number: 12, displayId: '12a' }])
      .mockResolvedValueOnce({ creativeId: 'c12a', family: [] })
      .mockResolvedValueOnce([])

    const result = await getCreative({ creativeId: 'c12a' }, config)

    expect(result['experiment']).toBeNull()
  })
})

describe('create_experiment', () => {
  it('POSTs creativeId/metric/windowHours to the experiments collection', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'exp-1', state: 'RUNNING' })

    await createExperiment({ creativeId: 'c12a', metric: 'avg_view_pct', windowHours: 48 }, config)

    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/experiments',
      { creativeId: 'c12a', metric: 'avg_view_pct', windowHours: 48 },
      config
    )
  })

  it('propagates a 409 when the family already has a RUNNING experiment', async () => {
    mocked(apiPost).mockRejectedValue(new ApiError(409, 'Creative 12a already has a RUNNING experiment'))

    await expect(createExperiment({ creativeId: 'c12a' }, config)).rejects.toMatchObject({ status: 409 })
  })
})

describe('create_creative', () => {
  it('POSTs to the creatives collection when no variantOf is given', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'c1' })
    await createCreative({ headline: '*hook*', layout: 'stacked' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives',
      { headline: '*hook*', layout: 'stacked' },
      config
    )
  })

  it('POSTs to the variants endpoint, with only headline/name, when variantOf is given', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'c12b' })
    await createCreative({ variantOf: 'c12a', headline: '*new hook*', layout: 'ignored' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives/c12a/variants',
      { headline: '*new hook*', name: undefined },
      config
    )
  })

  it('reformats a 422 violations body into one readable message', async () => {
    const body = JSON.stringify({
      message: 'invalid',
      violations: [
        { field: 'headline', ruleId: 'accentPhrase', message: 'needs exactly one accent phrase' },
        { field: 'body', ruleId: 'rule-1', message: 'must not contain "!"' },
      ],
    })
    mocked(apiPost).mockRejectedValue(new ApiError(422, body))

    await expect(createCreative({ headline: 'no phrase' }, config)).rejects.toThrow(
      '[headline] needs exactly one accent phrase; [body] must not contain "!"'
    )
  })
})

describe('update_creative', () => {
  it('PATCHes with the version and every other field, excluding creativeId from the body', async () => {
    mocked(apiPatch).mockResolvedValue({ id: 'c1', version: 2 })
    await updateCreative({ creativeId: 'c1', version: 1, headline: '*new*' }, config)
    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives/c1',
      { version: 1, headline: '*new*' },
      config
    )
  })

  it('passes a plain-text 409 conflict message through unchanged', async () => {
    mocked(apiPatch).mockRejectedValue(new ApiError(409, 'stale version: expected 2, was 3'))
    await expect(updateCreative({ creativeId: 'c1', version: 2 }, config)).rejects.toThrow(
      'stale version: expected 2, was 3'
    )
  })
})

describe('upload_creative_photo', () => {
  it('reads dimensions from a local file, mints, uploads bytes and confirms', async () => {
    mocked(readFile).mockResolvedValue(PNG_1X1)
    mocked(apiPost)
      .mockResolvedValueOnce({ id: 'photo1', uploadUrl: 'https://bucket.test/signed/photo1' })
      .mockResolvedValueOnce({ id: 'photo1', uploadStatus: 'UPLOADED' })

    const result = await uploadCreativePhoto(
      { filePath: '/tmp/hero.png', source: 'Own work', licence: 'Own work', aiGenerated: false },
      config
    )

    expect(apiPost).toHaveBeenNthCalledWith(
      1,
      '/api/v2/projects/proj-1/marketing/photos',
      {
        label: 'hero.png',
        contentType: 'image/png',
        sizeBytes: PNG_1X1.byteLength,
        width: 1,
        height: 1,
        source: 'Own work',
        licence: 'Own work',
        aiGenerated: false,
      },
      config
    )
    expect(putBytes).toHaveBeenCalledWith('https://bucket.test/signed/photo1', 'image/png', expect.anything())
    expect(apiPost).toHaveBeenNthCalledWith(
      2,
      '/api/v2/projects/proj-1/marketing/photos/photo1/confirm',
      { sizeBytes: PNG_1X1.byteLength },
      config
    )
    expect(result).toEqual({ id: 'photo1', uploadStatus: 'UPLOADED' })
  })

  it('throws when neither filePath nor url is given', async () => {
    await expect(uploadCreativePhoto({}, config)).rejects.toThrow(/Pass filePath/)
  })

  it('throws a clear error when the bytes are not a recognizable image', async () => {
    mocked(readFile).mockResolvedValue(Buffer.from([1, 2, 3, 4]))
    await expect(uploadCreativePhoto({ filePath: '/tmp/not-an-image.bin' }, config)).rejects.toThrow(
      /Could not read/
    )
  })
})

describe('render_creative', () => {
  it('returns the render result with a "look at it" next step on success', async () => {
    mocked(renderCreative).mockResolvedValue({
      ok: true,
      renderId: 'r1',
      state: 'SUCCEEDED',
      frames: [{ placementKey: '9x16', url: 'https://x.test/9x16.png' }],
    })

    const result = await renderCreativeTool({ creativeId: 'c1', previewOnly: false }, config)

    expect(renderCreative).toHaveBeenCalledWith(
      { creativeId: 'c1', previewOnly: false, renderer: 'mcp', workflowRunId: undefined },
      config
    )
    expect(result['nextStep']).toMatch(/preview_creative/)
  })

  it('points back at the error on failure instead of preview_creative', async () => {
    mocked(renderCreative).mockResolvedValue({ ok: false, error: 'photo failed to load', frames: [] })
    const result = await renderCreativeTool({ creativeId: 'c1' }, config)
    expect(result['nextStep']).toMatch(/error/)
  })
})

describe('preview_creative', () => {
  const fetchMock = vi.fn()
  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock)
    fetchMock.mockReset()
  })

  // previewCreative reads the creative (for its version) and its renders.
  function serve(version: number, renders: unknown[]) {
    mocked(apiGet).mockImplementation(async (path: string) =>
      (path.endsWith('/renders') ? renders : { id: 'c1', version }) as never
    )
  }
  const jpeg = {
    ok: true,
    headers: { get: (name: string) => (name === 'content-type' ? 'image/jpeg' : null) },
    arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer,
  }

  it('downloads the sheet of the latest render made from the current version', async () => {
    serve(3, [
      { id: 'r2', state: 'SUCCEEDED', creativeVersion: 3, frames: [{ placementKey: '9x16', url: 'https://x.test/9x16.jpg', sizeBytes: 100 }] },
      { id: 'r1', state: 'SUCCEEDED', creativeVersion: 3, frames: [{ placementKey: 'sheet', url: 'https://x.test/sheet.jpg', sizeBytes: 100 }] },
    ])
    fetchMock.mockResolvedValue(jpeg)

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(renderCreative).not.toHaveBeenCalled()
    expect(result.renderId).toBe('r1')
    expect(result.image?.mimeType).toBe('image/jpeg')
    expect(result.url).toBe('https://x.test/sheet.jpg')
  })

  it('renders a fresh sheet when the only one was made before the creative was edited', async () => {
    serve(4, [
      { id: 'r1', state: 'SUCCEEDED', creativeVersion: 3, frames: [{ placementKey: 'sheet', url: 'https://x.test/old.jpg', sizeBytes: 100 }] },
    ])
    mocked(renderCreative).mockResolvedValue({
      ok: true, renderId: 'r2', state: 'SUCCEEDED',
      frames: [{ placementKey: 'sheet', url: 'https://x.test/new.jpg', sizeBytes: 100 }],
    })
    fetchMock.mockResolvedValue(jpeg)

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(renderCreative).toHaveBeenCalledWith({ creativeId: 'c1', previewOnly: true, renderer: 'mcp' }, config)
    expect(result.url).toBe('https://x.test/new.jpg')
    expect(result.image).toBeDefined()
  })

  it('renders a fresh sheet instead of giving up when the stored one is too large to inline', async () => {
    serve(1, [
      { id: 'r1', state: 'SUCCEEDED', creativeVersion: 1, frames: [{ placementKey: 'sheet', url: 'https://x.test/big.png', sizeBytes: 5_000_000 }] },
    ])
    mocked(renderCreative).mockResolvedValue({
      ok: true, renderId: 'r2', state: 'SUCCEEDED',
      frames: [{ placementKey: 'sheet', url: 'https://x.test/small.jpg', sizeBytes: 200_000 }],
    })
    fetchMock.mockResolvedValue(jpeg)

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(result.url).toBe('https://x.test/small.jpg')
    expect(result.image).toBeDefined()
  })

  it('falls back to image/jpeg when the download response carries no content-type header', async () => {
    serve(1, [
      { id: 'r1', state: 'SUCCEEDED', creativeVersion: 1, frames: [{ placementKey: 'sheet', url: 'https://x.test/sheet.jpg', sizeBytes: 100 }] },
    ])
    fetchMock.mockResolvedValue({ ok: true, headers: { get: () => null }, arrayBuffer: async () => new Uint8Array([1]).buffer })

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(result.image?.mimeType).toBe('image/jpeg')
  })

  it('returns the URL without an image block when even a fresh sheet is too large to inline', async () => {
    serve(1, [])
    mocked(renderCreative).mockResolvedValue({
      ok: true, renderId: 'r2', state: 'SUCCEEDED',
      frames: [{ placementKey: 'sheet', url: 'https://x.test/huge.jpg', sizeBytes: 5_000_000 }],
    })

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.image).toBeUndefined()
    expect(result.url).toBe('https://x.test/huge.jpg')
    expect(result.note).toMatch(/larger than this tool can inline/)
  })

  it('reports failure plainly when the preview render fails', async () => {
    serve(1, [])
    mocked(renderCreative).mockResolvedValue({ ok: false, error: 'no photo', renderId: 'r3', frames: [] })

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(result.image).toBeUndefined()
    expect(result.note).toMatch(/no photo/)
  })
})

describe('attach_creative_to_post', () => {
  it('uses the given renderId without listing renders', async () => {
    mocked(apiPost).mockResolvedValue({ assets: [] })
    await attachCreativeToPost({ creativeId: 'c1', workItemId: 'w1', renderId: 'r9' }, config)
    expect(apiGet).not.toHaveBeenCalled()
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives/c1/attach',
      { renderId: 'r9', workItemId: 'w1' },
      config
    )
  })

  it('falls back to the latest SUCCEEDED, non-preview render when none is given', async () => {
    mocked(apiGet).mockResolvedValue([
      { id: 'r2', state: 'RUNNING', previewOnly: false },
      { id: 'r1', state: 'SUCCEEDED', previewOnly: true },
      { id: 'r0', state: 'SUCCEEDED', previewOnly: false },
    ])
    mocked(apiPost).mockResolvedValue({ assets: [] })

    await attachCreativeToPost({ creativeId: 'c1', workItemId: 'w1' }, config)

    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives/c1/attach',
      { renderId: 'r0', workItemId: 'w1' },
      config
    )
  })

  it('throws when there is no SUCCEEDED non-preview render to fall back to', async () => {
    mocked(apiGet).mockResolvedValue([{ id: 'r1', state: 'SUCCEEDED', previewOnly: true }])
    await expect(attachCreativeToPost({ creativeId: 'c1', workItemId: 'w1' }, config)).rejects.toThrow(
      /No SUCCEEDED render/
    )
  })

  it('accepts postId as the primary param name (matching get_post_status)', async () => {
    mocked(apiPost).mockResolvedValue({ assets: [] })
    await attachCreativeToPost({ creativeId: 'c1', postId: 'w1', renderId: 'r9' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives/c1/attach',
      { renderId: 'r9', workItemId: 'w1' },
      config
    )
  })

  it('prefers postId over the deprecated workItemId alias when both are given', async () => {
    mocked(apiPost).mockResolvedValue({ assets: [] })
    await attachCreativeToPost({ creativeId: 'c1', postId: 'w-new', workItemId: 'w-old', renderId: 'r9' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives/c1/attach',
      { renderId: 'r9', workItemId: 'w-new' },
      config
    )
  })

  it('throws a clear error when neither postId nor workItemId is given', async () => {
    await expect(attachCreativeToPost({ creativeId: 'c1', renderId: 'r9' }, config)).rejects.toThrow(/postId is required/)
    expect(apiGet).not.toHaveBeenCalled()
    expect(apiPost).not.toHaveBeenCalled()
  })
})
