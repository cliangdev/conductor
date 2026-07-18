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

import { KnowledgeDomainsPanel } from './KnowledgeDomainsPanel'

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

describe('KnowledgeDomainsPanel', () => {
  beforeEach(() => {
    canManage = true
    mockShowToast.mockClear()
    updateDomainMock.mockClear().mockResolvedValue(domain({ state: 'ACTIVE' }))
    createSpecialistMock.mockClear().mockResolvedValue(domain({ owningAgentSlug: 'knowledge-engineering' }))
    listDomainsBehavior = () => Promise.resolve([])
    listAgentsBehavior = () => Promise.resolve([])
  })

  it('renders an ACTIVE domain with display name, librarian default, pending/processed counts', async () => {
    listDomainsBehavior = () => Promise.resolve([domain()])

    render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Engineering')).toBeInTheDocument()
    expect(screen.getByText('Librarian')).toBeInTheDocument()
    expect(screen.getByText('3 pending')).toBeInTheDocument()
    expect(screen.getByText('12 processed')).toBeInTheDocument()
    const schemaLink = screen.getByRole('link', { name: 'Engineering' })
    expect(schemaLink).toHaveAttribute('href', '/app/projects/proj-1/knowledge/page?path=engineering%2F_schema.md')
  })

  it('shows the owning agent name and avatar when one is assigned', async () => {
    listDomainsBehavior = () => Promise.resolve([domain({ owningAgentSlug: 'knowledge-engineering' })])
    listAgentsBehavior = () => Promise.resolve([agent()])

    render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Knowledge Engineering')).toBeInTheDocument()
    expect(screen.queryByText('Librarian')).not.toBeInTheDocument()
  })

  it('shows Create specialist for an admin when no owning agent is assigned', async () => {
    canManage = true
    listDomainsBehavior = () => Promise.resolve([domain({ owningAgentSlug: null })])

    render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    expect(await screen.findByRole('button', { name: 'Create specialist' })).toBeInTheDocument()
  })

  it('hides Create specialist for a non-admin', async () => {
    canManage = false
    listDomainsBehavior = () => Promise.resolve([domain({ owningAgentSlug: null })])

    render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    await screen.findByText('Engineering')
    expect(screen.queryByRole('button', { name: 'Create specialist' })).not.toBeInTheDocument()
  })

  it('renders a SUGGESTED domain with an amber badge and its reason', async () => {
    listDomainsBehavior = () =>
      Promise.resolve([
        domain({
          slug: 'legal',
          displayName: 'Legal',
          state: 'SUGGESTED',
          suggestionReason: 'Contracts keep showing up with nowhere to go',
        }),
      ])

    render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    expect(await screen.findByText('Legal')).toBeInTheDocument()
    expect(screen.getByText('Suggested')).toBeInTheDocument()
    expect(screen.getByText('Contracts keep showing up with nowhere to go')).toBeInTheDocument()
  })

  it('shows Approve/Dismiss for a SUGGESTED domain to an admin, and calls updateKnowledgeDomain on approve', async () => {
    listDomainsBehavior = () => Promise.resolve([domain({ slug: 'legal', displayName: 'Legal', state: 'SUGGESTED' })])

    render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)
    const approveButton = await screen.findByRole('button', { name: 'Approve' })
    fireEvent.click(approveButton)

    await waitFor(() => {
      expect(updateDomainMock).toHaveBeenCalledWith('proj-1', 'legal', { state: 'ACTIVE' }, 'tok')
    })
    expect(mockShowToast).toHaveBeenCalled()
  })

  it('hides Approve/Dismiss for a SUGGESTED domain from a non-admin', async () => {
    canManage = false
    listDomainsBehavior = () => Promise.resolve([domain({ slug: 'legal', displayName: 'Legal', state: 'SUGGESTED' })])

    render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    await screen.findByText('Legal')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument()
  })

  it('never renders a DISMISSED domain', async () => {
    listDomainsBehavior = () =>
      Promise.resolve([domain({ slug: 'old-idea', displayName: 'Old Idea', state: 'DISMISSED' })])

    render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    await waitFor(() => {
      expect(screen.queryByText('Old Idea')).not.toBeInTheDocument()
    })
  })

  it('renders nothing when there are no ACTIVE or SUGGESTED domains', async () => {
    listDomainsBehavior = () => Promise.resolve([domain({ state: 'DISMISSED' })])

    const { container } = render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    await waitFor(() => {
      expect(container).toBeEmptyDOMElement()
    })
  })

  it('renders nothing when the domains fetch fails', async () => {
    listDomainsBehavior = () => Promise.reject(new Error('boom'))

    const { container } = render(<KnowledgeDomainsPanel projectId="proj-1" token="tok" />)

    await waitFor(() => {
      expect(container).toBeEmptyDOMElement()
    })
  })
})
