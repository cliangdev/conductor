import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => mockCanMutate,
}))

vi.mock('@/lib/api', () => ({
  listConnections: vi.fn(),
  createConnection: vi.fn(),
  deleteConnection: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
}))

vi.mock('./RuntimeTargetsPanel', () => ({
  default: ({ projectId, connections }: { projectId: string; connections: unknown[] }) => (
    <div data-testid="runtime-targets-panel" data-project-id={projectId} data-connections-count={connections.length} />
  ),
}))

import * as api from '@/lib/api'
import GcpConnectorPage from './GcpConnectorPage'

let mockCanMutate = true

const activeConnection = { id: 'conn-1', status: 'ACTIVE' as const, label: 'prod-sa' }

describe('GcpConnectorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
  })

  it('renders RuntimeTargetsPanel with fetched connections when connections exist', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([activeConnection])
    render(<GcpConnectorPage projectId="proj-1" />)

    const panel = await screen.findByTestId('runtime-targets-panel')
    expect(panel).toHaveAttribute('data-project-id', 'proj-1')
    expect(panel).toHaveAttribute('data-connections-count', '1')
  })

  it('still renders RuntimeTargetsPanel when the connections list is empty', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([])
    render(<GcpConnectorPage projectId="proj-1" />)

    const panel = await screen.findByTestId('runtime-targets-panel')
    expect(panel).toHaveAttribute('data-connections-count', '0')
  })

  it('points the retired Claude Code credential panel at Settings → AI Providers, regardless of connections', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([activeConnection])
    const { unmount } = render(<GcpConnectorPage projectId="proj-1" />)
    let link = await screen.findByRole('link', { name: /claude code credential moved/i })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/settings/providers')
    unmount()

    vi.mocked(api.listConnections).mockResolvedValue([])
    render(<GcpConnectorPage projectId="proj-1" />)
    link = await screen.findByRole('link', { name: /claude code credential moved/i })
    expect(link).toBeInTheDocument()
  })

  it('no longer shows the old "Manage runtime targets" link', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([activeConnection])
    render(<GcpConnectorPage projectId="proj-1" />)

    await screen.findByTestId('runtime-targets-panel')
    expect(screen.queryByText(/manage runtime targets/i)).not.toBeInTheDocument()
  })

  it('shows the connect form for users with integration.manage when there are no connections', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([])
    render(<GcpConnectorPage projectId="proj-1" />)

    expect(await screen.findByText(/connect google cloud/i)).toBeInTheDocument()
    expect(screen.getByText(/^service account key$/i)).toBeInTheDocument()
    expect(screen.getByPlaceholderText(/iam & admin/i)).toBeInTheDocument()
  })
})
