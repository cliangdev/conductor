import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { StepDetailPanel } from './StepDetailPanel'
import type { WorkflowStep } from '@/lib/workflowAutomation'
import type { WorkflowStepRunDto } from '@/types/workflow'

vi.mock('@/lib/workflowStepSchema', () => ({
  useWorkflowStepSchema: () => undefined,
}))

vi.mock('@/components/workflow/WorkflowLogStream', () => ({
  WorkflowLogStream: ({ runId }: { runId: string }) => <div data-testid="log-stream">streaming {runId}</div>,
}))

function httpStep(overrides: Partial<WorkflowStep> = {}): WorkflowStep {
  return {
    stepId: 'fetch',
    kind: 'http',
    raw: { id: 'fetch', type: 'http', url: 'https://api.example.com/status', method: 'GET' },
    ...overrides,
  }
}

describe('StepDetailPanel', () => {
  it('renders nothing when step is null', () => {
    const { container } = render(
      <StepDetailPanel open onOpenChange={vi.fn()} step={null} />
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('shows the step title, kind label, and config entries excluding structural keys', () => {
    render(<StepDetailPanel open onOpenChange={vi.fn()} step={httpStep()} />)
    expect(screen.getByText('fetch')).toBeInTheDocument()
    expect(screen.getByText('HTTP request')).toBeInTheDocument()
    expect(screen.getByText('url')).toBeInTheDocument()
    expect(screen.getByText('https://api.example.com/status')).toBeInTheDocument()
    expect(screen.getByText('method')).toBeInTheDocument()
    // structural keys (id/type) must not appear as config rows
    expect(screen.queryByText('id')).not.toBeInTheDocument()
    expect(screen.queryByText('type')).not.toBeInTheDocument()
  })

  it('reads config from the with: block for uses: steps', () => {
    const step = httpStep({
      kind: 'integration',
      raw: { id: 'collect', uses: 'integration', with: { connector: 'gsc', operation: 'top_pages' } },
    })
    render(<StepDetailPanel open onOpenChange={vi.fn()} step={step} />)
    expect(screen.getByText('connector')).toBeInTheDocument()
    expect(screen.getByText('gsc')).toBeInTheDocument()
    expect(screen.getByText('operation')).toBeInTheDocument()
    // the with: block itself is structural and must not appear as a row
    expect(screen.queryByText('with')).not.toBeInTheDocument()
  })

  it('shows a status badge when run data is present', () => {
    const runData: WorkflowStepRunDto = {
      id: 'run-step-1', stepId: 'fetch', stepName: 'fetch', stepType: 'http', status: 'SUCCESS',
    }
    render(<StepDetailPanel open onOpenChange={vi.fn()} step={httpStep()} runData={runData} />)
    expect(screen.getByText('Success')).toBeInTheDocument()
  })

  it('renders condition expression/result/branch for a condition step with run data', () => {
    const step: WorkflowStep = {
      stepId: 'route', kind: 'condition', raw: { id: 'route', type: 'condition' },
      then: 'deploy', else: 'notify_fail', expression: "${{ event.env == 'production' }}",
    }
    const runData: WorkflowStepRunDto = {
      id: 'run-step-2', stepId: 'route', stepName: 'route', stepType: 'condition', status: 'SUCCESS',
      outputJson: JSON.stringify({ expression: "event.env == 'production'", result: true, branch: 'then' }),
    }
    render(<StepDetailPanel open onOpenChange={vi.fn()} step={step} runData={runData} />)
    expect(screen.getByText(/event.env == 'production'/)).toBeInTheDocument()
    expect(screen.getByText('true')).toBeInTheDocument()
    expect(screen.getByText('then')).toBeInTheDocument()
  })

  it('renders the outputs table when run data has outputJson', () => {
    const runData: WorkflowStepRunDto = {
      id: 'run-step-3', stepId: 'fetch', stepName: 'fetch', stepType: 'http', status: 'SUCCESS',
      outputJson: JSON.stringify({ status: 'ready' }),
    }
    render(<StepDetailPanel open onOpenChange={vi.fn()} step={httpStep()} runData={runData} />)
    expect(screen.getByText('Outputs')).toBeInTheDocument()
    expect(screen.getByText('status')).toBeInTheDocument()
    expect(screen.getByText('ready')).toBeInTheDocument()
  })

  it('streams live logs for a running docker step', () => {
    const step = httpStep({ kind: 'docker', raw: { id: 'build', uses: 'docker://node:18', run: 'npm ci' } })
    const runData: WorkflowStepRunDto = {
      id: 'run-step-4', stepId: 'build', stepName: 'build', stepType: 'docker', status: 'RUNNING',
    }
    render(<StepDetailPanel open onOpenChange={vi.fn()} step={step} runData={runData} runId="run-1" />)
    expect(screen.getByTestId('log-stream')).toHaveTextContent('streaming run-1')
  })

  it('shows the error reason when a step failed', () => {
    const runData: WorkflowStepRunDto = {
      id: 'run-step-5', stepId: 'fetch', stepName: 'fetch', stepType: 'http', status: 'FAILED',
      errorReason: 'connection timed out',
    }
    render(<StepDetailPanel open onOpenChange={vi.fn()} step={httpStep()} runData={runData} />)
    expect(screen.getByText('connection timed out')).toBeInTheDocument()
  })
})
