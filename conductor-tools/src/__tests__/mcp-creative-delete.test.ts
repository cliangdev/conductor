import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiDelete: vi.fn(),
  ApiError: class ApiError extends Error {
    status: number
    detail?: string
    constructor(status: number, message: string, opts: { detail?: string } = {}) {
      super(message)
      this.status = status
      this.detail = opts.detail
    }
  },
}))

import { apiGet, apiDelete, ApiError } from '../mcp/api.js'
import { deleteMedia, deleteCreative } from '../mcp/tools/creative-delete.js'

const config = { projectId: 'p1' } as unknown as Config
const B = '/api/v2/projects/p1/marketing'
const CID = '11111111-1111-1111-1111-111111111111'

beforeEach(() => {
  vi.resetAllMocks()
})

describe('deleteMedia', () => {
  it('refuses without DELETE when Creatives or Posts use it', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({
      creatives: [{ displayId: '12a', name: 'Hook' }],
      posts: [{ displayId: 'MK-3', title: 'Launch' }],
    })
    const r = await deleteMedia({ mediaId: 'm1' }, config)
    expect(r['deleted']).toBe(false)
    expect(r['message']).toContain('12a "Hook"')
    expect(r['message']).toContain('MK-3 "Launch"')
    expect(apiDelete).not.toHaveBeenCalled()
  })

  it('deletes an unused item and verifies it is gone', async () => {
    vi.mocked(apiGet)
      .mockResolvedValueOnce({ creatives: [], posts: [] })
      .mockRejectedValueOnce(new ApiError(404, 'nf'))
    const r = await deleteMedia({ mediaId: 'm1' }, config)
    expect(apiDelete).toHaveBeenCalledWith(`${B}/photos/m1`, config)
    expect(r['deleted']).toBe(true)
  })

  it('surfaces a 409 race with the server detail', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce({ creatives: [], posts: [] })
    vi.mocked(apiDelete).mockRejectedValueOnce(new ApiError(409, 'x', { detail: 'Used by Creative 4a' }))
    const r = await deleteMedia({ mediaId: 'm1' }, config)
    expect(r['deleted']).toBe(false)
    expect(r['message']).toContain('Used by Creative 4a')
  })

  it('reports when the item is still readable after delete', async () => {
    vi.mocked(apiGet).mockResolvedValue({ creatives: [], posts: [] })
    const r = await deleteMedia({ mediaId: 'm1' }, config)
    expect(r['deleted']).toBe(false)
  })
})

describe('deleteCreative', () => {
  it('deletes, verifies 404 and confirms', async () => {
    vi.mocked(apiGet).mockRejectedValueOnce(new ApiError(404, 'nf'))
    const r = await deleteCreative({ creativeId: CID }, config)
    expect(apiDelete).toHaveBeenCalledWith(`${B}/creatives/${CID}`, config)
    expect(r['deleted']).toBe(true)
  })

  it('resolves a display id to the uuid', async () => {
    vi.mocked(apiGet)
      .mockResolvedValueOnce([{ id: CID, displayId: '12a' }])
      .mockRejectedValueOnce(new ApiError(404, 'nf'))
    await deleteCreative({ creativeId: '12a' }, config)
    expect(apiDelete).toHaveBeenCalledWith(`${B}/creatives/${CID}`, config)
  })

  it('surfaces a 409 for variants or a Post', async () => {
    vi.mocked(apiDelete).mockRejectedValueOnce(new ApiError(409, 'x', { detail: 'Delete variants b, c first' }))
    const r = await deleteCreative({ creativeId: CID }, config)
    expect(r['deleted']).toBe(false)
    expect(r['message']).toContain('Delete variants b, c first')
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('rethrows other errors', async () => {
    vi.mocked(apiDelete).mockRejectedValueOnce(new ApiError(403, 'forbidden'))
    await expect(deleteCreative({ creativeId: CID }, config)).rejects.toThrow('forbidden')
  })
})
