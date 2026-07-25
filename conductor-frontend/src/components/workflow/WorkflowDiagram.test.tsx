import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { WorkflowStep, WorkflowTrigger } from '@/lib/workflowAutomation'
import type { JobFrameNodeData } from '@/components/workflow/automation/JobFrameNode'

vi.mock('@/lib/workflowStepSchema', () => ({
  useWorkflowStepSchema: () => undefined,
}))

vi.mock('@/components/workflow/WorkflowLogStream', () => ({
  WorkflowLogStream: () => <div data-testid="log-stream" />,
}))

// Mock @xyflow/react — expose nodes/edges via data-testid so we can assert on the graph shape
// without mounting real xyflow (which needs ResizeObserver etc. jsdom doesn't provide). Each node
// div is clickable, invoking the same onNodeClick prop the diagram passes to the real <ReactFlow>,
// so click-to-inspect wiring is covered without needing real xyflow interaction internals.
vi.mock('@xyflow/react', () => ({
  ReactFlow: ({ nodes, edges, onNodeClick }: {
    nodes: { id: string; type?: string; parentId?: string; data: Record<string, unknown> }[]
    edges: { id: string; label?: string; sourceHandle?: string }[]
    onNodeClick?: (event: unknown, node: unknown) => void
  }) => (
    <div data-testid="react-flow">
      {nodes.map(n => {
        const step = n.data.step as WorkflowStep | undefined
        const trigger = n.data.trigger as WorkflowTrigger | undefined
        const frame = n.type === 'jobFrame' ? (n.data as unknown as JobFrameNodeData) : undefined
        return (
          <div
            key={n.id}
            data-testid={`node-${n.id}`}
            data-type={n.type}
            data-parent={n.parentId ?? ''}
            onClick={() => onNodeClick?.({}, n)}
          >
            {step && <span data-testid={`node-stepkind-${n.id}`}>{step.kind}</span>}
            {step && <span data-testid={`node-stepname-${n.id}`}>{step.name ?? step.stepId ?? ''}</span>}
            {!!n.data.status && <span data-testid={`node-status-${n.id}`}>{String(n.data.status)}</span>}
            {trigger && <span data-testid={`node-triggerkind-${n.id}`}>{trigger.kind}</span>}
            {frame && (
              <span data-testid={`node-frame-${n.id}`}>
                {frame.jobId}
                {frame.runsOn ? ` runs-on:${frame.runsOn}` : ''}
                {frame.iteration ? ` iter:${frame.iteration.current ?? 0}/${frame.iteration.max ?? ''}` : ''}
              </span>
            )}
          </div>
        )
      })}
      {edges.map(e => (
        <div key={e.id} data-testid={`edge-${e.id}`} data-source-handle={e.sourceHandle ?? ''}>
          {e.label && <span data-testid={`edge-label-${e.id}`}>{String(e.label)}</span>}
        </div>
      ))}
    </div>
  ),
  Background: () => null,
  MiniMap: () => null,
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  Handle: () => null,
  Position: { Top: 'top', Bottom: 'bottom', Left: 'left', Right: 'right' },
  useReactFlow: () => ({ zoomIn: vi.fn(), zoomOut: vi.fn() }),
  MarkerType: { ArrowClosed: 'arrowclosed' },
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
  conductor.work_item.status_changed:
    statuses: [SUBMITTED]

jobs:
  notify:
    steps:
      - uses: action
        with:
          connector: discord
          message: "New issue submitted"
`

const DAG_YAML = `
on:
  conductor.work_item.status_changed:
    statuses: [APPROVED]

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
      - uses: action
        with:
          connector: discord
          message: "Deployed!"
`

const LOOP_YAML = `
on:
  workflow_dispatch: {}

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
  workflow_dispatch: {}

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
      - uses: action
        with:
          connector: discord
          message: "Not ready"
`

const DOCKER_YAML = `
on:
  workflow_dispatch: {}

jobs:
  build:
    steps:
      - uses: docker://node:18
        run: npm ci
`

const MULTI_TRIGGER_YAML = `
on:
  workflow_dispatch: {}
  webhook: {}

jobs:
  notify:
    steps:
      - type: http
        url: https://example.com
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

  it('renders one trigger node per declared trigger', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} />)
    const node = screen.getByTestId('node-trigger-work_item_status_changed')
    expect(node).toHaveAttribute('data-type', 'trigger')
    expect(screen.getByTestId('node-triggerkind-trigger-work_item_status_changed')).toHaveTextContent('work_item_status_changed')
  })

  it('renders a trigger node per trigger kind for multiple declared triggers', () => {
    render(<WorkflowDiagram yaml={MULTI_TRIGGER_YAML} />)
    expect(screen.getByTestId('node-trigger-workflow_dispatch')).toBeInTheDocument()
    expect(screen.getByTestId('node-trigger-webhook')).toBeInTheDocument()
  })

  it('renders a job frame node for each job', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} />)
    const frame = screen.getByTestId('node-job-notify')
    expect(frame).toHaveAttribute('data-type', 'jobFrame')
    expect(screen.getByTestId('node-frame-job-notify')).toHaveTextContent('notify')
  })

  it('renders one step node per step, parented to its job frame', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} />)
    const step = screen.getByTestId('node-step-notify::0')
    expect(step).toHaveAttribute('data-type', 'step')
    expect(step).toHaveAttribute('data-parent', 'job-notify')
    expect(screen.getByTestId('node-stepkind-step-notify::0')).toHaveTextContent('action')
  })

  it('resolves a docker uses: step to the docker kind', () => {
    render(<WorkflowDiagram yaml={DOCKER_YAML} />)
    expect(screen.getByTestId('node-stepkind-step-build::0')).toHaveTextContent('docker')
  })

  it('renders multiple step nodes for a multi-step job, in array order', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    expect(screen.getByTestId('node-step-deploy::0')).toHaveAttribute('data-parent', 'job-deploy')
    expect(screen.getByTestId('node-step-deploy::1')).toHaveAttribute('data-parent', 'job-deploy')
    expect(screen.getByTestId('node-stepkind-step-deploy::0')).toHaveTextContent('http')
    expect(screen.getByTestId('node-stepkind-step-deploy::1')).toHaveTextContent('action')
  })

  it('connects sequential steps within a job', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    expect(screen.getByTestId('edge-step-deploy::0->step-deploy::1')).toBeInTheDocument()
  })

  it('connects trigger to the first step of jobs with no needs', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    expect(screen.getByTestId('edge-trigger-work_item_status_changed->step-build::0')).toBeInTheDocument()
  })

  it('does not connect trigger to jobs that have needs', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    expect(screen.queryByTestId('edge-trigger-work_item_status_changed->step-deploy::0')).not.toBeInTheDocument()
  })

  it('connects a needs: dependency from the upstream job\'s last step to the downstream job\'s first step', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    expect(screen.getByTestId('edge-step-build::0->step-deploy::0')).toBeInTheDocument()
  })

  it('shows the if: condition as an edge label', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} />)
    const label = screen.getByTestId('edge-label-step-build::0->step-deploy::0')
    expect(label).toHaveTextContent(/if:/)
    expect(label).toHaveTextContent(/jobs.build.status/)
  })

  it('passes status to the job frame when jobStatuses is provided', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} jobStatuses={{ notify: 'SUCCESS' }} />)
    expect(screen.getByTestId('node-status-job-notify')).toHaveTextContent('SUCCESS')
  })

  it('shows FAILED/SKIPPED status on the relevant job frames', () => {
    render(<WorkflowDiagram yaml={DAG_YAML} jobStatuses={{ build: 'FAILED', deploy: 'SKIPPED' }} />)
    expect(screen.getByTestId('node-status-job-build')).toHaveTextContent('FAILED')
    expect(screen.getByTestId('node-status-job-deploy')).toHaveTextContent('SKIPPED')
  })

  it('renders LOOP_EXHAUSTED status on the job frame', () => {
    render(<WorkflowDiagram yaml={LOOP_YAML} jobStatuses={{ poll: 'LOOP_EXHAUSTED' }} />)
    expect(screen.getByTestId('node-status-job-poll')).toHaveTextContent('LOOP_EXHAUSTED')
  })

  it('renders a self-loop edge on the job frame for loop jobs', () => {
    render(<WorkflowDiagram yaml={LOOP_YAML} />)
    expect(screen.getByTestId('edge-job-poll->self-loop')).toBeInTheDocument()
    expect(screen.getByTestId('edge-label-job-poll->self-loop')).toHaveTextContent('loop')
  })

  it('renders a condition step node as the condition node type', () => {
    render(<WorkflowDiagram yaml={CONDITION_YAML} />)
    const condNode = screen.getByTestId('node-step-check_status::1')
    expect(condNode).toHaveAttribute('data-type', 'condition')
  })

  it('renders the true branch edge from the condition step\'s true handle', () => {
    render(<WorkflowDiagram yaml={CONDITION_YAML} />)
    const edge = screen.getByTestId('edge-step-check_status::1->then-deploy')
    expect(edge).toHaveAttribute('data-source-handle', 'true')
    expect(screen.getByTestId('edge-label-step-check_status::1->then-deploy')).toHaveTextContent('true')
  })

  it('renders the false branch edge from the condition step\'s false handle', () => {
    render(<WorkflowDiagram yaml={CONDITION_YAML} />)
    const edge = screen.getByTestId('edge-step-check_status::1->else-notify_fail')
    expect(edge).toHaveAttribute('data-source-handle', 'false')
    expect(screen.getByTestId('edge-label-step-check_status::1->else-notify_fail')).toHaveTextContent('false')
  })

  it('shows iteration annotation on the job frame when jobRunData provides it', () => {
    render(<WorkflowDiagram yaml={LOOP_YAML} jobRunData={{ poll: { status: 'RUNNING', iteration: 2, maxIterations: 5 } }} />)
    expect(screen.getByTestId('node-frame-job-poll')).toHaveTextContent('iter:2/5')
  })

  it('uses jobRunData status over jobStatuses when both are provided', () => {
    render(<WorkflowDiagram yaml={LOOP_YAML} jobStatuses={{ poll: 'PENDING' }} jobRunData={{ poll: { status: 'LOOP_EXHAUSTED' } }} />)
    expect(screen.getByTestId('node-status-job-poll')).toHaveTextContent('LOOP_EXHAUSTED')
  })

  // ── Click-to-inspect ─────────────────────────────────────────────────────────

  it('opens the step detail panel when a step node is clicked', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} />)
    expect(screen.queryByText('New issue submitted')).not.toBeInTheDocument()
    fireEvent.click(screen.getByTestId('node-step-notify::0'))
    expect(screen.getByText('Action')).toBeInTheDocument()
  })

  it('opens the panel for a condition step node', () => {
    render(<WorkflowDiagram yaml={CONDITION_YAML} />)
    fireEvent.click(screen.getByTestId('node-step-check_status::1'))
    expect(screen.getByText('Condition')).toBeInTheDocument()
  })

  it('does not open the panel when a trigger or job frame node is clicked', () => {
    render(<WorkflowDiagram yaml={SIMPLE_YAML} />)
    fireEvent.click(screen.getByTestId('node-trigger-work_item_status_changed'))
    fireEvent.click(screen.getByTestId('node-job-notify'))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
