// Two-pass layout for the step-level automation diagram, left-to-right.
//
// The installed `dagre` package has no native subgraph/cluster concept, so a job-frame-aware layout
// can't be a single dagre call. Instead:
//   1. Intra-job pass: steps run strictly in YAML array order (the only branching within a job is a
//      terminal `condition` step, which has no "next step" of its own), so this is a plain left-to-right
//      row placement — not a dagre call — that produces each job's content size and each step's
//      position relative to its frame's origin.
//   2. Inter-job pass: one dagre(rankdir: 'LR') pass where each *job* is a single sized node (using the
//      frame dimensions from pass 1), edged by `needs:` and condition `then`/`else` — exactly the same
//      edges the old job-level diagram used for its single dagre call. This produces each frame's
//      absolute position. A step's final absolute position is simply frame position + its pass-1
//      relative offset, which is exactly what xyflow expects for `parentId`-based children.
//
// Trigger nodes aren't part of the dagre graph (they have no size dependency on job content) — they're
// placed in a fixed column to the left of the leftmost job frame.

import dagre from 'dagre'
import type { ParsedWorkflow, WorkflowJob, TriggerKind } from '@/lib/workflowAutomation'
import {
  STEP_W, STEP_H, CONDITION_W, CONDITION_H, STEP_GAP_X, JOB_GAP_X, JOB_GAP_Y,
  FRAME_HEADER_H, FRAME_PADDING_X, FRAME_PADDING_Y, TRIGGER_W, TRIGGER_H,
} from './dimensions'

export interface JobLayout {
  jobId: string
  x: number
  y: number
  width: number
  height: number
  /** relative to (x, y) — one entry per job.steps[i], in the same order */
  stepPositions: { x: number; y: number }[]
}

export interface TriggerLayout {
  kind: TriggerKind
  x: number
  y: number
}

export interface AutomationLayout {
  triggers: TriggerLayout[]
  jobs: JobLayout[]
}

function stepWidth(kind: WorkflowJob['steps'][number]['kind']): number {
  return kind === 'condition' ? CONDITION_W : STEP_W
}

function stepHeight(kind: WorkflowJob['steps'][number]['kind']): number {
  return kind === 'condition' ? CONDITION_H : STEP_H
}

/** Pass 1: content size + relative step positions for one job, laid out as a single left-to-right row. */
function layoutJobContent(job: WorkflowJob): { width: number; height: number; stepPositions: { x: number; y: number }[] } {
  const rowY = FRAME_HEADER_H + FRAME_PADDING_Y
  const rowHeight = job.steps.reduce((max, s) => Math.max(max, stepHeight(s.kind)), STEP_H)

  const stepPositions: { x: number; y: number }[] = []
  let cursorX = FRAME_PADDING_X
  for (const step of job.steps) {
    const h = stepHeight(step.kind)
    stepPositions.push({ x: cursorX, y: rowY + (rowHeight - h) / 2 })
    cursorX += stepWidth(step.kind) + STEP_GAP_X
  }
  const contentWidth = job.steps.length > 0 ? cursorX - STEP_GAP_X + FRAME_PADDING_X : STEP_W + FRAME_PADDING_X * 2

  return {
    width: contentWidth,
    height: FRAME_HEADER_H + FRAME_PADDING_Y * 2 + rowHeight,
    stepPositions,
  }
}

/** Pass 2: dagre-rank the jobs themselves (each sized to its pass-1 content), following the same
 * needs:/then:/else: edges the old job-level diagram used for its single dagre call. */
function layoutJobFrames(jobs: WorkflowJob[], contentSizes: Map<string, { width: number; height: number }>): Map<string, { x: number; y: number }> {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'LR', nodesep: JOB_GAP_Y, ranksep: JOB_GAP_X })

  for (const job of jobs) {
    const size = contentSizes.get(job.jobId)!
    g.setNode(job.jobId, { width: size.width, height: size.height })
  }
  for (const job of jobs) {
    for (const dep of job.needs) {
      if (contentSizes.has(dep)) g.setEdge(dep, job.jobId)
    }
    const lastStep = job.steps[job.steps.length - 1]
    if (lastStep?.kind === 'condition') {
      if (lastStep.then && contentSizes.has(lastStep.then)) g.setEdge(job.jobId, lastStep.then)
      if (lastStep.else && contentSizes.has(lastStep.else)) g.setEdge(job.jobId, lastStep.else)
    }
  }

  dagre.layout(g)

  const positions = new Map<string, { x: number; y: number }>()
  for (const job of jobs) {
    const size = contentSizes.get(job.jobId)!
    const { x, y } = g.node(job.jobId)
    // dagre positions are center-based; frames are top-left based.
    positions.set(job.jobId, { x: x - size.width / 2, y: y - size.height / 2 })
  }
  return positions
}

export function layoutAutomationGraph(parsed: ParsedWorkflow): AutomationLayout {
  const contentSizes = new Map<string, { width: number; height: number }>()
  const stepPositionsByJob = new Map<string, { x: number; y: number }[]>()

  for (const job of parsed.jobs) {
    const content = layoutJobContent(job)
    contentSizes.set(job.jobId, { width: content.width, height: content.height })
    stepPositionsByJob.set(job.jobId, content.stepPositions)
  }

  const framePositions = layoutJobFrames(parsed.jobs, contentSizes)

  const jobs: JobLayout[] = parsed.jobs.map((job) => {
    const size = contentSizes.get(job.jobId)!
    const pos = framePositions.get(job.jobId) ?? { x: 0, y: 0 }
    return {
      jobId: job.jobId,
      x: pos.x,
      y: pos.y,
      width: size.width,
      height: size.height,
      stepPositions: stepPositionsByJob.get(job.jobId) ?? [],
    }
  })

  const leftmostX = jobs.length > 0 ? Math.min(...jobs.map(j => j.x)) : 0
  const triggerColumnX = leftmostX - TRIGGER_W - JOB_GAP_X
  const totalTriggerHeight = parsed.triggers.length * TRIGGER_H + Math.max(0, parsed.triggers.length - 1) * (JOB_GAP_Y / 2)
  const jobsVerticalCenter = jobs.length > 0
    ? (Math.min(...jobs.map(j => j.y)) + Math.max(...jobs.map(j => j.y + j.height))) / 2
    : 0
  const triggerColumnTop = jobsVerticalCenter - totalTriggerHeight / 2

  const triggers: TriggerLayout[] = parsed.triggers.map((t, i) => ({
    kind: t.kind,
    x: triggerColumnX,
    y: triggerColumnTop + i * (TRIGGER_H + JOB_GAP_Y / 2),
  }))

  return { triggers, jobs }
}
