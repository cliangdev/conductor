import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { ChildProcess } from 'child_process'
import { EventEmitter } from 'events'

// ─── Mocks ───────────────────────────────────────────────────────────────────

vi.mock('child_process')

// Import runner once — vi.mock hoists the mock so child_process is already mocked
// when runner.ts loads its `spawn` binding.
const { runJob } = await import('../daemon/runner.js')
const mockChildProcess = vi.mocked(await import('child_process'))

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

const mockJob = {
  id: 'job_1',
  runsOn: 'conductor',
  container: { image: 'node:18' },
  env: { MY_VAR: 'hello' },
  steps: [{ name: 'Run script', run: 'node index.js' }],
}

// Helper: make a controlled fake ChildProcess
function makeControllableProcess(): {
  proc: ChildProcess
  stdout: EventEmitter
  stderr: EventEmitter
  close: (code: number) => void
} {
  const proc = new EventEmitter() as ChildProcess
  const stdout = new EventEmitter()
  const stderr = new EventEmitter()
  proc.stdout = stdout as NodeJS.ReadableStream
  proc.stderr = stderr as NodeJS.ReadableStream
  return {
    proc,
    stdout,
    stderr,
    close: (code: number) => proc.emit('close', code),
  }
}

// Helper: make a fake process that auto-closes with a given exit code (via microtask)
function makeAutoClosingProcess(exitCode: number): ChildProcess {
  const { proc, close } = makeControllableProcess()
  Promise.resolve().then(() => close(exitCode))
  return proc
}

// Helper: make a fake process that emits an error (e.g. Docker not installed)
function makeErrorProcess(): ChildProcess {
  const { proc } = makeControllableProcess()
  Promise.resolve().then(() => proc.emit('error', new Error('spawn ENOENT')))
  return proc
}

// ─── T4.1: runJob ─────────────────────────────────────────────────────────────

describe('runJob', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.unstubAllGlobals()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('returns FAILED and logs "Docker not available" when docker info fails', async () => {
    mockChildProcess.spawn.mockImplementationOnce(() => makeErrorProcess())

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const result = await runJob(mockEvent, mockJob, mockConfig)

    expect(result).toBe('FAILED')
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/internal/workflow-runs/run_abc/log-chunk',
      expect.objectContaining({
        method: 'POST',
        body: expect.stringContaining('Docker not available'),
      })
    )
  })

  it('returns FAILED when no container image is specified', async () => {
    mockChildProcess.spawn.mockImplementationOnce(() => makeAutoClosingProcess(0))

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const jobWithoutImage = { ...mockJob, container: undefined }
    const result = await runJob(mockEvent, jobWithoutImage, mockConfig)

    expect(result).toBe('FAILED')
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/internal/workflow-runs/run_abc/log-chunk',
      expect.objectContaining({
        body: expect.stringContaining('No container image'),
      })
    )
  })

  it('calls docker pull before docker run', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker run

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    await runJob(mockEvent, mockJob, mockConfig)

    const calls = mockChildProcess.spawn.mock.calls
    expect(calls[0][0]).toBe('docker')
    expect(calls[0][1]).toContain('info')
    expect(calls[1][0]).toBe('docker')
    expect(calls[1][1]).toContain('pull')
    expect(calls[1][1]).toContain('node:18')
    expect(calls[2][0]).toBe('docker')
    expect(calls[2][1]).toContain('run')
  })

  it('returns FAILED when docker pull fails', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(1)) // docker pull fails

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const result = await runJob(mockEvent, mockJob, mockConfig)

    expect(result).toBe('FAILED')
  })

  it('injects all required CONDUCTOR_* env vars in docker run command', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker run

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    await runJob(mockEvent, mockJob, mockConfig)

    const runArgs: string[] = mockChildProcess.spawn.mock.calls[2][1] as string[]
    const argsStr = runArgs.join(' ')

    expect(argsStr).toContain('CONDUCTOR_ISSUE_ID=iss_xyz')
    expect(argsStr).toContain('CONDUCTOR_PROJECT_ID=proj_123')
    expect(argsStr).toContain('CONDUCTOR_WORKFLOW_RUN_ID=run_abc')
    expect(argsStr).toContain('CONDUCTOR_API_KEY=test-api-key')
    expect(argsStr).toContain('CONDUCTOR_API_URL=http://localhost:8080')
  })

  it('injects job.env entries in docker run command', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker run

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    await runJob(mockEvent, mockJob, mockConfig)

    const runArgs: string[] = mockChildProcess.spawn.mock.calls[2][1] as string[]
    const argsStr = runArgs.join(' ')

    expect(argsStr).toContain('MY_VAR=hello')
  })

  it('streams stdout lines to POST /internal/workflow-runs/{runId}/log-chunk', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull

    const { proc: runProc, stdout, close } = makeControllableProcess()
    mockChildProcess.spawn.mockImplementationOnce(() => runProc)

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const runPromise = runJob(mockEvent, mockJob, mockConfig)

    // Drain microtask queue so docker info and pull complete and runner reaches
    // the docker run spawn; then emit stdout data and close
    for (let i = 0; i < 10; i++) await Promise.resolve()

    stdout.emit('data', Buffer.from('Build started\n'))
    stdout.emit('data', Buffer.from('Tests passing\n'))
    close(0)

    const result = await runPromise

    expect(result).toBe('SUCCESS')

    // Allow pending fetch promises to settle
    for (let i = 0; i < 5; i++) await Promise.resolve()

    const logCalls = mockFetch.mock.calls.filter(
      (c) => (c[0] as string).includes('/log-chunk')
    )
    const bodies = logCalls.map((c) => JSON.parse(c[1].body as string) as { chunk: string })
    expect(bodies.some((b) => b.chunk.includes('Build started'))).toBe(true)
    expect(bodies.some((b) => b.chunk.includes('Tests passing'))).toBe(true)
  })

  it('streams stderr lines to POST /internal/workflow-runs/{runId}/log-chunk', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull

    const { proc: runProc, stderr, close } = makeControllableProcess()
    mockChildProcess.spawn.mockImplementationOnce(() => runProc)

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const runPromise = runJob(mockEvent, mockJob, mockConfig)

    for (let i = 0; i < 10; i++) await Promise.resolve()

    stderr.emit('data', Buffer.from('Error: something went wrong\n'))
    close(0)

    await runPromise
    for (let i = 0; i < 5; i++) await Promise.resolve()

    const logCalls = mockFetch.mock.calls.filter(
      (c) => (c[0] as string).includes('/log-chunk')
    )
    const bodies = logCalls.map((c) => JSON.parse(c[1].body as string) as { chunk: string })
    expect(bodies.some((b) => b.chunk.includes('Error: something went wrong'))).toBe(true)
  })

  it('returns SUCCESS when docker run exits with code 0', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker run

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const result = await runJob(mockEvent, mockJob, mockConfig)

    expect(result).toBe('SUCCESS')
  })

  it('returns FAILED when docker run exits with non-zero code', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(1)) // docker run fails

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const result = await runJob(mockEvent, mockJob, mockConfig)

    expect(result).toBe('FAILED')
  })

  it('swallows errors from streamLogChunk (fetch failure does not throw)', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull

    const { proc: runProc, stdout, close } = makeControllableProcess()
    mockChildProcess.spawn.mockImplementationOnce(() => runProc)

    // Fetch rejects — should not propagate
    const mockFetch = vi.fn().mockRejectedValue(new Error('Network error'))
    vi.stubGlobal('fetch', mockFetch)
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    const runPromise = runJob(mockEvent, mockJob, mockConfig)

    for (let i = 0; i < 10; i++) await Promise.resolve()

    stdout.emit('data', Buffer.from('some output\n'))
    close(0)

    // Should not throw
    const result = await runPromise
    expect(result).toBe('SUCCESS')

    consoleSpy.mockRestore()
  })

  it('sends log-chunk with bearer auth header', async () => {
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker info
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull

    const { proc: runProc, stdout, close } = makeControllableProcess()
    mockChildProcess.spawn.mockImplementationOnce(() => runProc)

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const runPromise = runJob(mockEvent, mockJob, mockConfig)

    for (let i = 0; i < 10; i++) await Promise.resolve()

    stdout.emit('data', Buffer.from('line\n'))
    close(0)

    await runPromise
    for (let i = 0; i < 5; i++) await Promise.resolve()

    const logCalls = mockFetch.mock.calls.filter(
      (c) => (c[0] as string).includes('/log-chunk')
    )
    expect(logCalls.length).toBeGreaterThan(0)
    expect(logCalls[0][1].headers).toMatchObject({
      Authorization: 'Bearer test-api-key',
    })
  })
})
