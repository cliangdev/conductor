import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { KnowledgeSourceDto } from '@/lib/knowledge-api'

const push = vi.fn()
const replace = vi.fn()
let pathname = '/app/projects/proj-1/knowledge/activity'
let searchParams = new URLSearchParams()

// Plain (non-vi.fn) stubs so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let listKnowledgeSourcesBehavior: (opts?: { status?: string; domain?: string }) => Promise<KnowledgeSourceDto[]> = () =>
  Promise.resolve([])

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push, replace }),
  usePathname: () => pathname,
  useSearchParams: () => searchParams,
}))

vi.mock('@/lib/knowledge-api', async () => ({
  // Preserve real exports (KNOWLEDGE_LIBRARIAN_SLUG etc.) — components under test import
  // constants from this module, not just the network functions overridden below.
  ...(await vi.importActual<typeof import('@/lib/knowledge-api')>('@/lib/knowledge-api')),
  listKnowledgeSources: (_projectId: string, _token: string, opts?: { status?: string; domain?: string }) =>
    listKnowledgeSourcesBehavior(opts),
}))

import { KnowledgeInbox } from './KnowledgeInbox'

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

describe('KnowledgeInbox', () => {
  beforeEach(() => {
    push.mockClear()
    replace.mockClear()
    pathname = '/app/projects/proj-1/knowledge/activity'
    searchParams = new URLSearchParams()
    listKnowledgeSourcesBehavior = () => Promise.resolve([])
  })

  it('renders a SKIPPED tab labeled "Not filed", between "Filed" and "Needs attention"', async () => {
    render(<KnowledgeInbox projectId="proj-1" token="tok" />)

    await waitFor(() => expect(screen.getByRole('tablist')).toBeInTheDocument())
    const tabs = screen.getAllByRole('tab').map((t) => t.textContent)
    expect(tabs).toEqual(['Waiting', 'Filing', 'Filed', 'Not filed', 'Needs attention'])
  })

  it('requests the SKIPPED status when the "Not filed" tab is selected via ?status=', async () => {
    searchParams = new URLSearchParams('status=SKIPPED')
    listKnowledgeSourcesBehavior = (opts) => {
      expect(opts?.status).toBe('SKIPPED')
      return Promise.resolve([])
    }

    render(<KnowledgeInbox projectId="proj-1" token="tok" />)

    await waitFor(() => expect(screen.getByText(/No sources were skipped/)).toBeInTheDocument())
  })

  it('shows the bespoke empty-state copy for SKIPPED, not a lowercased "not filed sources" string', async () => {
    searchParams = new URLSearchParams('status=SKIPPED')
    listKnowledgeSourcesBehavior = () => Promise.resolve([])

    render(<KnowledgeInbox projectId="proj-1" token="tok" />)

    expect(await screen.findByText('No sources were skipped in the inbox.')).toBeInTheDocument()
    expect(screen.queryByText(/not filed sources/i)).not.toBeInTheDocument()
  })

  it('renders skipReason on a SKIPPED row, informational rather than an error', async () => {
    searchParams = new URLSearchParams('status=SKIPPED')
    listKnowledgeSourcesBehavior = () =>
      Promise.resolve([
        source({ id: 'src-2', status: 'SKIPPED', skipReason: 'duplicate of existing page' }),
      ])

    render(<KnowledgeInbox projectId="proj-1" token="tok" />)

    expect(await screen.findByText('duplicate of existing page')).toBeInTheDocument()
  })

  it('renders no skipReason text when the row has none', async () => {
    listKnowledgeSourcesBehavior = () => Promise.resolve([source()])

    render(<KnowledgeInbox projectId="proj-1" token="tok" />)

    expect(await screen.findByText('A note')).toBeInTheDocument()
  })
})
