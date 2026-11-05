import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'

const apiGet = vi.fn()
const viewsState = vi.hoisted(() => ({ settled: true }))

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  usePathname: () => '/app/projects/proj-1/engineering/issues',
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

import { RoadmapPanel } from './RoadmapPanel'

const base = { type: 'PRD', workflow: 'PRDS', createdAt: '2026-10-01T00:00:00Z' }
const noSignals = { blocked: false, blockedBy: [], ready: false, stale: false, needsInput: false }
const row = (item: Record<string, unknown>, extra: Record<string, unknown> = {}) => ({
  item: { ...base, ...item },
  progress: { total: 0, done: 0, percent: 0, criteriaTotal: 0, criteriaMet: 0 },
  forecast: { state: 'NO_PLAN', remaining: 0, samples: 0 },
  signals: noSignals,
  ...extra,
})

// The one delivery-overview call carries the open items (the server leaves finished ones out) and the milestones.
const overview = (activeItems: unknown[], milestones: unknown[] = []) => ({
  activeItems,
  milestones,
  attention: { blocked: [], stale: [], needsInput: [] },
})
const OPEN_ITEMS = [
  row({ id: 'a', displayId: 'PRD-1', title: 'Active one', status: 'IN_PROGRESS', priority: 'P1' }),
  row({ id: 'b', displayId: 'PRD-2', title: 'Backlog one', status: 'DRAFT' }),
]

beforeEach(() => {
  viewsState.settled = true
  apiGet.mockReset()
  apiGet.mockImplementation(async () => overview(OPEN_ITEMS))
})

describe('roadmap panel', () => {
  it('shows a skeleton, then buckets PRDs into Now and Later and drops finished ones', async () => {
    render(<RoadmapPanel projectId="proj-1" />)
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)

    expect(await screen.findByText('Active one')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Now' })).getByText('Active one')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Later' })).getByText('Backlog one')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Later' })).getByText('No milestone')).toBeInTheDocument()
    // nothing is dated, so the timeline strip stays hidden
    expect(screen.queryByLabelText('Milestone timeline')).not.toBeInTheDocument()
  })

  it('makes one call, and drops an item whose status is finished in its Workflow view', async () => {
    apiGet.mockImplementation(async () =>
      overview([...OPEN_ITEMS, row({ id: 'c', displayId: 'PRD-3', title: 'Finished one', status: 'DONE' })])
    )
    render(<RoadmapPanel projectId="proj-1" />)
    expect(await screen.findByText('Active one')).toBeInTheDocument()

    expect(screen.queryByText('Finished one')).not.toBeInTheDocument()
    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(apiGet.mock.calls[0][0]).toBe('/api/v2/projects/proj-1/delivery-overview')
  })

  it('groups items under their milestone and ends a bar at the forecast P85', async () => {
    apiGet.mockImplementation(async () =>
      overview(
        [
          row(
            { id: 'a', displayId: 'PRD-1', title: 'Active one', status: 'IN_PROGRESS', milestoneId: 'm1', startedAt: '2026-10-05T00:00:00Z' },
            { forecast: { state: 'OK', remaining: 3, samples: 12, p50Date: '2026-12-01', p85Date: '2026-12-15' } }
          ),
        ],
        [{ id: 'm1', name: 'Launch', state: 'OPEN', targetOn: '2026-12-31' }]
      )
    )
    render(<RoadmapPanel projectId="proj-1" />)

    expect(await screen.findByText('Launch', { selector: 'h3' })).toBeInTheDocument()
    expect(screen.getByLabelText('Milestone timeline')).toBeInTheDocument()
  })

  it('surfaces a failed load instead of an empty board', async () => {
    apiGet.mockRejectedValue(new Error('boom'))
    render(<RoadmapPanel projectId="proj-1" />)
    expect(await screen.findByText('Could not load the roadmap')).toBeInTheDocument()
  })

  it('holds a skeleton until the Workflow views are in, then excludes finished PRDs', async () => {
    viewsState.settled = false
    const { rerender } = render(<RoadmapPanel projectId="proj-1" />)
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('Active one')).not.toBeInTheDocument()
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)

    viewsState.settled = true
    rerender(<RoadmapPanel projectId="proj-1" />)
    expect(await screen.findByText('Active one')).toBeInTheDocument()
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
    apiGet.mockImplementation(async () => overview([]))
    render(<RoadmapPanel projectId="proj-1" />)
    expect(await screen.findByText('No open PRDs')).toBeInTheDocument()
  })
})
