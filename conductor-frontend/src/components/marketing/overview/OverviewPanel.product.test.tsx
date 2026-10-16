import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'
import { overviewResponse, productChannel, productFunnelStages } from './test-fixtures'

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
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, accessToken: 'test-token', loading: false }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: () => true, refresh: vi.fn() }),
}))

import { apiGet } from '@/lib/api'
import { OverviewPanel } from './OverviewPanel'

beforeEach(() => {
  vi.clearAllMocks()
  clearAllSidebarCaches()
  localStorage.clear()
})

describe('OverviewPanel with the In-app channel', () => {
  it('puts the In-app card last and the in-app stages in the funnel, flagged from the card metrics', async () => {
    const base = overviewResponse()
    ;(apiGet as Mock).mockResolvedValue({
      ...base,
      funnel: productFunnelStages(),
      channels: [...base.channels, productChannel()],
    })
    render(<OverviewPanel projectId="proj-1" />)
    expect(await screen.findByRole('region', { name: 'Funnel' })).toBeInTheDocument()
    const names = screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))
    expect(names.filter((n) => ['Web', 'Search', 'Social', 'App Store', 'Ads', 'In-app'].includes(n ?? ''))).toEqual([
      'Web',
      'Search',
      'Social',
      'App Store',
      'Ads',
      'In-app',
    ])
    expect(screen.getAllByText('First opens').length).toBeGreaterThanOrEqual(1)
    // the funnel stage and the card both carry the flags
    expect(screen.getAllByText('Small sample').length).toBeGreaterThanOrEqual(2)
  })

  it('loads with one placeholder per channel card', () => {
    ;(apiGet as Mock).mockReturnValue(new Promise(() => {}))
    render(<OverviewPanel projectId="proj-1" />)
    const loading = screen.getByLabelText('Loading overview')
    expect(loading.querySelectorAll('.md\\:grid-cols-2 > *')).toHaveLength(6)
  })
})
