import type { Config } from '../lib/config.js'
import { runJob, type WorkflowTriggerEvent } from './runner.js'
import { completeRun } from './run-lifecycle.js'
import { readDaemonState, writeDaemonState } from './state.js'
import { runWorkflowJob, type WorkflowJobEvent } from './job-runner.js'

type QueueItem =
  | { kind: 'trigger'; event: WorkflowTriggerEvent; getConfig: () => Config }
  | { kind: 'job'; event: WorkflowJobEvent; getConfig: () => Config }

export class RunQueue {
  private maxConcurrent: number
  private activeCount: number = 0
  private queue: QueueItem[] = []

  constructor(maxConcurrent: number = 1) {
    this.maxConcurrent = maxConcurrent
  }

  /** Legacy `workflow.trigger` path: one event carries the whole run's jobs. */
  enqueue(event: WorkflowTriggerEvent, getConfig: () => Config): void {
    this.queue.push({ kind: 'trigger', event, getConfig })
    this.processNext()
  }

  /** Protocol-2 `workflow.job` path: one event per self-hosted job. */
  enqueueJob(event: WorkflowJobEvent, getConfig: () => Config): void {
    this.queue.push({ kind: 'job', event, getConfig })
    this.processNext()
  }

  private processNext(): void {
    if (this.activeCount >= this.maxConcurrent || this.queue.length === 0) return
    const item = this.queue.shift()!
    this.activeCount++
    const run = item.kind === 'trigger' ? this.runTriggerItem(item) : this.runJobItem(item)
    run.finally(() => {
      this.activeCount--
      this.processNext()
    })
  }

  private async runTriggerItem(item: { event: WorkflowTriggerEvent; getConfig: () => Config }): Promise<void> {
    const { event, getConfig } = item
    const config = getConfig()
    const selfHostedJobs = event.jobs.filter((j) => j.runsOn === 'self-hosted')

    for (const job of selfHostedJobs) {
      // Add to activeRuns in daemon-state.json
      const stateBefore = readDaemonState()
      if (stateBefore) {
        writeDaemonState({
          ...stateBefore,
          activeRuns: [
            ...stateBefore.activeRuns,
            {
              runId: event.workflowRunId,
              issueTitle: event.workItemTitle,
              jobName: job.id,
              status: 'running',
              startedAt: new Date().toISOString(),
            },
          ],
        })
      }

      const status = await runJob(event, job, config)
      await completeRun(event, status, config)

      // Remove from activeRuns in daemon-state.json
      const stateAfter = readDaemonState()
      if (stateAfter) {
        writeDaemonState({
          ...stateAfter,
          activeRuns: stateAfter.activeRuns.filter((r) => r.runId !== event.workflowRunId),
        })
      }
    }
  }

  private async runJobItem(item: { event: WorkflowJobEvent; getConfig: () => Config }): Promise<void> {
    const { event, getConfig } = item
    const config = getConfig()
    // Protocol-2 events have no single "run" id worth keying activeRuns by —
    // a run can dispatch many jobs — so the entry is keyed runId:jobId.
    const activeRunKey = `${event.workflowRunId}:${event.jobId}`

    const stateBefore = readDaemonState()
    if (stateBefore) {
      writeDaemonState({
        ...stateBefore,
        activeRuns: [
          ...stateBefore.activeRuns,
          {
            runId: activeRunKey,
            issueTitle: event.workflowName,
            jobName: event.jobId,
            status: 'running',
            startedAt: new Date().toISOString(),
          },
        ],
      })
    }

    await runWorkflowJob(event, config)

    const stateAfter = readDaemonState()
    if (stateAfter) {
      writeDaemonState({
        ...stateAfter,
        activeRuns: stateAfter.activeRuns.filter((r) => r.runId !== activeRunKey),
      })
    }
  }
}
