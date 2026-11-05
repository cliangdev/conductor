import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const apiGet = vi.fn()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  usePathname: () => '/app/projects/proj-1',
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
      settled: true,
      views: {
      PRDS: {
        slug: 'PRDS', noun: 'PRD', area: 'ENGINEERING', defaultView: 'active', version: 1, types: ['PRD'], transitions: [],
        statuses: [
          { id: 'IN_PROGRESS', label: 'In Progress', category: 'in_progress' },
          { id: 'DONE', label: 'Done', category: 'terminal', terminal: true },
        ],
      },
      },
    }),
  }
})

import ProjectOverviewPage from './page'

const summary = { type: 'PRD', status: 'IN_PROGRESS', statusLabel: 'In Progress', workflow: 'PRDS' }
const signals = { blocked: false, blockedBy: [], ready: false, stale: false, needsInput: false }

const OVERVIEW = {
  activeItems: [
    {
      item: { ...summary, id: 'a', displayId: 'PRD-1', title: 'Ship billing' },
      progress: { total: 4, done: 1, percent: 25, criteriaTotal: 3, criteriaMet: 1 },
      health: { health: 'AT_RISK', note: 'Slipping', updatedAt: '2026-10-30T00:00:00Z', stale: false, atRisk: true, pendingDraft: null },
      forecast: { state: 'OK', mode: 'THROUGHPUT', remaining: 3, samples: 12, p50Date: '2026-11-12', p85Date: '2026-11-20', appetiteFit: 'FITS' },
      signals,
    },
    {
      item: { ...summary, id: 'b', displayId: 'PRD-2', title: 'No plan yet PRD' },
      progress: { total: 0, done: 0, percent: 0, criteriaTotal: 0, criteriaMet: 0 },
      health: null,
      forecast: { state: 'NO_PLAN', remaining: 0, samples: 0 },
      signals,
    },
  ],
  milestones: [{ id: 'm1', projectId: 'proj-1', name: 'Beta', targetOn: '2026-12-01', state: 'OPEN', sortOrder: 0, createdAt: '', updatedAt: '', progress: { topLevelTotal: 1, topLevelDone: 0, leafTotal: 4, leafDone: 1 } }],
  attention: {
    blocked: [{ ...summary, type: 'TASK', id: 'k', displayId: 'TASK-4', title: 'Stuck task' }],
    stale: [{ ...summary, type: 'TASK', id: 's', displayId: 'TASK-9', title: 'Forgotten task' }],
    needsInput: [{ ...summary, type: 'TASK', id: 'n', displayId: 'TASK-3', title: 'Needs an answer' }],
  },
}

beforeEach(() => {
  apiGet.mockReset()
  apiGet.mockResolvedValue(OVERVIEW)
})

describe('project overview page', () => {
  it('shows skeletons first, then active PRDs, milestones and attention lists from one call', async () => {
    render(<ProjectOverviewPage />)
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)

    expect(await screen.findByText('Ship billing')).toBeInTheDocument()
    expect(screen.getByText('1/3 criteria')).toBeInTheDocument()
    expect(screen.getByText('Beta')).toBeInTheDocument()
    expect(screen.getByText('Forgotten task')).toBeInTheDocument()
    expect(screen.getByText('Needs an answer')).toBeInTheDocument()
    expect(screen.getByText('Stuck task')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Ship billing/ })).toHaveAttribute('href', '/app/projects/proj-1/engineering/prds/PRD-1')
    expect(apiGet.mock.calls.map((c) => c[0])).toEqual(['/api/v2/projects/proj-1/delivery-overview'])
  })

  it('shows each active PRD health badge and forecast line', async () => {
    render(<ProjectOverviewPage />)
    await screen.findByText('Ship billing')
    expect(screen.getByText('At risk')).toBeInTheDocument()
    expect(screen.getByText('No health yet')).toBeInTheDocument()
    expect(screen.getByText(/P50 .* · P85 .* · fits appetite/)).toBeInTheDocument()
    expect(screen.getByText('No plan yet')).toBeInTheDocument()
  })

  it('shows an error message instead of crashing when the call fails', async () => {
    apiGet.mockRejectedValue(new Error('boom'))
    render(<ProjectOverviewPage />)
    await waitFor(() => expect(screen.getAllByText(/Could not load/).length).toBeGreaterThan(0))
  })
})
