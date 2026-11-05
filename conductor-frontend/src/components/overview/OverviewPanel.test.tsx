import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const apiGet = vi.fn()

vi.mock('next/navigation', () => ({ useParams: () => ({ projectId: 'proj-1' }) }))
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

import { OverviewPanel } from './OverviewPanel'

const item = { type: 'TASK', status: 'TODO', workflow: 'TASK' }
const noSignals = { blocked: false, blockedBy: [], ready: false, stale: false, needsInput: false }

beforeEach(() => {
  apiGet.mockReset()
  apiGet.mockResolvedValue({
    activeItems: [
      {
        item: { ...item, id: 'prd', displayId: 'COND-1', title: 'Big feature', type: 'PRD', milestoneId: 'm1' },
        progress: { total: 4, done: 1, percent: 25, criteriaTotal: 0, criteriaMet: 0 },
        forecast: { state: 'OK', remaining: 3, samples: 12, p50Date: '2026-11-12', p85Date: '2026-11-20', milestoneFit: 'EXCEEDS' },
        signals: noSignals,
      },
    ],
    milestones: [{ id: 'm1', name: 'Launch', state: 'OPEN', targetOn: '2026-11-18' }],
    attention: {
      blocked: [
        { ...item, id: 't1', displayId: 'COND-2', title: 'Held up', blockedByCount: 2 },
        { ...item, id: 't2', displayId: 'COND-3', title: 'Held by one', blockedByCount: 1 },
        { ...item, id: 't3', displayId: 'COND-4', title: 'Old payload' },
      ],
      stale: [{ ...item, id: 't4', displayId: 'COND-5', title: 'Gone quiet' }],
      needsInput: [],
    },
  })
})

describe('overview panel', () => {
  it('shows how many blockers hold an item up on the blocked chip', async () => {
    render(<OverviewPanel projectId="proj-1" />)

    expect(await screen.findByText('Held up')).toBeInTheDocument()
    expect(screen.getByText('Blocked by 2')).toBeInTheDocument()
    expect(screen.getByText('Blocked by 1')).toBeInTheDocument()
    // No count (an older payload) falls back to the plain chip.
    expect(screen.getByText('Old payload').parentElement).toHaveTextContent(/COND-4Old payloadBlocked$/)
    expect(screen.getByText('Gone quiet').parentElement).toHaveTextContent(/Stale$/)
  })

  it('names the days a milestone is missed by on the forecast line', async () => {
    render(<OverviewPanel projectId="proj-1" />)

    expect(await screen.findByText(/misses the milestone by 2 days/)).toBeInTheDocument()
  })

  describe('plan rows', () => {
    const summary = { type: 'PRD', status: 'IN_PROGRESS', statusLabel: 'In Progress', workflow: 'PRDS' }
    const PLANS = {
      activeItems: [
        {
          item: { ...summary, id: 'a', displayId: 'PRD-1', title: 'Ship billing' },
          progress: { total: 4, done: 1, percent: 25, criteriaTotal: 3, criteriaMet: 1 },
          health: { health: 'AT_RISK', note: 'Slipping', updatedAt: '2026-10-30T00:00:00Z', stale: false, atRisk: true, pendingDraft: null },
          forecast: { state: 'OK', mode: 'THROUGHPUT', remaining: 3, samples: 12, p50Date: '2026-11-12', p85Date: '2026-11-20', appetiteFit: 'FITS' },
          signals: noSignals,
        },
        {
          item: { ...summary, id: 'b', displayId: 'PRD-2', title: 'No plan yet PRD' },
          progress: { total: 0, done: 0, percent: 0, criteriaTotal: 0, criteriaMet: 0 },
          health: null,
          forecast: { state: 'NO_PLAN', remaining: 0, samples: 0 },
          signals: noSignals,
        },
      ],
      milestones: [{ id: 'm1', projectId: 'proj-1', name: 'Beta', targetOn: '2026-12-01', state: 'OPEN', sortOrder: 0, createdAt: '', updatedAt: '', progress: { topLevelTotal: 1, topLevelDone: 0, leafTotal: 4, leafDone: 1 } }],
      attention: {
        blocked: [{ ...summary, type: 'TASK', id: 'k', displayId: 'TASK-4', title: 'Stuck task' }],
        stale: [{ ...summary, type: 'TASK', id: 's', displayId: 'TASK-9', title: 'Forgotten task' }],
        needsInput: [{ ...summary, type: 'TASK', id: 'n', displayId: 'TASK-3', title: 'Needs an answer' }],
      },
    }

    it('shows skeletons first, then plans, milestones and attention lists from one call', async () => {
      apiGet.mockResolvedValue(PLANS)
      render(<OverviewPanel projectId="proj-1" />)
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

    it('shows each plan health badge and forecast line', async () => {
      apiGet.mockResolvedValue(PLANS)
      render(<OverviewPanel projectId="proj-1" />)
      await screen.findByText('Ship billing')
      expect(screen.getByText('At risk')).toBeInTheDocument()
      expect(screen.getByText('No health yet')).toBeInTheDocument()
      expect(screen.getByText(/P50 .* · P85 .* · fits appetite/)).toBeInTheDocument()
      expect(screen.getByText('No plan yet')).toBeInTheDocument()
    })

    it('shows an error message instead of crashing when the call fails', async () => {
      apiGet.mockRejectedValue(new Error('boom'))
      render(<OverviewPanel projectId="proj-1" />)
      await waitFor(() => expect(screen.getAllByText(/Could not load/).length).toBeGreaterThan(0))
    })
  })

  it('titles the PRD section Plans and says so when there are none', async () => {
    const { unmount } = render(<OverviewPanel projectId="proj-1" />)
    expect(await screen.findByText('Plans')).toBeInTheDocument()
    expect(screen.queryByText('Active PRDs')).not.toBeInTheDocument()
    unmount()

    apiGet.mockResolvedValue({ activeItems: [], milestones: [], attention: { blocked: [], stale: [], needsInput: [] } })
    render(<OverviewPanel projectId="proj-1" />)
    expect(await screen.findByText('No plans yet.')).toBeInTheDocument()
  })
})
