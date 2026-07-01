import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'

const push = vi.fn()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace: vi.fn() }),
  usePathname: () => '/app/projects/proj-1/engineering/issues',
  useSearchParams: () => new URLSearchParams(),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token', user: { id: 'user-1' } }),
}))

vi.mock('@/lib/api', () => ({
  apiGet: (url: string) => {
    if (url.includes('/work-items?workflow=')) {
      return Promise.resolve([
        {
          id: 'uuid-1',
          title: 'First Work Item',
          type: 'PRD',
          status: 'DRAFT',
          updatedAt: '2026-01-01T00:00:00Z',
          displayId: 'COND-1',
        },
      ])
    }
    return Promise.resolve([])
  },
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))

const view: WorkflowView = {
  slug: 'ENGINEERING',
  noun: 'Issue',
  area: 'ENGINEERING',
  defaultView: 'list',
  version: 1,
  types: ['PRD'],
  statuses: [{ id: 'DRAFT', label: 'Draft', category: 'open' }],
  transitions: [],
}

vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowView: () => view }
})

import { WorkItemListView } from './WorkItemListView'

describe('WorkItemListView links', () => {
  beforeEach(() => {
    push.mockClear()
  })

  it('links each Work Item to the workflow-scoped displayId route', async () => {
    render(<WorkItemListView projectId="proj-1" slug="ENGINEERING" noun="Issue" />)
    await waitFor(() => expect(screen.getAllByText('First Work Item').length).toBeGreaterThan(0))
    const links = screen.getAllByRole('link')
    const itemLink = links.find(
      (l) => l.getAttribute('href') === '/app/projects/proj-1/engineering/issues/COND-1',
    )
    expect(itemLink).toBeDefined()
  })

  it('renders an area breadcrumb sourced from the Workflow view', async () => {
    render(<WorkItemListView projectId="proj-1" slug="ENGINEERING" noun="Issue" />)
    // area "ENGINEERING" is humanized into the breadcrumb label.
    expect(await screen.findByText('Engineering')).toBeInTheDocument()
  })
})
