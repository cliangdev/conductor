import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'
import { channel, metric, overviewResponse } from './test-fixtures'

let role: 'ADMIN' | 'CREATOR' | 'REVIEWER' = 'ADMIN'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/projects/proj-1/marketing/overview',
  useSearchParams: () => new URLSearchParams(),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) =>
    (err as { detail?: string } | null)?.detail ?? fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, accessToken: 'test-token', loading: false }),
}))

vi.mock('@/contexts/PermissionsContext', async () => {
  const { can } = await import('@/lib/permissions')
  return { usePermissions: () => ({ role, loading: false, can: (c: Parameters<typeof can>[1]) => can(role, c), refresh: vi.fn() }) }
})

import { apiDelete, apiGet, apiPut } from '@/lib/api'
import { OVERVIEW_WINDOW_STORAGE_KEY, OverviewPanel } from './OverviewPanel'

beforeEach(() => {
  vi.clearAllMocks()
  clearAllSidebarCaches()
  localStorage.clear()
  role = 'ADMIN'
  ;(apiGet as Mock).mockResolvedValue(overviewResponse())
})

describe('OverviewPanel', () => {
  it('shows the funnel and the five channel cards in order, defaulting to 28d', async () => {
    render(<OverviewPanel projectId="proj-1" />)
    expect(await screen.findByRole('region', { name: 'Funnel' })).toBeInTheDocument()
    const names = screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))
    expect(names.filter((n) => ['Web', 'Search', 'Social', 'App Store', 'Ads'].includes(n ?? ''))).toEqual([
      'Web',
      'Search',
      'Social',
      'App Store',
      'Ads',
    ])
    expect((apiGet as Mock).mock.calls[0][0]).toContain('/marketing/overview?window=28d')
    expect(screen.getByText('Data through Sep 14')).toBeInTheDocument()
  })

  it('hides "Data through" when nothing is connected', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      overviewResponse({
        window: { from: '2026-08-18', to: null, dataCompleteThroughMin: null, previousFrom: '2026-07-21', previousTo: '2026-08-17' },
        channels: [channel({ channel: 'WEB', label: 'Web', status: 'NOT_CONNECTED', metrics: [], lastFetchedAt: null })],
      }),
    )
    render(<OverviewPanel projectId="proj-1" />)
    await screen.findByText('Nothing is connected yet')
    expect(screen.queryByText(/Data through/)).not.toBeInTheDocument()
  })

  it('shows a range when channels are complete through different days', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      overviewResponse({
        window: { from: '2026-09-14', to: '2026-10-12', dataCompleteThroughMin: '2026-10-11', previousFrom: '2026-08-17', previousTo: '2026-09-13' },
      }),
    )
    render(<OverviewPanel projectId="proj-1" />)
    expect(await screen.findByText('Data through Oct 11–12')).toBeInTheDocument()
  })

  it('switches to 7d and remembers the choice', async () => {
    render(<OverviewPanel projectId="proj-1" />)
    await screen.findByRole('region', { name: 'Funnel' })
    fireEvent.click(screen.getByRole('tab', { name: '7d' }))
    await waitFor(() =>
      expect((apiGet as Mock).mock.calls.some((c) => String(c[0]).includes('window=7d'))).toBe(true),
    )
    expect(localStorage.getItem(OVERVIEW_WINDOW_STORAGE_KEY)).toBe('7d')
  })

  it('shows an error with a retry', async () => {
    ;(apiGet as Mock).mockRejectedValueOnce(new Error('boom'))
    render(<OverviewPanel projectId="proj-1" />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the overview')
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('region', { name: 'Funnel' })).toBeInTheDocument()
  })

  it('shows an empty state when nothing is connected', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      overviewResponse({
        channels: [
          channel({ channel: 'WEB', label: 'Web', status: 'NOT_CONNECTED', metrics: [], lastFetchedAt: null }),
          channel({ channel: 'STORE', label: 'App Store', status: 'COMING_SOON', metrics: [] }),
        ],
      }),
    )
    render(<OverviewPanel projectId="proj-1" />)
    expect(await screen.findByText('Nothing is connected yet')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Funnel' })).not.toBeInTheDocument()
  })

  it.each([
    ['ADMIN', true],
    ['CREATOR', true],
    ['REVIEWER', false],
  ] as const)('gates Set target for %s', async (r, allowed) => {
    role = r
    render(<OverviewPanel projectId="proj-1" />)
    await screen.findByRole('region', { name: 'Funnel' })
    const buttons = screen.queryAllByRole('button', { name: /target for/i })
    expect(buttons.length > 0).toBe(allowed)
  })

  it('saves a target optimistically, converting percent input to a fraction', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      overviewResponse({
        channels: [channel({ metrics: [metric({ metric: 'ctr', label: 'Click-through rate', unit: 'PERCENT', value: 0.031 })] })],
      }),
    )
    let resolvePut: (v: unknown) => void = () => {}
    ;(apiPut as Mock).mockReturnValue(new Promise((r) => (resolvePut = r)))
    render(<OverviewPanel projectId="proj-1" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Set target for Click-through rate' }))
    fireEvent.change(screen.getByLabelText('Target (%)'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save target' }))

    // The card shows the target before the server answers.
    expect(await screen.findByText(/Target 4.0% · 78% there/)).toBeInTheDocument()
    expect((apiPut as Mock).mock.calls[0][0]).toContain('/marketing/targets/SEARCH/ctr')
    expect((apiPut as Mock).mock.calls[0][1]).toEqual({ target: 0.04, direction: 'UP' })
    resolvePut({ value: 0.04, direction: 'UP' })
  })

  it('reverts the card and shows the server message when saving fails', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      overviewResponse({ channels: [channel({ metrics: [metric({ metric: 'clicks', label: 'Clicks' })] })] }),
    )
    ;(apiPut as Mock).mockRejectedValue({ detail: 'Not allowed to set targets' })
    render(<OverviewPanel projectId="proj-1" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Set target for Clicks' }))
    fireEvent.change(screen.getByLabelText('Target'), { target: { value: '5000' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save target' }))
    expect(await screen.findByText('Not allowed to set targets')).toBeInTheDocument()
    expect(screen.getAllByText(/No target set/).length).toBeGreaterThan(0)
  })

  it('removes a target', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      overviewResponse({
        channels: [channel({ metrics: [metric({ metric: 'clicks', label: 'Clicks', target: { value: 2000, direction: 'UP' } })] })],
      }),
    )
    ;(apiDelete as Mock).mockResolvedValue(undefined)
    render(<OverviewPanel projectId="proj-1" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Edit target for Clicks' }))
    fireEvent.click(screen.getByRole('button', { name: 'Remove target' }))
    await waitFor(() => expect(apiDelete).toHaveBeenCalled())
    expect((apiDelete as Mock).mock.calls[0][0]).toContain('/marketing/targets/SEARCH/clicks')
    expect(await screen.findByText(/No target set/)).toBeInTheDocument()
  })
})
