import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: () => true, refresh: () => {} }),
  useCan: () => true,
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
  toastError: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  listIntegrations: vi.fn(),
  listConnections: vi.fn(),
  fetchConnectionData: vi.fn(),
  patchConnection: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => fallback,
}))

import * as api from '@/lib/api'
import type { ConnectorAppCredentialStatus } from '@/lib/api'
import GscConnectorPage from './GscConnectorPage'
import { ConnectorCatalogProvider } from './ConnectorCatalogContext'

const credential = (
  credentialSource: ConnectorAppCredentialStatus['credentialSource'],
): ConnectorAppCredentialStatus => ({
  connectorId: 'gsc',
  credentialSource,
  configured: credentialSource !== 'NONE',
  clientId: credentialSource === 'NONE' ? null : 'google-app.apps.googleusercontent.com',
  clientSecretLast4: credentialSource === 'NONE' ? null : 'wxyz',
  missingProperties: credentialSource === 'NONE' ? ['GOOGLE_OAUTH_CLIENT_ID'] : [],
  updatedBy: null,
  updatedAt: null,
})

const catalogEntry = (appCredential: ConnectorAppCredentialStatus | null) => ({
  connectorId: 'gsc',
  name: 'Google Search Console',
  category: 'Marketing',
  authType: 'OAUTH2' as const,
  capabilities: [],
  singleInstance: true,
  description: 'Organic search performance',
  iconLabel: 'GSC',
  connected: false,
  configFields: [],
  connections: [],
  appCredential,
})

function renderPage(appCredential: ConnectorAppCredentialStatus | null) {
  vi.mocked(api.listIntegrations).mockResolvedValue([catalogEntry(appCredential)])
  return render(
    <ConnectorCatalogProvider projectId="proj-1" connectorId="gsc">
      <GscConnectorPage projectId="proj-1" />
    </ConnectorCatalogProvider>,
  )
}

describe('GscConnectorPage app credential readiness', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listConnections).mockResolvedValue([])
  })

  it('renders the platform app credential panel', async () => {
    renderPage(credential('DEPLOYMENT'))

    expect(await screen.findByText('Platform app credentials')).toBeInTheDocument()
  })

  it('disables the authorize action when no platform app is configured', async () => {
    renderPage(credential('NONE'))

    const authorize = await screen.findByRole('button', { name: /connect google search console/i })
    await waitFor(() => expect(authorize).toBeDisabled())
    expect(screen.getByText(/once the platform app credentials above are configured/i)).toBeInTheDocument()
  })

  it('leaves the authorize action available when the deployment supplies the app', async () => {
    renderPage(credential('DEPLOYMENT'))

    const authorize = await screen.findByRole('button', { name: /connect google search console/i })
    await waitFor(() => expect(api.listIntegrations).toHaveBeenCalled())
    expect(authorize).toBeEnabled()
  })

  it('renders normally for a catalog entry without an app credential', async () => {
    renderPage(null)

    const authorize = await screen.findByRole('button', { name: /connect google search console/i })
    expect(authorize).toBeEnabled()
    expect(screen.queryByText('Platform app credentials')).not.toBeInTheDocument()
  })
})
