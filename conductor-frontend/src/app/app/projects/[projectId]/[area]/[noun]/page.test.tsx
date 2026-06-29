import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { AreaNounResolution } from '@/lib/workflows'

let resolution: AreaNounResolution = { status: 'loading' }

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', area: 'engineering', noun: 'issues' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/components/workitems/WorkItemListView', () => ({
  WorkItemListView: ({ noun, slug }: { noun: string; slug: string }) => (
    <div data-testid="list-view">list:{noun}:{slug}</div>
  ),
}))

vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowByAreaNoun: () => resolution }
})

import WorkItemAreaNounPage from './page'

describe('area/noun Work Item list route', () => {
  it('resolves the area/noun pair and renders the list view with the REAL slug', () => {
    resolution = {
      status: 'ready',
      workflow: {
        id: 'wf', projectId: 'proj-1', name: 'ENGINEERING', enabled: true,
        slug: 'ENGINEERING', noun: 'Issue', area: 'ENGINEERING',
        createdAt: '', updatedAt: '',
      },
    }
    render(<WorkItemAreaNounPage />)
    // The case-sensitive slug (ENGINEERING) is passed through unchanged, not the lowercased URL segment.
    expect(screen.getByTestId('list-view')).toHaveTextContent('list:Issue:ENGINEERING')
  })

  it('renders a not-found state when no workflow matches the area/noun pair', () => {
    resolution = { status: 'notfound' }
    render(<WorkItemAreaNounPage />)
    expect(screen.getByText(/workflow not found/i)).toBeInTheDocument()
  })

  it('renders a loading state while resolving', () => {
    resolution = { status: 'loading' }
    render(<WorkItemAreaNounPage />)
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })
})
