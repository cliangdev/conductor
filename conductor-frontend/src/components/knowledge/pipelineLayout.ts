// Pure graph-shape logic for the Pipeline tab's health diagram (issue #342) — kept separate from
// PipelineFlowDiagram.tsx so it stays unit-testable without mounting React/xyflow, the same split
// `automation/layout.ts` uses for the workflow diagram.
//
// Unlike a fixed vertical stack, the pipeline's stages form a genuine branching DAG (WEBHOOKS and
// FEEDS are independent producer paths that both feed INBOX) sourced from the backend's `edges` field
// — never assumed from array position. One dagre(rankdir: 'TB') pass places every stage; dagre's
// default ranker (not a hand-pinned rank) decides how WEBHOOKS's long skip-edge into INBOX actually
// routes, which is simpler and more honest than forcing a particular visual shape.

import dagre from 'dagre'
import type { PipelineStage, PipelineStageHealth, PipelineStageEdge } from '@/lib/knowledge-api'

export const NODE_W = 280
const HEADER_H = 32
const ROW_H = 22
const PAD_Y = 24
const NODESEP = 40
const RANKSEP = 70

export interface PipelineNodeLayout {
  stage: PipelineStage
  x: number
  y: number
  width: number
  height: number
}

export interface PipelineEdgeLayout {
  id: string
  from: PipelineStage
  to: PipelineStage
}

export interface PipelineLayoutResult {
  nodes: PipelineNodeLayout[]
  edges: PipelineEdgeLayout[]
}

/** Label row + one row per count bucket + top/bottom padding — the same "auto-height so a 4-bucket
 *  and 5-bucket stage both render fully" invariant as before, just computed up front since dagre
 *  needs an explicit size per node rather than letting CSS reflow decide it. */
export function nodeHeight(health: PipelineStageHealth): number {
  return HEADER_H + Object.keys(health.counts).length * ROW_H + PAD_Y
}

export function layoutPipelineGraph(
  stages: PipelineStageHealth[],
  edges: PipelineStageEdge[],
): PipelineLayoutResult {
  const byStage = new Map(stages.map((s) => [s.stage, s]))

  // A stage missing from the health response drops any edge touching it — the data-driven equivalent
  // of "don't bridge over a gap that isn't there."
  const visibleEdges = edges.filter((e) => byStage.has(e.from) && byStage.has(e.to))

  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: NODESEP, ranksep: RANKSEP })

  for (const health of stages) {
    g.setNode(health.stage, { width: NODE_W, height: nodeHeight(health) })
  }
  for (const edge of visibleEdges) {
    g.setEdge(edge.from, edge.to)
  }

  dagre.layout(g)

  const nodes: PipelineNodeLayout[] = stages.map((health) => {
    const height = nodeHeight(health)
    const { x, y } = g.node(health.stage)
    // dagre positions are center-based; xyflow positions are top-left based.
    return { stage: health.stage, x: x - NODE_W / 2, y: y - height / 2, width: NODE_W, height }
  })

  const layoutEdges: PipelineEdgeLayout[] = visibleEdges.map((e) => ({
    id: `${e.from}-${e.to}`,
    from: e.from,
    to: e.to,
  }))

  return { nodes, edges: layoutEdges }
}
