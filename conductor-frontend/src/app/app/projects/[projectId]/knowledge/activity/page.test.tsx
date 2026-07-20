import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import type { KnowledgePageView, KnowledgeSourceCounts, KnowledgeSourceDto } from '@/lib/knowledge-api'
import type { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow'

const push = vi.fn()
const replace = vi.fn()
let pathname = '/app/projects/proj-1/knowledge/activity'
let searchParams = new URLSearchParams()

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let getKnowledgePagesBehavior: (paths: string[]) => Promise<KnowledgePageView[]> = () => Promise.resolve([])
let getKnowledgeSourceCountsBehavior: () => Promise<KnowledgeSourceCounts> = () =>
  Promise.resolve({ pending: 0, processing: 0, processed: 0, dead: 0 })
let listKnowledgeSourcesBehavior: (opts?: { status?: string; domain?: string }) => Promise<KnowledgeSourceDto[]> = () =>
  Promise.resolve([])
let listWorkflowsBehavior: () => Promise<WorkflowDefinitionDto[]> = () => Promise.resolve([])
let listWorkflowRunsBehavior: () => Promise<WorkflowRunDto[]> = () => Promise.resolve([])
let retryDeadKnowledgeSourcesBehavior: () => Promise<{ retried: number }> = () => Promise.resolve({ retried: 0 })
let canManage = true

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push, replace }),
  usePathname: () => pathname,
  useSearchParams: () => searchParams,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

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
  getKnowledgePages: (_projectId: string, paths: string[]) => getKnowledgePagesBehavior(paths),
  getKnowledgeSourceCounts: () => getKnowledgeSourceCountsBehavior(),
  listKnowledgeSources: (_projectId: string, _token: string, opts?: { status?: string; domain?: string }) =>
    listKnowledgeSourcesBehavior(opts),
  retryDeadKnowledgeSources: () => retryDeadKnowledgeSourcesBehavior(),
}))

const mockShowToast = vi.fn()
vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/lib/workflows', async () => {
  // StatusBadge (rendered in the Runs tab) also imports statusHue/humanizeId from this module —
  // preserve the real exports and only override the two network-calling functions.
  const actual = await vi.importActual<typeof import('@/lib/workflows')>('@/lib/workflows')
  return {
    ...actual,
    listWorkflows: () => listWorkflowsBehavior(),
    listWorkflowRuns: () => listWorkflowRunsBehavior(),
  }
})

import KnowledgeActivityPage from './page'

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

function run(overrides: Partial<WorkflowRunDto> = {}): WorkflowRunDto {
  return {
    id: 'run-1',
    workflowId: 'wf-1',
    triggerType: 'schedule',
    status: 'SUCCESS',
    startedAt: new Date().toISOString(),
    ...overrides,
  }
}

function source(overrides: Partial<KnowledgeSourceDto> = {}): KnowledgeSourceDto {
  return {
    id: 'src-1',
    projectId: 'proj-1',
    sourceType: 'manual_note',
    title: 'A note',
    payloadOffloaded: false,
    receivedAt: new Date().toISOString(),
    status: 'PENDING',
    attempts: 0,
    ...overrides,
  }
}

describe('Knowledge Activity page', () => {
  beforeEach(() => {
    push.mockClear()
    replace.mockClear()
    mockShowToast.mockClear()
    canManage = true
    pathname = '/app/projects/proj-1/knowledge/activity'
    searchParams = new URLSearchParams()
    getKnowledgePagesBehavior = () => Promise.resolve([])
    getKnowledgeSourceCountsBehavior = () => Promise.resolve({ pending: 0, processing: 0, processed: 0, dead: 0 })
    listKnowledgeSourcesBehavior = () => Promise.resolve([])
    listWorkflowsBehavior = () => Promise.resolve([])
    listWorkflowRunsBehavior = () => Promise.resolve([])
    retryDeadKnowledgeSourcesBehavior = () => Promise.resolve({ retried: 0 })
  })

  it('defaults to the Page changes tab and renders log.md content', async () => {
    getKnowledgePagesBehavior = () =>
      Promise.resolve([{ path: 'log.md', version: 0, type: 'log', content: '# Log\n\nSomething happened.\n' }])

    render(<KnowledgeActivityPage />)

    expect(await screen.findByText('Something happened.')).toBeInTheDocument()
  })

  it('reads the initial tab from ?tab= and renders the Inbox tab content', async () => {
    searchParams = new URLSearchParams({ tab: 'inbox' })
    listKnowledgeSourcesBehavior = (opts) =>
      opts?.status === 'PENDING' || !opts?.status ? Promise.resolve([source()]) : Promise.resolve([])

    render(<KnowledgeActivityPage />)

    expect(await screen.findByText('A note')).toBeInTheDocument()
  })

  it('renders the Runs tab with librarian run rows', async () => {
    searchParams = new URLSearchParams({ tab: 'runs' })
    listWorkflowsBehavior = () => Promise.resolve([workflow()])
    listWorkflowRunsBehavior = () => Promise.resolve([run({ status: 'FAILED' })])

    render(<KnowledgeActivityPage />)

    expect(await screen.findByText('Failed')).toBeInTheDocument()
  })

  it('shows the Runs empty state when there are no librarian runs', async () => {
    searchParams = new URLSearchParams({ tab: 'runs' })
    listWorkflowsBehavior = () => Promise.resolve([workflow()])
    listWorkflowRunsBehavior = () => Promise.resolve([])

    render(<KnowledgeActivityPage />)

    expect(await screen.findByText('No librarian runs yet.')).toBeInTheDocument()
  })

  it('switches tabs via URL param on click', async () => {
    render(<KnowledgeActivityPage />)
    const inboxTab = await screen.findByRole('tab', { name: /inbox/i })

    fireEvent.click(inboxTab)

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/knowledge/activity?tab=inbox')
    })
  })

  it('shows a red count badge on the Inbox tab label when dead count is above zero', async () => {
    getKnowledgeSourceCountsBehavior = () => Promise.resolve({ pending: 0, processing: 0, processed: 5, dead: 3 })

    render(<KnowledgeActivityPage />)

    const inboxTab = await screen.findByRole('tab', { name: /inbox/i })
    expect(inboxTab).toHaveTextContent('3')
  })

  it('omits the count badge when dead count is zero', async () => {
    render(<KnowledgeActivityPage />)

    const inboxTab = await screen.findByRole('tab', { name: /inbox/i })
    await waitFor(() => expect(inboxTab).not.toHaveTextContent(/\d/))
  })

  it('shows the attention banner on the Inbox tab when dead count is above zero', async () => {
    searchParams = new URLSearchParams({ tab: 'inbox' })
    getKnowledgeSourceCountsBehavior = () => Promise.resolve({ pending: 0, processing: 0, processed: 5, dead: 2 })
    listWorkflowsBehavior = () => Promise.resolve([workflow()])
    listWorkflowRunsBehavior = () => Promise.resolve([run({ status: 'FAILED' })])

    render(<KnowledgeActivityPage />)

    expect(await screen.findByText(/2 sources couldn't be filed/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /open ai providers/i })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/settings/providers',
    )
  })

  it('omits the attention banner on the Inbox tab when dead count is zero', async () => {
    searchParams = new URLSearchParams({ tab: 'inbox' })

    render(<KnowledgeActivityPage />)

    await screen.findByRole('tablist', { name: /filter sources by status/i })
    expect(screen.queryByText(/couldn't be filed/)).not.toBeInTheDocument()
  })

  it('shows the retry button for an admin and refetches the dead count on success', async () => {
    searchParams = new URLSearchParams({ tab: 'inbox' })
    let deadCount = 2
    getKnowledgeSourceCountsBehavior = () =>
      Promise.resolve({ pending: 0, processing: 0, processed: 5, dead: deadCount })
    retryDeadKnowledgeSourcesBehavior = () => {
      deadCount = 0
      return Promise.resolve({ retried: 2 })
    }

    render(<KnowledgeActivityPage />)

    const retryButton = await screen.findByRole('button', { name: /retry 2 sources/i })
    fireEvent.click(retryButton)

    await waitFor(() => expect(mockShowToast).toHaveBeenCalledWith('2 sources queued for retry'))
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
    })
  })

  it('hides the retry button for a non-admin', async () => {
    searchParams = new URLSearchParams({ tab: 'inbox' })
    canManage = false
    getKnowledgeSourceCountsBehavior = () => Promise.resolve({ pending: 0, processing: 0, processed: 5, dead: 2 })

    render(<KnowledgeActivityPage />)

    await screen.findByText(/2 sources couldn't be filed/)
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
  })
})
