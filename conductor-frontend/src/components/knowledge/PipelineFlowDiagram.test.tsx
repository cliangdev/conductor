import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { PipelineStageHealth, PipelineStageEdge } from '@/lib/knowledge-api'

// Same xyflow mocking approach as WorkflowDiagram.test.tsx — real xyflow needs ResizeObserver etc.
// jsdom doesn't provide, so we swap <ReactFlow> for a plain div that renders each node's `data`
// directly, keeping the diagram's node-building logic under test without mounting real xyflow.
vi.mock('@xyflow/react', () => ({
  ReactFlow: ({
    nodes,
    edges,
    onNodeClick,
  }: {
    nodes: { id: string; data: Record<string, unknown> }[]
    edges: { id: string; source: string; target: string }[]
    onNodeClick?: (event: unknown, node: unknown) => void
  }) => (
    <div data-testid="react-flow">
      {nodes.map((n) => {
        const health = n.data.health as PipelineStageHealth
        return (
          <div key={n.id} data-testid={`node-${n.id}`} onClick={() => onNodeClick?.({}, n)}>
            <span data-testid={`node-label-${n.id}`}>{health.label}</span>
            {Object.entries(health.counts).map(([bucket, count]) => (
              <span key={bucket} data-testid={`node-count-${n.id}-${bucket}`}>
                {count}
              </span>
            ))}
          </div>
        )
      })}
      {edges.map((e) => (
        <div key={e.id} data-testid={`edge-${e.id}`} />
      ))}
    </div>
  ),
  Background: () => null,
  MiniMap: () => null,
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  useReactFlow: () => ({ zoomIn: vi.fn(), zoomOut: vi.fn() }),
  Handle: () => null,
  Position: { Top: 'top', Bottom: 'bottom', Left: 'left', Right: 'right' },
  MarkerType: { ArrowClosed: 'arrowclosed' },
}))

import { PipelineFlowDiagram } from './PipelineFlowDiagram'

function stage(overrides: Partial<PipelineStageHealth> = {}): PipelineStageHealth {
  return {
    stage: 'WEBHOOKS',
    label: 'Webhooks',
    counts: { pending: 0, processed: 3, failed: 0, dead: 0 },
    ...overrides,
  }
}

// The pipeline's real 5-edge branching DAG (issue #342 correction) — WEBHOOKS and DIGESTS both feed
// INBOX, which is exactly the shape a flat linear chain could never represent.
const REAL_EDGES: PipelineStageEdge[] = [
  { from: 'WEBHOOKS', to: 'INBOX' },
  { from: 'FEEDS', to: 'DIGESTS' },
  { from: 'DIGESTS', to: 'INBOX' },
  { from: 'INBOX', to: 'LIBRARIAN_RUNS' },
  { from: 'LIBRARIAN_RUNS', to: 'PAGES_WRITTEN' },
]

describe('PipelineFlowDiagram', () => {
  it('renders all six stages with their counts', () => {
    const stages: PipelineStageHealth[] = [
      stage({ stage: 'WEBHOOKS', label: 'Webhooks' }),
      stage({ stage: 'FEEDS', label: 'Connector feeds', counts: { active: 2, paused: 0, setupRequired: 0, dead: 0, stale: 0 } }),
      stage({ stage: 'DIGESTS', label: 'Metrics digests', counts: { pending: 0, narrating: 0, submitted: 1, skipped: 12, dead: 0 } }),
      stage({ stage: 'INBOX', label: 'Knowledge inbox', counts: { pending: 1, processing: 0, processed: 5, dead: 0 } }),
      stage({ stage: 'LIBRARIAN_RUNS', label: 'Librarian runs', counts: { pending: 0, running: 0, success: 4, failed: 0, cancelled: 0 } }),
      stage({ stage: 'PAGES_WRITTEN', label: 'Pages written (30d)', counts: { written: 7 } }),
    ]

    render(<PipelineFlowDiagram stages={stages} edges={REAL_EDGES} />)

    for (const s of stages) {
      expect(screen.getByTestId(`node-label-${s.stage}`)).toHaveTextContent(s.label)
    }
    expect(screen.getByTestId('node-count-DIGESTS-skipped')).toHaveTextContent('12')
    expect(screen.getByTestId('node-count-WEBHOOKS-processed')).toHaveTextContent('3')

    // The real 5-edge DAG, including the fan-in at INBOX — both WEBHOOKS and DIGESTS feed it.
    const edgeIds = screen.getAllByTestId(/^edge-/).map((el) => el.getAttribute('data-testid'))
    expect(edgeIds).toHaveLength(5)
    expect(screen.getByTestId('edge-WEBHOOKS-INBOX')).toBeInTheDocument()
    expect(screen.getByTestId('edge-DIGESTS-INBOX')).toBeInTheDocument()
  })

  it('invokes onStageClick with the clicked stage', () => {
    const onStageClick = vi.fn()
    render(
      <PipelineFlowDiagram
        stages={[stage({ stage: 'INBOX', label: 'Knowledge inbox' })]}
        edges={REAL_EDGES}
        onStageClick={onStageClick}
      />,
    )

    fireEvent.click(screen.getByTestId('node-INBOX'))

    expect(onStageClick).toHaveBeenCalledWith('INBOX')
  })

  it('drops an edge whose endpoint stage is absent from the stages actually rendered', () => {
    render(
      <PipelineFlowDiagram
        stages={[
          stage({ stage: 'FEEDS', label: 'Connector feeds', counts: { active: 1 } }),
          stage({ stage: 'DIGESTS', label: 'Metrics digests', counts: { pending: 0 } }),
        ]}
        edges={REAL_EDGES}
      />,
    )

    expect(screen.getByTestId('node-FEEDS')).toBeInTheDocument()
    expect(screen.getByTestId('node-DIGESTS')).toBeInTheDocument()
    expect(screen.queryByTestId('node-WEBHOOKS')).not.toBeInTheDocument()

    // Only the edge whose both endpoints are present renders — WEBHOOKS->INBOX has neither.
    expect(screen.getAllByTestId(/^edge-/)).toHaveLength(1)
    expect(screen.getByTestId('edge-FEEDS-DIGESTS')).toBeInTheDocument()
    expect(screen.queryByTestId('edge-WEBHOOKS-INBOX')).not.toBeInTheDocument()
  })

  it('renders both fan-in edges into INBOX — the scenario a linear chain could never produce', () => {
    render(
      <PipelineFlowDiagram
        stages={[
          stage({ stage: 'WEBHOOKS', label: 'Webhooks' }),
          stage({ stage: 'FEEDS', label: 'Connector feeds', counts: { active: 1 } }),
          stage({ stage: 'DIGESTS', label: 'Metrics digests', counts: { pending: 0 } }),
          stage({ stage: 'INBOX', label: 'Knowledge inbox', counts: { pending: 0 } }),
        ]}
        edges={[
          { from: 'WEBHOOKS', to: 'INBOX' },
          { from: 'FEEDS', to: 'DIGESTS' },
          { from: 'DIGESTS', to: 'INBOX' },
        ]}
      />,
    )

    expect(screen.getByTestId('node-INBOX')).toBeInTheDocument()
    expect(screen.getByTestId('edge-WEBHOOKS-INBOX')).toBeInTheDocument()
    expect(screen.getByTestId('edge-DIGESTS-INBOX')).toBeInTheDocument()
  })
})
