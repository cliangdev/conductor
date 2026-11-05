import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { DependencyGraph as Graph } from '@/types/workItem'

const { fetchDependencyGraph } = vi.hoisted(() => ({ fetchDependencyGraph: vi.fn() }))
vi.mock('@/lib/delivery', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/delivery')>()
  return { ...actual, fetchDependencyGraph }
})

// Expose the graph FlowCanvas is handed without mounting xyflow (needs ResizeObserver jsdom lacks).
vi.mock('@/components/workflow/FlowCanvas', () => ({
  FlowCanvas: ({
    nodes,
    edges,
  }: {
    nodes: { id: string; type?: string; position: { x: number; y: number }; data: { displayId: string; external: boolean } }[]
    edges: { id: string; source: string; target: string }[]
  }) => (
    <div data-testid="flow">
      {nodes.map((n) => (
        <div key={n.id} data-testid={`node-${n.id}`} data-type={n.type} data-x={n.position.x} data-external={String(n.data.external)}>
          {n.data.displayId}
        </div>
      ))}
      {edges.map((e) => (
        <div key={e.id} data-testid={`edge-${e.id}`}>
          {e.source}-{e.target}
        </div>
      ))}
    </div>
  ),
}))

import { DependencyGraph } from './DependencyGraph'

const GRAPH: Graph = {
  nodes: [
    { id: 'a', displayId: 'COND-2', title: 'Schema', status: 'DONE', statusCategory: 'terminal', external: false },
    { id: 'b', displayId: 'COND-3', title: 'Endpoint', status: 'TODO', statusCategory: 'open', external: false },
    { id: 'c', displayId: 'COND-77', title: 'Other PRD task', status: 'IN_PROGRESS', statusCategory: 'in_progress', external: true },
    { id: 'lonely', displayId: 'COND-5', title: 'Unrelated', status: 'TODO', statusCategory: 'open', external: false },
  ],
  edges: [
    { id: 'e1', from: 'a', to: 'b', kind: 'BLOCKS' },
    { id: 'e2', from: 'c', to: 'b', kind: 'BLOCKS' },
    { id: 'e3', from: 'b', to: 'lonely', kind: 'DISCOVERED_FROM' },
  ],
}

describe('DependencyGraph', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows a skeleton while loading', () => {
    fetchDependencyGraph.mockReturnValue(new Promise(() => {}))
    render(<DependencyGraph projectId="p1" rootId="prd" token="tok" />)
    expect(screen.getByLabelText('Loading dependency graph')).toBeInTheDocument()
  })

  it('draws the BLOCKS edges and the items they connect, blocker to the left', async () => {
    fetchDependencyGraph.mockResolvedValue(GRAPH)
    render(<DependencyGraph projectId="p1" rootId="prd" token="tok" />)

    expect(await screen.findByTestId('node-a')).toHaveTextContent('COND-2')
    expect(screen.getByTestId('node-b')).toHaveAttribute('data-type', 'task')
    expect(screen.getByTestId('edge-e1')).toHaveTextContent('a-b')
    expect(screen.getByTestId('edge-e2')).toHaveTextContent('c-b')
    // DISCOVERED_FROM is not an ordering, and an item with no BLOCKS edge is left out.
    expect(screen.queryByTestId('edge-e3')).not.toBeInTheDocument()
    expect(screen.queryByTestId('node-lonely')).not.toBeInTheDocument()
    // Left to right: the blocker sits at a smaller x than what it blocks.
    expect(Number(screen.getByTestId('node-a').dataset.x)).toBeLessThan(Number(screen.getByTestId('node-b').dataset.x))
  })

  it('marks a blocker outside the branch as external', async () => {
    fetchDependencyGraph.mockResolvedValue(GRAPH)
    render(<DependencyGraph projectId="p1" rootId="prd" token="tok" />)
    expect(await screen.findByTestId('node-c')).toHaveAttribute('data-external', 'true')
    expect(screen.getByTestId('node-a')).toHaveAttribute('data-external', 'false')
  })

  it('has an empty state when nothing blocks anything', async () => {
    fetchDependencyGraph.mockResolvedValue({ nodes: GRAPH.nodes, edges: [GRAPH.edges[2]] })
    render(<DependencyGraph projectId="p1" rootId="prd" token="tok" />)
    expect(await screen.findByText('No dependencies')).toBeInTheDocument()
    expect(screen.queryByTestId('flow')).not.toBeInTheDocument()
  })

  it('reports a load failure with a retry', async () => {
    fetchDependencyGraph.mockRejectedValue(new Error('boom'))
    render(<DependencyGraph projectId="p1" rootId="prd" token="tok" />)
    expect(await screen.findByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
