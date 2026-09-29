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

const resolveFfmpegPathMock = vi.fn()
vi.mock('../lib/media-probe.js', () => ({ resolveFfmpegPath: (...args: unknown[]) => resolveFfmpegPathMock(...args) }))

import { apiGet, apiPost, ApiError } from '../mcp/api.js'
import { renderCreative } from '../lib/creative-render.js'

// loadRenderCore() resolves the render core's path relative to THIS file's own compiled location
// (dist/lib/creative-render.js -> dist/creative/job/), which does not exist when vitest runs the TS
// source directly (src/lib -> src/creative/job) — so every test below that gets past ffmpeg/kind
// routing and actually reaches loadRenderCore() sees this same "packaging bug" error rather than a
// real render. That's fine: these tests exist to prove MOTION/STILL routing and ffmpeg resolution,
// not to exercise the real Playwright/ffmpeg pipeline (creative-render-integration.test.ts and
// conductor-creative/test/job-motion.test.mjs cover that against the real built artifacts).
const PACKAGING_BUG_ERROR = /Creative render core not found/

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

  it('re-throws a non-ApiError from the direct POST path rather than swallowing it', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'CLIP' })
    mocked(apiPost).mockRejectedValueOnce(new TypeError('network is down'))

    await expect(renderCreative({ creativeId: 'c1' }, config)).rejects.toThrow('network is down')
    expect(resolveFfmpegPathMock).not.toHaveBeenCalled()
  })
})

describe('renderCreative MOTION routing', () => {
  it('resolves a local ffmpeg and attempts the local browser render, never the direct POST path', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'MOTION' })
    resolveFfmpegPathMock.mockResolvedValueOnce('/usr/local/bin/ffmpeg')

    // loadRenderCore() throws in this test environment (see the header comment above) — that's the
    // packaging-bug error, not a MOTION-specific one, and it only happens *after* ffmpeg resolution
    // and *before* apiPost would ever be reached, so seeing it here proves the routing this test cares
    // about: ffmpeg got resolved, and the direct-POST (CLIP-only) path was never taken.
    await expect(renderCreative({ creativeId: 'c2' }, config)).rejects.toThrow(PACKAGING_BUG_ERROR)

    expect(resolveFfmpegPathMock).toHaveBeenCalledTimes(1)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('returns a clear error and never resolves a browser when no ffmpeg can be found', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'MOTION' })
    resolveFfmpegPathMock.mockRejectedValueOnce(new Error('No ffmpeg could be found to process this media.'))

    const result = await renderCreative({ creativeId: 'c2' }, config)

    expect(result).toEqual({ ok: false, error: 'No ffmpeg could be found to process this media.', frames: [] })
    expect(findBrowserFactoryMock).not.toHaveBeenCalled()
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('skips ffmpeg resolution entirely for a previewOnly MOTION render (the key-moments sheet needs no encode)', async () => {
    mocked(apiGet).mockResolvedValueOnce({ kind: 'MOTION' })

    await expect(renderCreative({ creativeId: 'c2', previewOnly: true }, config)).rejects.toThrow(PACKAGING_BUG_ERROR)

    expect(resolveFfmpegPathMock).not.toHaveBeenCalled()
  })
})
