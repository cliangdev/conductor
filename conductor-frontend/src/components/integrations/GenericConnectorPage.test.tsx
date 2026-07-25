import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => mockCanMutate,
}))

vi.mock('@/lib/api', () => ({
  listIntegrations: vi.fn(),
  createConnection: vi.fn(),
  deleteConnection: vi.fn(),
  apiPost: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
}))

import * as api from '@/lib/api'
import GenericConnectorPage from './GenericConnectorPage'
import { ConnectorCatalogProvider } from './ConnectorCatalogContext'

function renderPage(connectorId: string) {
  return render(
    <ConnectorCatalogProvider projectId="proj-1" connectorId={connectorId}>
      <GenericConnectorPage projectId="proj-1" connectorId={connectorId} />
    </ConnectorCatalogProvider>
  )
}

let mockCanMutate = true

const discordConnector = {
  connectorId: 'discord',
  name: 'Discord',
  category: 'Communication',
  authType: 'API_KEY' as const,
  capabilities: ['post_message'],
  singleInstance: true,
  description: 'Post messages to a Discord channel via an incoming webhook',
  iconLabel: 'DC',
  connected: true,
  configFields: [
    { key: 'webhook_url', label: 'Webhook URL', hint: null, type: 'SECRET' as const, source: 'USER_INPUT' as const, required: true, secret: true },
  ],
  connections: [{ id: 'conn-1', status: 'ACTIVE' as const, label: null }],
}

describe('GenericConnectorPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
  })

  it('renders a connected, already-configured connector without falling back to "Unknown connector"', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([discordConnector])
    renderPage('discord')

    expect(await screen.findByRole('heading', { name: 'Discord' })).toBeInTheDocument()
    expect(screen.getByText('Connected')).toBeInTheDocument()
    expect(screen.queryByText(/unknown connector/i)).not.toBeInTheDocument()
    // singleInstance + already connected: no "connect another" form
    expect(screen.queryByRole('button', { name: /^connect$/i })).not.toBeInTheDocument()
  })

  it('falls back to the unknown-connector message when the id is absent from the catalog', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([])
    renderPage('not-a-real-connector')

    expect(await screen.findByText('Unknown connector: not-a-real-connector')).toBeInTheDocument()
  })

  it('disconnecting shows an error inline without duplicating it', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([discordConnector])
    vi.mocked(api.deleteConnection).mockRejectedValue({ detail: 'Cannot disconnect right now' })
    renderPage('discord')

    fireEvent.click(await screen.findByRole('button', { name: /disconnect/i }))

    await waitFor(() => {
      expect(screen.getAllByText('Cannot disconnect right now')).toHaveLength(1)
    })
  })
})
