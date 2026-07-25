// Builds xyflow nodes/edges from a ParsedWorkflow + its computed AutomationLayout — the step-level
// successor to the old job-level `buildFlowGraph` in WorkflowDiagram.tsx. Kept separate from the
// component so the pure graph-shape logic stays unit-testable without mounting React/xyflow.

import { MarkerType, type Node, type Edge } from '@xyflow/react'
import type { ParsedWorkflow, WorkflowJob } from '@/lib/workflowAutomation'
import type { AutomationLayout } from './layout'
import type { JobFrameNodeData, JobRunStatusValue } from './JobFrameNode'
import type { StepNodeData, StepRunStatus } from './StepNode'
import type { TriggerNodeData } from './TriggerNode'

export type JobStatus = JobRunStatusValue

export interface JobRunStatus {
  status: JobStatus
  iteration?: number
  maxIterations?: number
}

type FlowEdge = Edge & { pathOptions?: { borderRadius?: number } }

function frameNodeId(jobId: string): string {
  return `job-${jobId}`
}

function stepNodeId(jobId: string, index: number): string {
  return `step-${jobId}::${index}`
}

/** The node an edge should terminate at for a given job's "entry" (first step, or the frame itself
 * if the job has no steps — a degenerate case the schema doesn't forbid but the layout must not
 * crash on). */
function entryNodeId(job: WorkflowJob): string {
  return job.steps.length > 0 ? stepNodeId(job.jobId, 0) : frameNodeId(job.jobId)
}

/** The node an edge should originate from for a given job's "exit" (last step, or the frame itself
 * if the job has no steps). */
function exitNodeId(job: WorkflowJob): string {
  return job.steps.length > 0 ? stepNodeId(job.jobId, job.steps.length - 1) : frameNodeId(job.jobId)
}

function flowEdge(partial: FlowEdge): FlowEdge {
  return {
    type: 'smoothstep',
    pathOptions: { borderRadius: 8 },
    markerEnd: { type: MarkerType.ArrowClosed },
    ...partial,
  }
}

export function buildAutomationGraph(
  parsed: ParsedWorkflow,
  layout: AutomationLayout,
  jobStatuses?: Record<string, JobStatus>,
  jobRunData?: Record<string, JobRunStatus>,
  stepStatuses?: Record<string, StepRunStatus>,
): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = []
  const edges: FlowEdge[] = []

  const jobsById = new Map(parsed.jobs.map(j => [j.jobId, j]))
  const frameLayoutById = new Map(layout.jobs.map(j => [j.jobId, j]))

  // Frames first, then their steps (parentId nodes must exist before/alongside their children;
  // array order also determines z-order, and frames must render behind their steps).
  for (const job of parsed.jobs) {
    const frameLayout = frameLayoutById.get(job.jobId)
    if (!frameLayout) continue

    const runData = jobRunData?.[job.jobId]
    const status = runData?.status ?? jobStatuses?.[job.jobId]
    const iteration = job.loop
      ? { current: runData?.iteration, max: runData?.maxIterations ?? job.loop.maxIterations }
      : undefined

    const frameData: JobFrameNodeData = { jobId: job.jobId, runsOn: job.runsOn, iteration, status }
    nodes.push({
      id: frameNodeId(job.jobId),
      type: 'jobFrame',
      position: { x: frameLayout.x, y: frameLayout.y },
      style: { width: frameLayout.width, height: frameLayout.height },
      draggable: false,
      selectable: false,
      data: frameData,
    })

    job.steps.forEach((step, index) => {
      const relPos = frameLayout.stepPositions[index] ?? { x: 0, y: 0 }
      const stepData: StepNodeData = {
        step,
        status: stepStatuses?.[stepNodeId(job.jobId, index)],
      }
      nodes.push({
        id: stepNodeId(job.jobId, index),
        type: step.kind === 'condition' ? 'condition' : 'step',
        position: relPos,
        parentId: frameNodeId(job.jobId),
        extent: 'parent',
        data: stepData,
      })

      // Sequential intra-job edge: this step to the next one in the same job. A condition step is
      // always last (schema-enforced), so it never has a "next step" of its own here — its outgoing
      // edges are the then/else branches added below instead.
      if (index < job.steps.length - 1) {
        edges.push(flowEdge({
          id: `${stepNodeId(job.jobId, index)}->${stepNodeId(job.jobId, index + 1)}`,
          source: stepNodeId(job.jobId, index),
          target: stepNodeId(job.jobId, index + 1),
        }))
      }
    })

    // Self-loop: the whole job repeats, not a specific step — keep it at the frame level.
    if (job.loop) {
      edges.push(flowEdge({
        id: `${frameNodeId(job.jobId)}->self-loop`,
        source: frameNodeId(job.jobId),
        target: frameNodeId(job.jobId),
        label: 'loop',
        style: { strokeDasharray: '4 2' },
      }))
    }

    // needs: edges — from the dependency job's exit node to this job's entry node.
    for (const dep of job.needs) {
      const depJob = jobsById.get(dep)
      if (!depJob) continue
      const label = job.ifExpr
        ? 'if: ' + job.ifExpr.replace(/\$\{\{|\}\}/g, '').trim().slice(0, 40)
        : undefined
      edges.push(flowEdge({
        id: `${exitNodeId(depJob)}->${entryNodeId(job)}`,
        source: exitNodeId(depJob),
        target: entryNodeId(job),
        label,
        labelStyle: { fontSize: 10 },
      }))
    }

    // condition then/else — from the condition step's own named handles to the target job's entry.
    const lastStep = job.steps[job.steps.length - 1]
    if (lastStep?.kind === 'condition') {
      const conditionNodeId = stepNodeId(job.jobId, job.steps.length - 1)
      if (lastStep.then) {
        const thenJob = jobsById.get(lastStep.then)
        if (thenJob) {
          edges.push(flowEdge({
            id: `${conditionNodeId}->then-${lastStep.then}`,
            source: conditionNodeId,
            sourceHandle: 'true',
            target: entryNodeId(thenJob),
            label: 'true',
            labelStyle: { fontSize: 10 },
          }))
        }
      }
      if (lastStep.else) {
        const elseJob = jobsById.get(lastStep.else)
        if (elseJob) {
          edges.push(flowEdge({
            id: `${conditionNodeId}->else-${lastStep.else}`,
            source: conditionNodeId,
            sourceHandle: 'false',
            target: entryNodeId(elseJob),
            label: 'false',
            labelStyle: { fontSize: 10 },
          }))
        }
      }
    }
  }

  // Trigger nodes + trigger->job edges. Every job with no `needs:` is an entry point, and the YAML
  // schema has no per-trigger job routing, so every declared trigger wires to every entry job.
  for (const triggerLayout of layout.triggers) {
    const trigger = parsed.triggers.find(t => t.kind === triggerLayout.kind)
    if (!trigger) continue
    const triggerId = `trigger-${trigger.kind}`
    const triggerData: TriggerNodeData = { trigger }
    nodes.push({
      id: triggerId,
      type: 'trigger',
      position: { x: triggerLayout.x, y: triggerLayout.y },
      draggable: false,
      selectable: false,
      data: triggerData,
    })

    for (const job of parsed.jobs) {
      if (job.needs.length === 0) {
        edges.push(flowEdge({
          id: `${triggerId}->${entryNodeId(job)}`,
          source: triggerId,
          target: entryNodeId(job),
        }))
      }
    }
  }

  return { nodes, edges }
}
