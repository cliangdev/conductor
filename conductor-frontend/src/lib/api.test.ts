import { describe, it, expect, vi, afterEach } from 'vitest'
import { apiDelete, apiGet, apiPatch, apiPost, apiPut, apiErrorMessage, type ApiError } from './api'

function problemResponse(status: number, body: unknown) {
  return {
    ok: false,
    status,
    headers: { get: () => 'application/problem+json' },
    json: async () => body,
  } as unknown as Response
}

/** A bodied 2xx — `json()` resolves, as it does for any real response with content. */
function jsonResponse(status: number, body: unknown) {
  return {
    ok: true,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body,
  } as unknown as Response
}

/** A 204: no content-type, and `json()` throws exactly as the real Response does on an empty body. */
function noContentResponse() {
  return {
    ok: true,
    status: 204,
    headers: { get: () => null },
    json: async () => {
      throw new SyntaxError('Unexpected end of JSON input')
    },
  } as unknown as Response
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('throwApiError (via apiPost)', () => {
  it('builds a typed ApiError carrying status + detail from an RFC 7807 body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      problemResponse(400, {
        type: 'about:blank',
        title: 'Bad Request',
        status: 400,
        detail: 'Invalid status transition from TODO to DONE',
      }),
    ))

    const err = await apiPost('/x', {}, 'tok').catch((e) => e as ApiError) as ApiError
    expect(err.status).toBe(400)
    expect(err.detail).toBe('Invalid status transition from TODO to DONE')
    expect(err.message).toBe('Invalid status transition from TODO to DONE')
  })

  it('captures fieldErrors when present (validation 400s)', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      problemResponse(400, {
        detail: 'Validation failed',
        fieldErrors: [{ field: 'email', message: 'must be a valid email' }],
      }),
    ))

    const err = await apiPost('/x', {}, 'tok').catch((e) => e as ApiError) as ApiError
    expect(err.fieldErrors).toEqual([{ field: 'email', message: 'must be a valid email' }])
  })

  it('falls back to an opaque message (no detail) without a leak', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      problemResponse(500, { type: 'about:blank', title: 'Internal Server Error', status: 500 }),
    ))

    const err = await apiPost('/x', {}, 'tok').catch((e) => e as ApiError) as ApiError
    expect(err.status).toBe(500)
    expect(err.detail).toBeUndefined()
    expect(err.message).toBe('Server error (500)')
  })
})

describe('apiErrorMessage', () => {
  it('returns the backend detail when present', () => {
    const err: ApiError = Object.assign(new Error('x'), { status: 409, detail: 'Already a member' })
    expect(apiErrorMessage(err, 'fallback')).toBe('Already a member')
  })

  it('returns the fallback for an opaque error (no detail)', () => {
    const err: ApiError = Object.assign(new Error('Server error (500)'), { status: 500 })
    expect(apiErrorMessage(err, 'Something went wrong')).toBe('Something went wrong')
  })

  it('returns the fallback for a network error / non-API value', () => {
    expect(apiErrorMessage(new Error('Could not reach server — please try again'), 'fb')).toBe('fb')
    expect(apiErrorMessage('weird', 'fb')).toBe('fb')
    expect(apiErrorMessage(undefined, 'fb')).toBe('fb')
  })
})

describe('empty-body (204) responses', () => {
  // [auto] apiPost resolves on 204 instead of rejecting
  it('apiPost resolves on a 204 No Content instead of rejecting', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noContentResponse()))

    await expect(apiPost('/x', {}, 'tok')).resolves.toBeUndefined()
  })

  it('apiPost still parses a normal JSON 200 body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(200, { id: 'a-1' })))

    await expect(apiPost('/x', {}, 'tok')).resolves.toEqual({ id: 'a-1' })
  })

  it('apiPut resolves on a 204 No Content instead of rejecting', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noContentResponse()))

    await expect(apiPut('/x', {}, 'tok')).resolves.toBeUndefined()
  })

  it('apiPatch keeps resolving on a 204 No Content', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noContentResponse()))

    await expect(apiPatch('/x', {}, 'tok')).resolves.toBeUndefined()
  })

  it('apiGet resolves on a 204 No Content instead of rejecting', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noContentResponse()))

    await expect(apiGet('/x', 'tok')).resolves.toBeUndefined()
  })

  it('apiDelete resolves on a 204 No Content', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(noContentResponse()))

    await expect(apiDelete('/x', 'tok')).resolves.toBeUndefined()
  })
})
