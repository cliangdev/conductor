import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import type { KnowledgeDomainDto } from '@/lib/knowledge-api'
import type { Agent } from '@/lib/api'

let canManage = true
const mockShowToast = vi.fn()

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let listDomainsBehavior: () => Promise<KnowledgeDomainDto[]> = () => Promise.resolve([])
let listAgentsBehavior: () => Promise<Agent[]> = () => Promise.resolve([])
const updateDomainMock = vi.fn()
const createSpecialistMock = vi.fn()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'tok' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({
    role: canManage ? 'ADMIN' : 'CREATOR',
    loading: false,
    can: (cap: string) => (cap === 'workspace.manage' ? canManage : false),
    refresh: vi.fn(),
  }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/lib/knowledge-api', () => ({
  listKnowledgeDomains: () => listDomainsBehavior(),
  updateKnowledgeDomain: (...args: unknown[]) => updateDomainMock(...args),
  createKnowledgeDomainSpecialist: (...args: unknown[]) => createSpecialistMock(...args),
}))

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api')
  return {
    ...actual,
    listAgents: () => listAgentsBehavior(),
  }
})

import KnowledgeManagePage from './page'

function domain(overrides: Partial<KnowledgeDomainDto> = {}): KnowledgeDomainDto {
  return {
    slug: 'engineering',
    displayName: 'Engineering',
    pathPrefix: 'engineering/',
    schemaPagePath: 'engineering/_schema.md',
    sourceTypePatterns: ['github.*'],
    owningAgentSlug: null,
    state: 'ACTIVE',
    pendingCount: 3,
    processingCount: 0,
    processedCount: 12,
    ...overrides,
  }
}

function agent(overrides: Partial<Agent> = {}): Agent {
  return {
    id: 'agent-1',
    projectId: 'proj-1',
    name: 'Knowledge Engineering',
    slug: 'knowledge-engineering',
    provider: 'claude',
    toolIds: [],
    state: 'ACTIVE',
    avatarEmoji: '🛠️',
    avatarColor: 'teal',
    isDefault: false,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('KnowledgeManagePage', () => {
  beforeEach(() => {
    canManage = true
    mockShowToast.mockClear()
    updateDomainMock.mockClear().mockResolvedValue(domain({ state: 'ACTIVE' }))
    createSpecialistMock.mockClear().mockResolvedValue(domain({ owningAgentSlug: 'knowledge-engineering' }))
    listDomainsBehavior = () => Promise.resolve([])
    listAgentsBehavior = () => Promise.resolve([])
  })

  it('shows an admins-only empty state for a non-admin', async () => {
    canManage = false

    render(<KnowledgeManagePage />)

    expect(await screen.findByText('Admins only')).toBeInTheDocument()
    expect(screen.queryByText('Manage knowledge')).not.toBeInTheDocument()
  })

  it('renders an ACTIVE domain with display name, librarian default, and a waiting count', async () => {
    listDomainsBehavior = () => Promise.resolve([domain()])

    render(<KnowledgeManagePage />)

    expect(await screen.findByText('Engineering')).toBeInTheDocument()
    expect(screen.getByText('Librarian')).toBeInTheDocument()
    expect(screen.getByText('3 waiting')).toBeInTheDocument()
    const schemaLink = screen.getByRole('link', { name: 'Filing rules' })
    expect(schemaLink).toHaveAttribute('href', '/app/projects/proj-1/knowledge/page?path=engineering%2F_schema.md')
  })

  it('shows the owning agent name and avatar when one is assigned', async () => {
    listDomainsBehavior = () => Promise.resolve([domain({ owningAgentSlug: 'knowledge-engineering' })])
    listAgentsBehavior = () => Promise.resolve([agent()])

    render(<KnowledgeManagePage />)

    expect(await screen.findByText('Knowledge Engineering')).toBeInTheDocument()
    expect(screen.queryByText('Librarian')).not.toBeInTheDocument()
  })

  it('shows Assign specialist when no owning agent is assigned, and calls createKnowledgeDomainSpecialist', async () => {
    listDomainsBehavior = () => Promise.resolve([domain({ owningAgentSlug: null })])

    render(<KnowledgeManagePage />)
    const assignButton = await screen.findByRole('button', { name: 'Assign specialist' })
    fireEvent.click(assignButton)

    await waitFor(() => {
      expect(createSpecialistMock).toHaveBeenCalledWith('proj-1', 'engineering', 'tok')
    })
    expect(mockShowToast).toHaveBeenCalled()
  })

  it('renders a SUGGESTED domain as an approval card with its reason', async () => {
    listDomainsBehavior = () =>
      Promise.resolve([
        domain({
          slug: 'legal',
          displayName: 'Legal',
          state: 'SUGGESTED',
          suggestionReason: 'Contracts keep showing up with nowhere to go',
        }),
      ])

    render(<KnowledgeManagePage />)

    expect(await screen.findByText('The librarian suggests a new area: "Legal"')).toBeInTheDocument()
    expect(screen.getByText('“Contracts keep showing up with nowhere to go”')).toBeInTheDocument()
  })

  it('calls updateKnowledgeDomain with ACTIVE on Approve', async () => {
    listDomainsBehavior = () => Promise.resolve([domain({ slug: 'legal', displayName: 'Legal', state: 'SUGGESTED' })])

    render(<KnowledgeManagePage />)
    const approveButton = await screen.findByRole('button', { name: 'Approve' })
    fireEvent.click(approveButton)

    await waitFor(() => {
      expect(updateDomainMock).toHaveBeenCalledWith('proj-1', 'legal', { state: 'ACTIVE' }, 'tok')
    })
    expect(mockShowToast).toHaveBeenCalled()
  })

  it('calls updateKnowledgeDomain with DISMISSED on Dismiss', async () => {
    listDomainsBehavior = () => Promise.resolve([domain({ slug: 'legal', displayName: 'Legal', state: 'SUGGESTED' })])

    render(<KnowledgeManagePage />)
    const dismissButton = await screen.findByRole('button', { name: 'Dismiss' })
    fireEvent.click(dismissButton)

    await waitFor(() => {
      expect(updateDomainMock).toHaveBeenCalledWith('proj-1', 'legal', { state: 'DISMISSED' }, 'tok')
    })
  })

  it('never renders a DISMISSED domain', async () => {
    listDomainsBehavior = () =>
      Promise.resolve([domain({ slug: 'old-idea', displayName: 'Old Idea', state: 'DISMISSED' })])

    render(<KnowledgeManagePage />)

    await screen.findByText('Manage knowledge')
    expect(screen.queryByText('Old Idea')).not.toBeInTheDocument()
  })

  it('shows the footer hint text', async () => {
    listDomainsBehavior = () => Promise.resolve([domain()])

    render(<KnowledgeManagePage />)

    expect(
      await screen.findByText(/Every area is maintained by the Librarian until you assign a specialist/)
    ).toBeInTheDocument()
  })

  it('shows an alert when the domains fetch fails', async () => {
    listDomainsBehavior = () => Promise.reject(new Error('boom'))

    render(<KnowledgeManagePage />)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
