import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

const apiGet = vi.fn()

vi.mock('next/navigation', () => ({ useParams: () => ({ projectId: 'proj-1' }) }))
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'token' }) }))
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, apiGet: (...args: unknown[]) => apiGet(...args) }
})
vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowViewsSettled: () => ({ settled: true, views: {} }) }
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
