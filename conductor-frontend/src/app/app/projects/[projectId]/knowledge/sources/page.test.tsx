import { describe, it, expect, beforeEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { vi } from 'vitest'

const replace = vi.fn()
let searchParams = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ replace }),
  useSearchParams: () => searchParams,
}))

import KnowledgeSourcesPage from './page'

describe('Knowledge sources redirect', () => {
  beforeEach(() => {
    replace.mockClear()
    searchParams = new URLSearchParams()
  })

  it('redirects to the Activity page Inbox tab', async () => {
    render(<KnowledgeSourcesPage />)

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/knowledge/activity?tab=inbox')
    })
  })

  it('preserves ?status= and ?domain= filters on redirect', async () => {
    searchParams = new URLSearchParams({ status: 'DEAD', domain: 'engineering' })

    render(<KnowledgeSourcesPage />)

    await waitFor(() => {
      expect(replace).toHaveBeenCalledWith(
        '/app/projects/proj-1/knowledge/activity?status=DEAD&domain=engineering&tab=inbox',
      )
    })
  })
})
