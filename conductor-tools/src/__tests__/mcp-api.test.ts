import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'
import { apiGet, ApiError, isClientError } from '../mcp/api.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}

function jsonResponse(status: number, statusText: string, body: unknown) {
  return {
    ok: false,
    status,
    statusText,
    text: async () => JSON.stringify(body),
  }
}

function textResponse(status: number, statusText: string, body: string) {
  return {
    ok: false,
    status,
    statusText,
    text: async () => body,
  }
}

beforeEach(() => {
  vi.restoreAllMocks()
})

describe('api error parsing', () => {
  it('turns a 422 RFC 7807 problem into an ApiError whose message is the detail', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(422, 'Unprocessable Content', {
          type: 'about:blank',
          title: 'Unprocessable Content',
          status: 422,
          detail: 'scheduledFor must be in the future',
          code: 'SCHEDULE_IN_PAST',
        })
      )
    )

    await expect(apiGet('/x', config)).rejects.toMatchObject({
      message: 'scheduledFor must be in the future',
      status: 422,
      code: 'SCHEDULE_IN_PAST',
      title: 'Unprocessable Content',
    })

    try {
      await apiGet('/x', config)
    } catch (err) {
      expect(err).toBeInstanceOf(ApiError)
      expect(isClientError(err)).toBe(true)
    }
  })

  it('falls back to the title when a problem body has no detail', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(422, 'Unprocessable Content', {
          type: 'about:blank',
          title: 'Unprocessable Content',
          status: 422,
        })
      )
    )

    await expect(apiGet('/x', config)).rejects.toMatchObject({ message: 'Unprocessable Content' })
  })

  it('falls back to the raw text for a 404 with a plain-text body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(textResponse(404, 'Not Found', 'work item not found')))

    await expect(apiGet('/x', config)).rejects.toMatchObject({
      message: 'work item not found',
      status: 404,
    })

    try {
      await apiGet('/x', config)
    } catch (err) {
      expect(isClientError(err)).toBe(true)
    }
  })

  it('falls back to status + statusText for a 500 with no body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(textResponse(500, 'Internal Server Error', '')))

    await expect(apiGet('/x', config)).rejects.toMatchObject({
      message: '500 Internal Server Error',
      status: 500,
    })

    try {
      await apiGet('/x', config)
    } catch (err) {
      expect(isClientError(err)).toBe(false)
    }
  })
})
