import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { AreaNounResolution } from '@/lib/workflows'

// Plain (non-vi.fn) stub for apiGet so the rejected-promise path is not flagged as unhandled.
let apiGetBehavior: () => Promise<unknown> = () =>
  Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })
let resolution: AreaNounResolution = { status: 'ready', workflow: workflow() }

function workflow() {
  return {
    id: 'wf', projectId: 'proj-1', name: 'ENGINEERING', enabled: true,
    slug: 'ENGINEERING', noun: 'Issue', area: 'ENGINEERING',
    createdAt: '', updatedAt: '',
  }
}

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', area: 'engineering', noun: 'issues', displayId: 'COND-22' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/lib/api', () => ({
  apiGet: (...args: unknown[]) => apiGetBehavior.call(null, ...(args as [])),
}))

vi.mock('@/components/workitems/WorkItemDetailView', () => ({
  WorkItemDetailView: ({ workItemId, slug }: { workItemId: string; slug: string }) => (
    <div data-testid="detail-view">detail:{workItemId}:{slug}</div>
  ),
}))

vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowByAreaNoun: () => resolution }
})

import WorkItemAreaNounDetailPage from './page'

describe('area/noun Work Item detail route', () => {
  beforeEach(() => {
    apiGetBehavior = () =>
      Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })
    resolution = { status: 'ready', workflow: workflow() }
  })

  it('resolves the displayId via by-display and renders the detail view with the REAL slug', async () => {
    const calls: string[] = []
    apiGetBehavior = (...args: unknown[]) => {
      calls.push(args[0] as string)
      return Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })
    }
    render(<WorkItemAreaNounDetailPage />)
    expect(await screen.findByTestId('detail-view')).toHaveTextContent('detail:uuid-1:ENGINEERING')
    expect(calls[0]).toBe('/api/v2/projects/proj-1/work-items/by-display/COND-22')
  })

  it('renders a not-found state for an unknown displayId', async () => {
    apiGetBehavior = () => Promise.reject(new Error('404'))
    render(<WorkItemAreaNounDetailPage />)
    expect(await screen.findByText(/work item not found/i)).toBeInTheDocument()
  })

  it('renders a not-found state when the area/noun pair resolves to no workflow', async () => {
    resolution = { status: 'notfound' }
    render(<WorkItemAreaNounDetailPage />)
    expect(await screen.findByText(/work item not found/i)).toBeInTheDocument()
  })
})
