import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../mcp/api.js', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../mcp/api.js')>()),
  apiGet: vi.fn(),
}))

import { ApiError, apiGet } from '../mcp/api.js'
import { resolveCreativeId } from '../lib/creative-id.js'

const config = { apiKey: 'k', apiUrl: 'http://x', projectId: 'p1', projectName: '', email: '' }
const uuid = '5fdf54dd-c832-4c7c-8266-8af9ff781949'

describe('resolveCreativeId', () => {
  beforeEach(() => {
    vi.mocked(apiGet).mockReset()
  })

  it('passes a UUID through without a lookup', async () => {
    await expect(resolveCreativeId(uuid, config)).resolves.toBe(uuid)
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('resolves a display id like 12b, case-insensitively', async () => {
    vi.mocked(apiGet).mockResolvedValue([
      { id: 'a', displayId: '12a' },
      { id: 'b', displayId: '12b' },
    ])
    await expect(resolveCreativeId('12B', config)).resolves.toBe('b')
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/p1/marketing/creatives', config)
  })

  it('says plainly when no Creative has that display id', async () => {
    vi.mocked(apiGet).mockResolvedValue([{ id: 'a', displayId: '12a' }])
    await expect(resolveCreativeId('9z', config)).rejects.toThrow(/No Creative 9z/)
  })

  it.each([401, 403])('explains that a display id needs a user key when the list returns %i', async (status) => {
    vi.mocked(apiGet).mockRejectedValue(new ApiError(status, 'nope'))
    await expect(resolveCreativeId('12a', config)).rejects.toThrow(
      "Resolve '12a' needs a user API key; in a workflow pass the Creative's id (UUID) instead."
    )
  })

  it('rethrows other list errors unchanged', async () => {
    vi.mocked(apiGet).mockRejectedValue(new ApiError(500, 'down'))
    await expect(resolveCreativeId('12a', config)).rejects.toThrow('down')
  })
})
