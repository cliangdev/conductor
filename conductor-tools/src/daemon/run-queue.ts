import type { Config } from '../lib/config.js'
import { runJob, type WorkflowTriggerEvent } from './runner.js'
import { completeRun } from './run-lifecycle.js'
import { readDaemonState, writeDaemonState } from './state.js'

export class RunQueue {
  private maxConcurrent: number
  private activeCount: number = 0
  private queue: Array<{ event: WorkflowTriggerEvent; getConfig: () => Config }> = []

  constructor(maxConcurrent: number = 1) {
    this.maxConcurrent = maxConcurrent
  }

  enqueue(event: WorkflowTriggerEvent, getConfig: () => Config): void {
    this.queue.push({ event, getConfig })
    this.processNext()
  }

  private processNext(): void {
    if (this.activeCount >= this.maxConcurrent || this.queue.length === 0) return
    const item = this.queue.shift()!
    this.activeCount++
    this.runItem(item).finally(() => {
      this.activeCount--
      this.processNext()
    })
  }

  private async runItem(item: { event: WorkflowTriggerEvent; getConfig: () => Config }): Promise<void> {
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
              issueTitle: event.issueTitle,
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
}
