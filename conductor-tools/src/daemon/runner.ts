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
  issueId: string
  issueTitle: string
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
    await fetch(`${config.apiUrl}/internal/workflow-runs/${runId}/log-chunk`, {
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
 * Spawns a command and returns its exit code. Streams stdout/stderr lines to
 * the log-chunk endpoint if a runId and config are provided.
 */
function spawnAndWait(
  cmd: string,
  args: string[],
  opts?: {
    runId?: string
    config?: Config
  }
): Promise<number> {
  return new Promise((resolve) => {
    const proc = spawn(cmd, args)

    proc.stdout?.on('data', (data: Buffer) => {
      const line = data.toString()
      if (opts?.runId && opts?.config) {
        void streamLogChunk(opts.runId, line, opts.config)
      }
    })

    proc.stderr?.on('data', (data: Buffer) => {
      const line = data.toString()
      if (opts?.runId && opts?.config) {
        void streamLogChunk(opts.runId, line, opts.config)
      }
    })

    proc.on('error', () => {
      resolve(1)
    })

    proc.on('close', (code: number | null) => {
      resolve(code ?? 1)
    })
  })
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
    CONDUCTOR_ISSUE_ID: event.issueId,
    CONDUCTOR_PROJECT_ID: event.projectId,
    CONDUCTOR_WORKFLOW_RUN_ID: event.workflowRunId,
    CONDUCTOR_API_KEY: config.apiKey,
    CONDUCTOR_API_URL: config.apiUrl,
  }

  const allEnv: Record<string, string> = {
    ...conductorEnv,
    ...(job.env ?? {}),
  }

  const envArgs: string[] = []
  for (const [key, value] of Object.entries(allEnv)) {
    envArgs.push('-e', `${key}=${value}`)
  }

  // Step 5: Run container
  const runArgs = ['run', '--rm', ...envArgs, image]
  const runCode = await spawnAndWait('docker', runArgs, {
    runId: workflowRunId,
    config,
  })

  // Step 6: Return result
  return runCode === 0 ? 'SUCCESS' : 'FAILED'
}
