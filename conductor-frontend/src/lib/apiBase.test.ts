import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/lib/telemetry', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/telemetry')>()),
  recordApiCall: vi.fn(),
}))

import { API_VIA_STORAGE_KEY, getApiBase, getApiVia } from './apiBase'
import { apiGet, apiPost } from './api'
import { recordApiCall } from '@/lib/telemetry'

const BACKEND = 'https://backend.example.com'

function setUrl(search: string) {
  window.history.replaceState({}, '', `/app/projects${search}`)
}

function okResponse() {
  return {
    ok: true,
    status: 200,
    headers: { get: (k: string) => (k === 'server-timing' ? 'app;dur=5' : 'application/json') },
    json: async () => ({}),
  } as unknown as Response
}

beforeEach(() => {
  vi.stubEnv('NEXT_PUBLIC_API_URL', BACKEND)
  window.localStorage.clear()
  setUrl('')
  vi.mocked(recordApiCall).mockClear()
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('getApiVia', () => {
  it('defaults to direct', () => {
    expect(getApiVia()).toBe('direct')
    expect(getApiBase()).toBe(BACKEND)
  })

  it('?api=proxy switches to the proxy and persists', () => {
    setUrl('?api=proxy')
    expect(getApiVia()).toBe('proxy')
    expect(getApiBase()).toBe('/_backend')
    expect(window.localStorage.getItem(API_VIA_STORAGE_KEY)).toBe('proxy')

    // A later navigation without the param keeps the choice.
    setUrl('')
    expect(getApiVia()).toBe('proxy')
  })

  it('?api=direct reverts a persisted proxy choice', () => {
    window.localStorage.setItem(API_VIA_STORAGE_KEY, 'proxy')
    setUrl('?api=direct')
    expect(getApiVia()).toBe('direct')
    expect(window.localStorage.getItem(API_VIA_STORAGE_KEY)).toBe('direct')
    setUrl('')
    expect(getApiVia()).toBe('direct')
  })

  it('ignores unknown values', () => {
    setUrl('?api=banana')
    expect(getApiVia()).toBe('direct')
    expect(window.localStorage.getItem(API_VIA_STORAGE_KEY)).toBeNull()
  })

  it('stays direct when storage throws', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    expect(getApiVia()).toBe('direct')
    // The query param still works for this page load even though it cannot be persisted.
    setUrl('?api=proxy')
    expect(getApiVia()).toBe('proxy')
    setUrl('')
    expect(getApiVia()).toBe('direct')
  })

  it('stays direct when the build has no absolute backend URL', () => {
    vi.stubEnv('NEXT_PUBLIC_API_URL', '')
    setUrl('?api=proxy')
    expect(getApiVia()).toBe('direct')
  })
})

describe('timedFetch via apiGet/apiPost', () => {
  it('calls the backend directly by default and records via=direct', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okResponse())
    vi.stubGlobal('fetch', fetchMock)
    await apiGet('/api/v1/things?x=1', 'tok')
    expect(fetchMock.mock.calls[0][0]).toBe(`${BACKEND}/api/v1/things?x=1`)
    expect(vi.mocked(recordApiCall).mock.calls[0][0]).toMatchObject({ path: '/api/v1/things?x=1', via: 'direct' })
  })

  it('uses the same-origin proxy when enabled and records via=proxy', async () => {
    setUrl('?api=proxy')
    const fetchMock = vi.fn().mockResolvedValue(okResponse())
    vi.stubGlobal('fetch', fetchMock)
    await apiPost('/api/v1/things', { a: 1 }, 'tok')
    expect(fetchMock.mock.calls[0][0]).toBe('/_backend/api/v1/things')
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe('Bearer tok')
    expect(vi.mocked(recordApiCall).mock.calls[0][0]).toMatchObject({ path: '/api/v1/things', via: 'proxy' })
  })
})
