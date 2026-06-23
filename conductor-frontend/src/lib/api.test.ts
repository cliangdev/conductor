import { describe, it, expect, vi, afterEach } from 'vitest'
import { apiPost, apiErrorMessage, type ApiError } from './api'

function problemResponse(status: number, body: unknown) {
  return {
    ok: false,
    status,
    headers: { get: () => 'application/problem+json' },
    json: async () => body,
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

    const err = await apiPost('/x', {}, 'tok').catch((e) => e as ApiError)
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

    const err = await apiPost('/x', {}, 'tok').catch((e) => e as ApiError)
    expect(err.fieldErrors).toEqual([{ field: 'email', message: 'must be a valid email' }])
  })

  it('falls back to an opaque message (no detail) without a leak', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      problemResponse(500, { type: 'about:blank', title: 'Internal Server Error', status: 500 }),
    ))

    const err = await apiPost('/x', {}, 'tok').catch((e) => e as ApiError)
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
