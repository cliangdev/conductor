import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

// Plain (non-vi.fn) stubs driven per test so vitest's settled-results tracking doesn't flag the
// rejected-promise path as unhandled even though the page awaits/catches it.
let apiGetBehavior: () => Promise<unknown> = () =>
  Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', slug: 'ENGINEERING', displayId: 'COND-22' }),
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

import WorkItemDetailPage from './page'

describe('workflow-scoped Work Item detail route', () => {
  beforeEach(() => {
    apiGetBehavior = () =>
      Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })
  })

  it('resolves the displayId via the by-display endpoint and renders the detail view', async () => {
    const calls: string[] = []
    apiGetBehavior = (...args: unknown[]) => {
      calls.push(args[0] as string)
      return Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })
    }
    render(<WorkItemDetailPage />)
    expect(await screen.findByTestId('detail-view')).toHaveTextContent('detail:uuid-1:ENGINEERING')
    expect(calls[0]).toBe('/api/v2/projects/proj-1/work-items/by-display/COND-22')
  })

  it('renders a not-found state for an unknown displayId', async () => {
    apiGetBehavior = () => Promise.reject(new Error('404'))
    render(<WorkItemDetailPage />)
    expect(await screen.findByText(/work item not found/i)).toBeInTheDocument()
  })
})
