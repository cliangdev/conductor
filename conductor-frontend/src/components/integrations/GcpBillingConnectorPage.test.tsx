import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'

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
import GcpBillingConnectorPage from './GcpBillingConnectorPage'
import { ConnectorCatalogProvider } from './ConnectorCatalogContext'

const credential = (
  credentialSource: ConnectorAppCredentialStatus['credentialSource'],
): ConnectorAppCredentialStatus => ({
  connectorId: 'gcp-billing',
  credentialSource,
  configured: credentialSource !== 'NONE',
  clientId: credentialSource === 'NONE' ? null : 'google-app.apps.googleusercontent.com',
  clientSecretLast4: credentialSource === 'NONE' ? null : 'wxyz',
  missingProperties: credentialSource === 'NONE' ? ['GOOGLE_OAUTH_CLIENT_ID'] : [],
  appOwnership: 'WORKSPACE_OR_DEPLOYMENT',
  updatedBy: null,
  updatedAt: null,
})

const catalogEntry = (appCredential: ConnectorAppCredentialStatus | null) => ({
  connectorId: 'gcp-billing',
  name: 'GCP Billing',
  category: 'Finance',
  authType: 'OAUTH2' as const,
  capabilities: [],
  singleInstance: true,
  description: 'Cloud spend by service',
  iconLabel: 'GCP',
  connected: false,
  configFields: [],
  connections: [],
  appCredential,
})

function renderPage(appCredential: ConnectorAppCredentialStatus | null) {
  vi.mocked(api.listIntegrations).mockResolvedValue([catalogEntry(appCredential)])
  return render(
    <ConnectorCatalogProvider projectId="proj-1" connectorId="gcp-billing">
      <GcpBillingConnectorPage projectId="proj-1" />
    </ConnectorCatalogProvider>,
  )
}

describe('GcpBillingConnectorPage app credential readiness', () => {
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

    const authorize = await screen.findByRole('button', { name: /connect with google/i })
    await waitFor(() => expect(authorize).toBeDisabled())
    expect(screen.getByText(/once the platform app credentials above are configured/i)).toBeInTheDocument()
  })

  it('leaves the authorize action available when the deployment supplies the app', async () => {
    renderPage(credential('DEPLOYMENT'))

    const authorize = await screen.findByRole('button', { name: /connect with google/i })
    await waitFor(() => expect(api.listIntegrations).toHaveBeenCalled())
    expect(authorize).toBeEnabled()
  })

  it('renders normally for a catalog entry without an app credential', async () => {
    renderPage(null)

    const authorize = await screen.findByRole('button', { name: /connect with google/i })
    expect(authorize).toBeEnabled()
    expect(screen.queryByText('Platform app credentials')).not.toBeInTheDocument()
  })
})

describe('GcpBillingConnectorPage expected project', () => {
  const healthy = {
    connectionId: 'c1',
    connectorId: 'gcp-billing',
    healthStatus: 'HEALTHY' as const,
    data: { services: [], totalCost: 0, currency: 'USD' },
    fetchedAt: '2026-10-25T00:00:00Z',
    isStale: false,
  }

  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.fetchConnectionData).mockResolvedValue(healthy as never)
    vi.mocked(api.patchConnection).mockResolvedValue({ id: 'c1' } as never)
  })

  it('shows the stored expected project and saves a new one through the config patch', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([{ id: 'c1', status: 'ACTIVE', config: { expectedProjectId: 'old-project' } }] as never)
    renderPage(null)

    const input = await screen.findByLabelText('Expected project')
    expect(input).toHaveValue('old-project')
    fireEvent.change(input, { target: { value: ' rexipe-prod ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save expected project' }))

    await waitFor(() => expect(api.patchConnection).toHaveBeenCalled())
    expect(api.patchConnection).toHaveBeenCalledWith('proj-1', 'gcp-billing', 'c1', { config: { expectedProjectId: 'rexipe-prod' } }, 'test-token')
  })

  it('sends an empty value when it is cleared, so the check is switched off', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([{ id: 'c1', status: 'ACTIVE', config: { expectedProjectId: 'old-project' } }] as never)
    renderPage(null)
    fireEvent.change(await screen.findByLabelText('Expected project'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save expected project' }))
    await waitFor(() => expect(api.patchConnection).toHaveBeenCalled())
    expect(vi.mocked(api.patchConnection).mock.calls[0][3]).toEqual({ config: { expectedProjectId: '' } })
  })

  it('starts empty when the connection has none, and saves only when changed', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([{ id: 'c1', status: 'ACTIVE' }] as never)
    renderPage(null)
    const input = await screen.findByLabelText('Expected project')
    expect(input).toHaveValue('')
    expect(screen.getByRole('button', { name: 'Save expected project' })).toBeDisabled()
  })

  it('sends the expected project with the dataset when finishing setup, and leaves it out when blank', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([{ id: 'c1', status: 'NEEDS_SETUP' }] as never)
    vi.mocked(api.fetchConnectionData).mockResolvedValue({ ...healthy, healthStatus: 'SETUP_REQUIRED', data: null } as never)
    vi.mocked(api.apiGet).mockImplementation(((path: string) =>
      Promise.resolve(path.includes('gcp-projects') ? { projects: [{ projectId: 'p-1', name: 'P' }] } : { datasets: [{ datasetId: 'billing', location: 'US' }] })) as never)
    renderPage(null)

    await screen.findByText('Almost there!')
    const [projectSelect] = screen.getAllByRole('combobox')
    await waitFor(() => expect(screen.getByRole('option', { name: 'P (p-1)' })).toBeInTheDocument())
    fireEvent.change(projectSelect, { target: { value: 'p-1' } })
    await waitFor(() => expect(screen.getByRole('option', { name: 'billing (US)' })).toBeInTheDocument())
    fireEvent.change(screen.getAllByRole('combobox')[1], { target: { value: 'billing' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save & Connect' }))
    await waitFor(() => expect(api.patchConnection).toHaveBeenCalledTimes(1))
    expect(vi.mocked(api.patchConnection).mock.calls[0][3]).toEqual({ config: { bqProjectId: 'p-1', bqDatasetName: 'billing' } })

    fireEvent.change(screen.getByLabelText('Expected project'), { target: { value: 'rexipe-prod' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save & Connect' }))
    await waitFor(() => expect(api.patchConnection).toHaveBeenCalledTimes(2))
    expect(vi.mocked(api.patchConnection).mock.calls[1][3]).toEqual({
      config: { bqProjectId: 'p-1', bqDatasetName: 'billing', expectedProjectId: 'rexipe-prod' },
    })
  })
})
