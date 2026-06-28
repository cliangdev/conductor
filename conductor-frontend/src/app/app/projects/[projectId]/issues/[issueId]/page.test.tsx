import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

// Plain (non-vi.fn) stub for apiGet so the rejected-promise path is not flagged as unhandled.
let apiGetBehavior: () => Promise<unknown> = () =>
  Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })
const replace = vi.fn()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', issueId: 'uuid-1' }),
  useRouter: () => ({ replace }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token' }),
}))

vi.mock('@/lib/api', () => ({
  apiGet: (...args: unknown[]) => apiGetBehavior.call(null, ...(args as [])),
}))

import LegacyIssueRedirectPage from './page'

describe('legacy /issues/[issueId] redirect', () => {
  beforeEach(() => {
    replace.mockClear()
    apiGetBehavior = () =>
      Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })
  })

  it('resolves the Work Item by UUID and replaces with the workflow-scoped displayId URL', async () => {
    const calls: string[] = []
    apiGetBehavior = (...args: unknown[]) => {
      calls.push(args[0] as string)
      return Promise.resolve({ id: 'uuid-1', workflow: 'ENGINEERING', displayId: 'COND-22' })
    }
    render(<LegacyIssueRedirectPage />)
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/work/ENGINEERING/COND-22'),
    )
    expect(calls[0]).toBe('/api/v2/projects/proj-1/work-items/uuid-1')
  })

  it('shows an error when the Work Item cannot be resolved', async () => {
    apiGetBehavior = () => Promise.reject(new Error('404'))
    render(<LegacyIssueRedirectPage />)
    expect(await screen.findByText(/work item not found/i)).toBeInTheDocument()
    expect(replace).not.toHaveBeenCalled()
  })
})
