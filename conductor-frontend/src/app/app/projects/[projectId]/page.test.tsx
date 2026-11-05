import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const apiGet = vi.fn()
const viewsState = vi.hoisted(() => ({ settled: true }))

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
      settled: viewsState.settled,
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

const base = { type: 'PRD', workflow: 'PRDS', createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }
const signals = { blocked: false, blockedBy: [], ready: false, stale: false, needsInput: false }

beforeEach(() => {
  viewsState.settled = true
  apiGet.mockReset()
  apiGet.mockImplementation(async (path: string) => {
    if (path.endsWith('/milestones')) {
      return [{ id: 'm1', projectId: 'proj-1', name: 'Beta', targetOn: '2026-12-01', state: 'OPEN', sortOrder: 0, createdAt: '', updatedAt: '', progress: { topLevelTotal: 1, topLevelDone: 0, leafTotal: 4, leafDone: 1 } }]
    }
    if (path.includes('type=PRD')) {
      return [
        { ...base, id: 'a', displayId: 'PRD-1', title: 'Ship billing', status: 'IN_PROGRESS', progress: { total: 4, done: 1, percent: 25, criteriaTotal: 3, criteriaMet: 1 } },
        { ...base, id: 'b', displayId: 'PRD-2', title: 'Old thing', status: 'DONE' },
      ]
    }
    // parentId=any: the whole backlog, sub-items included
    return [
      { ...base, id: 's', displayId: 'TASK-9', title: 'Forgotten task', status: 'IN_PROGRESS', signals: { ...signals, stale: true } },
      { ...base, id: 'n', displayId: 'TASK-3', title: 'Needs an answer', status: 'IN_PROGRESS', signals: { ...signals, needsInput: true } },
      { ...base, id: 'k', displayId: 'TASK-4', title: 'Stuck task', status: 'IN_PROGRESS', signals: { ...signals, blocked: true, blockedBy: ['x'] } },
    ]
  })
})

describe('project overview page', () => {
  it('shows skeletons first, then active PRDs, milestones and attention lists', async () => {
    render(<ProjectOverviewPage />)
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)

    expect(await screen.findByText('Ship billing')).toBeInTheDocument()
    expect(screen.queryByText('Old thing')).not.toBeInTheDocument() // terminal PRDs are not active
    expect(screen.getByText('1/3 criteria')).toBeInTheDocument()
    expect(screen.getByText('Beta')).toBeInTheDocument()
    expect(screen.getByText('Forgotten task')).toBeInTheDocument()
    expect(screen.getByText('Needs an answer')).toBeInTheDocument()
    expect(screen.getByText('Stuck task')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Ship billing/ })).toHaveAttribute('href', '/app/projects/proj-1/engineering/prds/PRD-1')

    const urls = apiGet.mock.calls.map((c) => c[0] as string)
    expect(urls).toContain('/api/v2/projects/proj-1/work-items?type=PRD&withProgress=true')
    // one backlog fetch feeds blocked, stale and needs-input
    expect(urls.filter((u) => u.includes('parentId=any'))).toEqual(['/api/v2/projects/proj-1/work-items?parentId=any'])
  })

  it('holds a skeleton until the Workflow views are in, so finished PRDs never flash as active', async () => {
    viewsState.settled = false
    render(<ProjectOverviewPage />)
    await waitFor(() => expect(apiGet.mock.calls.length).toBeGreaterThan(2))
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByText('Ship billing')).not.toBeInTheDocument()
    expect(screen.queryByText('Old thing')).not.toBeInTheDocument()
    expect(document.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0)
  })

  it('shows an error message instead of crashing when a list fails', async () => {
    apiGet.mockRejectedValue(new Error('boom'))
    render(<ProjectOverviewPage />)
    await waitFor(() => expect(screen.getAllByText(/Could not load/).length).toBeGreaterThan(0))
  })
})
