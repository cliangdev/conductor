import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'
import { channel, detailResponse, mismatch, productChannel } from './test-fixtures'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/projects/proj-1/marketing/overview/product',
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

function serve(detail: ReturnType<typeof detailResponse>) {
  ;(apiGet as Mock).mockImplementation((path: string) =>
    path.includes('/marketing/channels/') ? Promise.resolve(detail) : Promise.resolve([]),
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  clearAllSidebarCaches()
  localStorage.clear()
})

describe('ChannelDetailPanel for the In-app channel', () => {
  it('shows the stage chart, flags, read-only target, and the source and campaign tables', async () => {
    const columns = [{ key: 'trial_started', label: 'Trials started', unit: 'COUNT' as const }]
    serve(
      detailResponse({
        channel: productChannel(),
        dimensions: [
          { key: 'sources', label: 'By source', columns, rows: [{ key: 'source:organic', label: 'organic', values: [90] }] },
          { key: 'campaigns', label: 'By campaign', columns, rows: [{ key: 'campaign:1', label: 'Brand', values: [50] }] },
        ],
      }),
    )
    render(<ChannelDetailPanel projectId="proj-1" channel="product" />)
    expect(await screen.findByRole('heading', { name: 'In-app' })).toBeInTheDocument()
    const stages = within(screen.getByRole('region', { name: 'Stages' }))
    expect(stages.getAllByRole('listitem').map((li) => li.getAttribute('aria-label'))).toEqual([
      'First opens',
      'Onboarding started',
      'Trials started',
      'Paid',
    ])
    expect(screen.getAllByText('Provisional').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Small sample').length).toBeGreaterThan(0)
    expect(screen.getByText('Set in Rexipe backend')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /target for/ })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'By source' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'By campaign' })).toBeInTheDocument()
    expect(screen.queryByText('Mismatch')).not.toBeInTheDocument()
    expect((apiGet as Mock).mock.calls.some((c) => String(c[0]).includes('/marketing/channels/PRODUCT?window=28d'))).toBe(true)
  })

  it('links a not-connected channel to the product metrics integration', async () => {
    serve(detailResponse({ channel: productChannel({ status: 'NOT_CONNECTED', metrics: [], connections: [] }), dimensions: [] }))
    render(<ChannelDetailPanel projectId="proj-1" channel="product" />)
    expect(await screen.findByRole('link', { name: 'Connect Product metrics' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/integrations/product-metrics',
    )
    expect(screen.queryByRole('region', { name: 'Stages' })).not.toBeInTheDocument()
  })
})

describe('ChannelDetailPanel mismatch', () => {
  it.each([
    ['STORE', 'App Store', mismatch(), /Conductor 1,204 vs Product metrics 1,190/],
    ['ADS', 'Apple Ads', mismatch({ metric: 'spend', channelValue: 520, referenceValue: 480 }), /Conductor \$520 vs Product metrics \$480/],
  ] as const)('explains a %s mismatch with both values', async (key, label, m, text) => {
    serve(detailResponse({ channel: channel({ channel: key, label, status: 'MISMATCH', mismatch: m }), dimensions: [] }))
    render(<ChannelDetailPanel projectId="proj-1" channel={key.toLowerCase()} />)
    expect(await screen.findByText(text)).toBeInTheDocument()
    expect(screen.getByText('Mismatch')).toBeInTheDocument()
  })
})
