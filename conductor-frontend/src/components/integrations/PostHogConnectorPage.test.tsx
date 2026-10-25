import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({ useAuth: () => ({ accessToken: 'tok' }) }))
vi.mock('@/contexts/PermissionsContext', () => ({ useCan: () => true }))
vi.mock('recharts', () => ({
  LineChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Line: () => null,
  XAxis: () => null,
  YAxis: () => null,
  Tooltip: () => null,
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))
vi.mock('@/lib/api', () => ({
  listConnections: vi.fn(),
  createConnection: vi.fn(),
  fetchConnectionData: vi.fn(),
  patchConnection: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
}))

import * as api from '@/lib/api'
import PostHogConnectorPage from './PostHogConnectorPage'

const data = (over: Record<string, unknown> = {}) => ({
  connectionId: 'c1',
  connectorId: 'posthog',
  data: { total: 10, series: [{ date: '2026-10-03', count: 10 }] },
  healthStatus: 'HEALTHY',
  fetchedAt: '2026-10-25T00:00:00Z',
  isStale: false,
  ...over,
})
const conn = (over: Record<string, unknown> = {}) => ({
  id: 'c1', status: 'ACTIVE', healthStatus: 'HEALTHY', config: { projectId: '42' }, ...over,
})

beforeEach(() => {
  vi.resetAllMocks()
})

describe('PostHogConnectorPage', () => {
  it('connect form offers region, event filter and conversion event, and sends no host for US', async () => {
    vi.mocked(api.listConnections).mockResolvedValueOnce([])
    vi.mocked(api.createConnection).mockResolvedValue({ id: 'c1' } as never)
    vi.mocked(api.listConnections).mockResolvedValueOnce([conn()] as never)
    vi.mocked(api.fetchConnectionData).mockResolvedValue(data() as never)
    render(<PostHogConnectorPage projectId="p1" />)

    await screen.findByText('Connect PostHog')
    expect(screen.getByLabelText('Region')).toBeTruthy()
    expect(screen.getByLabelText(/Event filter/)).toBeTruthy()
    expect(screen.getByLabelText(/Conversion event/)).toBeTruthy()

    fireEvent.change(screen.getByLabelText('Personal API key'), { target: { value: 'phx_abc' } })
    fireEvent.change(screen.getByLabelText('Project ID'), { target: { value: '42' } })
    fireEvent.change(screen.getByLabelText(/Event filter/), { target: { value: "$host = 'rexipe.io'" } })
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))

    await waitFor(() => expect(api.createConnection).toHaveBeenCalled())
    expect(vi.mocked(api.createConnection).mock.calls[0][2]).toEqual({
      apiKey: 'phx_abc',
      configJson: { projectId: '42', eventFilter: "$host = 'rexipe.io'" },
    })
  })

  it('shows the connected view after connecting even when the first fetch is SETUP_REQUIRED', async () => {
    vi.mocked(api.listConnections).mockResolvedValueOnce([])
    vi.mocked(api.createConnection).mockResolvedValue({ id: 'c1' } as never)
    vi.mocked(api.listConnections).mockResolvedValueOnce([conn()] as never)
    vi.mocked(api.fetchConnectionData).mockResolvedValue(
      data({ healthStatus: 'SETUP_REQUIRED', data: null, errorMessage: 'Fix the key' }) as never)
    render(<PostHogConnectorPage projectId="p1" />)

    await screen.findByText('Connect PostHog')
    fireEvent.change(screen.getByLabelText('Personal API key'), { target: { value: 'phx_abc' } })
    fireEvent.change(screen.getByLabelText('Project ID'), { target: { value: '42' } })
    fireEvent.click(screen.getByRole('button', { name: 'Connect' }))

    await screen.findByText('Edit settings')
    expect(screen.queryByText('Connect PostHog')).toBeNull()
    expect(screen.getByText('Setup required')).toBeTruthy()
    expect(screen.getByText('Fix the key')).toBeTruthy()
  })

  it('keeps the connected view when the data fetch throws', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([conn()] as never)
    vi.mocked(api.fetchConnectionData).mockRejectedValue({ detail: 'boom' })
    render(<PostHogConnectorPage projectId="p1" />)
    await screen.findByText('Edit settings')
    expect(screen.getByText('boom')).toBeTruthy()
    expect(screen.queryByText('Connect PostHog')).toBeNull()
  })

  it('badge says Degraded with the message instead of Connected when the connection is degraded', async () => {
    vi.mocked(api.listConnections).mockResolvedValue(
      [conn({ healthStatus: 'DEGRADED', healthMessage: 'No new data since Sep 10' })] as never)
    vi.mocked(api.fetchConnectionData).mockResolvedValue(data() as never)
    render(<PostHogConnectorPage projectId="p1" />)
    await screen.findByText('Degraded')
    expect(screen.queryByText('Connected')).toBeNull()
    expect(screen.getByText('No new data since Sep 10')).toBeTruthy()
  })

  it('badge says Connected when everything is healthy', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([conn()] as never)
    vi.mocked(api.fetchConnectionData).mockResolvedValue(data() as never)
    render(<PostHogConnectorPage projectId="p1" />)
    await screen.findByText('Connected')
  })

  it('edit dialog loads current settings and saves changes plus a replaced key', async () => {
    vi.mocked(api.listConnections).mockResolvedValue(
      [conn({ config: { projectId: '42', host: 'eu', eventFilter: "$host = 'a.io'" } })] as never)
    vi.mocked(api.fetchConnectionData).mockResolvedValue(data() as never)
    vi.mocked(api.patchConnection).mockResolvedValue({ id: 'c1' } as never)
    render(<PostHogConnectorPage projectId="p1" />)

    fireEvent.click(await screen.findByText('Edit settings'))
    expect((screen.getByLabelText('Region') as HTMLSelectElement).value).toBe('eu')
    expect((screen.getByLabelText(/Event filter/) as HTMLInputElement).value).toBe("$host = 'a.io'")

    fireEvent.change(screen.getByLabelText(/Conversion event/), { target: { value: 'signup' } })
    fireEvent.change(screen.getByLabelText(/Replace Personal API key/), { target: { value: 'phx_new' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(api.patchConnection).toHaveBeenCalled())
    expect(vi.mocked(api.patchConnection).mock.calls[0].slice(0, 4)).toEqual(['p1', 'posthog', 'c1', {
      config: { projectId: '42', host: 'eu', eventFilter: "$host = 'a.io'", conversionEvent: 'signup' },
      apiKey: 'phx_new',
    }])
  })

  it('shows the server refusal in the dialog and keeps it open', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([conn()] as never)
    vi.mocked(api.fetchConnectionData).mockResolvedValue(data() as never)
    vi.mocked(api.patchConnection).mockRejectedValue({ detail: 'PostHog refused the key for project 42' })
    render(<PostHogConnectorPage projectId="p1" />)
    fireEvent.click(await screen.findByText('Edit settings'))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await screen.findByText('PostHog refused the key for project 42')
    expect(screen.getByRole('button', { name: 'Save' })).toBeTruthy()
  })
})
