import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { vi } from 'vitest'
import type { KnowledgeSourceDto } from '@/lib/knowledge-api'

// Plain (non-vi.fn) stub so rejected-promise paths aren't flagged as unhandled — see
// reference_vitest_rejected_promise_mock memory.
let listKnowledgeSourcesBehavior: (
  projectId: string,
  token: string,
  opts?: { status?: string },
) => Promise<KnowledgeSourceDto[]> = () => Promise.resolve([])

const push = vi.fn()
const replace = vi.fn()
let pathname = '/app/projects/proj-1/knowledge/sources'
let searchParams = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push, replace }),
  usePathname: () => pathname,
  useSearchParams: () => searchParams,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/lib/knowledge-api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/knowledge-api')>('@/lib/knowledge-api')
  return {
    ...actual,
    listKnowledgeSources: (...args: unknown[]) =>
      listKnowledgeSourcesBehavior.call(null, ...(args as [string, string, { status?: string }])),
  }
})

import KnowledgeSourcesPage from './page'

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

describe('Knowledge sources page', () => {
  beforeEach(() => {
    push.mockClear()
    replace.mockClear()
    pathname = '/app/projects/proj-1/knowledge/sources'
    searchParams = new URLSearchParams()
    listKnowledgeSourcesBehavior = () => Promise.resolve([])
  })

  it('defaults to the PENDING filter and lists rows', async () => {
    listKnowledgeSourcesBehavior = (_projectId, _token, opts) =>
      opts?.status === 'PENDING' ? Promise.resolve([source()]) : Promise.resolve([])

    render(<KnowledgeSourcesPage />)

    expect(await screen.findByText('A note')).toBeInTheDocument()
    expect(screen.getByText('manual_note')).toBeInTheDocument()
  })

  it('shows an EmptyState when no sources match the filter', async () => {
    listKnowledgeSourcesBehavior = () => Promise.resolve([])

    render(<KnowledgeSourcesPage />)

    expect(await screen.findByText('No sources')).toBeInTheDocument()
  })

  it('switches the status filter and re-fetches', async () => {
    listKnowledgeSourcesBehavior = (_projectId, _token, opts) =>
      opts?.status === 'DEAD'
        ? Promise.resolve([source({ id: 'src-dead', title: 'Dead one', status: 'DEAD' })])
        : Promise.resolve([source()])

    render(<KnowledgeSourcesPage />)
    await screen.findByText('A note')

    const deadTab = screen.getByRole('tab', { name: 'Dead' })
    fireEvent.click(deadTab)

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/knowledge/sources?status=DEAD')
    })
  })

  it('reads the initial status from the ?status= query param', async () => {
    searchParams = new URLSearchParams({ status: 'DEAD' })
    listKnowledgeSourcesBehavior = (_projectId, _token, opts) =>
      opts?.status === 'DEAD'
        ? Promise.resolve([source({ id: 'src-dead', title: 'Dead one', status: 'DEAD' })])
        : Promise.resolve([])

    render(<KnowledgeSourcesPage />)

    expect(await screen.findByText('Dead one')).toBeInTheDocument()
  })

  it('shows a purged indicator when purgedAt is set', async () => {
    listKnowledgeSourcesBehavior = () => Promise.resolve([source({ purgedAt: '2026-01-01T00:00:00Z' })])

    render(<KnowledgeSourcesPage />)

    expect(await screen.findByText('purged')).toBeInTheDocument()
  })

  it('shows a domain badge when the source was routed to a domain', async () => {
    listKnowledgeSourcesBehavior = () => Promise.resolve([source({ domain: 'engineering' })])

    render(<KnowledgeSourcesPage />)

    expect(await screen.findByText('engineering')).toBeInTheDocument()
  })

  it('shows no domain badge for an unclassified (null-domain) source', async () => {
    listKnowledgeSourcesBehavior = () => Promise.resolve([source({ domain: null })])

    render(<KnowledgeSourcesPage />)

    expect(await screen.findByText('A note')).toBeInTheDocument()
    expect(screen.queryByText('engineering')).not.toBeInTheDocument()
  })

  it('shows an alert when the fetch fails', async () => {
    listKnowledgeSourcesBehavior = () => Promise.reject(Object.assign(new Error('boom'), { detail: 'Server error' }))

    render(<KnowledgeSourcesPage />)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})
