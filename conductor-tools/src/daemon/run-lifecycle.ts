import type { Config } from '../lib/config.js'
import type { WorkflowTriggerEvent } from './runner.js'

// ─── updateRunStatus ──────────────────────────────────────────────────────────

/**
 * PATCH /api/v1/workflow-runs/{runId}
 * Updates the status of a WorkflowRun. Errors are swallowed and logged.
 */
export async function updateRunStatus(
  runId: string,
  status: 'SUCCESS' | 'FAILED',
  config: Config
): Promise<void> {
  try {
    await fetch(`${config.apiUrl}/api/v1/workflow-runs/${runId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.apiKey}`,
      },
      body: JSON.stringify({ status }),
    })
  } catch (err) {
    console.error('[run-lifecycle] Failed to update run status:', err)
  }
}

// ─── acknowledgeEvent ─────────────────────────────────────────────────────────

/**
 * POST /api/v1/projects/{projectId}/daemon/events/ack
 * Marks a daemon event as processed. Errors are swallowed and logged.
 */
export async function acknowledgeEvent(
  projectId: string,
  eventId: string,
  config: Config
): Promise<void> {
  try {
    await fetch(`${config.apiUrl}/api/v1/projects/${projectId}/daemon/events/ack`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.apiKey}`,
      },
      body: JSON.stringify({ eventIds: [eventId] }),
    })
  } catch (err) {
    console.error('[run-lifecycle] Failed to acknowledge event:', err)
  }
}

// ─── completeRun ──────────────────────────────────────────────────────────────

/**
 * Finalises a workflow run after Docker container exit:
 * 1. PATCH the WorkflowRun status to SUCCESS or FAILED
 * 2. POST to the ack endpoint to mark the event as processed
 *
 * Used by the run queue orchestrator.
 */
export async function completeRun(
  event: WorkflowTriggerEvent,
  status: 'SUCCESS' | 'FAILED',
  config: Config
): Promise<void> {
  await updateRunStatus(event.workflowRunId, status, config)
  await acknowledgeEvent(event.projectId, event.eventId, config)
}

// ─── completeJob (protocol 2) ──────────────────────────────────────────────────

/**
 * POST /api/v1/workflow-runs/{runId}/jobs/{jobId}/complete
 * Finalises a single self-hosted job under the per-job dispatch protocol.
 * Errors are swallowed and logged — the caller acks the daemon event
 * regardless, matching completeRun's fire-and-forget shape.
 */
export async function completeJob(
  runId: string,
  jobId: string,
  status: 'SUCCESS' | 'FAILED',
  config: Config,
  errorReason?: string,
  exitCode?: number
): Promise<void> {
  try {
    await fetch(`${config.apiUrl}/api/v1/workflow-runs/${runId}/jobs/${jobId}/complete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${config.apiKey}`,
      },
      body: JSON.stringify({
        status,
        ...(errorReason !== undefined ? { errorReason } : {}),
        ...(exitCode !== undefined ? { exitCode } : {}),
      }),
    })
  } catch (err) {
    console.error('[run-lifecycle] Failed to complete job:', err)
  }
}
