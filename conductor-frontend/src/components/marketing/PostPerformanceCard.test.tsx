import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'
import { PostPerformanceCard, type PublishMetricsResponse } from './PostPerformanceCard'

const API = 'https://api.test'
const VIEW: WorkflowView = {
  slug: 'MARKETING',
  noun: 'Post',
  area: 'MARKETING',
  defaultView: 'calendar',
  version: 1,
  types: ['POST'],
  statuses: [
    { id: 'SCHEDULED', label: 'Scheduled', category: 'in_progress' },
    { id: 'PUBLISHED', label: 'Published', category: 'terminal' },
  ],
  transitions: [],
  assetTypes: ['tiktok_post'],
}

let current: PublishMetricsResponse = { workItemId: 'post-1', targets: [], totals: null }
const fetchMock = vi.fn(async () => ({ ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => current }))

beforeEach(() => {
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

function renderCard(status: string) {
  render(<PostPerformanceCard projectId="p" workItemId="post-1" token="t" status={status} workflowView={VIEW} />)
}

describe('PostPerformanceCard', () => {
  it('shows each destination’s latest counters and the total once numbers exist', async () => {
    current = {
      workItemId: 'post-1',
      targets: [
        { targetId: 't1', platform: 'tiktok', accountLabel: '@rexipe2', latest: { observedAt: '2026-09-10T20:00:00Z', views: 1234, likes: 56, comments: 7, shares: 3 }, series: [] },
        { targetId: 't2', platform: 'facebook', accountLabel: 'Rexipe', latest: { observedAt: '2026-09-10T20:00:00Z', views: 100, likes: 4, comments: null, shares: 1 }, series: [] },
      ],
      totals: { observedAt: '2026-09-10T20:00:00Z', views: 1334, likes: 60, comments: 7, shares: 4 },
    }
    renderCard('PUBLISHED')
    expect(await screen.findByText('Performance')).toBeInTheDocument()
    expect(screen.getByText('1,234')).toBeInTheDocument()
    expect(screen.getByText('(@rexipe2)')).toBeInTheDocument()
    expect(screen.getByText('All destinations')).toBeInTheDocument()
    expect(screen.getByText('1,334')).toBeInTheDocument()
    // A counter the platform does not report reads as a dash, not a zero.
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('says numbers are on their way when a published Post has none yet', async () => {
    current = { workItemId: 'post-1', targets: [], totals: null }
    renderCard('PUBLISHED')
    expect(await screen.findByText(/No numbers yet/)).toBeInTheDocument()
  })

  it('renders nothing while the Post has not published and has nothing to count', async () => {
    current = { workItemId: 'post-1', targets: [], totals: null }
    renderCard('SCHEDULED')
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(screen.queryByTestId('post-performance')).not.toBeInTheDocument()
  })

  it('marks a post the platform no longer returns', async () => {
    current = {
      workItemId: 'post-1',
      targets: [{ targetId: 't1', platform: 'tiktok', latest: { observedAt: '2026-09-10T20:00:00Z', unavailable: true }, series: [] }],
      totals: null,
    }
    renderCard('PUBLISHED')
    expect(await screen.findByText('No longer on the platform')).toBeInTheDocument()
  })
})
