import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { vi } from 'vitest'
import type { KnowledgeSourceCounts } from '@/lib/knowledge-api'
import type { Agent } from '@/lib/api'
import type { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow'

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let countsBehavior: () => Promise<KnowledgeSourceCounts> = () =>
  Promise.resolve({ pending: 0, processing: 0, processed: 0, dead: 0 })
let listWorkflowsBehavior: () => Promise<WorkflowDefinitionDto[]> = () => Promise.resolve([])
let listWorkflowRunsBehavior: () => Promise<WorkflowRunDto[]> = () => Promise.resolve([])
let listAgentsBehavior: () => Promise<Agent[]> = () => Promise.resolve([])

vi.mock('@/lib/knowledge-api', () => ({
  getKnowledgeSourceCounts: () => countsBehavior(),
}))

vi.mock('@/lib/workflows', async () => {
  // StatusBadge (rendered inside the strip) also imports statusHue/humanizeId from this module —
  // preserve the real exports and only override the two network-calling functions.
  const actual = await vi.importActual<typeof import('@/lib/workflows')>('@/lib/workflows')
  return {
    ...actual,
    listWorkflows: () => listWorkflowsBehavior(),
    listWorkflowRuns: () => listWorkflowRunsBehavior(),
  }
})

vi.mock('@/lib/api', () => ({
  listAgents: () => listAgentsBehavior(),
}))

import { KnowledgePipelineStrip } from './KnowledgePipelineStrip'

function workflow(overrides: Partial<WorkflowDefinitionDto> = {}): WorkflowDefinitionDto {
  return {
    id: 'wf-1',
    projectId: 'proj-1',
    name: 'knowledge-librarian',
    enabled: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function agent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: 'agent-1',
    projectId: 'proj-1',
    name: 'Knowledge Librarian',
    slug: 'knowledge-librarian',
    provider: 'claude',
    toolIds: [],
    state: 'ACTIVE',
    isDefault: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('KnowledgePipelineStrip', () => {
  beforeEach(() => {
    countsBehavior = () => Promise.resolve({ pending: 0, processing: 0, processed: 0, dead: 0 })
    listWorkflowsBehavior = () => Promise.resolve([])
    listWorkflowRunsBehavior = () => Promise.resolve([])
    listAgentsBehavior = () => Promise.resolve([])
  })

  it('renders the pending count', async () => {
    countsBehavior = () => Promise.resolve({ pending: 5, processing: 1, processed: 20, dead: 0 })

    render(<KnowledgePipelineStrip projectId="proj-1" token="tok" />)

    expect(await screen.findByText('5 pending')).toBeInTheDocument()
  })

  it('hides the dead badge when dead count is zero', async () => {
    countsBehavior = () => Promise.resolve({ pending: 2, processing: 0, processed: 10, dead: 0 })

    render(<KnowledgePipelineStrip projectId="proj-1" token="tok" />)

    await screen.findByText('2 pending')
    expect(screen.queryByText(/dead/)).not.toBeInTheDocument()
  })

  it('shows the dead badge when dead count is above zero', async () => {
    countsBehavior = () => Promise.resolve({ pending: 2, processing: 0, processed: 10, dead: 3 })

    render(<KnowledgePipelineStrip projectId="proj-1" token="tok" />)

    expect(await screen.findByText('3 dead')).toBeInTheDocument()
  })

  it('renders the last librarian run status and the Librarian link when both resolve', async () => {
    listWorkflowsBehavior = () => Promise.resolve([workflow()])
    listWorkflowRunsBehavior = () =>
      Promise.resolve([{ id: 'run-1', workflowId: 'wf-1', triggerType: 'workflow_dispatch', status: 'SUCCESS', startedAt: new Date().toISOString() }])
    listAgentsBehavior = () => Promise.resolve([agent()])

    render(<KnowledgePipelineStrip projectId="proj-1" token="tok" />)

    await waitFor(() => {
      expect(screen.getByText('Success')).toBeInTheDocument()
    })
    const librarianLink = screen.getByRole('link', { name: /librarian/i })
    expect(librarianLink).toHaveAttribute('href', '/app/projects/proj-1/agents/agent-1/overview')
  })

  it('omits the run segment when the librarian workflow is not provisioned', async () => {
    listWorkflowsBehavior = () => Promise.resolve([])

    render(<KnowledgePipelineStrip projectId="proj-1" token="tok" />)

    await screen.findByText('0 pending')
    expect(screen.queryByText('Last run')).not.toBeInTheDocument()
  })

  it('omits the librarian link when the agent is not found', async () => {
    listAgentsBehavior = () => Promise.resolve([])

    render(<KnowledgePipelineStrip projectId="proj-1" token="tok" />)

    await screen.findByText('0 pending')
    expect(screen.queryByRole('link', { name: /librarian/i })).not.toBeInTheDocument()
  })

  it('renders nothing when the counts fetch fails', async () => {
    countsBehavior = () => Promise.reject(new Error('boom'))

    const { container } = render(<KnowledgePipelineStrip projectId="proj-1" token="tok" />)

    await waitFor(() => {
      expect(container).toBeEmptyDOMElement()
    })
  })
})
