import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => true,
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPut: vi.fn(),
  apiPost: vi.fn(),
  listIntegrations: vi.fn(),
  createConnection: vi.fn(),
  deleteConnection: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
}))

import * as api from '@/lib/api'
import { OAuthAccountPicker } from './OAuthAccountPicker'
import GenericConnectorPage from './GenericConnectorPage'
import { ConnectorCatalogProvider } from './ConnectorCatalogContext'

const ACCOUNTS_PATH =
  '/api/v1/projects/proj-1/integrations/meta/connections/conn-1/oauth/accounts'
const SELECT_PATH = '/api/v1/projects/proj-1/integrations/meta/connections/conn-1/oauth/account'

const pages = {
  accounts: [
    { id: 'page-1', label: 'Acme Bakery' },
    { id: 'page-2', label: 'Acme Coffee' },
  ],
}

function renderPicker(overrides: { onSelected?: () => void; onDismiss?: () => void } = {}) {
  const onSelected = overrides.onSelected ?? vi.fn()
  const onDismiss = overrides.onDismiss ?? vi.fn()
  render(
    <OAuthAccountPicker
      projectId="proj-1"
      connectorId="meta"
      connectionId="conn-1"
      connectorName="Meta"
      onSelected={onSelected}
      onDismiss={onDismiss}
    />
  )
  return { onSelected, onDismiss }
}

describe('OAuthAccountPicker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists the accounts the authorization covers', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(pages)
    renderPicker()

    expect(await screen.findByRole('option', { name: 'Acme Bakery' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Acme Coffee' })).toBeInTheDocument()
    expect(api.apiGet).toHaveBeenCalledWith(ACCOUNTS_PATH, 'test-token')
  })

  it('selecting an account finalizes the connection', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(pages)
    vi.mocked(api.apiPut).mockResolvedValue({ id: 'conn-1', status: 'ACTIVE' })
    const { onSelected } = renderPicker()

    const select = await screen.findByLabelText('Account')
    fireEvent.change(select, { target: { value: 'page-2' } })
    fireEvent.click(screen.getByRole('button', { name: /use this account/i }))

    await waitFor(() => {
      expect(api.apiPut).toHaveBeenCalledWith(SELECT_PATH, { accountId: 'page-2' }, 'test-token')
    })
    await waitFor(() => expect(onSelected).toHaveBeenCalled())
  })

  it('defaults to the first account so a single-account grant is one click', async () => {
    vi.mocked(api.apiGet).mockResolvedValue({ accounts: [{ id: 'page-1', label: 'Acme Bakery' }] })
    vi.mocked(api.apiPut).mockResolvedValue({})
    renderPicker()

    fireEvent.click(await screen.findByRole('button', { name: /use this account/i }))

    await waitFor(() => {
      expect(api.apiPut).toHaveBeenCalledWith(SELECT_PATH, { accountId: 'page-1' }, 'test-token')
    })
  })

  it('surfaces a failure to enumerate accounts and offers a retry', async () => {
    vi.mocked(api.apiGet).mockRejectedValueOnce({ detail: 'Token no longer valid' })
    renderPicker()

    expect(await screen.findByRole('alert')).toHaveTextContent('Token no longer valid')

    vi.mocked(api.apiGet).mockResolvedValueOnce(pages)
    fireEvent.click(screen.getByRole('button', { name: /try again/i }))

    expect(await screen.findByRole('option', { name: 'Acme Bakery' })).toBeInTheDocument()
  })

  it('surfaces a failure to finalize without pretending the connection is done', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(pages)
    vi.mocked(api.apiPut).mockRejectedValue({ detail: 'Page is not administered by this account' })
    const { onSelected } = renderPicker()

    fireEvent.click(await screen.findByRole('button', { name: /use this account/i }))

    expect(
      await screen.findByText('Page is not administered by this account')
    ).toBeInTheDocument()
    expect(onSelected).not.toHaveBeenCalled()
  })

  it('explains a grant that covers no publishable account instead of showing an empty picker', async () => {
    vi.mocked(api.apiGet).mockResolvedValue({ accounts: [] })
    renderPicker()

    expect(await screen.findByRole('alert')).toHaveTextContent(/no publishable account/i)
    expect(screen.queryByRole('button', { name: /use this account/i })).not.toBeInTheDocument()
  })

  it('never renders a credential — the picker only ever receives account identity', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(pages)
    const { container } = render(
      <OAuthAccountPicker
        projectId="proj-1"
        connectorId="meta"
        connectionId="conn-1"
        connectorName="Meta"
        onSelected={vi.fn()}
        onDismiss={vi.fn()}
      />
    )

    await screen.findAllByRole('option', { name: 'Acme Bakery' })
    expect(container.textContent).not.toMatch(/token/i)
  })
})

/**
 * The picker is hung off GenericConnectorPage rather than a bespoke per-connector page, so the
 * wiring — the OAuth callback's `?selectAccount=<connectionId>` marker opening it, and finalizing
 * clearing it — is part of the behavior, not an implementation detail.
 */
describe('GenericConnectorPage account selection', () => {
  const metaConnector = {
    connectorId: 'meta',
    name: 'Meta',
    category: 'Marketing',
    authType: 'OAUTH2' as const,
    capabilities: ['publish_facebook_post'],
    singleInstance: false,
    description: 'Publish to a Facebook Page and its linked Instagram Business account',
    iconLabel: 'MT',
    connected: true,
    configFields: [],
    connections: [{ id: 'conn-1', status: 'ACTIVE' as const, label: null }],
  }

  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState(null, '', '/app/projects/proj-1/integrations/meta')
  })

  function renderMetaPage() {
    return render(
      <ConnectorCatalogProvider projectId="proj-1" connectorId="meta">
        <GenericConnectorPage projectId="proj-1" connectorId="meta" />
      </ConnectorCatalogProvider>
    )
  }

  it('opens the picker for the connection the OAuth callback parked', async () => {
    window.history.replaceState(null, '', '/app/projects/proj-1/integrations/meta?selectAccount=conn-1')
    vi.mocked(api.listIntegrations).mockResolvedValue([metaConnector])
    vi.mocked(api.apiGet).mockResolvedValue(pages)

    renderMetaPage()

    expect(await screen.findByRole('heading', { name: 'Choose an account' })).toBeInTheDocument()
    expect(await screen.findByRole('option', { name: 'Acme Coffee' })).toBeInTheDocument()
    expect(api.apiGet).toHaveBeenCalledWith(ACCOUNTS_PATH, 'test-token')
  })

  it('leaves the picker closed on an ordinary visit', async () => {
    vi.mocked(api.listIntegrations).mockResolvedValue([metaConnector])

    renderMetaPage()

    expect(await screen.findByRole('heading', { name: 'Meta' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Choose an account' })).not.toBeInTheDocument()
    expect(api.apiGet).not.toHaveBeenCalled()
  })

  it('finalizing closes the picker and drops the marker from the URL', async () => {
    window.history.replaceState(null, '', '/app/projects/proj-1/integrations/meta?selectAccount=conn-1')
    vi.mocked(api.listIntegrations).mockResolvedValue([metaConnector])
    vi.mocked(api.apiGet).mockResolvedValue(pages)
    vi.mocked(api.apiPut).mockResolvedValue({ id: 'conn-1', status: 'ACTIVE' })

    renderMetaPage()

    fireEvent.click(await screen.findByRole('button', { name: /use this account/i }))

    await waitFor(() => {
      expect(screen.queryByRole('heading', { name: 'Choose an account' })).not.toBeInTheDocument()
    })
    expect(window.location.search).toBe('')
  })
})
