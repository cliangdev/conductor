'use client'

// The Plan tab's graph: who blocks whom inside one PRD, left to right (a blocker sits left of what it
// blocks). Only BLOCKS edges are drawn; DISCOVERED_FROM is provenance, not ordering.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { MarkerType, Position, type Edge, type Node } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import dagre from 'dagre'
import { Network } from 'lucide-react'
import { apiErrorMessage } from '@/lib/api'
import { fetchDependencyGraph } from '@/lib/delivery'
import { FlowCanvas } from '@/components/workflow/FlowCanvas'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { TASK_NODE_H, TASK_NODE_W, TaskNode } from '@/components/workitems/plan/TaskNode'
import type { DependencyGraph as DependencyGraphData } from '@/types/workItem'

const nodeTypes = { task: TaskNode } as const

/** One dagre left-to-right pass, as the lifecycle statechart diagram does top-to-bottom. */
function layout(nodes: Node[], edges: Edge[]): Node[] {
  const g = new dagre.graphlib.Graph()
  g.setDefaultEdgeLabel(() => ({}))
  g.setGraph({ rankdir: 'LR', nodesep: 24, ranksep: 80 })
  nodes.forEach((n) => g.setNode(n.id, { width: TASK_NODE_W, height: TASK_NODE_H }))
  edges.forEach((e) => g.setEdge(e.source, e.target))
  dagre.layout(g)
  return nodes.map((n) => {
    const { x, y } = g.node(n.id)
    return {
      ...n,
      position: { x: x - TASK_NODE_W / 2, y: y - TASK_NODE_H / 2 },
      sourcePosition: Position.Right,
      targetPosition: Position.Left,
    }
  })
}

export function buildGraph(graph: DependencyGraphData): { nodes: Node[]; edges: Edge[] } {
  const blocks = graph.edges.filter((e) => e.kind === 'BLOCKS')
  // Only items that take part in an ordering: a task nothing blocks and nothing waits on is just noise.
  const linked = new Set(blocks.flatMap((e) => [e.from, e.to]))
  const nodes: Node[] = graph.nodes
    .filter((n) => linked.has(n.id))
    .map((n) => ({
      id: n.id,
      type: 'task',
      position: { x: 0, y: 0 },
      data: {
        displayId: n.displayId,
        title: n.title,
        status: n.status,
        statusCategory: n.statusCategory,
        external: n.external,
      },
    }))
  const known = new Set(nodes.map((n) => n.id))
  const edges: Edge[] = blocks
    .filter((e) => known.has(e.from) && known.has(e.to))
    .map((e) => ({
      id: e.id,
      source: e.from,
      target: e.to,
      markerEnd: { type: MarkerType.ArrowClosed },
    }))
  return { nodes: layout(nodes, edges), edges }
}

export function DependencyGraph({ projectId, rootId, token }: { projectId: string; rootId: string; token: string }) {
  const [graph, setGraph] = useState<DependencyGraphData | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    setError(null)
    fetchDependencyGraph(projectId, rootId, token)
      .then(setGraph)
      .catch((err) => setError(apiErrorMessage(err, 'Couldn’t load the dependency graph.')))
  }, [projectId, rootId, token])

  useEffect(() => {
    load()
  }, [load])

  const built = useMemo(() => (graph ? buildGraph(graph) : null), [graph])

  if (error) {
    return (
      <Alert variant="warning">
        <div className="flex items-center justify-between gap-3">
          <span>{error}</span>
          <Button variant="link" size="sm" className="h-auto p-0" onClick={load}>
            Retry
          </Button>
        </div>
      </Alert>
    )
  }
  if (!built) {
    return <Skeleton className="h-[28rem] w-full" aria-busy="true" aria-label="Loading dependency graph" />
  }
  if (built.edges.length === 0) {
    return (
      <EmptyState
        icon={Network}
        title="No dependencies"
        description="When one task has to finish before another can start, the order shows up here."
      />
    )
  }
  return (
    <div className="h-[28rem] overflow-hidden rounded-lg border border-border">
      <FlowCanvas nodes={built.nodes} edges={built.edges} nodeTypes={nodeTypes} minimap />
    </div>
  )
}
