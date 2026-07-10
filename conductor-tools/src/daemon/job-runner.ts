import type { Config } from '../lib/config.js'
import { spawnAndWait, buildEnvArgs } from './runner.js'
import { acknowledgeEvent, completeJob } from './run-lifecycle.js'

// ─── Types ───────────────────────────────────────────────────────────────────

/**
 * Pointer-only daemon event for the per-job dispatch protocol ("protocol 2").
 * Carries no secrets/env — the daemon fetches the interpolated dispatch
 * payload (env, steps, run token) at pickup. Flattened top-level shape,
 * matching how `workflow.trigger` events are already delivered (see
 * WorkflowTriggerEvent in runner.ts) rather than nested under `payload`.
 */
export interface WorkflowJobEvent {
  eventId: string
  type: string
  protocol: 2
  workflowRunId: string
  jobId: string
  jobRunId: string
  projectId: string
  workflowName: string
}

export interface DispatchStep {
  stepIndex: number
  workerJobId: string
  stepId: string
  stepName: string
  stepType: string
  prompt?: string
  env?: Record<string, string>
  timeoutMinutes?: number
  conductorMcp?: boolean
  allowedTools?: string
  maxTurns?: number
  inputsJson?: string
  outputSchemaJson?: string
}

export interface DispatchPayload {
  jobRunId: string
  protocol: number
  image?: string
  env: Record<string, string>
  steps: DispatchStep[]
  runToken: string
  callbacks: {
    logChunkUrlTemplate: string
    stepCompleteUrlTemplate: string
  }
}

interface StepResult {
  status: 'SUCCESS' | 'FAILED'
  errorReason?: string
  exitCode?: number
}

// ─── Constants ───────────────────────────────────────────────────────────────

export const DEFAULT_RUNNER_IMAGE = 'ghcr.io/cliangdev/conductor-runner:3'
export const DEFAULT_TIMEOUT_MINUTES = 30
const LOG_BATCH_INTERVAL_MS = 2000

/** Extra headroom for the daemon's kill backstop on claude-code steps: the
 * entrypoint enforces the real timeout itself (SIGTERM→SIGKILL, then
 * self-reports CLAUDE_TIMEOUT); killing at exactly the same moment would race
 * that self-report. */
const CLAUDE_TIMEOUT_GRACE_MS = 2 * 60_000

/** conductor-claude-entrypoint exit taxonomy → job-level errorReason. */
const CLAUDE_EXIT_ERROR_REASONS: Record<number, string> = {
  10: 'CLAUDE_AGENT_ERROR',
  11: 'CLAUDE_AUTH_ERROR',
  12: 'CLAUDE_RATE_LIMITED',
  13: 'CLAUDE_TIMEOUT',
  20: 'CLAUDE_CONFIG_ERROR',
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

/** Fills a `{workerJobId}` placeholder in a callback URL template. Templates
 * with no placeholder (e.g. a run-scoped log-chunk URL) are returned as-is. */
export function fillTemplate(template: string, workerJobId: string): string {
  return template.replace(/\{workerJobId\}/g, workerJobId)
}

/**
 * Assembles the container env contract for a single step: job-level payload
 * env, overridden by step-level env, overridden by the CONDUCTOR_* contract
 * vars. `ANTHROPIC_API_KEY` is stripped unconditionally — self-hosted jobs
 * must never carry it, even if it leaked into the payload's env — since it
 * silently overrides subscription auth in `claude -p`.
 */
export function buildStepEnv(
  payload: DispatchPayload,
  step: DispatchStep,
  event: WorkflowJobEvent,
  config: Config,
  stepCompleteUrl: string,
  logChunkUrl: string
): Record<string, string> {
  const baseEnv: Record<string, string> = { ...payload.env, ...(step.env ?? {}) }
  delete baseEnv['ANTHROPIC_API_KEY']

  const contractEnv: Record<string, string> = {
    CONDUCTOR_API_URL: config.apiUrl,
    CONDUCTOR_PROJECT_ID: event.projectId,
    CONDUCTOR_WORKFLOW_RUN_ID: event.workflowRunId,
    CONDUCTOR_JOB_ID: event.jobId,
    CONDUCTOR_WORKER_JOB_ID: step.workerJobId,
    CONDUCTOR_RUN_TOKEN: payload.runToken,
    CONDUCTOR_LOG_CHUNK_URL: logChunkUrl,
    CONDUCTOR_STEP_COMPLETE_URL: stepCompleteUrl,
    CONDUCTOR_TIMEOUT_MINUTES: String(step.timeoutMinutes ?? DEFAULT_TIMEOUT_MINUTES),
    CONDUCTOR_MCP_ENABLED: step.conductorMcp ? 'true' : 'false',
  }
  if (step.conductorMcp) {
    contractEnv.CONDUCTOR_API_KEY = config.apiKey
  }

  if (step.stepType === 'claude-code') {
    contractEnv.CONDUCTOR_STEP_PROMPT = step.prompt ?? ''
    if (step.inputsJson !== undefined) contractEnv.CONDUCTOR_STEP_INPUTS_JSON = step.inputsJson
    if (step.allowedTools !== undefined) contractEnv.CONDUCTOR_ALLOWED_TOOLS = step.allowedTools
    if (step.maxTurns !== undefined) contractEnv.CONDUCTOR_MAX_TURNS = String(step.maxTurns)
    if (step.outputSchemaJson !== undefined) contractEnv.CONDUCTOR_OUTPUT_SCHEMA_JSON = step.outputSchemaJson
    // Caller (runStep) guarantees this is present before reaching here.
    contractEnv.CLAUDE_CODE_OAUTH_TOKEN = config.claudeCodeOauthToken ?? ''
  }

  const merged = { ...baseEnv, ...contractEnv }
  delete merged['ANTHROPIC_API_KEY']
  return merged
}

/** Redacts secret values (OAuth token, API key, run token) from a log line
 * before it's posted upstream. */
function redactSecrets(line: string, secrets: string[]): string {
  let out = line
  for (const secret of secrets) {
    if (!secret) continue
    out = out.split(secret).join('***REDACTED***')
  }
  return out
}

/** Batches log lines and POSTs them to the log-chunk callback as `{lines}`
 * with the per-run token — the protocol-2 shape, distinct from the legacy
 * `{chunk}` + apiKey shape in runner.ts's streamLogChunk. */
function createLogBatcher(logChunkUrl: string, runToken: string, secrets: string[]) {
  let buffer: string[] = []
  let timer: ReturnType<typeof setInterval> | null = null

  async function flush(): Promise<void> {
    if (buffer.length === 0) return
    const lines = buffer
    buffer = []
    try {
      await fetch(logChunkUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${runToken}`,
        },
        body: JSON.stringify({ lines }),
      })
    } catch (err) {
      console.error('[job-runner] Failed to stream log chunk:', err)
    }
  }

  function push(rawChunk: string): void {
    const redacted = redactSecrets(rawChunk, secrets)
    for (const line of redacted.split('\n')) {
      if (line.length > 0) buffer.push(line)
    }
  }

  function start(): void {
    timer = setInterval(() => {
      void flush()
    }, LOG_BATCH_INTERVAL_MS)
  }

  function stop(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  return { push, flush, start, stop }
}

async function fetchDispatchPayload(
  event: WorkflowJobEvent,
  config: Config
): Promise<DispatchPayload | 'not-found' | null> {
  const url = `${config.apiUrl}/api/v1/workflow-runs/${event.workflowRunId}/jobs/${event.jobId}/dispatch-payload`
  try {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${config.apiKey}`,
        'Content-Type': 'application/json',
      },
    })
    if (response.status === 404 || response.status === 410) {
      return 'not-found'
    }
    if (!response.ok) {
      console.error(`[job-runner] dispatch-payload fetch failed with status ${response.status}`)
      return null
    }
    return (await response.json()) as DispatchPayload
  } catch (err) {
    console.error('[job-runner] Failed to fetch dispatch payload:', err)
    return null
  }
}

async function postStepComplete(
  stepCompleteUrl: string,
  runToken: string,
  body: { status: 'SUCCESS' | 'FAILED'; errorReason?: string; exitCode?: number }
): Promise<void> {
  try {
    await fetch(stepCompleteUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${runToken}`,
      },
      body: JSON.stringify(body),
    })
  } catch (err) {
    console.error('[job-runner] Failed to post step-complete:', err)
  }
}

// ─── Step execution ─────────────────────────────────────────────────────────

async function runStep(
  event: WorkflowJobEvent,
  payload: DispatchPayload,
  step: DispatchStep,
  config: Config
): Promise<StepResult> {
  const stepCompleteUrl = fillTemplate(payload.callbacks.stepCompleteUrlTemplate, step.workerJobId)
  const logChunkUrl = fillTemplate(payload.callbacks.logChunkUrlTemplate, step.workerJobId)

  const isClaudeCode = step.stepType === 'claude-code'

  if (isClaudeCode && !config.claudeCodeOauthToken) {
    const result: StepResult = { status: 'FAILED', errorReason: 'CLAUDE_SUBSCRIPTION_NOT_CONFIGURED' }
    await postStepComplete(stepCompleteUrl, payload.runToken, result)
    return result
  }

  const env = buildStepEnv(payload, step, event, config, stepCompleteUrl, logChunkUrl)
  const image = payload.image ?? DEFAULT_RUNNER_IMAGE
  const containerName = `conductor-job-${payload.jobRunId}-${step.stepIndex}`
  const command = isClaudeCode ? ['conductor-claude-entrypoint'] : []
  const runArgs = ['run', '--rm', '--name', containerName, ...buildEnvArgs(env), image, ...command]

  const secrets = [payload.runToken, config.apiKey, config.claudeCodeOauthToken ?? '']
  const logBatcher = createLogBatcher(logChunkUrl, payload.runToken, secrets)
  logBatcher.start()

  let timedOut = false
  const timeoutMs =
    (step.timeoutMinutes ?? DEFAULT_TIMEOUT_MINUTES) * 60_000 +
    (isClaudeCode ? CLAUDE_TIMEOUT_GRACE_MS : 0)
  const timeoutTimer = setTimeout(() => {
    timedOut = true
    void spawnAndWait('docker', ['kill', containerName])
  }, timeoutMs)

  let spawnFailed = false
  const exitCode = await spawnAndWait('docker', runArgs, {
    onLine: logBatcher.push,
    onSpawnError: () => {
      spawnFailed = true
    },
  })

  clearTimeout(timeoutTimer)
  logBatcher.stop()
  await logBatcher.flush()

  if (timedOut) {
    const result: StepResult = { status: 'FAILED', errorReason: 'STEP_TIMEOUT', exitCode }
    await postStepComplete(stepCompleteUrl, payload.runToken, result)
    return result
  }

  if (spawnFailed) {
    const result: StepResult = { status: 'FAILED', errorReason: 'STEP_LAUNCH_FAILED', exitCode }
    await postStepComplete(stepCompleteUrl, payload.runToken, result)
    return result
  }

  if (isClaudeCode) {
    // The container self-reports success/failure to CONDUCTOR_STEP_COMPLETE_URL
    // as part of its own contract — the daemon must not double-post here.
    // The exit code is only used to decide whether the job continues.
    return exitCode === 0
      ? { status: 'SUCCESS' }
      : {
          status: 'FAILED',
          errorReason: CLAUDE_EXIT_ERROR_REASONS[exitCode] ?? 'CLAUDE_AGENT_ERROR',
          exitCode,
        }
  }

  // Non-claude-code steps don't self-report; the daemon posts on their behalf.
  const status: 'SUCCESS' | 'FAILED' = exitCode === 0 ? 'SUCCESS' : 'FAILED'
  const result: StepResult =
    status === 'SUCCESS' ? { status } : { status, errorReason: 'STEP_FAILED', exitCode }
  await postStepComplete(stepCompleteUrl, payload.runToken, result)
  return result
}

// ─── Main ────────────────────────────────────────────────────────────────────

/**
 * Handles a single `workflow.job` event end-to-end: fetches the interpolated
 * dispatch payload, runs each step sequentially (stopping at the first
 * failure), posts the job-level completion, then acks the event.
 */
export async function runWorkflowJob(event: WorkflowJobEvent, config: Config): Promise<void> {
  const payload = await fetchDispatchPayload(event, config)

  if (payload === 'not-found') {
    // Job/run no longer exists server-side (e.g. cancelled) — nothing to
    // report back to; just drop it.
    await acknowledgeEvent(event.projectId, event.eventId, config)
    return
  }

  if (payload === null) {
    // Transient failure — leave the event un-acked so the poller redelivers it.
    return
  }

  const image = payload.image ?? DEFAULT_RUNNER_IMAGE

  let jobStatus: 'SUCCESS' | 'FAILED' = 'SUCCESS'
  let jobErrorReason: string | undefined
  let jobExitCode: number | undefined

  const pullCode = await spawnAndWait('docker', ['pull', image])
  if (pullCode !== 0) {
    jobStatus = 'FAILED'
    jobErrorReason = 'IMAGE_PULL_FAILED'
  } else {
    for (const step of payload.steps) {
      const result = await runStep(event, payload, step, config)
      if (result.status === 'FAILED') {
        jobStatus = 'FAILED'
        jobErrorReason = result.errorReason
        jobExitCode = result.exitCode
        break
      }
    }
  }

  await completeJob(event.workflowRunId, event.jobId, jobStatus, config, jobErrorReason, jobExitCode)
  await acknowledgeEvent(event.projectId, event.eventId, config)
}
