import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const apiGet = vi.fn()
vi.mock('@/lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api')>()),
  apiGet: (...args: unknown[]) => apiGet(...args),
}))

import {
  SIDEBAR_REVALIDATE_MS,
  clearAllSidebarCaches,
  fetchSidebarWorkflows,
  invalidateSidebarCache,
  subscribeSidebarCache,
} from './workflows'

const wf = (name: string) => ({ id: name, projectId: 'p1', name, enabled: true, createdAt: '', updatedAt: '' })

describe('fetchSidebarWorkflows (stale-while-revalidate)', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    apiGet.mockReset()
    clearAllSidebarCaches()
  })
  afterEach(() => vi.useRealTimers())

  it('serves a fresh list from cache with no request inside the revalidate window', async () => {
    apiGet.mockResolvedValue([wf('A')])
    await fetchSidebarWorkflows('p1', 't')
    expect(apiGet).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(SIDEBAR_REVALIDATE_MS - 1000)
    const again = await fetchSidebarWorkflows('p1', 't')
    expect(again).toEqual([wf('A')])
    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('shares one in-flight request between concurrent cold callers', async () => {
    apiGet.mockResolvedValue([wf('A')])
    await Promise.all([fetchSidebarWorkflows('p1', 't'), fetchSidebarWorkflows('p1', 't')])
    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('serves the stale list immediately after the window, revalidates once, and notifies on change', async () => {
    apiGet.mockResolvedValueOnce([wf('A')])
    await fetchSidebarWorkflows('p1', 't')
    const listener = vi.fn()
    subscribeSidebarCache('p1', listener)

    vi.advanceTimersByTime(SIDEBAR_REVALIDATE_MS + 1)
    apiGet.mockResolvedValueOnce([wf('A'), wf('B')])
    const stale = await fetchSidebarWorkflows('p1', 't')
    await fetchSidebarWorkflows('p1', 't')
    expect(stale).toEqual([wf('A')]) // not blocked on the network
    expect(apiGet).toHaveBeenCalledTimes(2) // one background revalidation, not two
    await vi.waitFor(() => expect(listener).toHaveBeenCalledTimes(1))
    expect(await fetchSidebarWorkflows('p1', 't')).toEqual([wf('A'), wf('B')])
  })

  it('refetches after an explicit invalidation', async () => {
    apiGet.mockResolvedValue([wf('A')])
    await fetchSidebarWorkflows('p1', 't')
    invalidateSidebarCache('p1')
    await fetchSidebarWorkflows('p1', 't')
    expect(apiGet).toHaveBeenCalledTimes(2)
  })
})
