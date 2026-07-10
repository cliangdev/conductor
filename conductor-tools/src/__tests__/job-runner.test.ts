import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { ChildProcess } from 'child_process'
import { EventEmitter } from 'events'

// ─── Mocks ───────────────────────────────────────────────────────────────────

vi.mock('child_process')

const { runWorkflowJob, buildStepEnv, fillTemplate, DEFAULT_RUNNER_IMAGE } = await import(
  '../daemon/job-runner.js'
)
const mockChildProcess = vi.mocked(await import('child_process'))

// ─── Fixtures ────────────────────────────────────────────────────────────────

const mockConfig = {
  apiKey: 'test-api-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'test@example.com',
  apiUrl: 'http://localhost:8080',
  claudeCodeOauthToken: 'oauth-secret-token',
}

const mockConfigNoOauth = {
  apiKey: 'test-api-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'test@example.com',
  apiUrl: 'http://localhost:8080',
}

const mockEvent = {
  eventId: 'evt_1',
  type: 'workflow.job',
  protocol: 2 as const,
  workflowRunId: 'run_abc',
  jobId: 'job_1',
  jobRunId: 'jobrun_1',
  projectId: 'proj_123',
  workflowName: 'Weekly SEO Report',
}

function makeStep(overrides: Partial<{
  stepIndex: number
  workerJobId: string
  stepId: string
  stepName: string
  stepType: string
  prompt: string
  env: Record<string, string>
  timeoutMinutes: number
  conductorMcp: boolean
  allowedTools: string
  maxTurns: number
  inputsJson: string
  outputSchemaJson: string
}> = {}) {
  return {
    stepIndex: overrides.stepIndex ?? 0,
    workerJobId: overrides.workerJobId ?? 'jobrun_1:0',
    stepId: overrides.stepId ?? 'seo',
    stepName: overrides.stepName ?? 'SEO analysis',
    stepType: overrides.stepType ?? 'claude-code',
    ...overrides,
  }
}

function makeDispatchPayload(overrides: Partial<{
  jobRunId: string
  protocol: number
  image?: string
  env: Record<string, string>
  steps: ReturnType<typeof makeStep>[]
  runToken: string
}> = {}) {
  return {
    jobRunId: overrides.jobRunId ?? 'jobrun_1',
    protocol: overrides.protocol ?? 2,
    image: overrides.image,
    env: overrides.env ?? {},
    steps: overrides.steps ?? [makeStep()],
    runToken: overrides.runToken ?? 'run-token-secret',
    callbacks: {
      logChunkUrlTemplate: 'http://localhost:8080/internal/v1/workflow-runs/run_abc/log-chunk',
      stepCompleteUrlTemplate: 'http://localhost:8080/internal/v1/workflow-runs/run_abc/steps/{workerJobId}/complete',
    },
  }
}

// Helper: make a controlled fake ChildProcess (mirrors runner.test.ts pattern)
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

function makeAutoClosingProcess(exitCode: number): ChildProcess {
  const { proc, close } = makeControllableProcess()
  Promise.resolve().then(() => close(exitCode))
  return proc
}

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response
}

function fetchUrls(mockFetch: ReturnType<typeof vi.fn>): string[] {
  return mockFetch.mock.calls.map((c) => c[0] as string)
}

// ─── buildStepEnv ────────────────────────────────────────────────────────────

describe('buildStepEnv', () => {
  it('injects CLAUDE_CODE_OAUTH_TOKEN and never ANTHROPIC_API_KEY, even if present in payload env', () => {
    const payload = makeDispatchPayload({
      env: { ANTHROPIC_API_KEY: 'sk-ant-leaked', SOME_VAR: 'x' },
      runToken: 'rt-1',
    })
    const step = makeStep({ stepType: 'claude-code', prompt: 'Do the thing' })

    const env = buildStepEnv(
      payload,
      step,
      mockEvent,
      mockConfig,
      'http://step-complete-url',
      'http://log-chunk-url'
    )

    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBe('oauth-secret-token')
    expect(env.ANTHROPIC_API_KEY).toBeUndefined()
    expect(env.SOME_VAR).toBe('x')
    expect(env.CONDUCTOR_STEP_PROMPT).toBe('Do the thing')
    expect(env.CONDUCTOR_RUN_TOKEN).toBe('rt-1')
    expect(env.CONDUCTOR_WORKER_JOB_ID).toBe(step.workerJobId)
    expect(env.CONDUCTOR_PROJECT_ID).toBe('proj_123')
    expect(env.CONDUCTOR_WORKFLOW_RUN_ID).toBe('run_abc')
    expect(env.CONDUCTOR_JOB_ID).toBe('job_1')
    expect(env.CONDUCTOR_STEP_COMPLETE_URL).toBe('http://step-complete-url')
    expect(env.CONDUCTOR_LOG_CHUNK_URL).toBe('http://log-chunk-url')
    expect(env.CONDUCTOR_API_URL).toBe('http://localhost:8080')
    expect(env.CONDUCTOR_TIMEOUT_MINUTES).toBe('30')
    expect(env.CONDUCTOR_MCP_ENABLED).toBe('false')
  })

  it('sets CONDUCTOR_API_KEY only when conductorMcp is true', () => {
    const payload = makeDispatchPayload()
    const withMcp = makeStep({ conductorMcp: true })
    const withoutMcp = makeStep({ conductorMcp: false })

    const envWithMcp = buildStepEnv(payload, withMcp, mockEvent, mockConfig, 'u1', 'u2')
    const envWithoutMcp = buildStepEnv(payload, withoutMcp, mockEvent, mockConfig, 'u1', 'u2')

    expect(envWithMcp.CONDUCTOR_API_KEY).toBe('test-api-key')
    expect(envWithMcp.CONDUCTOR_MCP_ENABLED).toBe('true')
    expect(envWithoutMcp.CONDUCTOR_API_KEY).toBeUndefined()
    expect(envWithoutMcp.CONDUCTOR_MCP_ENABLED).toBe('false')
  })

  it('does not set claude-code-only fields for non-claude-code steps', () => {
    const payload = makeDispatchPayload()
    const step = makeStep({ stepType: 'http', prompt: 'ignored' })

    const env = buildStepEnv(payload, step, mockEvent, mockConfig, 'u1', 'u2')

    expect(env.CONDUCTOR_STEP_PROMPT).toBeUndefined()
    expect(env.CLAUDE_CODE_OAUTH_TOKEN).toBeUndefined()
  })

  it('uses the step timeoutMinutes over the default', () => {
    const payload = makeDispatchPayload()
    const step = makeStep({ timeoutMinutes: 5 })

    const env = buildStepEnv(payload, step, mockEvent, mockConfig, 'u1', 'u2')

    expect(env.CONDUCTOR_TIMEOUT_MINUTES).toBe('5')
  })
})

// ─── fillTemplate ────────────────────────────────────────────────────────────

describe('fillTemplate', () => {
  it('substitutes the workerJobId placeholder', () => {
    const result = fillTemplate('http://x/steps/{workerJobId}/complete', 'jobrun_1:0')
    expect(result).toBe('http://x/steps/jobrun_1:0/complete')
  })

  it('returns the template unchanged when there is no placeholder', () => {
    const result = fillTemplate('http://x/log-chunk', 'jobrun_1:0')
    expect(result).toBe('http://x/log-chunk')
  })
})

// ─── runWorkflowJob ──────────────────────────────────────────────────────────

describe('runWorkflowJob', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.unstubAllGlobals()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('fetches the dispatch payload with Bearer apiKey auth', async () => {
    const mockFetch = vi.fn().mockResolvedValue(jsonResponse(404, {}))
    vi.stubGlobal('fetch', mockFetch)

    await runWorkflowJob(mockEvent, mockConfig)

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/workflow-runs/run_abc/jobs/job_1/dispatch-payload',
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test-api-key' }),
      })
    )
  })

  it('404 dispatch-payload → acks the event and drops (no job-complete, no docker)', async () => {
    const mockFetch = vi.fn().mockResolvedValue(jsonResponse(404, {}))
    vi.stubGlobal('fetch', mockFetch)

    await runWorkflowJob(mockEvent, mockConfig)

    expect(mockChildProcess.spawn).not.toHaveBeenCalled()
    const urls = fetchUrls(mockFetch)
    expect(urls).toHaveLength(2)
    expect(urls[0]).toContain('/dispatch-payload')
    expect(urls[1]).toContain('/daemon/events/ack')
    const [, ackInit] = mockFetch.mock.calls[1] as [string, RequestInit]
    expect(JSON.parse(ackInit.body as string)).toEqual({ eventIds: ['evt_1'] })
  })

  it('410 dispatch-payload → acks the event and drops', async () => {
    const mockFetch = vi.fn().mockResolvedValue(jsonResponse(410, {}))
    vi.stubGlobal('fetch', mockFetch)

    await runWorkflowJob(mockEvent, mockConfig)

    expect(mockChildProcess.spawn).not.toHaveBeenCalled()
    expect(fetchUrls(mockFetch).some((u) => u.includes('/daemon/events/ack'))).toBe(true)
  })

  it('does not ack on a transient (non-404/410) dispatch-payload failure, allowing redelivery', async () => {
    const mockFetch = vi.fn().mockResolvedValue(jsonResponse(500, {}))
    vi.stubGlobal('fetch', mockFetch)

    await runWorkflowJob(mockEvent, mockConfig)

    expect(fetchUrls(mockFetch).some((u) => u.includes('/daemon/events/ack'))).toBe(false)
    expect(mockChildProcess.spawn).not.toHaveBeenCalled()
  })

  it('missing OAuth token: does not run docker, posts step-complete FAILED CLAUDE_SUBSCRIPTION_NOT_CONFIGURED, fails the job', async () => {
    const payload = makeDispatchPayload({
      steps: [makeStep({ stepType: 'claude-code', workerJobId: 'jobrun_1:0' })],
    })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn.mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull

    await runWorkflowJob(mockEvent, mockConfigNoOauth)

    // Only the docker pull ran — never a docker run for the step.
    expect(mockChildProcess.spawn).toHaveBeenCalledTimes(1)
    expect((mockChildProcess.spawn.mock.calls[0][1] as string[])).toContain('pull')

    const stepCompleteCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/steps/jobrun_1:0/complete')
    )
    expect(stepCompleteCall).toBeDefined()
    const stepBody = JSON.parse((stepCompleteCall![1] as RequestInit).body as string)
    expect(stepBody).toEqual({ status: 'FAILED', errorReason: 'CLAUDE_SUBSCRIPTION_NOT_CONFIGURED' })
    expect((stepCompleteCall![1] as RequestInit).headers).toMatchObject({
      Authorization: `Bearer ${payload.runToken}`,
    })

    const jobCompleteCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/jobs/job_1/complete')
    )
    expect(jobCompleteCall).toBeDefined()
    const jobBody = JSON.parse((jobCompleteCall![1] as RequestInit).body as string)
    expect(jobBody).toEqual({ status: 'FAILED', errorReason: 'CLAUDE_SUBSCRIPTION_NOT_CONFIGURED' })
  })

  it('non-claude-code step posts step-complete itself on success', async () => {
    const payload = makeDispatchPayload({
      steps: [makeStep({ stepType: 'http', workerJobId: 'jobrun_1:0' })],
    })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker run

    await runWorkflowJob(mockEvent, mockConfig)

    const stepCompleteCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/steps/jobrun_1:0/complete')
    )
    expect(stepCompleteCall).toBeDefined()
    const stepBody = JSON.parse((stepCompleteCall![1] as RequestInit).body as string)
    expect(stepBody).toEqual({ status: 'SUCCESS' })

    // docker run for a non-claude-code step uses the default entrypoint (no command appended)
    const runArgs = mockChildProcess.spawn.mock.calls[1][1] as string[]
    expect(runArgs).not.toContain('conductor-claude-entrypoint')
  })

  it('non-claude-code step posts step-complete FAILED on nonzero exit and fails the job', async () => {
    const payload = makeDispatchPayload({
      steps: [makeStep({ stepType: 'http', workerJobId: 'jobrun_1:0' })],
    })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(1)) // docker run fails

    await runWorkflowJob(mockEvent, mockConfig)

    const jobCompleteCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/jobs/job_1/complete')
    )
    const jobBody = JSON.parse((jobCompleteCall![1] as RequestInit).body as string)
    expect(jobBody).toEqual({ status: 'FAILED', errorReason: 'STEP_FAILED', exitCode: 1 })
  })

  it('claude-code step does not double-post step-complete on normal exit (trusts self-report)', async () => {
    const payload = makeDispatchPayload({
      steps: [makeStep({ stepType: 'claude-code', workerJobId: 'jobrun_1:0' })],
    })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker run (entrypoint self-reports)

    await runWorkflowJob(mockEvent, mockConfig)

    const stepCompleteCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/steps/jobrun_1:0/complete')
    )
    expect(stepCompleteCall).toBeUndefined()

    const runArgs = mockChildProcess.spawn.mock.calls[1][1] as string[]
    expect(runArgs).toContain('conductor-claude-entrypoint')

    const jobCompleteCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/jobs/job_1/complete')
    )
    const jobBody = JSON.parse((jobCompleteCall![1] as RequestInit).body as string)
    expect(jobBody).toEqual({ status: 'SUCCESS' })
  })

  it('first step failure stops remaining steps and fails the job', async () => {
    const payload = makeDispatchPayload({
      steps: [
        makeStep({ stepType: 'http', stepIndex: 0, workerJobId: 'jobrun_1:0' }),
        makeStep({ stepType: 'http', stepIndex: 1, workerJobId: 'jobrun_1:1' }),
      ],
    })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(1)) // step 0 fails

    await runWorkflowJob(mockEvent, mockConfig)

    // Only 2 spawn calls: pull + step 0's run. Step 1 never runs.
    expect(mockChildProcess.spawn).toHaveBeenCalledTimes(2)
  })

  it('orders calls: dispatch-payload, docker pull, step run, job-complete, then ack', async () => {
    const payload = makeDispatchPayload({
      steps: [makeStep({ stepType: 'http', workerJobId: 'jobrun_1:0' })],
    })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull
      .mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker run

    await runWorkflowJob(mockEvent, mockConfig)

    const urls = fetchUrls(mockFetch)
    const dispatchIdx = urls.findIndex((u) => u.includes('/dispatch-payload'))
    const stepCompleteIdx = urls.findIndex((u) => u.includes('/steps/'))
    const jobCompleteIdx = urls.findIndex((u) => u.includes('/jobs/job_1/complete'))
    const ackIdx = urls.findIndex((u) => u.includes('/daemon/events/ack'))

    expect(dispatchIdx).toBe(0)
    expect(stepCompleteIdx).toBeGreaterThan(dispatchIdx)
    expect(jobCompleteIdx).toBeGreaterThan(stepCompleteIdx)
    expect(ackIdx).toBeGreaterThan(jobCompleteIdx)

    // Docker: pull before run.
    expect((mockChildProcess.spawn.mock.calls[0][1] as string[])).toContain('pull')
    expect((mockChildProcess.spawn.mock.calls[1][1] as string[])).toContain('run')
  })

  it('image pull failure fails the job without running any step', async () => {
    const payload = makeDispatchPayload({ image: 'ghcr.io/cliangdev/conductor-runner:3' })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn.mockImplementationOnce(() => makeAutoClosingProcess(1)) // pull fails

    await runWorkflowJob(mockEvent, mockConfig)

    expect(mockChildProcess.spawn).toHaveBeenCalledTimes(1)
    const jobCompleteCall = mockFetch.mock.calls.find((c) =>
      (c[0] as string).includes('/jobs/job_1/complete')
    )
    const jobBody = JSON.parse((jobCompleteCall![1] as RequestInit).body as string)
    expect(jobBody).toEqual({ status: 'FAILED', errorReason: 'IMAGE_PULL_FAILED' })
  })

  it('falls back to the default runner image when payload.image is absent', async () => {
    const payload = makeDispatchPayload({ image: undefined })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn
      .mockImplementationOnce(() => makeAutoClosingProcess(0))
      .mockImplementationOnce(() => makeAutoClosingProcess(0))

    await runWorkflowJob(mockEvent, mockConfig)

    const pullArgs = mockChildProcess.spawn.mock.calls[0][1] as string[]
    expect(pullArgs).toContain(DEFAULT_RUNNER_IMAGE)
  })

  it('streams log lines to the log-chunk callback as {lines} with runToken bearer auth, redacting secrets', async () => {
    const payload = makeDispatchPayload({
      runToken: 'run-token-secret',
      steps: [makeStep({ stepType: 'http', workerJobId: 'jobrun_1:0' })],
    })
    const mockFetch = vi.fn().mockImplementation((url: string) => {
      if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
      return Promise.resolve(jsonResponse(200, {}))
    })
    vi.stubGlobal('fetch', mockFetch)
    mockChildProcess.spawn.mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull

    const { proc: runProc, stdout, close } = makeControllableProcess()
    mockChildProcess.spawn.mockImplementationOnce(() => runProc)

    const runPromise = runWorkflowJob(mockEvent, mockConfig)

    for (let i = 0; i < 10; i++) await Promise.resolve()

    stdout.emit('data', Buffer.from(`token in output: run-token-secret and key ${mockConfig.apiKey}\n`))
    close(0)

    await runPromise

    const logCalls = mockFetch.mock.calls.filter((c) => (c[0] as string).includes('/log-chunk'))
    expect(logCalls.length).toBeGreaterThan(0)
    const [, init] = logCalls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer run-token-secret')
    const body = JSON.parse(init.body as string) as { lines: string[] }
    expect(Array.isArray(body.lines)).toBe(true)
    const joined = body.lines.join('\n')
    expect(joined).not.toContain('run-token-secret')
    expect(joined).not.toContain(mockConfig.apiKey)
    expect(joined).toContain('***REDACTED***')
  })

  it('kills the container and fails the step with STEP_TIMEOUT when the step exceeds timeoutMinutes', async () => {
    vi.useFakeTimers()
    try {
      const payload = makeDispatchPayload({
        steps: [makeStep({ stepType: 'http', workerJobId: 'jobrun_1:0', timeoutMinutes: 1 })],
      })
      const mockFetch = vi.fn().mockImplementation((url: string) => {
        if (url.includes('/dispatch-payload')) return Promise.resolve(jsonResponse(200, payload))
        return Promise.resolve(jsonResponse(200, {}))
      })
      vi.stubGlobal('fetch', mockFetch)

      mockChildProcess.spawn.mockImplementationOnce(() => makeAutoClosingProcess(0)) // docker pull

      const { proc: runProc, close: closeRun } = makeControllableProcess()
      const killProc = makeAutoClosingProcess(0)
      mockChildProcess.spawn
        .mockImplementationOnce(() => runProc) // docker run — never closes on its own
        .mockImplementationOnce(() => killProc) // docker kill

      const runPromise = runWorkflowJob(mockEvent, mockConfig)

      // Let the pull resolve and the run spawn happen.
      await vi.advanceTimersByTimeAsync(0)

      // Advance past the 1-minute timeout — triggers `docker kill`.
      await vi.advanceTimersByTimeAsync(60_000)

      // Simulate the container actually dying once killed.
      closeRun(137)

      await runPromise

      const killCall = mockChildProcess.spawn.mock.calls.find(
        (c) => c[0] === 'docker' && (c[1] as string[])[0] === 'kill'
      )
      expect(killCall).toBeDefined()

      const stepCompleteCall = mockFetch.mock.calls.find((c) =>
        (c[0] as string).includes('/steps/jobrun_1:0/complete')
      )
      expect(stepCompleteCall).toBeDefined()
      const stepBody = JSON.parse((stepCompleteCall![1] as RequestInit).body as string)
      expect(stepBody).toMatchObject({ status: 'FAILED', errorReason: 'STEP_TIMEOUT' })

      const jobCompleteCall = mockFetch.mock.calls.find((c) =>
        (c[0] as string).includes('/jobs/job_1/complete')
      )
      const jobBody = JSON.parse((jobCompleteCall![1] as RequestInit).body as string)
      expect(jobBody).toMatchObject({ status: 'FAILED', errorReason: 'STEP_TIMEOUT' })
    } finally {
      vi.useRealTimers()
    }
  })
})
