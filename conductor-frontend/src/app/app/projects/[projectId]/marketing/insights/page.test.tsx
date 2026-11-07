import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, waitFor } from '@testing-library/react'

const replace = vi.fn()
let search = new URLSearchParams()
let nav: { entries: unknown[]; loading: boolean }

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ replace }),
  useSearchParams: () => search,
}))
vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'token' }) }))
vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useSidebarWorkNav: () => nav }
})

import MarketingInsightsRedirectPage from './page'

const postsEntry = { slug: 'MARKETING', label: 'Posts', noun: 'Post', area: 'MARKETING', createdAt: '2026-01-01T00:00:00Z' }

beforeEach(() => {
  vi.clearAllMocks()
  search = new URLSearchParams()
  nav = { entries: [postsEntry], loading: false }
})

describe('marketing/insights redirect', () => {
  it('forwards to the Posts list with view=insights', async () => {
    render(<MarketingInsightsRedirectPage />)
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/marketing/posts?view=insights'))
  })

  it('keeps window and platform params', async () => {
    search = new URLSearchParams('window=7d&platform=tiktok')
    render(<MarketingInsightsRedirectPage />)
    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/marketing/posts?window=7d&platform=tiktok&view=insights'),
    )
  })

  it('waits for the Workflow list, then falls back to the default Posts path', async () => {
    nav = { entries: [], loading: true }
    const { rerender } = render(<MarketingInsightsRedirectPage />)
    expect(replace).not.toHaveBeenCalled()
    nav = { entries: [], loading: false }
    rerender(<MarketingInsightsRedirectPage />)
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/marketing/posts?view=insights'))
  })
})
