'use client'

// The Pipeline tab's "live health" strip (issue #342): one fixed node per pipeline stage, rendered
// on the shared FlowCanvas shell (see components/workflow/FlowCanvas.tsx) the same way the
// automation/lifecycle diagrams do. Positions are hardcoded, not dagre-laid-out — the topology is a
// known constant (WEBHOOKS -> FEEDS -> DIGESTS -> INBOX -> LIBRARIAN_RUNS -> PAGES_WRITTEN), so an
// auto-layout pass would be pure overhead for six nodes that never reflow.

import { useMemo } from 'react'
import type { Node, Edge, NodeTypes } from '@xyflow/react'
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

const NODE_W = 168
const NODE_H = 108
const NODE_GAP_X = 220

const STAGE_POSITIONS: Record<PipelineStage, { x: number; y: number }> = Object.fromEntries(
  STAGE_ORDER.map((stage, i) => [stage, { x: i * NODE_GAP_X, y: 0 }]),
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
  const ring = statusHueClasses(overallHue(health.counts))
  const entries = Object.entries(health.counts)

  return (
    <div
      className={cn('flex flex-col gap-1.5 rounded-md border border-border bg-surface px-3 py-2.5 shadow-sm ring-2', ring.ring)}
      style={{ width: NODE_W, height: NODE_H }}
    >
      <div className="truncate text-xs font-semibold text-foreground">{health.label}</div>
      <div className="flex-1 space-y-0.5 overflow-hidden">
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
              <span className="shrink-0 font-mono tabular-nums">{count}</span>
            </div>
          )
        })}
      </div>
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

  const nodes: Node[] = useMemo(
    () =>
      STAGE_ORDER.filter((stage) => byStage.has(stage)).map((stage) => ({
        id: stage,
        type: 'pipelineStage',
        position: STAGE_POSITIONS[stage],
        data: { health: byStage.get(stage)! } satisfies PipelineStageNodeData,
        draggable: false,
      })),
    [byStage],
  )

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
        })
      }
    }
    return result
  }, [byStage])

  return (
    <div className="h-[180px] w-full">
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
