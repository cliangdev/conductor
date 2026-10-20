import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  FLUSH_INTERVAL_MS,
  bufferedCount,
  enqueue,
  flush,
  navigation,
  normalizePath,
  parseServerTiming,
  recordApiCall,
  resetTelemetry,
  setTelemetryEnabled,
  startNavigation,
} from '@/lib/telemetry'
import { markPageReady } from '@/lib/perf'

const UUID = '3f2b8a52-1c4d-4e6f-9a7b-0123456789ab'

describe('normalizePath', () => {
  it('strips origin and query and templates ids', () => {
    expect(normalizePath(`https://api.example.com/api/v2/projects/${UUID}/work-items/COND-123?x=1#h`)).toBe(
      '/api/v2/projects/:id/work-items/:id',
    )
    expect(normalizePath(`/app/projects/${UUID}/engineering/issues/ABC-7`)).toBe(
      '/app/projects/:id/engineering/issues/:id',
    )
    expect(normalizePath('/api/v1/items/42/comments')).toBe('/api/v1/items/:id/comments')
  })

  it('leaves ordinary segments alone', () => {
    expect(normalizePath('/app/projects')).toBe('/app/projects')
    expect(normalizePath('/api/v1/health')).toBe('/api/v1/health')
  })
})

describe('parseServerTiming', () => {
  it('reads db duration, query count and app duration', () => {
    expect(
      parseServerTiming('db;dur=12;desc="7 queries", pool;dur=1, sign;dur=0, gcs;dur=3, app;dur=45'),
    ).toEqual({ dbMs: 12, dbQueries: 7, serverMs: 45 })
  })

  it('tolerates missing or malformed headers', () => {
    expect(parseServerTiming(null)).toEqual({})
    expect(parseServerTiming('')).toEqual({})
    expect(parseServerTiming('db;dur=abc, app')).toEqual({})
  })
})

describe('buffer and flush', () => {
  const beacon = vi.fn(() => true)

  beforeEach(() => {
    vi.useFakeTimers()
    resetTelemetry()
    setTelemetryEnabled(true)
    beacon.mockClear()
    Object.defineProperty(navigator, 'sendBeacon', { value: beacon, configurable: true })
  })

  afterEach(() => {
    resetTelemetry()
    setTelemetryEnabled(null)
    vi.useRealTimers()
  })

  async function lastBody() {
    const calls = beacon.mock.calls as unknown as [string, Blob][]
    const [url, blob] = calls[calls.length - 1]
    return { url, body: JSON.parse(await blob.text()) }
  }

  it('is a no-op when disabled (the default under test)', () => {
    setTelemetryEnabled(null)
    enqueue({ type: 'api_call', path: '/x' })
    expect(bufferedCount()).toBe(0)
  })

  it('flushes every 5 seconds via sendBeacon', async () => {
    enqueue({ type: 'web_vital', name: 'LCP', value: 1200 })
    expect(beacon).not.toHaveBeenCalled()
    vi.advanceTimersByTime(FLUSH_INTERVAL_MS)
    expect(beacon).toHaveBeenCalledTimes(1)
    const { url, body } = await lastBody()
    expect(url).toBe('/api/telemetry')
    expect(body.samples).toEqual([{ type: 'web_vital', name: 'LCP', value: 1200 }])
    expect(bufferedCount()).toBe(0)
  })

  it('flushes when the page is hidden', () => {
    enqueue({ type: 'web_vital', name: 'CLS', value: 1 })
    Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true })
    document.dispatchEvent(new Event('visibilitychange'))
    expect(beacon).toHaveBeenCalledTimes(1)
    Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true })
  })

  it('splits large buffers into beacons of at most 100 samples', async () => {
    for (let i = 0; i < 150; i++) enqueue({ type: 'web_vital', name: 'n', value: i })
    flush()
    expect(beacon).toHaveBeenCalledTimes(2)
    expect((await lastBody()).body.samples).toHaveLength(50)
  })

  it('records api_call with server timing and counts calls for the navigation', async () => {
    startNavigation()
    recordApiCall({
      path: `/api/v2/projects/${UUID}/work-items/ABC-1?x=1`,
      method: 'GET',
      status: 200,
      clientMs: 80.4,
      serverTiming: 'db;dur=10;desc="3 queries", app;dur=30',
    })
    flush()
    const { body } = await lastBody()
    expect(body.samples[0]).toMatchObject({
      type: 'api_call',
      path: '/api/v2/projects/:id/work-items/:id',
      method: 'GET',
      status: 200,
      clientMs: 80,
      serverMs: 30,
      dbMs: 10,
      dbQueries: 3,
    })
    expect(navigation.apiCalls).toBe(1)
  })

  it('tags api_call samples with how the call was made (default direct)', async () => {
    recordApiCall({ path: '/api/v1/a', method: 'GET', status: 200, clientMs: 1, serverTiming: null })
    recordApiCall({ path: '/api/v1/b', method: 'GET', status: 200, clientMs: 1, serverTiming: null, via: 'proxy' })
    flush()
    const { body } = await lastBody()
    expect(body.samples.map((s: { via: string }) => s.via)).toEqual(['direct', 'proxy'])
  })

  it('does not record the telemetry endpoint itself', () => {
    recordApiCall({ path: '/api/telemetry', method: 'POST', status: 204, clientMs: 1, serverTiming: null })
    expect(bufferedCount()).toBe(0)
  })

  it('markPageReady fires once per navigation with the api call count', async () => {
    startNavigation()
    recordApiCall({ path: '/api/v1/a', method: 'GET', status: 200, clientMs: 1, serverTiming: null })
    markPageReady('work-item-detail')
    markPageReady('work-item-detail')
    flush()
    const samples = (await lastBody()).body.samples.filter((s: { type: string }) => s.type === 'page_ready')
    expect(samples).toHaveLength(1)
    expect(samples[0]).toMatchObject({ page: 'work-item-detail', apiCalls: 1 })

    startNavigation()
    markPageReady('work-item-detail')
    flush()
    const again = (await lastBody()).body.samples.filter((s: { type: string }) => s.type === 'page_ready')
    expect(again).toHaveLength(1)
  })
})
