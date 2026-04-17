import { describe, it, expect, beforeEach, vi } from 'vitest'

// ─── Mocks ───────────────────────────────────────────────────────────────────

const mockFetch = vi.fn()
vi.stubGlobal('fetch', mockFetch)

const { updateRunStatus, acknowledgeEvent, completeRun } = await import(
  '../daemon/run-lifecycle.js'
)

// ─── Fixtures ─────────────────────────────────────────────────────────────────

const mockConfig = {
  apiKey: 'test-api-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'test@example.com',
  apiUrl: 'http://localhost:8080',
}

const mockEvent = {
  eventId: 'evt_1',
  type: 'ISSUE_STATUS_CHANGED',
  workflowRunId: 'run_abc',
  workflowId: 'wf_1',
  workflowName: 'CI Pipeline',
  issueId: 'iss_xyz',
  issueTitle: 'My Issue',
  projectId: 'proj_123',
  trigger: { type: 'STATUS_CHANGED', fromStatus: 'DRAFT', toStatus: 'IN_PROGRESS' },
  jobs: [],
}

// ─── updateRunStatus ──────────────────────────────────────────────────────────

describe('updateRunStatus', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue({ ok: true })
  })

  it('PATCHes the correct URL with SUCCESS status', async () => {
    await updateRunStatus('run_abc', 'SUCCESS', mockConfig)

    expect(mockFetch).toHaveBeenCalledOnce()
    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/workflow-runs/run_abc')
    expect(init.method).toBe('PATCH')
    expect(JSON.parse(init.body as string)).toEqual({ status: 'SUCCESS' })
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer test-api-key')
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('PATCHes the correct URL with FAILED status', async () => {
    await updateRunStatus('run_xyz', 'FAILED', mockConfig)

    expect(mockFetch).toHaveBeenCalledOnce()
    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/workflow-runs/run_xyz')
    expect(JSON.parse(init.body as string)).toEqual({ status: 'FAILED' })
  })

  it('swallows errors and logs to console.error', async () => {
    mockFetch.mockRejectedValue(new Error('network error'))
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    await expect(updateRunStatus('run_abc', 'SUCCESS', mockConfig)).resolves.toBeUndefined()
    expect(errorSpy).toHaveBeenCalledWith(
      '[run-lifecycle] Failed to update run status:',
      expect.any(Error)
    )

    errorSpy.mockRestore()
  })
})

// ─── acknowledgeEvent ─────────────────────────────────────────────────────────

describe('acknowledgeEvent', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue({ ok: true })
  })

  it('POSTs the correct URL with eventId in body', async () => {
    await acknowledgeEvent('proj_123', 'evt_1', mockConfig)

    expect(mockFetch).toHaveBeenCalledOnce()
    const [url, init] = mockFetch.mock.calls[0] as [string, RequestInit]
    expect(url).toBe('http://localhost:8080/api/v1/projects/proj_123/daemon/events/ack')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ eventIds: ['evt_1'] })
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer test-api-key')
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json')
  })

  it('swallows errors and logs to console.error', async () => {
    mockFetch.mockRejectedValue(new Error('timeout'))
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    await expect(acknowledgeEvent('proj_123', 'evt_1', mockConfig)).resolves.toBeUndefined()
    expect(errorSpy).toHaveBeenCalledWith(
      '[run-lifecycle] Failed to acknowledge event:',
      expect.any(Error)
    )

    errorSpy.mockRestore()
  })
})

// ─── completeRun ──────────────────────────────────────────────────────────────

describe('completeRun', () => {
  beforeEach(() => {
    mockFetch.mockReset()
    mockFetch.mockResolvedValue({ ok: true })
  })

  it('calls updateRunStatus then acknowledgeEvent in sequence for SUCCESS', async () => {
    await completeRun(mockEvent, 'SUCCESS', mockConfig)

    expect(mockFetch).toHaveBeenCalledTimes(2)

    // First call: PATCH status
    const [url1, init1] = mockFetch.mock.calls[0] as [string, RequestInit]
    expect(url1).toBe('http://localhost:8080/api/v1/workflow-runs/run_abc')
    expect(init1.method).toBe('PATCH')
    expect(JSON.parse(init1.body as string)).toEqual({ status: 'SUCCESS' })

    // Second call: POST ack
    const [url2, init2] = mockFetch.mock.calls[1] as [string, RequestInit]
    expect(url2).toBe('http://localhost:8080/api/v1/projects/proj_123/daemon/events/ack')
    expect(init2.method).toBe('POST')
    expect(JSON.parse(init2.body as string)).toEqual({ eventIds: ['evt_1'] })
  })

  it('calls updateRunStatus then acknowledgeEvent in sequence for FAILED', async () => {
    await completeRun(mockEvent, 'FAILED', mockConfig)

    expect(mockFetch).toHaveBeenCalledTimes(2)

    const [, init1] = mockFetch.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(init1.body as string)).toEqual({ status: 'FAILED' })
  })

  it('acks the event even if updateRunStatus fails', async () => {
    // First call (PATCH) fails, second call (ack) succeeds
    mockFetch
      .mockRejectedValueOnce(new Error('patch failed'))
      .mockResolvedValueOnce({ ok: true })

    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})

    await expect(completeRun(mockEvent, 'SUCCESS', mockConfig)).resolves.toBeUndefined()

    // Both fetch calls still happened
    expect(mockFetch).toHaveBeenCalledTimes(2)

    // The ack call was the second one
    const [url2] = mockFetch.mock.calls[1] as [string, RequestInit]
    expect(url2).toBe('http://localhost:8080/api/v1/projects/proj_123/daemon/events/ack')

    errorSpy.mockRestore()
  })
})
