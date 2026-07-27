'use client'

// The Pipeline tab's "live health" strip (issue #342): one fixed node per pipeline stage, rendered
// on the shared FlowCanvas shell (see components/workflow/FlowCanvas.tsx) the same way the
// automation/lifecycle diagrams do. Positions are hardcoded, not dagre-laid-out — the topology is a
// known constant (WEBHOOKS -> FEEDS -> DIGESTS -> INBOX -> LIBRARIAN_RUNS -> PAGES_WRITTEN), so an
// auto-layout pass would be pure overhead for six nodes that never reflow. Top-to-bottom (not
// left-to-right): a single column reads top-to-bottom like the rest of the page, and keeps each
// stage's height independent of how many count buckets it has.

import { useMemo } from 'react'
import { Handle, Position, MarkerType, type Node, type Edge, type NodeTypes } from '@xyflow/react'
import { FlowCanvas } from '@/components/workflow/FlowCanvas'
import { statusHueClasses } from '@/components/ui/status-badge'
import { statusHue, humanizeId } from '@/lib/workflows'
import { cn } from '@/lib/utils'
import type { PipelineStage, PipelineStageHealth } from '@/lib/knowledge-api'

/** Bucket keys are camelCase (e.g. `setupRequired`) — split before humanizeId's underscore-based
 *  title-casing so "setupRequired" reads as "Setup Required", not "Setuprequired". */
function bucketLabel(bucket: string): string {
  return humanizeId(bucket.replace(/([a-z])([A-Z])/g, '$1_$2'))
}

const STAGE_ORDER: PipelineStage[] = ['WEBHOOKS', 'FEEDS', 'DIGESTS', 'INBOX', 'LIBRARIAN_RUNS', 'PAGES_WRITTEN']

// A wide, short row per stage (label + a single horizontal line of counts) rather than a tall
// narrow card — height stays constant regardless of a stage having 4 or 5 buckets, which is what a
// fixed-height card got wrong before (counts got clipped for the busier stages).
const NODE_W = 620
const NODE_H = 60
const NODE_GAP_Y = 44

const STAGE_POSITIONS: Record<PipelineStage, { x: number; y: number }> = Object.fromEntries(
  STAGE_ORDER.map((stage, i) => [stage, { x: 0, y: i * (NODE_H + NODE_GAP_Y) }]),
) as Record<PipelineStage, { x: number; y: number }>

// Count buckets that mean "something needs attention" for a stage's overall ring color — kept as a
// flat list rather than a per-stage scoring function, since every stage's "bad" buckets are just the
// terminal-failure-shaped ones (dead/failed/setupRequired/stale). `skipped` is deliberately absent:
// it's a legitimate no-op, not a problem (see docs/knowledge.md's "quiet by design" note).
const ATTENTION_BUCKETS = new Set(['dead', 'failed', 'setuprequired', 'stale'])

function overallHue(counts: Record<string, number>) {
  const hasAttention = Object.entries(counts).some(
    ([bucket, count]) => count > 0 && ATTENTION_BUCKETS.has(bucket.toLowerCase()),
  )
  return statusHue(hasAttention ? 'dead' : 'processed')
}

export interface PipelineStageNodeData {
  health: PipelineStageHealth
  [key: string]: unknown
}

function PipelineStageNode({ data }: { data: PipelineStageNodeData }) {
  const { health } = data
  const hue = statusHueClasses(overallHue(health.counts))
  const entries = Object.entries(health.counts)

  return (
    <div
      className={cn(
        'flex items-center gap-4 rounded-md border border-border bg-surface px-4 py-3 shadow-sm ring-2',
        hue.ring,
        hue.text,
      )}
      style={{ width: NODE_W, minHeight: NODE_H }}
    >
      <Handle type="target" position={Position.Top} className="!bg-current opacity-40" />
      <div className="w-32 shrink-0 truncate text-sm font-semibold text-foreground">{health.label}</div>
      <div className="flex flex-1 flex-wrap items-center gap-x-4 gap-y-1">
        {entries.map(([bucket, count]) => {
          const isSkipped = bucket.toLowerCase() === 'skipped'
          return (
            <div
              key={bucket}
              className={cn(
                'flex items-center gap-1.5 whitespace-nowrap text-[11px]',
                isSkipped ? 'text-foreground-subtle italic' : 'text-foreground-muted',
              )}
            >
              <span>{isSkipped ? 'skipped (by design)' : bucketLabel(bucket)}</span>
              <span className="font-mono tabular-nums text-foreground">{count}</span>
            </div>
          )
        })}
      </div>
      <Handle type="source" position={Position.Bottom} className="!bg-current opacity-40" />
    </div>
  )
}

const NODE_TYPES: NodeTypes = { pipelineStage: PipelineStageNode }

export interface PipelineFlowDiagramProps {
  stages: PipelineStageHealth[]
  onStageClick?: (stage: PipelineStage) => void
}

export function PipelineFlowDiagram({ stages, onStageClick }: PipelineFlowDiagramProps) {
  const byStage = useMemo(() => new Map(stages.map((s) => [s.stage, s])), [stages])
  const visibleStages = useMemo(() => STAGE_ORDER.filter((stage) => byStage.has(stage)), [byStage])

  const nodes: Node[] = useMemo(
    () =>
      visibleStages.map((stage) => ({
        id: stage,
        type: 'pipelineStage',
        position: STAGE_POSITIONS[stage],
        data: { health: byStage.get(stage)! } satisfies PipelineStageNodeData,
        draggable: false,
      })),
    [byStage, visibleStages],
  )

  // Only connects stages that are adjacent in the canonical STAGE_ORDER — a missing middle stage
  // (not expected today; PipelineHealthService always returns all six) doesn't get bridged over with
  // an edge implying a direct connection that isn't real.
  const edges: Edge[] = useMemo(() => {
    const result: Edge[] = []
    for (let i = 0; i < STAGE_ORDER.length - 1; i++) {
      const source = STAGE_ORDER[i]
      const target = STAGE_ORDER[i + 1]
      if (byStage.has(source) && byStage.has(target)) {
        result.push({
          id: `${source}-${target}`,
          source,
          target,
          type: 'straight',
          style: { stroke: 'hsl(var(--border-strong))' },
          markerEnd: { type: MarkerType.ArrowClosed },
        })
      }
    }
    return result
  }, [byStage])

  // Sized to fit the whole column at ~1:1 scale (fitView still handles any overflow), so the page
  // scrolls normally instead of the diagram needing its own internal pan/zoom to see every stage.
  const height = Math.max(1, visibleStages.length) * (NODE_H + NODE_GAP_Y) - NODE_GAP_Y + 32

  return (
    <div className="w-full" style={{ height }}>
      <FlowCanvas
        nodes={nodes}
        edges={edges}
        nodeTypes={NODE_TYPES}
        interactive={!!onStageClick}
        onNodeClick={onStageClick ? (_e, node) => onStageClick(node.id as PipelineStage) : undefined}
      />
    </div>
  )
}
