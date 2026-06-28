'use client'

// COND-18: a read-only diagram of a lifecycle Workflow — statuses as nodes (colored by category),
// transitions as edges (labelled; review-gated edges dashed). Mirrors WorkflowDiagram's xyflow +
// dagre approach, but renders the statechart rather than the YAML automation job graph.

import { useMemo } from 'react'
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  Handle,
  Position,
  MarkerType,
  type Node,
  type Edge,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from 'dagre'
import { categoryColor, humanizeId } from '@/lib/workflows'
import type { StatechartStatus, StatechartTransition } from '@/lib/workflowDefinition'

const NODE_W = 168
const NODE_H = 52

interface StatusNodeData {
  label: string
  category: string
  initial?: boolean
  terminal?: boolean
  [key: string]: unknown
}

function StatusNode({ data }: { data: StatusNodeData }) {
  return (
    <div
      className={`rounded-lg border px-3 py-2 text-xs font-medium text-center shadow-sm ${categoryColor(
        data.category,
      )} ${data.terminal ? 'border-2' : ''}`}
      style={{ width: NODE_W }}
    >
      <Handle type="target" position={Position.Top} className="!bg-current opacity-40" />
      <div className="truncate">{data.label}</div>
      <div className="mt-0.5 text-[10px] uppercase tracking-wide opacity-60">
        {data.initial ? 'initial · ' : ''}
        {data.category.replace(/_/g, ' ')}
      </div>
      <Handle type="source" position={Position.Bottom} className="!bg-current opacity-40" />
    </div>
  )
}

const nodeTypes = { status: StatusNode } as const

function layout(nodes: Node[], edges: Edge[]): Node[] {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'TB', nodesep: 50, ranksep: 60 })
  nodes.forEach((n) => g.setNode(n.id, { width: NODE_W, height: NODE_H }))
  edges.forEach((e) => {
    if (e.source !== e.target) g.setEdge(e.source, e.target)
  })
  dagre.layout(g)
  return nodes.map((n) => {
    const { x, y } = g.node(n.id)
    return { ...n, position: { x: x - NODE_W / 2, y: y - NODE_H / 2 } }
  })
}

function buildGraph(
  statuses: StatechartStatus[],
  transitions: StatechartTransition[],
): { nodes: Node[]; edges: Edge[] } {
  const nodes: Node[] = statuses.map((s) => ({
    id: s.id,
    type: 'status',
    position: { x: 0, y: 0 },
    data: {
      label: s.label || humanizeId(s.id),
      category: s.category,
      initial: s.initial,
      terminal: s.terminal,
    },
  }))

  const edges: Edge[] = transitions
    .filter((t) => t.from && t.to)
    .map((t, i) => ({
      id: `${t.from}->${t.to}-${i}`,
      source: t.from,
      target: t.to,
      label: t.label,
      labelStyle: { fontSize: 10 },
      animated: !!t.requiresReview,
      style: t.requiresReview ? { strokeDasharray: '5 3' } : undefined,
      markerEnd: { type: MarkerType.ArrowClosed },
    }))

  return { nodes: layout(nodes, edges), edges }
}

export function StatechartDiagram({
  statuses,
  transitions,
}: {
  statuses: StatechartStatus[]
  transitions: StatechartTransition[]
}) {
  // Defensive: a malformed or empty definition may omit these arrays — never crash on `.map`.
  const { nodes, edges } = useMemo(
    () => buildGraph(statuses ?? [], transitions ?? []),
    [statuses, transitions],
  )

  if (nodes.length === 0) {
    return (
      <div className="flex items-center justify-center h-full p-4 text-sm text-muted-foreground">
        No statuses defined yet
      </div>
    )
  }

  return (
    <ReactFlowProvider>
      <div className="relative h-full w-full">
        <div className="absolute inset-0 overflow-hidden rounded-md">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ padding: 0.3 }}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={false}
            proOptions={{ hideAttribution: true }}
          >
            <Background color="#e5e7eb" gap={16} />
          </ReactFlow>
        </div>
      </div>
    </ReactFlowProvider>
  )
}

export default StatechartDiagram
