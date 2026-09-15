import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { AreaNounResolution } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'

let resolution: AreaNounResolution = { status: 'ready', workflow: workflow() }
let view: WorkflowView | undefined

function workflow() {
  return {
    id: 'wf', projectId: 'proj-1', name: 'MARKETING', enabled: true,
    slug: 'MARKETING', noun: 'Post', area: 'MARKETING',
    createdAt: '', updatedAt: '',
  }
}

const publishing: WorkflowView = {
  slug: 'MARKETING', noun: 'Post', area: 'MARKETING', defaultView: 'calendar', version: 1, types: ['POST'],
  statuses: [], transitions: [], assetTypes: ['instagram_post'],
}
const engineering: WorkflowView = { ...publishing, slug: 'ENGINEERING', noun: 'Issue', area: 'ENGINEERING', assetTypes: ['github_pr'] }

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', area: 'marketing', noun: 'posts' }),
}))
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'token' }) }))
vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowByAreaNoun: () => resolution, useWorkflowView: () => view }
})
vi.mock('@/components/marketing/compose/ComposePostPage', () => ({
  ComposePostPage: ({ workflowSlug, noun, detailArea }: { workflowSlug: string; noun: string; detailArea: string }) => (
    <div data-testid="compose-page">compose:{workflowSlug}:{noun}:{detailArea}</div>
  ),
}))

import ComposeWorkItemPage from './page'

describe('area/noun compose route', () => {
  beforeEach(() => {
    resolution = { status: 'ready', workflow: workflow() }
    view = publishing
  })

  it('renders the compose page for a publishing Workflow with its real slug and noun', async () => {
    render(<ComposeWorkItemPage />)
    expect(await screen.findByTestId('compose-page')).toHaveTextContent('compose:MARKETING:Post:MARKETING')
  })

  it('has nothing to compose for a Workflow that does not publish', async () => {
    view = engineering
    render(<ComposeWorkItemPage />)
    expect(await screen.findByText(/Nothing to compose here/)).toBeInTheDocument()
  })

  it('has nothing to compose when the area/noun pair resolves to no Workflow', async () => {
    resolution = { status: 'notfound' }
    render(<ComposeWorkItemPage />)
    expect(await screen.findByText(/Nothing to compose here/)).toBeInTheDocument()
  })
})
