import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({ can: () => true, loading: false }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}))

vi.mock('@/components/auth/Can', () => ({
  Can: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

vi.mock('@/lib/api', () => {
  const apiGet = vi.fn()
  return {
    apiGet,
    apiPost: vi.fn(),
    createConnection: vi.fn(),
    deleteConnection: vi.fn(),
    listIntegrations: (projectId: string, token: string) =>
      apiGet(`/api/v1/projects/${projectId}/integrations`, token),
    apiErrorMessage: (err: unknown, fallback: string) => {
      const detail = (err as { detail?: unknown })?.detail
      return typeof detail === 'string' && detail.trim() ? detail : fallback
    },
  }
})

vi.mock('@/components/ui/modal', () => ({
  Modal: ({ open, children, title }: { open: boolean; children: React.ReactNode; title: string }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
      </div>
    ) : null,
}))

import * as api from '@/lib/api'
import IntegrationsPage from './page'

// The real gcp connector is singleInstance: false (routed to its own GcpConnectorPage, not this
// modal — see components/integrations/GcpConnectorPage.tsx). This fixture uses singleInstance:
// true to exercise the generic modal's JSON field support, which is data-driven and applies to
// any future single-instance SERVICE_ACCOUNT connector.
const serviceAccountConnector = {
  connectorId: 'gcp',
  name: 'Google Cloud',
  category: 'Infrastructure',
  authType: 'SERVICE_ACCOUNT' as const,
  capabilities: [],
  singleInstance: true,
  description: 'Run workflow jobs in your own GCP project',
  iconLabel: 'GCP',
  connected: false,
  configFields: [
    {
      key: 'serviceAccountKey',
      label: 'Service Account Key',
      hint: 'Paste your service account JSON key',
      type: 'JSON' as const,
      source: 'USER_INPUT' as const,
      required: true,
      secret: true,
    },
  ],
  connections: [],
}

const VALID_KEY = JSON.stringify({ type: 'service_account', project_id: 'my-project' })

describe('IntegrationsPage — JSON field (gcp connector)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.apiGet).mockResolvedValue([serviceAccountConnector])
  })

  it('renders a textarea for a JSON-typed field and blocks connect on invalid JSON', async () => {
    render(<IntegrationsPage />)

    fireEvent.click(await screen.findByRole('tab', { name: 'Browse' }))
    fireEvent.click(await screen.findByRole('button', { name: /add/i }))

    const modal = await screen.findByTestId('modal')
    const textarea = within(modal).getByPlaceholderText(/paste your service account json key/i)
    expect(textarea.tagName).toBe('TEXTAREA')

    fireEvent.change(textarea, { target: { value: '{not json' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^connect$/i }))

    await waitFor(() => {
      expect(within(modal).getAllByText(/not valid json/i).length).toBeGreaterThan(0)
    })
    expect(api.createConnection).not.toHaveBeenCalled()
  })

  it('submits serviceAccountKey (not apiKey) for a SERVICE_ACCOUNT connector with a valid key', async () => {
    vi.mocked(api.createConnection).mockResolvedValue({
      id: 'conn-1', connectorId: 'gcp', status: 'ACTIVE', authType: 'SERVICE_ACCOUNT',
    })
    render(<IntegrationsPage />)

    fireEvent.click(await screen.findByRole('tab', { name: 'Browse' }))
    fireEvent.click(await screen.findByRole('button', { name: /add/i }))

    const modal = await screen.findByTestId('modal')
    const textarea = within(modal).getByPlaceholderText(/paste your service account json key/i)
    fireEvent.change(textarea, { target: { value: VALID_KEY } })
    fireEvent.click(within(modal).getByRole('button', { name: /^connect$/i }))

    await waitFor(() => {
      expect(api.createConnection).toHaveBeenCalledWith(
        'proj-1',
        'gcp',
        { serviceAccountKey: VALID_KEY, configJson: {} },
        'test-token',
      )
    })
  })
})

// ── Browse grid readiness ─────────────────────────────────────────────────────
//
// A single-instance, unconnected OAuth2 connector connects straight from the card. Without a
// platform app credential that consent flow can only fail mid-redirect with a server error naming
// an environment variable, so the card must withhold the affordance instead of offering it.

const oauthConnector = (credentialSource: 'PROJECT' | 'DEPLOYMENT' | 'NONE' | null) => ({
  connectorId: 'meta',
  name: 'Meta',
  category: 'Marketing',
  authType: 'OAUTH2' as const,
  capabilities: ['publish'],
  singleInstance: true,
  description: 'Publish to Facebook and Instagram',
  iconLabel: 'MT',
  connected: false,
  configFields: [],
  connections: [],
  appCredential:
    credentialSource === null
      ? null
      : {
          connectorId: 'meta',
          credentialSource,
          configured: credentialSource !== 'NONE',
          clientId: credentialSource === 'NONE' ? null : 'app-123',
          clientSecretLast4: credentialSource === 'NONE' ? null : 'cdef',
          missingProperties: credentialSource === 'NONE' ? ['META_APP_ID', 'META_APP_SECRET'] : [],
          updatedBy: null,
          updatedAt: null,
        },
})

async function openBrowse() {
  render(<IntegrationsPage />)
  fireEvent.click(await screen.findByRole('tab', { name: 'Browse' }))
}

describe('IntegrationsPage — browse grid credential readiness', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('disables an OAuth2 card whose platform app is not configured and says why', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([oauthConnector('NONE')])
    await openBrowse()

    const card = (await screen.findByText('Meta')).closest('[aria-disabled="true"]')
    expect(card).not.toBeNull()
    expect(within(card as HTMLElement).getByText(/platform app/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /authorize/i })).not.toBeInTheDocument()
  })

  it('starts no OAuth flow when the disabled card is clicked', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([oauthConnector('NONE')])
    await openBrowse()

    // A click on the card body bubbles to whatever wrapper the card rendered — so this fails
    // loudly if the connect affordance is ever restored for an unconfigured connector.
    fireEvent.click(await screen.findByText('Meta'))

    expect(api.apiPost).not.toHaveBeenCalled()
  })

  it('leaves a DEPLOYMENT-credentialed OAuth2 card connectable', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([oauthConnector('DEPLOYMENT')])
    vi.mocked(api.apiPost).mockResolvedValue({ authorizationUrl: 'https://example.com/consent' })
    await openBrowse()

    fireEvent.click(await screen.findByRole('button', { name: /authorize/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/integrations/meta/oauth/authorize',
        {},
        'test-token',
      )
    })
  })

  it('leaves a PROJECT-credentialed OAuth2 card connectable', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([oauthConnector('PROJECT')])
    vi.mocked(api.apiPost).mockResolvedValue({ authorizationUrl: 'https://example.com/consent' })
    await openBrowse()

    fireEvent.click(await screen.findByRole('button', { name: /authorize/i }))

    await waitFor(() => expect(api.apiPost).toHaveBeenCalled())
  })

  it('leaves a non-OAuth2 connector — which carries no app credential — untouched', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([serviceAccountConnector])
    await openBrowse()

    const add = await screen.findByRole('button', { name: /add/i })
    expect(add).toBeInTheDocument()
    expect(screen.queryByText(/platform app/i)).not.toBeInTheDocument()
  })
})
