import { spawn } from 'child_process'
import type { Config } from '../lib/config.js'

// ─── Types ───────────────────────────────────────────────────────────────────

export interface JobConfig {
  id: string
  runsOn: string
  container?: { image: string }
  steps?: Array<{ name: string; run?: string }>
  env?: Record<string, string>
}

export interface WorkflowTriggerEvent {
  eventId: string
  type: string
  workflowRunId: string
  workflowId: string
  workflowName: string
  workItemId: string
  workItemTitle: string
  projectId: string
  trigger: {
    type: string
    fromStatus?: string
    toStatus?: string
  }
  jobs: JobConfig[]
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

async function streamLogChunk(runId: string, chunk: string, config: Config): Promise<void> {
  try {
    await fetch(`${config.apiUrl}/internal/v1/workflow-runs/${runId}/log-chunk`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.apiKey}`,
      },
      body: JSON.stringify({ chunk }),
    })
  } catch (err) {
    console.error('[runner] Failed to stream log chunk:', err)
  }
}

/**
 * Spawns a command and returns its exit code. `onLine` receives each raw
 * stdout/stderr chunk as it arrives; `onSpawnError` fires when the command
 * itself never launches (e.g. Docker not installed) — distinct from a
 * nonzero exit — so callers that need to know whether a container actually
 * ran (job-runner's claude-code self-report bookkeeping) can tell the two
 * apart. Shared by runner.ts (legacy `workflow.trigger` jobs) and
 * job-runner.ts (protocol-2 `workflow.job` steps) to avoid duplicating the
 * spawn/stream/resolve plumbing.
 */
export function spawnAndWait(
  cmd: string,
  args: string[],
  opts?: {
    onLine?: (line: string) => void
    onSpawnError?: (err: Error) => void
  }
): Promise<number> {
  return new Promise((resolve) => {
    const proc = spawn(cmd, args)

    proc.stdout?.on('data', (data: Buffer) => {
      opts?.onLine?.(data.toString())
    })

    proc.stderr?.on('data', (data: Buffer) => {
      opts?.onLine?.(data.toString())
    })

    proc.on('error', (err) => {
      opts?.onSpawnError?.(err as Error)
      resolve(1)
    })

    proc.on('close', (code: number | null) => {
      resolve(code ?? 1)
    })
  })
}

/** Renders an env map as repeated `-e KEY=VALUE` docker CLI args. */
export function buildEnvArgs(env: Record<string, string>): string[] {
  const args: string[] = []
  for (const [key, value] of Object.entries(env)) {
    args.push('-e', `${key}=${value}`)
  }
  return args
}

// ─── Main ────────────────────────────────────────────────────────────────────

export async function runJob(
  event: WorkflowTriggerEvent,
  job: JobConfig,
  config: Config
): Promise<'SUCCESS' | 'FAILED'> {
  const { workflowRunId } = event

  // Step 1: Check Docker is available
  const dockerInfoCode = await spawnAndWait('docker', ['info'])
  if (dockerInfoCode !== 0) {
    await streamLogChunk(workflowRunId, 'Docker not available on this host', config)
    return 'FAILED'
  }

  // Step 2: Validate container image
  if (!job.container?.image) {
    await streamLogChunk(workflowRunId, 'No container image specified for this job', config)
    return 'FAILED'
  }

  const image = job.container.image

  // Step 3: Pull image
  const pullCode = await spawnAndWait('docker', ['pull', image])
  if (pullCode !== 0) {
    await streamLogChunk(workflowRunId, `Failed to pull image: ${image}`, config)
    return 'FAILED'
  }

  // Step 4: Build env args
  const conductorEnv: Record<string, string> = {
    CONDUCTOR_ISSUE_ID: event.workItemId,
    CONDUCTOR_PROJECT_ID: event.projectId,
    CONDUCTOR_WORKFLOW_RUN_ID: event.workflowRunId,
    CONDUCTOR_API_KEY: config.apiKey,
    CONDUCTOR_API_URL: config.apiUrl,
  }

  const allEnv: Record<string, string> = {
    ...conductorEnv,
    ...(job.env ?? {}),
  }

  // Step 5: Run container
  const runArgs = ['run', '--rm', ...buildEnvArgs(allEnv), image]
  const runCode = await spawnAndWait('docker', runArgs, {
    onLine: (line) => {
      void streamLogChunk(workflowRunId, line, config)
    },
  })

  // Step 6: Return result
  return runCode === 0 ? 'SUCCESS' : 'FAILED'
}
