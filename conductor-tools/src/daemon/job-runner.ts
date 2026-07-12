import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'
import { randomUUID } from 'crypto'
import type { Config } from '../lib/config.js'
import { spawnAndWait, buildEnvArgs } from './runner.js'
import { acknowledgeEvent, completeJob } from './run-lifecycle.js'

// ─── Types ───────────────────────────────────────────────────────────────────

/**
 * Pointer-only daemon event for the per-job dispatch protocol ("protocol 2").
 * Carries no secrets/env — the daemon fetches the interpolated dispatch
 * payload (env, steps, run token) at pickup. The events API delivers
 * {eventId, type, payload: {...}}; the watcher flattens the payload into
 * this top-level shape before enqueueing.
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

/** One declared producer artifact — {@link DispatchStep.artifacts}. */
export interface DispatchArtifact {
  name: string
  path: string
}

/** One resolved consumed artifact — {@link DispatchPayload.consumedArtifacts}. */
export interface ConsumedArtifact {
  name: string
  downloadUrl: string
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
  /** This step's declared `artifacts:` (docker/claude-code only). Optional/additive. */
  artifacts?: DispatchArtifact[]
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
  /** Internal base URL for this run's artifact endpoints. Present iff any step declares artifacts. */
  artifactsUrl?: string
  /** This job's `consumes:` artifacts, pre-resolved to signed download URLs. Optional/additive. */
  consumedArtifacts?: ConsumedArtifact[]
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

/** Fixed in-container path consumed artifacts are made available at — mirrors
 * ClaudeCodeStepExecutor's CONDUCTOR_ARTIFACTS_DIR value for the Cloud Run path, so the same
 * entrypoint contract works regardless of which launcher runs it. */
const ARTIFACTS_CONTAINER_DIR = '/conductor/artifacts'
/** Workspace-relative root a producing step's declared `path:` is resolved against, inside the
 * container — matches the claude-code entrypoint's WORKSPACE_DIR. */
const WORKSPACE_CONTAINER_DIR = '/conductor/workspace'

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

/** Env keys that must never appear in `docker run -e KEY=VALUE` argv — visible to any local user
 * via `ps`/procfs. These go into a `--env-file` instead (see {@link splitSecretEnv}). */
const DOCKER_ARGV_SECRET_KEYS = [
  'CLAUDE_CODE_OAUTH_TOKEN',
  'CONDUCTOR_API_KEY',
  'CONDUCTOR_RUN_TOKEN',
  'ANTHROPIC_API_KEY',
]

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

    if (step.artifacts && step.artifacts.length > 0 && payload.artifactsUrl) {
      contractEnv.CONDUCTOR_ARTIFACTS_URL = payload.artifactsUrl
      contractEnv.CONDUCTOR_STEP_ARTIFACTS_JSON = JSON.stringify(step.artifacts)
    }
    if (payload.consumedArtifacts && payload.consumedArtifacts.length > 0) {
      contractEnv.CONDUCTOR_CONSUMED_ARTIFACTS_JSON = JSON.stringify(payload.consumedArtifacts)
      contractEnv.CONDUCTOR_ARTIFACTS_DIR = ARTIFACTS_CONTAINER_DIR
    }
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

/**
 * Splits a step's container env into secret keys (never argv/procfs, see
 * {@link DOCKER_ARGV_SECRET_KEYS}) and everything else, which still goes through `-e` args —
 * `docker run --env-file` can't carry multiline values, and non-secret env is never multiline in
 * practice (the claude-code prompt, which can be, is a secret-adjacent CONDUCTOR_STEP_PROMPT var
 * that isn't in the secret list and stays on argv; only the four listed keys are ever redirected).
 */
function splitSecretEnv(env: Record<string, string>): {
  fileEnv: Record<string, string>
  argEnv: Record<string, string>
} {
  const fileEnv: Record<string, string> = {}
  const argEnv: Record<string, string> = {}
  for (const [key, value] of Object.entries(env)) {
    if (!DOCKER_ARGV_SECRET_KEYS.includes(key)) {
      argEnv[key] = value
      continue
    }
    if (value.includes('\n')) {
      console.error(
        `[job-runner] ${key} contains a newline, which docker --env-file cannot carry; ` +
          'omitting it from the container env'
      )
      continue
    }
    fileEnv[key] = value
  }
  return { fileEnv, argEnv }
}

/** Writes secret env vars to a mode-0600 temp file for `docker run --env-file`, keeping them out
 * of process argv. Caller is responsible for deleting the returned path once the container exits. */
function writeSecretEnvFile(fileEnv: Record<string, string>): string {
  const filePath = path.join(os.tmpdir(), `conductor-env-${randomUUID()}.env`)
  const contents = Object.entries(fileEnv)
    .map(([key, value]) => `${key}=${value}`)
    .join('\n')
  fs.writeFileSync(filePath, contents + '\n', { mode: 0o600 })
  return filePath
}

// ─── Artifacts ───────────────────────────────────────────────────────────────

/** Result of {@link downloadConsumedArtifacts}: either every consumed artifact downloaded
 * cleanly (`dir` is the host directory, or null if there was nothing to consume), or the first
 * failure, carrying a message that names the artifact — the job-level failure reason. */
type ConsumedArtifactsResult = { ok: true; dir: string | null } | { ok: false; errorReason: string }

/**
 * Downloads every consumed artifact into a fresh per-job temp directory, once per job (not per
 * step) — the same directory is bind-mounted at {@link ARTIFACTS_CONTAINER_DIR} into every
 * container this job runs, so any step (not just claude-code, whose entrypoint additionally gets
 * `CONDUCTOR_CONSUMED_ARTIFACTS_JSON`) can read the files directly.
 *
 * A failed download (non-OK response or a network error) fails fast with a named reason — mirrors
 * conductor-claude-entrypoint.mjs's materializeConsumedArtifacts, which throws a ConfigError on the
 * same condition: a consumer job that can't get a file it declared `consumes:` on has nothing
 * sensible to fall back to, and silently running steps against a missing file is exactly the
 * silent-absence failure mode artifact passing exists to prevent.
 */
async function downloadConsumedArtifacts(payload: DispatchPayload, jobRunId: string): Promise<ConsumedArtifactsResult> {
  if (!payload.consumedArtifacts || payload.consumedArtifacts.length === 0) return { ok: true, dir: null }
  const dir = path.join(os.tmpdir(), `conductor-artifacts-${jobRunId}`)
  fs.mkdirSync(dir, { recursive: true })
  for (const artifact of payload.consumedArtifacts) {
    let res: Response
    try {
      res = await fetch(artifact.downloadUrl)
    } catch (err) {
      cleanupDir(dir)
      return {
        ok: false,
        errorReason: `ARTIFACT_DOWNLOAD_FAILED: failed to download consumed artifact '${artifact.name}': ${String(err)}`,
      }
    }
    if (!res.ok) {
      cleanupDir(dir)
      return {
        ok: false,
        errorReason: `ARTIFACT_DOWNLOAD_FAILED: failed to download consumed artifact '${artifact.name}': HTTP ${res.status}`,
      }
    }
    const buf = Buffer.from(await res.arrayBuffer())
    fs.writeFileSync(path.join(dir, artifact.name), buf)
  }
  return { ok: true, dir }
}

/** Best-effort recursive removal — used to clean up the per-job artifacts dir when a partial
 * download fails partway through, since the caller never gets a handle to it in that case. */
function cleanupDir(dir: string): void {
  try {
    fs.rmSync(dir, { recursive: true, force: true })
  } catch (err) {
    console.error('[job-runner] Failed to remove consumed-artifacts dir after a failed download:', err)
  }
}

/** Declares one artifact (`POST {artifactsUrl}`) and returns its upload target, or null on failure. */
async function createArtifact(
  artifactsUrl: string,
  jobId: string,
  name: string,
  runToken: string
): Promise<{ artifactId: string; uploadUrl: string } | null> {
  try {
    const res = await fetch(artifactsUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${runToken}` },
      body: JSON.stringify({ jobId, name }),
    })
    if (!res.ok) return null
    return (await res.json()) as { artifactId: string; uploadUrl: string }
  } catch (err) {
    console.error(`[job-runner] Failed to create artifact '${name}':`, err)
    return null
  }
}

/**
 * PUTs the artifact's raw bytes to its upload URL. A signed GCS URL is self-contained and must NOT
 * carry an Authorization header (it would invalidate the signature); the local-profile passthrough
 * URL is backend-relative (starts with `apiUrl`) and DOES need the run-token bearer header — that's
 * the only signal available to tell the two apart.
 */
async function putArtifactContent(uploadUrl: string, content: Buffer, runToken: string, apiUrl: string): Promise<boolean> {
  const headers: Record<string, string> = {}
  if (uploadUrl.startsWith(apiUrl)) {
    headers['Authorization'] = `Bearer ${runToken}`
  }
  try {
    const res = await fetch(uploadUrl, { method: 'PUT', headers, body: content })
    return res.ok
  } catch (err) {
    console.error('[job-runner] Failed to PUT artifact content:', err)
    return false
  }
}

async function completeArtifact(artifactsUrl: string, artifactId: string, runToken: string): Promise<boolean> {
  try {
    const res = await fetch(`${artifactsUrl}/${artifactId}/complete`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${runToken}` },
    })
    return res.ok
  } catch (err) {
    console.error(`[job-runner] Failed to complete artifact ${artifactId}:`, err)
    return false
  }
}

/**
 * Uploads a `docker` step's declared artifacts after it exits successfully — `claude-code` steps
 * never reach here (the entrypoint uploads its own artifacts over the network, from inside the
 * container, before exiting). Requires the container to still exist (caller must not have used
 * `--rm`): each declared `path:` is `docker cp`'d out of {@link WORKSPACE_CONTAINER_DIR} before the
 * create/PUT/complete sequence. Returns a FAILED StepResult on the first problem (missing file or
 * any HTTP step failing) — no partial credit for a job that promised an artifact it didn't deliver.
 */
async function uploadDockerStepArtifacts(
  payload: DispatchPayload,
  event: WorkflowJobEvent,
  step: DispatchStep,
  containerName: string,
  config: Config
): Promise<StepResult | null> {
  if (!step.artifacts || step.artifacts.length === 0) return null
  if (!payload.artifactsUrl) {
    return { status: 'FAILED', errorReason: 'ARTIFACTS_URL_MISSING' }
  }

  for (const artifact of step.artifacts) {
    const tmpFile = path.join(os.tmpdir(), `conductor-artifact-${randomUUID()}`)
    const containerPath = `${containerName}:${WORKSPACE_CONTAINER_DIR}/${artifact.path}`
    const cpCode = await spawnAndWait('docker', ['cp', containerPath, tmpFile])
    if (cpCode !== 0 || !fs.existsSync(tmpFile)) {
      return {
        status: 'FAILED',
        errorReason: `ARTIFACT_MISSING: declared artifact '${artifact.name}' not found at ${artifact.path}`,
      }
    }
    const content = fs.readFileSync(tmpFile)
    try {
      fs.unlinkSync(tmpFile)
    } catch {
      // best-effort cleanup
    }

    const created = await createArtifact(payload.artifactsUrl, event.jobId, artifact.name, payload.runToken)
    if (!created) {
      return { status: 'FAILED', errorReason: `ARTIFACT_UPLOAD_FAILED: could not declare artifact '${artifact.name}'` }
    }
    const putOk = await putArtifactContent(created.uploadUrl, content, payload.runToken, config.apiUrl)
    if (!putOk) {
      return { status: 'FAILED', errorReason: `ARTIFACT_UPLOAD_FAILED: upload failed for '${artifact.name}'` }
    }
    const completedOk = await completeArtifact(payload.artifactsUrl, created.artifactId, payload.runToken)
    if (!completedOk) {
      return { status: 'FAILED', errorReason: `ARTIFACT_UPLOAD_FAILED: complete failed for '${artifact.name}'` }
    }
  }
  return null
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
  config: Config,
  artifactsDir: string | null
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
  const { fileEnv, argEnv } = splitSecretEnv(env)
  const envFilePath = Object.keys(fileEnv).length > 0 ? writeSecretEnvFile(fileEnv) : null
  const image = payload.image ?? DEFAULT_RUNNER_IMAGE
  const containerName = `conductor-job-${payload.jobRunId}-${step.stepIndex}`
  const command = isClaudeCode ? ['conductor-claude-entrypoint'] : []
  // `claude-code` steps self-upload their declared artifacts over the network before exiting, so
  // `--rm` is always safe for them. A `docker` step's produced files must be `docker cp`'d out
  // AFTER it exits (see uploadDockerStepArtifacts), so its container is kept around and removed
  // explicitly further down instead.
  const hasProducerArtifacts = !isClaudeCode && (step.artifacts?.length ?? 0) > 0
  const runArgs = [
    'run',
    ...(hasProducerArtifacts ? [] : ['--rm']),
    '--name',
    containerName,
    ...(artifactsDir ? ['-v', `${artifactsDir}:${ARTIFACTS_CONTAINER_DIR}`] : []),
    ...(envFilePath ? ['--env-file', envFilePath] : []),
    ...buildEnvArgs(argEnv),
    image,
    ...command,
  ]

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
  let exitCode: number
  try {
    exitCode = await spawnAndWait('docker', runArgs, {
      onLine: logBatcher.push,
      onSpawnError: () => {
        spawnFailed = true
      },
    })
  } finally {
    if (envFilePath) {
      try {
        fs.unlinkSync(envFilePath)
      } catch (err) {
        console.error('[job-runner] Failed to remove secret env file:', err)
      }
    }
  }

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
  let result: StepResult =
    exitCode === 0 ? { status: 'SUCCESS' } : { status: 'FAILED', errorReason: 'STEP_FAILED', exitCode }

  if (exitCode === 0 && hasProducerArtifacts) {
    const artifactFailure = await uploadDockerStepArtifacts(payload, event, step, containerName, config)
    if (artifactFailure) result = artifactFailure
  }
  if (hasProducerArtifacts) {
    // Container was kept alive (no --rm) so uploadDockerStepArtifacts could `docker cp` out of it —
    // remove it now regardless of outcome.
    await spawnAndWait('docker', ['rm', containerName])
  }

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

  // Downloaded once per job (not per step) and bind-mounted into every step's container — see
  // downloadConsumedArtifacts. A failed download fails the whole job before any step (or even the
  // image pull) runs — no step should ever start against a consumed artifact that's silently missing.
  const artifactsResult = await downloadConsumedArtifacts(payload, payload.jobRunId)
  let artifactsDir: string | null = null

  if (!artifactsResult.ok) {
    jobStatus = 'FAILED'
    jobErrorReason = artifactsResult.errorReason
  } else {
    artifactsDir = artifactsResult.dir

    const pullCode = await spawnAndWait('docker', ['pull', image])
    if (pullCode !== 0) {
      jobStatus = 'FAILED'
      jobErrorReason = 'IMAGE_PULL_FAILED'
    } else {
      for (const step of payload.steps) {
        const result = await runStep(event, payload, step, config, artifactsDir)
        if (result.status === 'FAILED') {
          jobStatus = 'FAILED'
          jobErrorReason = result.errorReason
          jobExitCode = result.exitCode
          break
        }
      }
    }
  }

  if (artifactsDir) {
    try {
      fs.rmSync(artifactsDir, { recursive: true, force: true })
    } catch (err) {
      console.error('[job-runner] Failed to remove consumed-artifacts dir:', err)
    }
  }

  await completeJob(event.workflowRunId, event.jobId, jobStatus, config, jobErrorReason, jobExitCode)
  await acknowledgeEvent(event.projectId, event.eventId, config)
}
