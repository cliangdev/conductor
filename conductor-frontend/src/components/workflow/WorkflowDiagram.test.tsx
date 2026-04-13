import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

// Mock @xyflow/react — expose nodes/edges via data-testid so we can assert on the graph
vi.mock('@xyflow/react', () => ({
  ReactFlow: ({ nodes, edges }: { nodes: { id: string; type?: string; data: Record<string, unknown> }[]; edges: { id: string; label?: string }[] }) => (
    <div data-testid="react-flow">
      {nodes.map(n => (
        <div key={n.id} data-testid={`node-${n.id}`} data-type={n.type}>
          <span data-testid={`node-label-${n.id}`}>{String(n.data.label ?? '')}</span>
          {n.data.stepInfo && <span data-testid={`node-stepinfo-${n.id}`}>{String(n.data.stepInfo)}</span>}
          {n.data.status && <span data-testid={`node-status-${n.id}`}>{String(n.data.status)}</span>}
        </div>
      ))}
      {edges.map(e => (
        <div key={e.id} data-testid={`edge-${e.id}`}>
          {e.label && <span data-testid={`edge-label-${e.id}`}>{String(e.label)}</span>}
        </div>
      ))}
    </div>
  ),
  Background: () => null,
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Handle: () => null,
  Position: { Top: 'top', Bottom: 'bottom' },
  useReactFlow: () => ({ zoomIn: vi.fn(), zoomOut: vi.fn() }),
}))

// Mock dagre — stub layout so it doesn't crash in jsdom
vi.mock('dagre', () => {
  class Graph {
    private _nodes = new Map<string, { x: number; y: number; width: number; height: number }>()
    setDefaultEdgeLabel() {}
    setGraph() {}
    setNode(id: string, attrs: { width: number; height: number }) {
      this._nodes.set(id, { x: 100, y: 100 * (this._nodes.size + 1), ...attrs })
    }
    setEdge() {}
    node(id: string) {
      return this._nodes.get(id) ?? { x: 0, y: 0, width: 200, height: 64 }
    }
  }
  return {
    default: {
      graphlib: { Graph },
      layout: () => {},
    },
  }
})

import WorkflowDiagram from './WorkflowDiagram'

const SIMPLE_YAML = `
on:
  issue_state_change:
    states: [SUBMITTED]

jobs:
  notify:
    steps:
      - type: discord
        message: "New issue submitted"
`

const DAG_YAML = `
on:
  issue_state_change:
    states: [APPROVED]

jobs:
  build:
    steps:
      - type: http
        url: https://ci.example.com/build
  deploy:
    needs: [build]
    if: \${{ jobs.build.status == 'success' }}
    steps:
      - type: http
        url: https://ci.example.com/deploy
      - type: discord
        message: "Deployed!"
`

const LOOP_YAML = `
on:
  workflow_dispatch:

jobs:
  poll:
    loop:
      max_iterations: 5
      until: \${{ steps.check.outputs.done == 'true' }}
    steps:
      - type: http
        id: check
        url: https://api.example.com/status
`

const CONDITION_YAML = `
on:
  workflow_dispatch:

jobs:
  check_status:
    steps:
      - type: http
        id: fetch
        url: https://api.example.com/status
      - type: condition
        expression: \${{ steps.fetch.outputs.status == 'ready' }}
        then: deploy
        else: notify_fail
  deploy:
    needs: [check_status]
    steps:
      - type: http
        url: https://deploy.example.com
  notify_fail:
    needs: [check_status]
    steps:
      - type: discord
        message: "Not ready"
`

const DOCKER_YAML = `
on:
  workflow_dispatch:

jobs:
  build:
    steps:
      - uses: docker://node:18
        run: npm ci
`

describe('WorkflowDiagram', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows empty state for blank yaml', () => {
    render(<WorkflowDiagram yaml="" />)
    expect(screen.getByText('No workflow defined yet')).toBeInTheDocument()
    expect(screen.queryByTestId('react-flow')).not.toBeInTheDocument()
  })

  it('shows error banner for invalid yaml', () => {
    render(<WorkflowDiagram yaml=": invalid: [yaml" />)
    expect(screen.getByText(/Invalid YAML/i)).toBeInTheDocument()
    expect(screen.queryByTestId('react-flow')).not.toBeInTheDocument()
  })

  it('renders trigger node with event name', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} />)
    expect(screen.getByTestId('node-__trigger__')).toBeInTheDocument()
    expect(screen.getByTestId('node-label-__trigger__')).toHaveTextContent('issue_state_change')
  })

  it('renders job nodes for each job in the yaml', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} />)
    expect(screen.getByTestId('node-notify')).toBeInTheDocument()
    expect(screen.getByTestId('node-label-notify')).toHaveTextContent('notify')
  })

  it('shows step count and type in job node', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} />)
    expect(screen.getByTestId('node-stepinfo-notify')).toHaveTextContent('1 step · discord')
  })

  it('renders multiple jobs and trigger→job edges', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    expect(screen.getByTestId('node-build')).toBeInTheDocument()
    expect(screen.getByTestId('node-deploy')).toBeInTheDocument()
    // trigger → build (no needs)
    expect(screen.getByTestId('edge-__trigger__->build')).toBeInTheDocument()
    // build → deploy (has needs)
    expect(screen.getByTestId('edge-build->deploy')).toBeInTheDocument()
  })

  it('shows conditional label on edges with if condition', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    const edgeLabel = screen.getByTestId('edge-label-build->deploy')
    expect(edgeLabel).toHaveTextContent(/if:/)
    expect(edgeLabel).toHaveTextContent(/jobs.build.status/)
  })

  it('passes status to job node when jobStatuses provided', () => {
    render(
      <WorkflowDiagram
        yaml={SIMPLE_YAML}
        jobStatuses={{ notify: 'SUCCESS' }}
      />
    )
    expect(screen.getByTestId('node-status-notify')).toHaveTextContent('SUCCESS')
  })

  it('shows FAILED status on job node', () => {
    render(
      <WorkflowDiagram
        yaml={DAG_YAML}
        jobStatuses={{ build: 'FAILED', deploy: 'SKIPPED' }}
      />
    )
    expect(screen.getByTestId('node-status-build')).toHaveTextContent('FAILED')
    expect(screen.getByTestId('node-status-deploy')).toHaveTextContent('SKIPPED')
  })

  it('shows step count with plural for multiple steps', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    expect(screen.getByTestId('node-stepinfo-deploy')).toHaveTextContent('2 steps · http, discord')
  })

  it('does not connect trigger to jobs that have needs', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    // deploy has `needs: [build]` so should NOT have trigger→deploy edge
    expect(screen.queryByTestId('edge-__trigger__->deploy')).not.toBeInTheDocument()
  })

  // ── LOOP_EXHAUSTED status ────────────────────────────────────────────────────

  it('renders LOOP_EXHAUSTED status on job node', () => {
    render(
      <WorkflowDiagram
        yaml={LOOP_YAML}
        jobStatuses={{ poll: 'LOOP_EXHAUSTED' }}
      />
    )
    expect(screen.getByTestId('node-status-poll')).toHaveTextContent('LOOP_EXHAUSTED')
  })

  // ── Loop self-edge ───────────────────────────────────────────────────────────

  it('renders self-loop edge for loop jobs', () => {
    render(<WorkflowDiagram yaml={LOOP_YAML} />)
    expect(screen.getByTestId('edge-poll->self-loop')).toBeInTheDocument()
    expect(screen.getByTestId('edge-label-poll->self-loop')).toHaveTextContent('↺ loop')
  })

  // ── ConditionNode ────────────────────────────────────────────────────────────

  it('renders condition job as condition node type', () => {
    render(<WorkflowDiagram yaml={CONDITION_YAML} />)
    const condNode = screen.getByTestId('node-check_status')
    expect(condNode).toBeInTheDocument()
    expect(condNode).toHaveAttribute('data-type', 'condition')
  })

  it('renders true edge from condition job', () => {
    render(<WorkflowDiagram yaml={CONDITION_YAML} />)
    expect(screen.getByTestId('edge-check_status->then-deploy')).toBeInTheDocument()
    expect(screen.getByTestId('edge-label-check_status->then-deploy')).toHaveTextContent('true')
  })

  it('renders false edge from condition job', () => {
    render(<WorkflowDiagram yaml={CONDITION_YAML} />)
    expect(screen.getByTestId('edge-check_status->else-notify_fail')).toBeInTheDocument()
    expect(screen.getByTestId('edge-label-check_status->else-notify_fail')).toHaveTextContent('false')
  })

  // ── Docker label ─────────────────────────────────────────────────────────────

  it('appends [docker] to stepInfo for docker steps', () => {
    render(<WorkflowDiagram yaml={DOCKER_YAML} />)
    expect(screen.getByTestId('node-stepinfo-build')).toHaveTextContent('[docker]')
  })

  // ── jobRunData / iteration annotation ────────────────────────────────────────

  it('shows iteration annotation on loop node when jobRunData provided', () => {
    render(
      <WorkflowDiagram
        yaml={LOOP_YAML}
        jobRunData={{ poll: { status: 'RUNNING', iteration: 2, maxIterations: 5 } }}
      />
    )
    expect(screen.getByTestId('node-label-poll')).toHaveTextContent('poll · 2/5')
  })

  it('uses jobRunData status over jobStatuses when both provided', () => {
    render(
      <WorkflowDiagram
        yaml={LOOP_YAML}
        jobStatuses={{ poll: 'PENDING' }}
        jobRunData={{ poll: { status: 'LOOP_EXHAUSTED' } }}
      />
    )
    expect(screen.getByTestId('node-status-poll')).toHaveTextContent('LOOP_EXHAUSTED')
  })
})
