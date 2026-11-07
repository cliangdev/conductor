import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'
import { emptyInsightsResponse, insightsResponse } from './test-fixtures'

const push = vi.fn()
const replace = vi.fn()
let pathname = '/app/projects/proj-1/marketing/posts'
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

// Marketing changes shares the MARKETING area and is listed first (older), so "first entry in the area"
// would resolve Posts to /marketing/changes.
const CHANGES_WORKFLOW = {
  ...MARKETING_WORKFLOW,
  id: 'wf-changes',
  name: 'MARKETING_CHANGES',
  slug: 'MARKETING_CHANGES',
  noun: 'Change',
  createdAt: '2025-11-01T00:00:00Z',
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
    pathname = '/app/projects/proj-1/marketing/posts'
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

  it('shows median views and engagements per platform, and greys out a rate on few engagements', async () => {
    const base = insightsResponse()
    stubApi(() =>
      Promise.resolve(
        insightsResponse({
          byPlatform: [
            base.byPlatform[0],
            {
              key: 'youtube',
              label: 'YouTube',
              posts: 4,
              views: 452,
              likes: 1,
              comments: 0,
              shares: 0,
              saves: 0,
              engagementRate: 0.0022,
              medianViews: 91,
              avgViewPct: 59.6,
            },
          ],
        }),
      ),
    )
    renderPanel()

    expect(await screen.findAllByRole('columnheader', { name: 'Median views' })).not.toHaveLength(0)
    expect(screen.getAllByRole('columnheader', { name: 'Engagements' })).not.toHaveLength(0)
    expect(screen.getByText('3.4K')).toBeInTheDocument() // Instagram median views
    expect(screen.getByText('2.8K')).toBeInTheDocument() // Instagram engagements 2200+300+120+200

    const lowRate = screen.getByTitle('Fewer than 20 engagements')
    expect(lowRate).toHaveTextContent('0.2%')
    expect(lowRate).toHaveClass('text-muted-foreground')
    expect(screen.getByText(/Greyed-out rates rest on fewer than 20 engagements/)).toBeInTheDocument()
  })

  it('leaves the low-sample note out when every rate rests on enough engagements', async () => {
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    await screen.findByText('By platform')
    expect(screen.queryByText(/Greyed-out rates/)).not.toBeInTheDocument()
  })

  it('opens and closes the "How to read these numbers" help', async () => {
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    const toggle = await screen.findByRole('button', { name: 'How to read these numbers' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    // aria-controls points at the section even while it is closed.
    const help = document.getElementById(toggle.getAttribute('aria-controls')!)
    expect(help).not.toBeNull()
    expect(help).not.toBeVisible()

    fireEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('Why more views can mean a lower rate')).toBeVisible()
    expect(screen.getByText(/Engagements divided by views/)).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: 'Hide help' }))
    expect(help).not.toBeVisible()
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

  it('"Go to Posts" resolves Posts by noun even when Changes shares the area and sorts first', async () => {
    stubApi(() => Promise.resolve(emptyInsightsResponse()), [CHANGES_WORKFLOW, MARKETING_WORKFLOW])
    renderPanel()

    expect(await screen.findByText('No published Posts in this window yet')).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: /go to posts/i })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/marketing/posts',
    )
  })

  it('shows an error state when the request fails', async () => {
    stubApi(() => Promise.reject(new Error('boom')))
    renderPanel()

    expect(await screen.findByText('Could not load insights — please try again.')).toBeInTheDocument()
  })

  it('shows Top creatives linking to the Creative editor', async () => {
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    expect(await screen.findByText('Top creatives')).toBeInTheDocument()
    const link = screen.getByRole('link', { name: '12b' })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/marketing/creatives/cr-12b')
    expect(screen.getByText('Stop scrolling, start cooking')).toBeInTheDocument()
  })

  it('hides the Top creatives section when creatives is empty', async () => {
    stubApi(() => Promise.resolve(insightsResponse({ creatives: [] })))
    renderPanel()

    await screen.findByText('Top posts')
    expect(screen.queryByText('Top creatives')).not.toBeInTheDocument()
  })

  it('hides the "Needs a rethink" section when bottomPosts is empty', async () => {
    stubApi(() => Promise.resolve(insightsResponse({ bottomPosts: [] })))
    renderPanel()

    await screen.findByText('Top posts')
    expect(screen.queryByText('Needs a rethink')).not.toBeInTheDocument()
  })

  it('keeps view=insights when it writes the window into the URL', async () => {
    searchParams = new URLSearchParams('view=insights')
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    await screen.findByText('Top posts')
    fireEvent.click(screen.getByRole('radio', { name: '7d' }))

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/marketing/posts?view=insights&window=7d'),
    )
  })

  it('selecting the 7d tab updates the URL to window=7d', async () => {
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    await screen.findByText('Top posts')
    fireEvent.click(screen.getByRole('radio', { name: '7d' }))

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/marketing/posts?window=7d'),
    )
  })

  it('changing the platform filter updates the URL to platform=tiktok', async () => {
    stubApi(() => Promise.resolve(insightsResponse()))
    renderPanel()

    await screen.findByText('Top posts')
    fireEvent.change(screen.getByLabelText('Platform'), { target: { value: 'tiktok' } })

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/marketing/posts?platform=tiktok'),
    )
  })

  it('keeps every platform in the dropdown after filtering to one', async () => {
    // The server narrows coverage.platforms to the active filter; the dropdown must not follow it.
    const filtered = insightsResponse({ coverage: { platforms: ['tiktok'], notes: [] } })
    stubApi(() => Promise.resolve(insightsResponse()))
    const view = renderPanel()
    await screen.findByText('Top posts')

    searchParams = new URLSearchParams({ platform: 'tiktok' })
    stubApi(() => Promise.resolve(filtered))
    view.rerender(<WhatsWorkingPanel projectId="proj-1" />)

    await waitFor(() =>
      expect(apiGet as Mock).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/marketing/insights?window=30d&platform=tiktok',
        'test-token',
      ),
    )
    const options = Array.from((screen.getByLabelText('Platform') as HTMLSelectElement).options).map((o) => o.value)
    expect(options).toEqual(['', 'instagram', 'tiktok'])
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
