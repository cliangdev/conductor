import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  ApiError: class ApiError extends Error {
    status: number
    constructor(status: number, message: string) {
      super(message)
      this.name = 'ApiError'
      this.status = status
    }
  },
}))

const findBrowserFactoryMock = vi.fn()
vi.mock('../lib/creative-browser.js', () => ({ findBrowserFactory: (...args: unknown[]) => findBrowserFactoryMock(...args) }))

import { apiGet, apiPost, ApiError } from '../mcp/api.js'
import { renderCreative } from '../lib/creative-render.js'

const config: Config = { apiKey: 'k', projectId: 'proj-1', projectName: 'P', email: 'e@x.test', apiUrl: 'https://api.test' }
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.clearAllMocks()
})

describe('renderCreative kind branching', () => {
  it('checks the creative kind via GET before deciding how to render', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'CLIP' })
    mocked(apiPost).mockResolvedValueOnce({ id: 'r1', state: 'SUCCEEDED', frames: [] })

    await renderCreative({ creativeId: 'c1' }, config)

    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/creatives/c1', config)
  })

  it('POSTs the render directly and never touches the browser for a CLIP creative', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'CLIP' })
    mocked(apiPost).mockResolvedValueOnce({
      id: 'r1',
      state: 'SUCCEEDED',
      error: null,
      frames: [
        { placementKey: '9x16', url: 'https://x.test/9x16.mp4', durationSeconds: 6, hasAudio: true, posterUrl: 'https://x.test/9x16.jpg' },
      ],
    })

    const result = await renderCreative({ creativeId: 'c1', previewOnly: false, renderer: 'mcp' }, config)

    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives/c1/renders',
      { previewOnly: false, renderer: 'mcp', workflowRunId: undefined },
      config
    )
    expect(findBrowserFactoryMock).not.toHaveBeenCalled()
    expect(result).toEqual({
      ok: true,
      renderId: 'r1',
      state: 'SUCCEEDED',
      error: null,
      frames: [
        { placementKey: '9x16', url: 'https://x.test/9x16.mp4', durationSeconds: 6, hasAudio: true, posterUrl: 'https://x.test/9x16.jpg' },
      ],
    })
  })

  it('refuses previewOnly for a CLIP creative by relaying the backend 422 unchanged', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'CLIP' })
    mocked(apiPost).mockRejectedValueOnce(new ApiError(422, 'Preview renders are not supported for CLIP creatives'))

    const result = await renderCreative({ creativeId: 'c1', previewOnly: true }, config)

    expect(result.ok).toBe(false)
    expect(result.error).toBe('Preview renders are not supported for CLIP creatives')
    expect(findBrowserFactoryMock).not.toHaveBeenCalled()
  })

  it('passes through the backend 422 message for a MOTION creative, without touching the browser', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'MOTION' })
    mocked(apiPost).mockRejectedValueOnce(new ApiError(422, 'Motion creatives render in the next release'))

    const result = await renderCreative({ creativeId: 'c2' }, config)

    expect(result.ok).toBe(false)
    expect(result.error).toBe('Motion creatives render in the next release')
    expect(result.frames).toEqual([])
    expect(findBrowserFactoryMock).not.toHaveBeenCalled()
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/creatives/c2/renders',
      { previewOnly: false, renderer: undefined, workflowRunId: undefined },
      config
    )
  })

  it('re-throws a non-ApiError from the direct POST path rather than swallowing it', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'CLIP' })
    mocked(apiPost).mockRejectedValueOnce(new TypeError('network is down'))

    await expect(renderCreative({ creativeId: 'c1' }, config)).rejects.toThrow('network is down')
  })
})
