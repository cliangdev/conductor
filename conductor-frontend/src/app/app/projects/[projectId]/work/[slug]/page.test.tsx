import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'

// A plain (non-vi.fn) stub driven per test. Using vi.fn here would make vitest's settled-results
// tracking flag the rejected promise as unhandled even though the page awaits/catches it.
let mockBehavior: () => Promise<WorkflowView> = () => Promise.resolve(view({}))

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', slug: 'ENGINEERING' }),
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
  return { ...actual, fetchWorkflowView: () => mockBehavior() }
})

import WorkItemPage from './page'

function view(overrides: Partial<WorkflowView>): WorkflowView {
  return {
    slug: 'ENGINEERING',
    noun: 'Issue',
    defaultView: 'list',
    version: 1,
    types: [],
    statuses: [],
    transitions: [],
    ...overrides,
  }
}

describe('generic Work Item page', () => {
  beforeEach(() => {
    mockBehavior = () => Promise.resolve(view({}))
  })

  it('renders the list view with the workflow noun and slug', async () => {
    // The list view owns its own header/title; the page just delegates to it for default_view: list.
    mockBehavior = () => Promise.resolve(view({ noun: 'Issue', defaultView: 'list' }))
    render(<WorkItemPage />)
    expect(await screen.findByTestId('list-view')).toHaveTextContent('list:Issue:ENGINEERING')
  })

  it('renders a board placeholder for default_view board', async () => {
    mockBehavior = () => Promise.resolve(view({ noun: 'Deal', defaultView: 'board' }))
    render(<WorkItemPage />)
    expect(await screen.findByText(/board view coming soon/i)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Deals' })).toBeInTheDocument()
    expect(screen.queryByTestId('list-view')).not.toBeInTheDocument()
  })

  it('renders a not-found state when the slug resolves to no workflow', async () => {
    mockBehavior = () => Promise.reject(new Error('404'))
    render(<WorkItemPage />)
    expect(await screen.findByText(/workflow not found/i)).toBeInTheDocument()
  })
})
