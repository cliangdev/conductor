import { describe, it, expect, beforeEach, vi } from 'vitest'

// ─── Mocks ───────────────────────────────────────────────────────────────────

const mockRunJob = vi.fn()
const mockCompleteRun = vi.fn()
const mockReadDaemonState = vi.fn()
const mockWriteDaemonState = vi.fn()

vi.mock('../daemon/runner.js', () => ({ runJob: mockRunJob }))
vi.mock('../daemon/run-lifecycle.js', () => ({ completeRun: mockCompleteRun }))
vi.mock('../daemon/state.js', () => ({
  readDaemonState: mockReadDaemonState,
  writeDaemonState: mockWriteDaemonState,
}))

const mockConfig = {
  apiKey: 'test-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'test@example.com',
  apiUrl: 'http://localhost:8080',
  maxConcurrentRuns: 1,
}

function makeEvent(overrides: Partial<{
  workflowRunId: string
  eventId: string
  issueId: string
  issueTitle: string
  projectId: string
  jobs: Array<{ id: string; runsOn: string }>
}> = {}) {
  return {
    eventId: overrides.eventId ?? 'evt_1',
    type: 'workflow.trigger',
    workflowRunId: overrides.workflowRunId ?? 'run_1',
    workflowId: 'wf_1',
    workflowName: 'Test Workflow',
    issueId: overrides.issueId ?? 'iss_1',
    issueTitle: overrides.issueTitle ?? 'Test Issue',
    projectId: overrides.projectId ?? 'proj_123',
    trigger: { type: 'status_change', fromStatus: 'DRAFT', toStatus: 'IN_PROGRESS' },
    jobs: overrides.jobs ?? [{ id: 'job_1', runsOn: 'self-hosted' }],
  }
}

function makeBaseState() {
  return {
    pid: 1234,
    startedAt: '2026-04-15T00:00:00.000Z',
    pollMode: 'idle' as const,
    lastPollAt: null,
    consecutiveErrors: 0,
    eventsThisSession: 0,
    activeRuns: [],
    syncQueueSize: 0,
  }
}

// ─── Tests ───────────────────────────────────────────────────────────────────

describe('RunQueue', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    mockRunJob.mockResolvedValue('SUCCESS')
    mockCompleteRun.mockResolvedValue(undefined)
    mockReadDaemonState.mockReturnValue(makeBaseState())
    mockWriteDaemonState.mockReturnValue(undefined)
  })

  // ─── T4.3.1: runs a single self-hosted job ─────────────────────────────────

  it('runs a self-hosted job and calls completeRun', async () => {
    const { RunQueue } = await import('../daemon/run-queue.js')
    const queue = new RunQueue(1)
    const event = makeEvent()

    await new Promise<void>((resolve) => {
      mockCompleteRun.mockImplementation(async () => {
        resolve()
      })
      queue.enqueue(event, () => mockConfig)
    })

    expect(mockRunJob).toHaveBeenCalledWith(event, event.jobs[0], mockConfig)
    expect(mockCompleteRun).toHaveBeenCalledWith(event, 'SUCCESS', mockConfig)
  })

  // ─── T4.3.2: skips non-self-hosted jobs ───────────────────────────────────

  it('skips jobs with runsOn != self-hosted', async () => {
    const { RunQueue } = await import('../daemon/run-queue.js')
    const queue = new RunQueue(1)

    const event = makeEvent({
      jobs: [
        { id: 'job_hosted', runsOn: 'ubuntu-latest' },
        { id: 'job_self', runsOn: 'self-hosted' },
      ],
    })

    await new Promise<void>((resolve) => {
      let callCount = 0
      mockCompleteRun.mockImplementation(async () => {
        callCount++
        if (callCount === 1) resolve()
      })
      queue.enqueue(event, () => mockConfig)
    })

    // Only the self-hosted job is run
    expect(mockRunJob).toHaveBeenCalledTimes(1)
    expect(mockRunJob).toHaveBeenCalledWith(event, { id: 'job_self', runsOn: 'self-hosted' }, mockConfig)
  })

  // ─── T4.3.3: maxConcurrentRuns=1 queues second event until first completes ─

  it('queues second event when maxConcurrentRuns=1 and first run is active', async () => {
    const { RunQueue } = await import('../daemon/run-queue.js')
    const queue = new RunQueue(1)

    let resolveFirst!: () => void
    const firstJobPromise = new Promise<void>((resolve) => {
      resolveFirst = resolve
    })

    const completionOrder: string[] = []

    // First job blocks until firstJobPromise resolves
    mockRunJob
      .mockImplementationOnce(async () => {
        await firstJobPromise
        return 'SUCCESS'
      })
      .mockImplementationOnce(async () => {
        return 'SUCCESS'
      })

    mockCompleteRun.mockImplementation(async (_event: { workflowRunId: string }) => {
      completionOrder.push(_event.workflowRunId)
    })

    const event1 = makeEvent({ workflowRunId: 'run_1', eventId: 'evt_1' })
    const event2 = makeEvent({ workflowRunId: 'run_2', eventId: 'evt_2' })

    queue.enqueue(event1, () => mockConfig)
    queue.enqueue(event2, () => mockConfig)

    // At this point, event1 is active and event2 should be queued
    expect(mockRunJob).toHaveBeenCalledTimes(1)

    // Release the first job
    resolveFirst()

    // Wait for both to complete
    await new Promise<void>((resolve) => {
      const interval = setInterval(() => {
        if (completionOrder.length >= 2) {
          clearInterval(interval)
          resolve()
        }
      }, 10)
    })

    expect(completionOrder).toEqual(['run_1', 'run_2'])
    expect(mockRunJob).toHaveBeenCalledTimes(2)
  })

  // ─── T4.3.4: maxConcurrentRuns defaults to 1 ──────────────────────────────

  it('defaults maxConcurrentRuns to 1 when constructed with no args', async () => {
    const { RunQueue } = await import('../daemon/run-queue.js')
    // Default constructor should serialize jobs
    const queue = new RunQueue()

    let resolveFirst!: () => void
    const firstBlocked = new Promise<void>((resolve) => {
      resolveFirst = resolve
    })

    mockRunJob
      .mockImplementationOnce(async () => {
        await firstBlocked
        return 'SUCCESS'
      })
      .mockImplementationOnce(async () => 'SUCCESS')

    const event1 = makeEvent({ workflowRunId: 'run_1' })
    const event2 = makeEvent({ workflowRunId: 'run_2' })

    queue.enqueue(event1, () => mockConfig)
    queue.enqueue(event2, () => mockConfig)

    // Only 1 run started at once
    expect(mockRunJob).toHaveBeenCalledTimes(1)
    resolveFirst()

    await new Promise<void>((resolve) => setTimeout(resolve, 50))
    expect(mockRunJob).toHaveBeenCalledTimes(2)
  })

  // ─── T4.3.5: daemon-state.json activeRuns updated on start/complete ────────

  it('adds activeRun entry when job starts and removes it when job completes', async () => {
    const { RunQueue } = await import('../daemon/run-queue.js')
    const queue = new RunQueue(1)

    const baseState = makeBaseState()
    mockReadDaemonState.mockReturnValue(baseState)

    const writeCalls: Array<typeof baseState & { activeRuns: unknown[] }> = []
    mockWriteDaemonState.mockImplementation((state: typeof baseState & { activeRuns: unknown[] }) => {
      writeCalls.push(JSON.parse(JSON.stringify(state)))
    })

    const event = makeEvent({ workflowRunId: 'run_99', issueTitle: 'My Issue' })

    await new Promise<void>((resolve) => {
      mockCompleteRun.mockImplementation(async () => {
        resolve()
      })
      queue.enqueue(event, () => mockConfig)
    })

    // Allow internal state cleanup to run
    await new Promise<void>((resolve) => setTimeout(resolve, 20))

    // First write: job added to activeRuns
    const addWrite = writeCalls.find((s) =>
      (s.activeRuns as Array<{ runId: string }>).some((r) => r.runId === 'run_99')
    )
    expect(addWrite).toBeDefined()
    const activeRun = (addWrite!.activeRuns as Array<{ runId: string; jobName: string; status: string }>).find(
      (r) => r.runId === 'run_99'
    )
    expect(activeRun?.jobName).toBe('job_1')
    expect(activeRun?.status).toBe('running')

    // Last write: job removed from activeRuns
    const removeWrite = writeCalls[writeCalls.length - 1]
    const stillActive = (removeWrite.activeRuns as Array<{ runId: string }>).find(
      (r) => r.runId === 'run_99'
    )
    expect(stillActive).toBeUndefined()
  })

  // ─── T4.3.6: no self-hosted jobs → no runJob calls ────────────────────────

  it('does nothing when event has no self-hosted jobs', async () => {
    const { RunQueue } = await import('../daemon/run-queue.js')
    const queue = new RunQueue(1)

    const event = makeEvent({
      jobs: [{ id: 'job_1', runsOn: 'ubuntu-latest' }],
    })

    queue.enqueue(event, () => mockConfig)

    // Give microtasks a chance to run
    await new Promise<void>((resolve) => setTimeout(resolve, 20))

    expect(mockRunJob).not.toHaveBeenCalled()
    // completeRun should not be called either since there are no self-hosted jobs
    expect(mockCompleteRun).not.toHaveBeenCalled()
  })

  // ─── T4.3.7: maxConcurrentRuns=2 runs two jobs in parallel ────────────────

  it('runs up to maxConcurrentRuns jobs in parallel', async () => {
    const { RunQueue } = await import('../daemon/run-queue.js')
    const queue = new RunQueue(2)

    let concurrentCount = 0
    let maxConcurrentObserved = 0

    let resolveFirst!: () => void
    let resolveSecond!: () => void
    const firstBlocked = new Promise<void>((r) => { resolveFirst = r })
    const secondBlocked = new Promise<void>((r) => { resolveSecond = r })

    mockRunJob
      .mockImplementationOnce(async () => {
        concurrentCount++
        maxConcurrentObserved = Math.max(maxConcurrentObserved, concurrentCount)
        await firstBlocked
        concurrentCount--
        return 'SUCCESS'
      })
      .mockImplementationOnce(async () => {
        concurrentCount++
        maxConcurrentObserved = Math.max(maxConcurrentObserved, concurrentCount)
        await secondBlocked
        concurrentCount--
        return 'SUCCESS'
      })
      .mockImplementationOnce(async () => 'SUCCESS')

    mockCompleteRun.mockResolvedValue(undefined)

    const event1 = makeEvent({ workflowRunId: 'run_1', eventId: 'evt_1' })
    const event2 = makeEvent({ workflowRunId: 'run_2', eventId: 'evt_2' })
    const event3 = makeEvent({ workflowRunId: 'run_3', eventId: 'evt_3' })

    queue.enqueue(event1, () => mockConfig)
    queue.enqueue(event2, () => mockConfig)
    queue.enqueue(event3, () => mockConfig)

    // Allow both initial runs to start
    await new Promise<void>((resolve) => setTimeout(resolve, 10))

    expect(mockRunJob).toHaveBeenCalledTimes(2)
    expect(maxConcurrentObserved).toBe(2)

    resolveFirst()
    resolveSecond()
    await new Promise<void>((resolve) => setTimeout(resolve, 50))
    expect(mockRunJob).toHaveBeenCalledTimes(3)
  })
})
