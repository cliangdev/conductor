import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'

const apiGet = vi.fn()
const viewsState = vi.hoisted(() => ({ settled: true }))

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
    useWorkflowViewsSettled: () => ({
      settled: viewsState.settled,
      views: {
      PRDS: {
        slug: 'PRDS', noun: 'PRD', area: 'ENGINEERING', defaultView: 'active', version: 1, types: ['PRD'], transitions: [],
        statuses: [
          { id: 'DRAFT', label: 'Draft', category: 'open' },
          { id: 'IN_PROGRESS', label: 'In Progress', category: 'in_progress' },
          { id: 'DONE', label: 'Done', category: 'terminal', terminal: true },
        ],
      },
      },
    }),
  }
})

import RoadmapPage from './page'
import { RoadmapPanel } from '@/components/roadmap/RoadmapPanel'

const base = { type: 'PRD', workflow: 'PRDS', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }

beforeEach(() => {
  viewsState.settled = true
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

  it('holds a skeleton until the Workflow views are in, then excludes finished PRDs', async () => {
    viewsState.settled = false
    const { rerender } = render(<RoadmapPage />)
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('Finished one')).not.toBeInTheDocument()
    expect(screen.queryByText('Active one')).not.toBeInTheDocument()
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)

    viewsState.settled = true
    rerender(<RoadmapPage />)
    expect(await screen.findByText('Active one')).toBeInTheDocument()
    expect(screen.queryByText('Finished one')).not.toBeInTheDocument()
  })

  it('shows a skeleton, not the previous workspace, after the workspace changes', async () => {
    const { rerender } = render(<RoadmapPanel projectId="proj-1" />)
    expect(await screen.findByText('Active one')).toBeInTheDocument()

    apiGet.mockImplementation(() => new Promise(() => {})) // the new workspace never answers
    rerender(<RoadmapPanel projectId="proj-2" />)
    expect(screen.queryByText('Active one')).not.toBeInTheDocument()
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
  })

  it('shows an empty state when there are no open PRDs', async () => {
    apiGet.mockImplementation(async (path: string) => (path.endsWith('/milestones') ? [] : []))
    render(<RoadmapPage />)
    expect(await screen.findByText('No open PRDs')).toBeInTheDocument()
  })
})
