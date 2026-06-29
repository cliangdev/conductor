import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'

// Plain (non-vi.fn) stub so the rejected-promise path is not flagged as unhandled.
let fetchBehavior: () => Promise<WorkflowView> = () => Promise.resolve(view({}))
const replace = vi.fn()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', slug: 'ENGINEERING', displayId: 'COND-22' }),
  useRouter: () => ({ replace }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, fetchWorkflowView: () => fetchBehavior() }
})

import LegacyWorkSlugDetailRedirectPage from './page'

function view(overrides: Partial<WorkflowView>): WorkflowView {
  return {
    slug: 'ENGINEERING',
    noun: 'Issue',
    area: 'ENGINEERING',
    defaultView: 'list',
    version: 1,
    types: [],
    statuses: [],
    transitions: [],
    ...overrides,
  }
}

describe('legacy /work/[slug]/[displayId] redirect shim', () => {
  beforeEach(() => {
    replace.mockClear()
    fetchBehavior = () => Promise.resolve(view({}))
  })

  it('resolves the workflow by slug and replaces with the area/noun detail path (keeping displayId)', async () => {
    render(<LegacyWorkSlugDetailRedirectPage />)
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/engineering/issues/COND-22'),
    )
  })

  it('shows an error when the slug resolves to no workflow', async () => {
    fetchBehavior = () => Promise.reject(new Error('404'))
    render(<LegacyWorkSlugDetailRedirectPage />)
    expect(await screen.findByText(/workflow not found/i)).toBeInTheDocument()
    expect(replace).not.toHaveBeenCalled()
  })
})
