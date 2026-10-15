import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'
import { detailResponse } from './test-fixtures'

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

  it('shows "No breakdown yet" for an empty dimension', async () => {
    render(<ChannelDetailPanel projectId="proj-1" channel="search" />)
    const region = await screen.findByRole('region', { name: 'Top referrers' })
    expect(within(region).getByText(/No breakdown yet/)).toBeInTheDocument()
    expect(within(region).queryByRole('table')).not.toBeInTheDocument()
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
