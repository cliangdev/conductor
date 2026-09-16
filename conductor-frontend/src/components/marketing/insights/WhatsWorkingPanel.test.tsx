import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'
import { emptyInsightsResponse, insightsResponse } from './test-fixtures'

const push = vi.fn()
const replace = vi.fn()
let pathname = '/app/projects/proj-1/marketing/insights'
let searchParams = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push, replace }),
  usePathname: () => pathname,
  useSearchParams: () => searchParams,
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'user-1', name: 'Test User', email: 'test@example.com' },
    accessToken: 'test-token',
    loading: false,
  }),
}))

import { apiGet } from '@/lib/api'
import { WhatsWorkingPanel } from './WhatsWorkingPanel'

const MARKETING_WORKFLOW = {
  id: 'wf-marketing',
  projectId: 'proj-1',
  name: 'MARKETING',
  enabled: true,
  kind: 'LIFECYCLE',
  sidebarEnabled: true,
  area: 'MARKETING',
  slug: 'MARKETING',
  noun: 'Post',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

// Created before MARKETING_WORKFLOW so it would be the "first Marketing workflow" guess if a post's
// own workflowSlug were ignored — used to prove the slug-based resolution actually takes effect.
const CAMPAIGNS_WORKFLOW = {
  ...MARKETING_WORKFLOW,
  id: 'wf-campaigns',
  name: 'CAMPAIGNS',
  slug: 'CAMPAIGNS',
  noun: 'Campaign',
  createdAt: '2025-12-01T00:00:00Z',
}

function stubApi(insights: () => Promise<unknown>, workflows: unknown[] = [MARKETING_WORKFLOW]) {
  ;(apiGet as Mock).mockImplementation((path: string) => {
    if (path.includes('/marketing/insights')) return insights()
    if (path.includes('/workflows?')) return Promise.resolve(workflows)
    return Promise.resolve([])
  })
}

function renderPanel() {
  return render(<WhatsWorkingPanel projectId="proj-1" />)
}

describe('WhatsWorkingPanel', () => {
  beforeEach(() => {
    clearAllSidebarCaches()
    localStorage.clear()
    ;(apiGet as Mock).mockReset()
    push.mockClear()
    replace.mockClear()
    pathname = '/app/projects/proj-1/marketing/insights'
    searchParams = new URLSearchParams()
  })

  it('renders totals, movers, the by-platform/by-format tables, and top posts', async () => {
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    expect(await screen.findByText('12')).toBeInTheDocument() // posts total
    expect(screen.getByText('48K')).toBeInTheDocument() // views total (compact)
    expect(screen.getByText('8.5%')).toBeInTheDocument() // engagement rate total

    // Mover delta next to a totals figure.
    expect(screen.getByText('+33.3%')).toBeInTheDocument()
    expect(screen.getByText('-5.9%')).toBeInTheDocument()

    // By-platform table (the platform filter's <select> options also read "Instagram"/"TikTok").
    expect(screen.getAllByText('Instagram').length).toBeGreaterThan(0)
    expect(screen.getAllByText('TikTok').length).toBeGreaterThan(0)

    // By-format table.
    expect(screen.getByText('By format')).toBeInTheDocument()

    // Top posts / needs a rethink.
    const topLink = await screen.findByRole('link', { name: 'Launch teaser' })
    expect(topLink).toHaveAttribute('href', '/app/projects/proj-1/marketing/posts/MK-101')
    expect(screen.getByText('Needs a rethink')).toBeInTheDocument()
    expect(screen.getByText('Behind the scenes')).toBeInTheDocument()

    // Coverage footnote.
    expect(screen.getByText(/Facebook doesn't report views/)).toBeInTheDocument()
  })

  it('links a top post using its own workflowSlug, not the first-Marketing-workflow guess', async () => {
    // CAMPAIGNS sorts before MARKETING (older createdAt), so the naive "first Marketing workflow"
    // guess would misroute to /marketing/campaigns/MK-101 if InsightsPost.workflowSlug were ignored.
    stubApi(() => Promise.resolve(insightsResponse()), [CAMPAIGNS_WORKFLOW, MARKETING_WORKFLOW])
    renderPanel()

    const topLink = await screen.findByRole('link', { name: 'Launch teaser' })
    expect(topLink).toHaveAttribute('href', '/app/projects/proj-1/marketing/posts/MK-101')
  })

  it('shows the empty state with no published Posts in the window', async () => {
    stubApi(() => Promise.resolve(emptyInsightsResponse()))
    renderPanel()

    expect(await screen.findByText('No published Posts in this window yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /go to posts/i })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/marketing/posts',
    )
  })

  it('shows an error state when the request fails', async () => {
    stubApi(() => Promise.reject(new Error('boom')))
    renderPanel()

    expect(await screen.findByText('Could not load insights — please try again.')).toBeInTheDocument()
  })

  it('hides the "Needs a rethink" section when bottomPosts is empty', async () => {
    stubApi(() => Promise.resolve(insightsResponse({ bottomPosts: [] })))
    renderPanel()

    await screen.findByText('Top posts')
    expect(screen.queryByText('Needs a rethink')).not.toBeInTheDocument()
  })

  it('selecting the 7d tab updates the URL to window=7d', async () => {
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    await screen.findByText('Top posts')
    fireEvent.click(screen.getByRole('tab', { name: '7d' }))

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/marketing/insights?window=7d'),
    )
  })

  it('changing the platform filter updates the URL to platform=tiktok', async () => {
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    await screen.findByText('Top posts')
    fireEvent.change(screen.getByLabelText('Platform'), { target: { value: 'tiktok' } })

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/marketing/insights?platform=tiktok'),
    )
  })

  it('requests the window and platform already present in the URL on load', async () => {
    searchParams = new URLSearchParams({ window: '7d', platform: 'tiktok' })
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    await waitFor(() =>
      expect(apiGet as Mock).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/marketing/insights?window=7d&platform=tiktok',
        'test-token',
      ),
    )
  })
})
