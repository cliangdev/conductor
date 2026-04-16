import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

// ─── Mocks ───────────────────────────────────────────────────────────────────

vi.mock('fs')
const mockFs = vi.mocked(await import('fs'))

const mockWriteDaemonState = vi.fn()
vi.mock('../daemon/state.js', () => ({
  writeDaemonState: mockWriteDaemonState,
  readDaemonState: vi.fn().mockReturnValue(null),
}))

const mockConfig = {
  apiKey: 'test-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'test@example.com',
  apiUrl: 'http://localhost:8080',
  localPath: '/home/user/myproject',
}

// ─── T3.2: startPoller ───────────────────────────────────────────────────────

describe('startPoller', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    vi.useFakeTimers()
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('starts in idle mode and polls the events endpoint with bearer auth', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ events: [] }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn()

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 1000,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
    })

    // Advance past idle interval
    await vi.advanceTimersByTimeAsync(1001)

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/projects/proj_123/daemon/events',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test-key' }),
      })
    )

    handle.stop()
  })

  it('switches to active mode when events are returned', async () => {
    const events = [{ eventId: 'evt_1', type: 'ISSUE_UPDATED', payload: { issueId: 'iss_abc' } }]
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ events }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn().mockResolvedValue(undefined)

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 1000,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
    })

    // Trigger first poll
    await vi.advanceTimersByTimeAsync(1001)

    expect(onEvent).toHaveBeenCalledWith(events)

    // In active mode, should poll again at 200ms
    mockFetch.mockResolvedValue({ ok: true, json: async () => ({ events: [] }) })
    await vi.advanceTimersByTimeAsync(201)

    // Should have polled again (total 2 calls now)
    expect(mockFetch).toHaveBeenCalledTimes(2)

    handle.stop()
  })

  it('writes pollMode=active and eventsThisSession incremented to daemon-state.json', async () => {
    const events = [
      { eventId: 'evt_1', type: 'ISSUE_UPDATED', payload: {} },
      { eventId: 'evt_2', type: 'REVIEW_SUBMITTED', payload: {} },
    ]
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ events }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn().mockResolvedValue(undefined)

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 1000,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
    })

    await vi.advanceTimersByTimeAsync(1001)

    expect(mockWriteDaemonState).toHaveBeenCalledWith(
      expect.objectContaining({
        pollMode: 'active',
        consecutiveErrors: 0,
        eventsThisSession: 2,
      })
    )

    handle.stop()
  })

  it('stays in idle mode when no events returned', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ events: [] }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn()

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 1000,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
    })

    await vi.advanceTimersByTimeAsync(1001)

    expect(mockWriteDaemonState).toHaveBeenCalledWith(
      expect.objectContaining({ pollMode: 'idle' })
    )
    expect(onEvent).not.toHaveBeenCalled()

    handle.stop()
  })

  it('reverts to idle mode after activeWindowMs with no new events', async () => {
    // First poll: returns events → active mode
    const events = [{ eventId: 'evt_1', type: 'ISSUE_UPDATED', payload: {} }]
    const mockFetch = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => ({ events }) })
      // Subsequent polls: no events
      .mockResolvedValue({ ok: true, json: async () => ({ events: [] }) })
    vi.stubGlobal('fetch', mockFetch)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn().mockResolvedValue(undefined)

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 60000,
      activeIntervalMs: 200,
      activeWindowMs: 1000, // 1s active window for test
    })

    // First poll at idle interval (60s)
    await vi.advanceTimersByTimeAsync(60001)
    // Now in active mode; advance 200ms for next poll (no events)
    await vi.advanceTimersByTimeAsync(201)
    // Advance past activeWindowMs (1000ms) — still no events
    await vi.advanceTimersByTimeAsync(1200)
    // Next active poll
    await vi.advanceTimersByTimeAsync(201)

    // After activeWindowMs expires with no events, should revert to idle
    const lastCall = mockWriteDaemonState.mock.calls[mockWriteDaemonState.mock.calls.length - 1][0]
    expect(lastCall.pollMode).toBe('idle')

    handle.stop()
  })

  it('increments consecutiveErrors on poll error and logs warning at threshold', async () => {
    const mockFetch = vi.fn().mockRejectedValue(new Error('Network error'))
    vi.stubGlobal('fetch', mockFetch)

    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn()

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 500,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
      maxConsecutiveErrors: 3,
    })

    // 3 polls → 3 consecutive errors → warning logged
    await vi.advanceTimersByTimeAsync(501)
    await vi.advanceTimersByTimeAsync(501)
    await vi.advanceTimersByTimeAsync(501)

    expect(mockWriteDaemonState).toHaveBeenCalledWith(
      expect.objectContaining({ consecutiveErrors: expect.any(Number) })
    )

    const calls = mockWriteDaemonState.mock.calls.map(c => c[0].consecutiveErrors)
    expect(calls).toContain(3)

    expect(consoleSpy).toHaveBeenCalledWith(
      expect.stringContaining('Poll endpoint returning errors')
    )

    consoleSpy.mockRestore()
    handle.stop()
  })

  it('stays in idle mode on poll error', async () => {
    const mockFetch = vi.fn().mockRejectedValue(new Error('Network error'))
    vi.stubGlobal('fetch', mockFetch)
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const consoleSpy2 = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn()

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 500,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
    })

    await vi.advanceTimersByTimeAsync(501)

    expect(mockWriteDaemonState).toHaveBeenCalledWith(
      expect.objectContaining({ pollMode: 'idle' })
    )

    consoleSpy.mockRestore()
    consoleSpy2.mockRestore()
    handle.stop()
  })

  it('stop() prevents further polling', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ events: [] }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn()

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 500,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
    })

    await vi.advanceTimersByTimeAsync(501)
    const callsAfterFirst = mockFetch.mock.calls.length

    handle.stop()

    await vi.advanceTimersByTimeAsync(2000)

    // No additional calls after stop
    expect(mockFetch).toHaveBeenCalledTimes(callsAfterFirst)
  })

  it('updates lastPollAt after every poll', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ events: [] }),
    })
    vi.stubGlobal('fetch', mockFetch)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn()

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 500,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
    })

    await vi.advanceTimersByTimeAsync(501)

    expect(mockWriteDaemonState).toHaveBeenCalledWith(
      expect.objectContaining({
        lastPollAt: expect.any(String),
      })
    )

    handle.stop()
  })

  it('resets consecutiveErrors to 0 on successful poll', async () => {
    const mockFetch = vi.fn()
      .mockRejectedValueOnce(new Error('Network error'))
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValue({ ok: true, json: async () => ({ events: [] }) })

    vi.stubGlobal('fetch', mockFetch)
    const consoleSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const consoleSpy2 = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    const { startPoller } = await import('../daemon/poller.js')
    const onEvent = vi.fn()

    const handle = startPoller(() => mockConfig, onEvent, {
      idleIntervalMs: 500,
      activeIntervalMs: 200,
      activeWindowMs: 5000,
    })

    // Two error polls
    await vi.advanceTimersByTimeAsync(501)
    await vi.advanceTimersByTimeAsync(501)
    // One success poll
    await vi.advanceTimersByTimeAsync(501)

    const lastCall = mockWriteDaemonState.mock.calls[mockWriteDaemonState.mock.calls.length - 1][0]
    expect(lastCall.consecutiveErrors).toBe(0)

    consoleSpy.mockRestore()
    consoleSpy2.mockRestore()
    handle.stop()
  })
})
