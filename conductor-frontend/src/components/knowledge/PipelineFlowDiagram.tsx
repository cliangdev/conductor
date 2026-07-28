'use client'

// The Pipeline tab's "live health" strip (issue #342): one node per pipeline stage, laid out as a
// branching DAG — WEBHOOKS and FEEDS are independent producer paths that both feed INBOX (FEEDS by
// way of DIGESTS first), continuing through LIBRARIAN_RUNS to PAGES_WRITTEN. The shape comes from the
// backend's `edges` field (`PipelineTopology` on the server), never assumed from array position — see
// pipelineLayout.ts for the dagre pass that turns {stages, edges} into node/edge positions.

import { useMemo } from 'react'
import { Handle, Position, MarkerType, type Node, type Edge, type NodeTypes } from '@xyflow/react'
import { FlowCanvas } from '@/components/workflow/FlowCanvas'
import { statusHueClasses } from '@/components/ui/status-badge'
import { statusHue, humanizeId } from '@/lib/workflows'
import { cn } from '@/lib/utils'
import type { PipelineStage, PipelineStageHealth, PipelineStageEdge } from '@/lib/knowledge-api'
import { layoutPipelineGraph, NODE_W } from './pipelineLayout'

/** Bucket keys are camelCase (e.g. `setupRequired`) — split before humanizeId's underscore-based
 *  title-casing so "setupRequired" reads as "Setup Required", not "Setuprequired". */
function bucketLabel(bucket: string): string {
  return humanizeId(bucket.replace(/([a-z])([A-Z])/g, '$1_$2'))
}

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
        'flex flex-col gap-1.5 rounded-md border border-border bg-surface px-4 py-3 shadow-sm ring-2',
        hue.ring,
        hue.text,
      )}
      style={{ width: NODE_W }}
    >
      <Handle type="target" position={Position.Top} className="!bg-current opacity-40" />
      <div className="truncate text-sm font-semibold text-foreground">{health.label}</div>
      <div className="flex flex-col gap-0.5">
        {entries.map(([bucket, count]) => {
          const isSkipped = bucket.toLowerCase() === 'skipped'
          return (
            <div
              key={bucket}
              className={cn(
                'flex items-center justify-between text-[11px]',
                isSkipped ? 'text-foreground-subtle italic' : 'text-foreground-muted',
              )}
            >
              <span className="truncate">{isSkipped ? 'skipped (by design)' : bucketLabel(bucket)}</span>
              <span className="shrink-0 font-mono tabular-nums text-foreground">{count}</span>
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
  edges: PipelineStageEdge[]
  onStageClick?: (stage: PipelineStage) => void
}

export function PipelineFlowDiagram({ stages, edges, onStageClick }: PipelineFlowDiagramProps) {
  const layout = useMemo(() => layoutPipelineGraph(stages, edges), [stages, edges])
  // Guards the same rolling-deploy version-skew case layoutPipelineGraph does — stages/edges are typed
  // as required, but a live backend a step behind this frontend's deploy could still omit either field.
  const byStage = useMemo(() => new Map((stages ?? []).map((s) => [s.stage, s])), [stages])

  const nodes: Node[] = useMemo(
    () =>
      layout.nodes.map((n) => ({
        id: n.stage,
        type: 'pipelineStage',
        position: { x: n.x, y: n.y },
        data: { health: byStage.get(n.stage)! } satisfies PipelineStageNodeData,
        draggable: false,
      })),
    [layout, byStage],
  )

  const flowEdges: Edge[] = useMemo(
    () =>
      layout.edges.map((e) => ({
        id: e.id,
        source: e.from,
        target: e.to,
        type: 'smoothstep',
        pathOptions: { borderRadius: 8 },
        style: { stroke: 'hsl(var(--border-strong))' },
        markerEnd: { type: MarkerType.ArrowClosed },
      })),
    [layout],
  )

  // Sized to the actual laid-out extent (varies with node count/height and the branching shape, not
  // a single fixed column anymore) so the page scrolls normally instead of the diagram needing its
  // own internal pan/zoom to see every stage.
  const maxY = layout.nodes.length > 0 ? Math.max(...layout.nodes.map((n) => n.y + n.height)) : 0
  const minY = layout.nodes.length > 0 ? Math.min(...layout.nodes.map((n) => n.y)) : 0
  const height = Math.max(1, maxY - minY) + 32

  return (
    <div className="w-full" style={{ height }}>
      <FlowCanvas
        nodes={nodes}
        edges={flowEdges}
        nodeTypes={NODE_TYPES}
        interactive={!!onStageClick}
        onNodeClick={onStageClick ? (_e, node) => onStageClick(node.id as PipelineStage) : undefined}
      />
    </div>
  )
}
