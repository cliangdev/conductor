import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import type { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow'

const push = vi.fn()
const mockShowToast = vi.fn()

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let listWorkflowsBehavior: () => Promise<WorkflowDefinitionDto[]> = () => Promise.resolve([])
let dispatchWorkflowBehavior: () => Promise<WorkflowRunDto> = () =>
  Promise.resolve({ id: 'run-1', workflowId: 'wf-bootstrap', triggerType: 'workflow_dispatch', status: 'RUNNING', startedAt: '2026-07-19T00:00:00Z' })

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/lib/workflows', async () => {
  const actual = await vi.importActual<typeof import('@/lib/workflows')>('@/lib/workflows')
  return {
    ...actual,
    listWorkflows: () => listWorkflowsBehavior(),
    dispatchWorkflow: (...args: unknown[]) => {
      dispatchArgs = args
      return dispatchWorkflowBehavior()
    },
  }
})

let dispatchArgs: unknown[] = []

import { KnowledgeBootstrapDialog } from './KnowledgeBootstrapDialog'

function bootstrapWorkflow(overrides: Partial<WorkflowDefinitionDto> = {}): WorkflowDefinitionDto {
  return {
    id: 'wf-bootstrap',
    projectId: 'proj-1',
    name: 'knowledge-bootstrap',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('KnowledgeBootstrapDialog', () => {
  beforeEach(() => {
    push.mockClear()
    mockShowToast.mockClear()
    dispatchArgs = []
    listWorkflowsBehavior = () => Promise.resolve([bootstrapWorkflow()])
    dispatchWorkflowBehavior = () =>
      Promise.resolve({ id: 'run-1', workflowId: 'wf-bootstrap', triggerType: 'workflow_dispatch', status: 'RUNNING', startedAt: '2026-07-19T00:00:00Z' })
  })

  it('disables Start until the repo matches owner/repo', () => {
    render(<KnowledgeBootstrapDialog projectId="proj-1" token="tok" onClose={vi.fn()} />)

    const startButton = screen.getByRole('button', { name: /^start$/i })
    expect(startButton).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/repository/i), { target: { value: 'not-a-repo' } })
    expect(startButton).toBeDisabled()

    fireEvent.change(screen.getByLabelText(/repository/i), { target: { value: 'cliangdev/conductor' } })
    expect(startButton).not.toBeDisabled()
  })

  it('dispatches the bootstrap workflow with the repo input and navigates to the runs tab', async () => {
    const onClose = vi.fn()
    render(<KnowledgeBootstrapDialog projectId="proj-1" token="tok" onClose={onClose} />)

    fireEvent.change(screen.getByLabelText(/repository/i), { target: { value: 'cliangdev/conductor' } })
    fireEvent.click(screen.getByRole('button', { name: /^start$/i }))

    await waitFor(() => expect(mockShowToast).toHaveBeenCalledWith('Bootstrap started — pages will appear as it works'))
    expect(dispatchArgs).toEqual(['proj-1', 'wf-bootstrap', { repo: 'cliangdev/conductor' }, 'tok'])
    expect(push).toHaveBeenCalledWith('/app/projects/proj-1/knowledge/activity?tab=runs')
    expect(onClose).toHaveBeenCalled()
  })

  it('shows a provisioning error when the bootstrap workflow is missing', async () => {
    listWorkflowsBehavior = () => Promise.resolve([])

    render(<KnowledgeBootstrapDialog projectId="proj-1" token="tok" onClose={vi.fn()} />)

    fireEvent.change(screen.getByLabelText(/repository/i), { target: { value: 'cliangdev/conductor' } })
    fireEvent.click(screen.getByRole('button', { name: /^start$/i }))

    await waitFor(() =>
      expect(mockShowToast).toHaveBeenCalledWith(expect.stringMatching(/bootstrap workflow not found/i), 'error'),
    )
    expect(push).not.toHaveBeenCalled()
  })
})
