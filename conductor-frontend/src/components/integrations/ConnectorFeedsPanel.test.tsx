import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => (cap === 'integration.manage' ? mockCanMutate : false),
}))

const showToast = vi.fn()
vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast }),
}))

vi.mock('@/lib/api', () => ({
  listConnectorFeeds: vi.fn(),
  updateConnectorFeed: vi.fn(),
  runConnectorFeedNow: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
}))

import * as api from '@/lib/api'
import ConnectorFeedsPanel from './ConnectorFeedsPanel'

let mockCanMutate = true

const weeklyMrrFeed: api.ConnectorFeedDto = {
  id: 'feed-1',
  ingestId: 'weekly-mrr',
  label: 'Weekly MRR',
  description: 'Weekly revenue snapshot',
  enabled: true,
  intervalMinutes: 1440,
  status: 'ACTIVE',
  lastRunAt: new Date(Date.now() - 3 * 60 * 60_000).toISOString(),
  lastSuccessAt: new Date(Date.now() - 3 * 60 * 60_000).toISOString(),
  lastError: null,
  consecutiveFailures: 0,
  nextRunAt: new Date(Date.now() + 60_000).toISOString(),
  isMetricFeed: true,
}

describe('ConnectorFeedsPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
  })

  it('renders nothing when the connector declares no feeds', async () => {
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([])
    const { container } = render(<ConnectorFeedsPanel projectId="proj-1" connectorId="posthog" />)

    await waitFor(() => expect(api.listConnectorFeeds).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing while still loading (no flash before the fetch resolves)', () => {
    vi.mocked(api.listConnectorFeeds).mockReturnValue(new Promise(() => {}))
    const { container } = render(<ConnectorFeedsPanel projectId="proj-1" connectorId="posthog" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when the fetch fails (best-effort, matches IngestCadenceSetting)', async () => {
    vi.mocked(api.listConnectorFeeds).mockRejectedValue(new Error('network'))
    const { container } = render(<ConnectorFeedsPanel projectId="proj-1" connectorId="posthog" />)

    await waitFor(() => expect(api.listConnectorFeeds).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it('shows label, description, cadence, status, and last run for a declared feed', async () => {
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([weeklyMrrFeed])
    render(<ConnectorFeedsPanel projectId="proj-1" connectorId="datadog" />)

    expect(await screen.findByText('Weekly MRR')).toBeInTheDocument()
    expect(screen.getByText('Weekly revenue snapshot')).toBeInTheDocument()
    expect(screen.getByText('Active')).toBeInTheDocument()
    expect(screen.getByText(/Last run/)).toBeInTheDocument()
    expect(screen.getByRole('combobox', { name: 'Weekly MRR cadence' })).toHaveValue('1440')
  })

  it('shows lastError when present', async () => {
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([
      { ...weeklyMrrFeed, status: 'SETUP_REQUIRED', lastError: 'Connection revoked' },
    ])
    render(<ConnectorFeedsPanel projectId="proj-1" connectorId="datadog" />)

    expect(await screen.findByText('Connection revoked')).toBeInTheDocument()
    expect(screen.getByText('Setup required')).toBeInTheDocument()
  })

  it('toggling the switch calls updateConnectorFeed with enabled and rolls back on failure', async () => {
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([weeklyMrrFeed])
    vi.mocked(api.updateConnectorFeed).mockRejectedValue({ detail: 'nope' })
    render(<ConnectorFeedsPanel projectId="proj-1" connectorId="datadog" />)

    const toggle = await screen.findByRole('switch', { name: 'Disable Weekly MRR' })
    fireEvent.click(toggle)

    await waitFor(() =>
      expect(api.updateConnectorFeed).toHaveBeenCalledWith('proj-1', 'datadog', 'feed-1', { enabled: false }, 'test-token'),
    )
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('nope', 'error'))
    // Rolled back to enabled after the failed write.
    expect(await screen.findByRole('switch', { name: 'Disable Weekly MRR' })).toBeInTheDocument()
  })

  it('changing cadence calls updateConnectorFeed with intervalMinutes', async () => {
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([weeklyMrrFeed])
    vi.mocked(api.updateConnectorFeed).mockResolvedValue({ ...weeklyMrrFeed, intervalMinutes: 60 })
    render(<ConnectorFeedsPanel projectId="proj-1" connectorId="datadog" />)

    const select = await screen.findByRole('combobox', { name: 'Weekly MRR cadence' })
    fireEvent.change(select, { target: { value: '60' } })

    await waitFor(() =>
      expect(api.updateConnectorFeed).toHaveBeenCalledWith(
        'proj-1', 'datadog', 'feed-1', { intervalMinutes: 60 }, 'test-token',
      ),
    )
  })

  it('changing cadence rolls back the Select and shows a toast on failure', async () => {
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([weeklyMrrFeed])
    vi.mocked(api.updateConnectorFeed).mockRejectedValue({ detail: 'cadence rejected' })
    render(<ConnectorFeedsPanel projectId="proj-1" connectorId="datadog" />)

    const select = await screen.findByRole('combobox', { name: 'Weekly MRR cadence' })
    expect(select).toHaveValue('1440')
    fireEvent.change(select, { target: { value: '60' } })

    await waitFor(() =>
      expect(api.updateConnectorFeed).toHaveBeenCalledWith(
        'proj-1', 'datadog', 'feed-1', { intervalMinutes: 60 }, 'test-token',
      ),
    )
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('cadence rejected', 'error'))
    // Rolled back to the prior cadence after the failed write.
    expect(await screen.findByRole('combobox', { name: 'Weekly MRR cadence' })).toHaveValue('1440')
  })

  it('clicking Sync now calls runConnectorFeedNow and shows a confirmation toast', async () => {
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([weeklyMrrFeed])
    vi.mocked(api.runConnectorFeedNow).mockResolvedValue(weeklyMrrFeed)
    render(<ConnectorFeedsPanel projectId="proj-1" connectorId="datadog" />)

    fireEvent.click(await screen.findByRole('button', { name: /Sync now/ }))

    await waitFor(() =>
      expect(api.runConnectorFeedNow).toHaveBeenCalledWith('proj-1', 'datadog', 'feed-1', 'test-token'),
    )
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('Weekly MRR queued to sync'))
  })

  it('disables Sync now when the feed is disabled -- syncing a disabled feed would be a silent no-op', async () => {
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([{ ...weeklyMrrFeed, enabled: false }])
    render(<ConnectorFeedsPanel projectId="proj-1" connectorId="datadog" />)

    expect(await screen.findByRole('button', { name: /Sync now/ })).toBeDisabled()
  })

  it('disables mutating controls when the caller lacks integration.manage', async () => {
    mockCanMutate = false
    vi.mocked(api.listConnectorFeeds).mockResolvedValue([weeklyMrrFeed])
    render(<ConnectorFeedsPanel projectId="proj-1" connectorId="datadog" />)

    expect(await screen.findByRole('switch', { name: 'Disable Weekly MRR' })).toBeDisabled()
    expect(screen.getByRole('combobox', { name: 'Weekly MRR cadence' })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Sync now/ })).toBeDisabled()
  })
})
