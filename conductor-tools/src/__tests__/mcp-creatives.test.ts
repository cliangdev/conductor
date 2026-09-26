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
  it('merges the creative, its readiness, and sibling variants from the full list', async () => {
    mocked(apiGet)
      .mockResolvedValueOnce({ id: 'c12a', number: 12, displayId: '12a' })
      .mockResolvedValueOnce({ ready: false, items: [] })
      .mockResolvedValueOnce([
        { id: 'c12a', number: 12, displayId: '12a' },
        { id: 'c12b', number: 12, displayId: '12b', state: 'DRAFT', headline: 'h' },
        { id: 'c9a', number: 9, displayId: '9a' },
      ])

    const result = await getCreative({ creativeId: 'c12a' }, config)

    expect(result['readiness']).toEqual({ ready: false, items: [] })
    expect(result['variants']).toEqual([{ id: 'c12b', displayId: '12b', state: 'DRAFT', headline: 'h' }])
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

  it('downloads the sheet frame of the latest render that has one', async () => {
    mocked(apiGet).mockResolvedValue([
      { id: 'r2', state: 'SUCCEEDED', frames: [{ placementKey: '9x16', url: 'https://x.test/9x16.png', sizeBytes: 100 }] },
      { id: 'r1', state: 'SUCCEEDED', frames: [{ placementKey: 'sheet', url: 'https://x.test/sheet.png', sizeBytes: 100 }] },
    ])
    fetchMock.mockResolvedValue({
      ok: true,
      headers: { get: (name: string) => (name === 'content-type' ? 'image/png' : null) },
      arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer,
    })

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(renderCreative).not.toHaveBeenCalled()
    expect(result.renderId).toBe('r1')
    expect(result.image?.mimeType).toBe('image/png')
    expect(result.url).toBe('https://x.test/sheet.png')
  })

  it('falls back to image/png when the download response carries no content-type header', async () => {
    mocked(apiGet).mockResolvedValue([
      { id: 'r1', state: 'SUCCEEDED', frames: [{ placementKey: 'sheet', url: 'https://x.test/sheet.png', sizeBytes: 100 }] },
    ])
    fetchMock.mockResolvedValue({ ok: true, headers: { get: () => null }, arrayBuffer: async () => new Uint8Array([1]).buffer })

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(result.image?.mimeType).toBe('image/png')
  })

  it('renders a fresh previewOnly sheet when no render has one yet', async () => {
    mocked(apiGet).mockResolvedValue([{ id: 'r1', state: 'SUCCEEDED', frames: [{ placementKey: '9x16', url: 'x', sizeBytes: 1 }] }])
    mocked(renderCreative).mockResolvedValue({
      ok: true,
      renderId: 'r2',
      state: 'SUCCEEDED',
      frames: [{ placementKey: 'sheet', url: 'https://x.test/sheet.png', sizeBytes: 100 }],
    })
    fetchMock.mockResolvedValue({
      ok: true,
      headers: { get: (name: string) => (name === 'content-type' ? 'image/png' : null) },
      arrayBuffer: async () => new Uint8Array([1]).buffer,
    })

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(renderCreative).toHaveBeenCalledWith({ creativeId: 'c1', previewOnly: true, renderer: 'mcp' }, config)
    expect(result.renderId).toBe('r2')
    expect(result.image).toBeDefined()
  })

  it('returns the URL without an image block when the frame is too large to inline', async () => {
    mocked(apiGet).mockResolvedValue([
      { id: 'r1', state: 'SUCCEEDED', frames: [{ placementKey: 'sheet', url: 'https://x.test/sheet.png', sizeBytes: 5_000_000 }] },
    ])

    const result = await previewCreative({ creativeId: 'c1' }, config)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.image).toBeUndefined()
    expect(result.url).toBe('https://x.test/sheet.png')
    expect(result.note).toMatch(/larger than this tool can inline/)
  })

  it('reports failure plainly when the fallback preview render fails', async () => {
    mocked(apiGet).mockResolvedValue([])
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
})
