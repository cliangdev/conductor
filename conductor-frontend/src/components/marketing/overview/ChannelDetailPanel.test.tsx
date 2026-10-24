import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'
import { channel, detailResponse, metric } from './test-fixtures'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/projects/proj-1/marketing/overview/search',
  useSearchParams: () => new URLSearchParams(),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, accessToken: 'test-token', loading: false }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: () => true, refresh: vi.fn() }),
}))

import { apiGet } from '@/lib/api'
import { ChannelDetailPanel } from './ChannelDetailPanel'

beforeEach(() => {
  vi.clearAllMocks()
  clearAllSidebarCaches()
  localStorage.clear()
  ;(apiGet as Mock).mockImplementation((path: string) =>
    path.includes('/marketing/channels/') ? Promise.resolve(detailResponse()) : Promise.resolve([]),
  )
})

describe('ChannelDetailPanel', () => {
  it('shows the header, back link, metric charts and a 90d option', async () => {
    render(<ChannelDetailPanel projectId="proj-1" channel="search" />)
    expect(await screen.findByRole('heading', { name: 'Search' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Marketing overview/ })).toHaveAttribute('href', '/app/projects/proj-1/marketing/overview')
    expect(screen.getByRole('link', { name: 'Site search' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '90d' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Clicks by day/ })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: /Click-through rate by day.*target 4.0%/ })).toBeInTheDocument()
    expect((apiGet as Mock).mock.calls.some((c) => String(c[0]).includes('/marketing/channels/SEARCH?window=28d'))).toBe(true)
  })

  it('renders dimension tables sorted by the first column, and re-sorts on click', async () => {
    render(<ChannelDetailPanel projectId="proj-1" channel="search" />)
    const table = within(await screen.findByRole('region', { name: 'Top queries' }))
    const firstColumn = () => table.getAllByRole('row').slice(1).map((r) => within(r).getAllByRole('cell')[0].textContent)
    expect(firstColumn()).toEqual(['beta query', 'gamma query', 'alpha query'])
    fireEvent.click(table.getByRole('button', { name: 'Clicks' }))
    expect(firstColumn()).toEqual(['alpha query', 'gamma query', 'beta query'])
    fireEvent.click(table.getByRole('button', { name: 'Name' }))
    expect(firstColumn()).toEqual(['gamma query', 'beta query', 'alpha query'].sort())
  })

  it('shows the App Store charts, the rating with its date and the source table', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) =>
      path.includes('/marketing/channels/')
        ? Promise.resolve(
            detailResponse({
              channel: channel({
                channel: 'STORE',
                label: 'App Store',
                connections: [{ id: 'c2', connectorId: 'app-store-connect', name: 'Rexipe', status: 'LIVE' }],
                metrics: [
                  metric({ metric: 'downloads', label: 'Downloads', value: 310 }),
                  metric({ metric: 'conversion_rate', label: 'Conversion rate', unit: 'PERCENT', value: 0.042 }),
                  metric({ metric: 'rating_avg', label: 'Average rating', unit: 'RATING', value: 4.66, asOf: '2026-09-14' }),
                ],
              }),
              dimensions: [
                {
                  key: 'sources',
                  label: 'By traffic source',
                  columns: [
                    { key: 'impressions', label: 'Impressions', unit: 'COUNT' },
                    { key: 'download_rate', label: 'Downloads per page view', unit: 'PERCENT' },
                  ],
                  rows: [{ key: 'APP_STORE_SEARCH', label: 'App Store search', values: [900, 0.25] }],
                },
              ],
            }),
          )
        : Promise.resolve([]),
    )
    render(<ChannelDetailPanel projectId="proj-1" channel="store" />)
    expect(await screen.findByRole('img', { name: /Conversion rate by day/ })).toBeInTheDocument()
    const rating = screen.getByRole('region', { name: 'Average rating' })
    expect(within(rating).getByText('4.7')).toBeInTheDocument()
    expect(within(rating).getByText(/as of Sep 14/)).toBeInTheDocument()
    const sources = within(screen.getByRole('region', { name: 'By traffic source' }))
    expect(sources.getByText('App Store search')).toBeInTheDocument()
    expect(sources.getByText('25.0%')).toBeInTheDocument()
  })

  it('shows "No breakdown yet" for an empty dimension', async () => {
    render(<ChannelDetailPanel projectId="proj-1" channel="search" />)
    const region = await screen.findByRole('region', { name: 'Top referrers' })
    expect(within(region).getByText(/No breakdown yet/)).toBeInTheDocument()
    expect(within(region).queryByRole('table')).not.toBeInTheDocument()
  })

  it('has one title, with the status row beneath it', async () => {
    render(<ChannelDetailPanel projectId="proj-1" channel="search" />)
    await screen.findByText('Live')
    expect(screen.getAllByRole('heading', { name: 'Search' })).toHaveLength(1)
  })

  it('uses the API label for the title', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) =>
      path.includes('/marketing/channels/')
        ? Promise.resolve(detailResponse({ channel: { ...detailResponse().channel, channel: 'ADS', label: 'Ads' } }))
        : Promise.resolve([]),
    )
    render(<ChannelDetailPanel projectId="proj-1" channel="ads" />)
    expect(await screen.findByRole('heading', { name: 'Ads' })).toBeInTheDocument()
  })

  it('shows a Top posts empty state for Social with no posts', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) =>
      path.includes('/marketing/channels/')
        ? Promise.resolve(detailResponse({ channel: { ...detailResponse().channel, channel: 'SOCIAL', label: 'Social' }, topPosts: [] }))
        : Promise.resolve([]),
    )
    render(<ChannelDetailPanel projectId="proj-1" channel="social" />)
    expect(await screen.findByText('Top posts')).toBeInTheDocument()
    expect(screen.getByText('No published posts in this window.')).toBeInTheDocument()
  })

  it('shows the snapshot day on a level metric', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) =>
      path.includes('/marketing/channels/')
        ? Promise.resolve(
            detailResponse({
              channel: { ...detailResponse().channel, metrics: [metric({ metric: 'followers', label: 'Followers', asOf: '2026-10-15' })] },
            }),
          )
        : Promise.resolve([]),
    )
    render(<ChannelDetailPanel projectId="proj-1" channel="social" />)
    expect(await screen.findByText('as of Oct 15')).toBeInTheDocument()
  })

  it('says when a metric history starts', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) =>
      path.includes('/marketing/channels/')
        ? Promise.resolve(
            detailResponse({
              channel: {
                ...detailResponse().channel,
                metrics: [metric({ metric: 'followers', label: 'Followers', previous: null, change: null, changePct: null, historyStartsOn: '2026-10-01' })],
              },
            }),
          )
        : Promise.resolve([]),
    )
    render(<ChannelDetailPanel projectId="proj-1" channel="social" />)
    expect(await screen.findByText('History starts Oct 1')).toBeInTheDocument()
  })

  it('shows top posts for Social only', async () => {
    const post = {
      workItemId: 'wi-1', displayId: 'POST-1', title: 'Launch reel', targetId: 't1', platform: 'instagram',
      views: 900, observedAt: '2026-09-14T00:00:00Z', engagementRate: 0.08,
    }
    ;(apiGet as Mock).mockImplementation((path: string) =>
      path.includes('/marketing/channels/')
        ? Promise.resolve(detailResponse({ channel: { ...detailResponse().channel, channel: 'SOCIAL', label: 'Social' }, topPosts: [post] }))
        : Promise.resolve([]),
    )
    render(<ChannelDetailPanel projectId="proj-1" channel="social" />)
    expect(await screen.findByText('Top posts')).toBeInTheDocument()
    expect(screen.getByText('Launch reel')).toBeInTheDocument()
  })

  it('links a top post with no workflowSlug to Posts, not Changes, when both share MARKETING', async () => {
    const wf = (slug: string, noun: string, createdAt: string) => ({
      id: slug, projectId: 'proj-1', name: slug, enabled: true, kind: 'LIFECYCLE', sidebarEnabled: true,
      area: 'MARKETING', slug, noun, createdAt, updatedAt: createdAt,
    })
    const post = {
      workItemId: 'wi-1', displayId: 'POST-1', title: 'Launch reel', targetId: 't1', platform: 'instagram',
      views: 900, observedAt: '2026-09-14T00:00:00Z', engagementRate: 0.08,
    }
    ;(apiGet as Mock).mockImplementation((path: string) => {
      if (path.includes('/marketing/channels/'))
        return Promise.resolve(detailResponse({ channel: { ...detailResponse().channel, channel: 'SOCIAL', label: 'Social' }, topPosts: [post] }))
      if (path.includes('/workflows?'))
        return Promise.resolve([
          wf('MARKETING_CHANGES', 'Change', '2025-11-01T00:00:00Z'),
          wf('MARKETING', 'Post', '2026-01-01T00:00:00Z'),
        ])
      return Promise.resolve([])
    })
    render(<ChannelDetailPanel projectId="proj-1" channel="social" />)
    const link = await screen.findByRole('link', { name: /Launch reel/ })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/marketing/posts/POST-1')
  })

  it('gives an unknown channel a not-found state without calling the API', () => {
    render(<ChannelDetailPanel projectId="proj-1" channel="bogus" />)
    expect(screen.getByText('Channel not found')).toBeInTheDocument()
    expect(apiGet).not.toHaveBeenCalledWith(expect.stringContaining('/marketing/channels/'), expect.anything())
  })

  it('shows an error with retry', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) =>
      path.includes('/marketing/channels/') ? Promise.reject(new Error('x')) : Promise.resolve([]),
    )
    render(<ChannelDetailPanel projectId="proj-1" channel="search" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load this channel')
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
