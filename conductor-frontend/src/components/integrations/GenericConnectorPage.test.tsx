import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => mockCanMutate,
}))

vi.mock('@/components/ui/toast', () => ({
  toastError: vi.fn(),
  useToast: () => ({ showToast: vi.fn() }),
}))

vi.mock('@/lib/api', () => ({
  listIntegrations: vi.fn(),
  createConnection: vi.fn(),
  deleteConnection: vi.fn(),
  apiPost: vi.fn(),
  apiGet: vi.fn(),
  apiPut: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
}))

import * as api from '@/lib/api'
import { toastError } from '@/components/ui/toast'
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

  it('asks before disconnecting, naming the connection and what is lost, and only deletes on confirm', async () => {
    const asc = {
      ...discordConnector,
      connectorId: 'app-store-connect',
      name: 'App Store Connect',
      singleInstance: false,
      connections: [{ id: 'c1', status: 'ACTIVE' as const, label: 'Rexipe iOS' }],
    }
    vi.mocked(api.listIntegrations).mockResolvedValue([asc])
    vi.mocked(api.deleteConnection).mockResolvedValue(undefined as never)
    renderPage('app-store-connect')

    fireEvent.click(await screen.findByRole('button', { name: 'Disconnect' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Disconnect Rexipe iOS?')).toBeInTheDocument()
    expect(dialog).toHaveTextContent(/history stored for this connection is deleted/i)
    expect(dialog).toHaveTextContent(/cannot be fetched again/i)
    expect(dialog).toHaveTextContent(/about 35 days/i)
    expect(api.deleteConnection).not.toHaveBeenCalled()

    // Cancel leaves everything alone
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(api.deleteConnection).not.toHaveBeenCalled()

    // Confirm deletes
    fireEvent.click(screen.getByRole('button', { name: 'Disconnect' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Disconnect' }))
    await waitFor(() =>
      expect(api.deleteConnection).toHaveBeenCalledWith('proj-1', 'app-store-connect', 'c1', 'test-token'),
    )
  })

  it('does not mention the Marketing overview for a connector that does not feed it', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([discordConnector])
    renderPage('discord')

    fireEvent.click(await screen.findByRole('button', { name: 'Disconnect' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent(/history stored for this connection is deleted/i)
    expect(dialog).not.toHaveTextContent(/Marketing overview/)
  })

  it('shows a refused connect (bad credentials) inline in the form, once, and keeps the form filled', async () => {
    const asc = {
      ...discordConnector,
      connectorId: 'app-store-connect',
      name: 'App Store Connect',
      singleInstance: false,
      connected: false,
      connections: [],
      configFields: [
        { key: 'issuerId', label: 'Issuer ID', hint: null, type: 'STRING' as const, source: 'USER_INPUT' as const, required: true, secret: false },
      ],
    }
    vi.mocked(api.listIntegrations).mockResolvedValue([asc])
    vi.mocked(api.createConnection).mockRejectedValue({ detail: 'App Store Connect rejected the API key' })
    renderPage('app-store-connect')

    fireEvent.change(await screen.findByLabelText('Issuer ID'), { target: { value: 'bad' } })
    fireEvent.click(screen.getByRole('button', { name: /^connect$/i }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('App Store Connect rejected the API key')
    expect(screen.getAllByText('App Store Connect rejected the API key')).toHaveLength(1)
    expect(screen.getByLabelText('Issuer ID')).toHaveValue('bad')
  })

  it('links a connector that feeds the Marketing overview to its overview page instead of saying it has no dashboard', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([
      { ...discordConnector, connectorId: 'app-store-connect', name: 'App Store Connect' },
    ])
    renderPage('app-store-connect')

    const link = await screen.findByRole('link', { name: /open the app store overview/i })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/marketing/overview/store')
    expect(screen.queryByText(/doesn.t have a dedicated dashboard/i)).not.toBeInTheDocument()
  })

  it('links Product metrics to the In-app overview', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([
      { ...discordConnector, connectorId: 'product-metrics', name: 'Product metrics' },
    ])
    renderPage('product-metrics')

    const link = await screen.findByRole('link', { name: /open the in-app overview/i })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/marketing/overview/product')
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
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Disconnect' }))

    await waitFor(() => {
      expect(screen.getAllByText('Cannot disconnect right now')).toHaveLength(1)
    })
  })

  it('reopens the account picker from a connection whose selection never happened', async () => {
    const metaConnector = {
      ...discordConnector,
      connectorId: 'meta',
      name: 'Meta',
      authType: 'OAUTH2' as const,
      singleInstance: false,
      configFields: [],
      connections: [{ id: 'conn-meta', status: 'ACTIVE' as const, label: null, authType: 'OAUTH2', awaitingAccountSelection: true }],
    }
    vi.mocked(api.listIntegrations).mockResolvedValue([metaConnector])
    vi.mocked(api.apiGet).mockResolvedValue({ accounts: [{ id: 'page-1', label: 'Rexipe' }] })
    renderPage('meta')

    expect(await screen.findByRole('button', { name: 'Choose account' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Choose an account' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Choose account' }))

    expect(await screen.findByRole('heading', { name: 'Choose an account' })).toBeInTheDocument()
    await waitFor(() =>
      expect(api.apiGet).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/integrations/meta/connections/conn-meta/oauth/accounts',
        'test-token'
      )
    )
  })

  it('toasts once when the OAuth callback returns ?oauthError=access_denied, then clears the param', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([discordConnector])
    window.history.replaceState(null, '', '/integrations/discord?oauthError=access_denied')
    renderPage('discord')

    await waitFor(() => expect(toastError).toHaveBeenCalledTimes(1))
    expect(vi.mocked(toastError).mock.calls[0][0]).toMatch(/cancelled or refused/i)
    expect(window.location.search).toBe('')
    window.history.replaceState(null, '', '/')
  })

  it('names any other OAuth error code in the toast', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([discordConnector])
    window.history.replaceState(null, '', '/integrations/discord?oauthError=server_error')
    renderPage('discord')

    await waitFor(() => expect(toastError).toHaveBeenCalledWith('Authorization failed (server_error). Please try again.'))
    window.history.replaceState(null, '', '/')
  })
})
