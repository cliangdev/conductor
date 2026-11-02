import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'

const apiGet = vi.fn()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  usePathname: () => '/app/projects/proj-1/roadmap',
}))
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'token' }) }))
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, apiGet: (...args: unknown[]) => apiGet(...args) }
})
vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return {
    ...actual,
    useWorkflowViews: () => ({
      PRDS: {
        slug: 'PRDS', noun: 'PRD', area: 'ENGINEERING', defaultView: 'active', version: 1, types: ['PRD'], transitions: [],
        statuses: [
          { id: 'DRAFT', label: 'Draft', category: 'open' },
          { id: 'IN_PROGRESS', label: 'In Progress', category: 'in_progress' },
          { id: 'DONE', label: 'Done', category: 'terminal', terminal: true },
        ],
      },
    }),
  }
})

import RoadmapPage from './page'

const base = { type: 'PRD', workflow: 'PRDS', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }

beforeEach(() => {
  apiGet.mockReset()
  apiGet.mockImplementation(async (path: string) => {
    if (path.endsWith('/milestones')) return []
    return [
      { ...base, id: 'a', displayId: 'PRD-1', title: 'Active one', status: 'IN_PROGRESS', priority: 'P1' },
      { ...base, id: 'b', displayId: 'PRD-2', title: 'Backlog one', status: 'DRAFT' },
      { ...base, id: 'c', displayId: 'PRD-3', title: 'Finished one', status: 'DONE' },
    ]
  })
})

describe('roadmap page', () => {
  it('shows a skeleton, then buckets PRDs into Now and Later and drops finished ones', async () => {
    render(<RoadmapPage />)
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)

    expect(await screen.findByText('Active one')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Now' })).getByText('Active one')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Later' })).getByText('Backlog one')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Later' })).getByText('No milestone')).toBeInTheDocument()
    expect(screen.queryByText('Finished one')).not.toBeInTheDocument()
    // nothing is dated, so the timeline strip stays hidden
    expect(screen.queryByLabelText('Milestone timeline')).not.toBeInTheDocument()
  })

  it('shows an empty state when there are no open PRDs', async () => {
    apiGet.mockImplementation(async (path: string) => (path.endsWith('/milestones') ? [] : []))
    render(<RoadmapPage />)
    expect(await screen.findByText('No open PRDs')).toBeInTheDocument()
  })
})
