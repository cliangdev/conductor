import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'

// Plain (non-vi.fn) stub so the rejected-promise path is not flagged as unhandled.
let fetchBehavior: () => Promise<WorkflowView> = () => Promise.resolve(view({}))
const replace = vi.fn()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', slug: 'ENGINEERING' }),
  useRouter: () => ({ replace }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, fetchWorkflowView: () => fetchBehavior() }
})

import LegacyWorkSlugRedirectPage from './page'

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

describe('legacy /work/[slug] redirect shim', () => {
  beforeEach(() => {
    replace.mockClear()
    fetchBehavior = () => Promise.resolve(view({}))
  })

  it('resolves the workflow by slug and replaces with the area/noun list path', async () => {
    render(<LegacyWorkSlugRedirectPage />)
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/engineering/issues'),
    )
  })

  it('shows an error when the slug resolves to no workflow', async () => {
    fetchBehavior = () => Promise.reject(new Error('404'))
    render(<LegacyWorkSlugRedirectPage />)
    expect(await screen.findByText(/workflow not found/i)).toBeInTheDocument()
    expect(replace).not.toHaveBeenCalled()
  })
})
