import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import type { WorkflowDefinitionDto } from '@/types/workflow'

const push = vi.fn()
const replace = vi.fn()
let searchParams = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  usePathname: () => '/app/projects/proj-1/workflows',
  useRouter: () => ({ push, replace }),
  useSearchParams: () => searchParams,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}))

vi.mock('@/components/auth/Can', () => ({
  Can: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => fallback,
}))

import * as api from '@/lib/api'
import WorkflowsPage from './page'

function automationWorkflow(overrides: Partial<WorkflowDefinitionDto> = {}): WorkflowDefinitionDto {
  return {
    id: 'wf-automation-1',
    projectId: 'proj-1',
    name: 'Nightly digest',
    yaml: 'on:\n  schedule: "0 0 * * *"\n',
    enabled: true,
    kind: 'AUTOMATION',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function lifecycleWorkflow(overrides: Partial<WorkflowDefinitionDto> = {}): WorkflowDefinitionDto {
  return {
    id: 'wf-lifecycle-1',
    projectId: 'proj-1',
    name: 'Issue tracker',
    noun: 'Issue',
    enabled: true,
    kind: 'LIFECYCLE',
    state: 'PUBLISHED',
    definition: { statuses: [{ id: 'OPEN' }, { id: 'DONE' }], noun: 'Issue' },
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('WorkflowsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    push.mockClear()
    replace.mockClear()
    searchParams = new URLSearchParams()
    vi.mocked(api.apiGet).mockImplementation((url: string) => {
      if (url.includes('/runs')) return Promise.resolve([])
      return Promise.resolve([automationWorkflow(), lifecycleWorkflow()])
    })
  })

  it('defaults to the Automation tab and lists automation workflows with counts', async () => {
    render(<WorkflowsPage />)

    expect(await screen.findByText('Nightly digest')).toBeInTheDocument()
    expect(screen.queryByText('Issue tracker')).not.toBeInTheDocument()

    const automationTab = screen.getByRole('tab', { name: /automation/i })
    const lifecycleTab = screen.getByRole('tab', { name: /lifecycle/i })
    expect(automationTab).toHaveAttribute('aria-selected', 'true')
    expect(lifecycleTab).toHaveAttribute('aria-selected', 'false')
    expect(lifecycleTab).toHaveTextContent('1')
  })

  it('switches to the Lifecycle tab via the URL param on click', async () => {
    render(<WorkflowsPage />)
    await screen.findByText('Nightly digest')

    fireEvent.click(screen.getByRole('tab', { name: /lifecycle/i }))

    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/workflows?tab=lifecycle'))
  })

  it('renders the Lifecycle table when ?tab=lifecycle is set', async () => {
    searchParams = new URLSearchParams({ tab: 'lifecycle' })
    render(<WorkflowsPage />)

    expect(await screen.findByText('Issue tracker')).toBeInTheDocument()
    expect(screen.queryByText('Nightly digest')).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /lifecycle/i })).toHaveAttribute('aria-selected', 'true')
  })

  it('shows a per-tab empty state when a kind has no workflows', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([automationWorkflow()])
    searchParams = new URLSearchParams({ tab: 'lifecycle' })
    render(<WorkflowsPage />)

    expect(await screen.findByText('No lifecycle workflows yet')).toBeInTheDocument()
  })
})
