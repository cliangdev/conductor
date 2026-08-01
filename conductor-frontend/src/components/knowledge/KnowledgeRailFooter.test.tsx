import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { KnowledgeSourceCounts, KnowledgeDomainDto } from '@/lib/knowledge-api'
import type { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow'

let canManage = true

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let countsBehavior: () => Promise<KnowledgeSourceCounts> = () =>
  Promise.resolve({ pending: 0, processing: 0, processed: 0, skipped: 0, dead: 0 })
let listWorkflowsBehavior: () => Promise<WorkflowDefinitionDto[]> = () => Promise.resolve([])
let listWorkflowRunsBehavior: () => Promise<WorkflowRunDto[]> = () => Promise.resolve([])
let listDomainsBehavior: () => Promise<KnowledgeDomainDto[]> = () => Promise.resolve([])

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({
    role: canManage ? 'ADMIN' : 'CREATOR',
    loading: false,
    can: (cap: string) => (cap === 'workspace.manage' ? canManage : false),
    refresh: vi.fn(),
  }),
}))

vi.mock('@/lib/knowledge-api', async () => ({
  // Preserve real exports (KNOWLEDGE_LIBRARIAN_SLUG etc.) — components under test import
  // constants from this module, not just the network functions overridden below.
  ...(await vi.importActual<typeof import('@/lib/knowledge-api')>('@/lib/knowledge-api')),
  getKnowledgeSourceCounts: () => countsBehavior(),
  listKnowledgeDomains: () => listDomainsBehavior(),
}))

vi.mock('@/lib/workflows', async () => {
  // StatusBadge (rendered inside the footer) also imports statusHue/humanizeId from this module —
  // preserve the real exports and only override the two network-calling functions.
  const actual = await vi.importActual<typeof import('@/lib/workflows')>('@/lib/workflows')
  return {
    ...actual,
    listWorkflows: () => listWorkflowsBehavior(),
    listWorkflowRuns: () => listWorkflowRunsBehavior(),
  }
})

import { KnowledgeRailFooter } from './KnowledgeRailFooter'

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

function domain(overrides: Partial<KnowledgeDomainDto> = {}): KnowledgeDomainDto {
  return {
    slug: 'engineering',
    displayName: 'Engineering',
    pathPrefix: 'engineering/',
    schemaPagePath: 'engineering/_schema.md',
    sourceTypePatterns: [],
    owningAgentSlug: null,
    state: 'SUGGESTED',
    pendingCount: 0,
    processingCount: 0,
    processedCount: 0,
    ...overrides,
  }
}

describe('KnowledgeRailFooter', () => {
  beforeEach(() => {
    canManage = true
    countsBehavior = () => Promise.resolve({ pending: 0, processing: 0, processed: 0, skipped: 0, dead: 0 })
    listWorkflowsBehavior = () => Promise.resolve([])
    listWorkflowRunsBehavior = () => Promise.resolve([])
    listDomainsBehavior = () => Promise.resolve([])
  })

  it('shows "up to date" when there are no dead sources, no failed run, and nothing pending/processing', async () => {
    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Librarian · up to date')).toBeInTheDocument()
    const link = screen.getByText('Librarian · up to date').closest('a')
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/knowledge/activity')
  })

  it('shows "needs attention" when dead count is above zero, linking to the DEAD filter', async () => {
    countsBehavior = () => Promise.resolve({ pending: 0, processing: 0, processed: 10, skipped: 0, dead: 2 })

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Librarian · needs attention')).toBeInTheDocument()
    const link = screen.getByText('Librarian · needs attention').closest('a')
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/knowledge/activity?tab=inbox&status=DEAD')
  })

  it('shows "needs attention" when the last librarian run failed, even with zero dead sources', async () => {
    listWorkflowsBehavior = () => Promise.resolve([workflow()])
    listWorkflowRunsBehavior = () =>
      Promise.resolve([{ id: 'run-1', workflowId: 'wf-1', triggerType: 'workflow_dispatch', status: 'FAILED', startedAt: new Date().toISOString() }])

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Librarian · needs attention')).toBeInTheDocument()
  })

  it('shows "filing N sources" when pending/processing are above zero and nothing needs attention', async () => {
    countsBehavior = () => Promise.resolve({ pending: 2, processing: 3, processed: 10, skipped: 0, dead: 0 })

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Librarian · filing 5 sources…')).toBeInTheDocument()
  })

  it('prioritizes needs-attention over working when both conditions hold', async () => {
    countsBehavior = () => Promise.resolve({ pending: 2, processing: 0, processed: 10, skipped: 0, dead: 1 })

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Librarian · needs attention')).toBeInTheDocument()
  })

  it('omits the chip but still shows the admin Manage entry when the counts fetch fails', async () => {
    countsBehavior = () => Promise.reject(new Error('boom'))

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    await waitFor(() => {
      expect(screen.queryByText(/Librarian ·/)).not.toBeInTheDocument()
    })
    expect(screen.getByRole('link', { name: /manage/i })).toBeInTheDocument()
  })

  it('shows the Manage entry for an admin', async () => {
    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    const manageLink = await screen.findByRole('link', { name: /manage/i })
    expect(manageLink).toHaveAttribute('href', '/app/projects/proj-1/knowledge/manage')
  })

  it('hides the Manage entry for a non-admin', async () => {
    canManage = false

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    await screen.findByText('Librarian · up to date')
    expect(screen.queryByRole('link', { name: /manage/i })).not.toBeInTheDocument()
  })

  it('shows a count badge on Manage when there are SUGGESTED domains', async () => {
    listDomainsBehavior = () => Promise.resolve([domain(), domain({ slug: 'legal', state: 'SUGGESTED' })])

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    const manageLink = await screen.findByRole('link', { name: /manage/i })
    expect(manageLink).toHaveTextContent('2')
  })

  it('omits the count badge when there are no SUGGESTED domains', async () => {
    listDomainsBehavior = () => Promise.resolve([domain({ state: 'ACTIVE' })])

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    const manageLink = await screen.findByRole('link', { name: /manage/i })
    await waitFor(() => expect(manageLink).not.toHaveTextContent(/\d/))
  })

  it('shows "waiting for sources" when nothing has ever been processed and the wiki has no content pages', async () => {
    render(<KnowledgeRailFooter projectId="proj-1" token="tok" hasContent={false} />)

    expect(await screen.findByText('Librarian · waiting for sources')).toBeInTheDocument()
  })

  it('shows "up to date" instead of "waiting for sources" once the wiki has content pages', async () => {
    render(<KnowledgeRailFooter projectId="proj-1" token="tok" hasContent={true} />)

    expect(await screen.findByText('Librarian · up to date')).toBeInTheDocument()
  })

  it('shows "up to date" rather than "waiting for sources" while hasContent is still unknown', async () => {
    render(<KnowledgeRailFooter projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Librarian · up to date')).toBeInTheDocument()
  })

  it('prioritizes "needs attention" over "waiting for sources" when both conditions hold', async () => {
    countsBehavior = () => Promise.resolve({ pending: 0, processing: 0, processed: 0, skipped: 0, dead: 1 })

    render(<KnowledgeRailFooter projectId="proj-1" token="tok" hasContent={false} />)

    expect(await screen.findByText('Librarian · needs attention')).toBeInTheDocument()
  })
})
