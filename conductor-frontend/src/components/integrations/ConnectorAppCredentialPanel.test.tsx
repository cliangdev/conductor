import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react'
import { useState, type ReactNode } from 'react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

let mockRole: 'ADMIN' | 'CREATOR' | 'REVIEWER' = 'ADMIN'
// Resolve capabilities through the real rule table so the role→capability mapping under test is
// the shipped one, not a stand-in that would hide an ADMIN-only capability being mis-assigned.
vi.mock('@/contexts/PermissionsContext', async () => {
  const { can } = await vi.importActual<typeof import('@/lib/permissions')>('@/lib/permissions')
  const check = (capability: Parameters<typeof can>[1]) => can(mockRole, capability)
  return {
    usePermissions: () => ({ role: mockRole, loading: false, can: check, refresh: () => {} }),
    useCan: check,
  }
})

const showToast = vi.fn()
vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast }),
}))

// Flatten the modal so ConfirmModal's content is in the DOM whenever it is open. The description is
// rendered here as the real Modal renders it: it carries the consequence of confirming, so a double
// that dropped it would let that copy change untested.
vi.mock('@/components/ui/modal', () => ({
  Modal: ({
    open,
    children,
    title,
    description,
    footer,
  }: {
    open: boolean
    children: ReactNode
    title: string
    description?: string
    footer: ReactNode
  }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {description && <p>{description}</p>}
        {children}
        {footer}
      </div>
    ) : null,
}))

vi.mock('@/lib/workflows', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/workflows')>()),
  fetchMembersCached: () => mockFetchMembers(),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPut: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  listIntegrations: vi.fn(),
  createConnection: vi.fn(),
  deleteConnection: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
}))

import * as api from '@/lib/api'
import type { IntegrationListItem } from '@/lib/api'
import {
  ConnectorAppCredentialPanel,
  appCredentialOf,
  type ConnectorAppCredentialStatus,
  type ConnectorAppCredentialVerificationReport,
} from './ConnectorAppCredentialPanel'
import { ConnectorCard } from './ConnectorCard'
import GenericConnectorPage from './GenericConnectorPage'
import { ConnectorCatalogProvider } from './ConnectorCatalogContext'

const members = () => [
  { userId: 'user-7', name: 'Dana Ops', email: 'dana@example.com', avatarUrl: null, role: 'ADMIN' as const, joinedAt: '2026-01-01T00:00:00Z' },
]
let mockFetchMembers = vi.fn(async () => members())

// Meta's shape now that it publishes through Conductor's own reviewed app: when configured,
// neither a client id nor a secret is ever sent to a workspace.
const META_DEPLOYMENT_ONLY: ConnectorAppCredentialStatus = {
  connectorId: 'meta',
  credentialSource: 'DEPLOYMENT',
  configured: true,
  clientId: null,
  clientSecretLast4: null,
  missingProperties: [],
  appOwnership: 'DEPLOYMENT_ONLY',
  updatedBy: null,
  updatedAt: null,
}

// Same connector, before an operator has set the deployment's own app secrets.
const META_DEPLOYMENT_ONLY_UNCONFIGURED: ConnectorAppCredentialStatus = {
  connectorId: 'meta',
  credentialSource: 'NONE',
  configured: false,
  clientId: null,
  clientSecretLast4: null,
  missingProperties: ['META_APP_ID', 'META_APP_SECRET'],
  appOwnership: 'DEPLOYMENT_ONLY',
  updatedBy: null,
  updatedAt: null,
}

// YouTube's shape: its app carries its own App Review, so there is no deployment app to inherit
// and no env var whose absence explains a NONE.
const YOUTUBE_NONE: ConnectorAppCredentialStatus = {
  connectorId: 'youtube',
  credentialSource: 'NONE',
  configured: false,
  clientId: null,
  clientSecretLast4: null,
  missingProperties: [],
  appOwnership: 'WORKSPACE_ONLY',
  updatedBy: null,
  updatedAt: null,
}

const YOUTUBE_PROJECT: ConnectorAppCredentialStatus = {
  connectorId: 'youtube',
  credentialSource: 'PROJECT',
  configured: true,
  clientId: 'proj-client-5678',
  clientSecretLast4: '8888',
  missingProperties: [],
  appOwnership: 'WORKSPACE_ONLY',
  updatedBy: 'user-7',
  updatedAt: new Date(Date.now() - 2 * 60 * 60_000).toISOString(),
}

// Google Search Console's shape: one deployment app can be inherited, and a workspace may still
// override it: this exercises inherit, override, replace, clear and verify.
const GSC_NONE: ConnectorAppCredentialStatus = {
  connectorId: 'gsc',
  credentialSource: 'NONE',
  configured: false,
  clientId: null,
  clientSecretLast4: null,
  missingProperties: ['GOOGLE_OAUTH_CLIENT_ID', 'GOOGLE_OAUTH_CLIENT_SECRET'],
  appOwnership: 'WORKSPACE_OR_DEPLOYMENT',
  updatedBy: null,
  updatedAt: null,
}

const GSC_DEPLOYMENT: ConnectorAppCredentialStatus = {
  connectorId: 'gsc',
  credentialSource: 'DEPLOYMENT',
  configured: true,
  clientId: 'dep-client-1234',
  clientSecretLast4: '9999',
  missingProperties: [],
  appOwnership: 'WORKSPACE_OR_DEPLOYMENT',
  updatedBy: null,
  updatedAt: null,
}

const GSC_PROJECT: ConnectorAppCredentialStatus = {
  connectorId: 'gsc',
  credentialSource: 'PROJECT',
  configured: true,
  clientId: 'proj-client-5678',
  clientSecretLast4: '8888',
  missingProperties: [],
  appOwnership: 'WORKSPACE_OR_DEPLOYMENT',
  updatedBy: 'user-7',
  updatedAt: new Date(Date.now() - 2 * 60 * 60_000).toISOString(),
}

function renderPanel(
  status: ConnectorAppCredentialStatus,
  overrides: { connectorId?: string; connectorName?: string } = {}
) {
  return render(
    <ConnectorAppCredentialPanel
      projectId="proj-1"
      connectorId={overrides.connectorId ?? 'meta'}
      connectorName={overrides.connectorName ?? 'Meta'}
      status={status}
      onChange={vi.fn()}
    />
  )
}

/** Re-renders the panel with whatever status the component last handed back through `onChange`. */
function renderStatefulPanel(
  initial: ConnectorAppCredentialStatus,
  overrides: { connectorId?: string; connectorName?: string } = {}
) {
  function Harness() {
    const [status, setStatus] = useState(initial)
    return (
      <ConnectorAppCredentialPanel
        projectId="proj-1"
        connectorId={overrides.connectorId ?? 'meta'}
        connectorName={overrides.connectorName ?? 'Meta'}
        status={status}
        onChange={setStatus}
      />
    )
  }
  return render(<Harness />)
}

/** Opens the "Use your own app" disclosure that hides the override affordance by default. */
function openOwnAppDisclosure() {
  fireEvent.click(screen.getByText(/use your own app/i))
}

beforeEach(() => {
  vi.clearAllMocks()
  mockRole = 'ADMIN'
  mockFetchMembers = vi.fn(async () => members())
})

describe('ConnectorAppCredentialPanel — readiness states', () => {
  it('names every missing property when a connector that can inherit one is unconfigured', () => {
    renderPanel(GSC_NONE, { connectorId: 'gsc', connectorName: 'Google Search Console' })

    expect(screen.getByText('Not configured')).toBeInTheDocument()
    expect(screen.getByText('GOOGLE_OAUTH_CLIENT_ID')).toBeInTheDocument()
    expect(screen.getByText('GOOGLE_OAUTH_CLIENT_SECRET')).toBeInTheDocument()
  })

  it('tells a workspace to enter its own app, naming no env var, when none can be inherited', () => {
    renderPanel(YOUTUBE_NONE, { connectorId: 'youtube', connectorName: 'YouTube' })

    expect(screen.getByText('Not configured')).toBeInTheDocument()
    expect(screen.getByText(/needs this workspace's own app/i)).toBeInTheDocument()
    // Naming an env var here would send an admin to set something nothing reads for this connector.
    expect(screen.queryByText(/YOUTUBE_CLIENT_ID/)).not.toBeInTheDocument()
    expect(screen.queryByText(/on the deployment/i)).not.toBeInTheDocument()
  })

  it('says a deployment credential is inherited and shared, and collapses the override behind a disclosure', () => {
    renderPanel(GSC_DEPLOYMENT, { connectorId: 'gsc', connectorName: 'Google Search Console' })

    expect(screen.getByText('Inherited from the deployment')).toBeInTheDocument()
    expect(screen.getByText(/shared with every workspace on this deployment/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^clear$/i })).not.toBeInTheDocument()
    // The override affordance lives behind a disclosure rather than sitting in the open, so the
    // inherited state reads as settled instead of as setup work.
    expect(screen.getByText(/use your own app/i).closest('details')).not.toHaveAttribute('open')

    openOwnAppDisclosure()

    expect(
      screen.getByRole('button', { name: /use a credential for this workspace instead/i })
    ).toBeInTheDocument()
  })

  it('shows the workspace credential masked, with who set it and when, plus replace and clear', async () => {
    renderPanel(GSC_PROJECT, { connectorId: 'gsc', connectorName: 'Google Search Console' })

    expect(screen.getByText('Set on this workspace')).toBeInTheDocument()
    expect(screen.getByText('proj-client-5678')).toBeInTheDocument()
    expect(screen.getByText('••••8888')).toBeInTheDocument()
    expect(await screen.findByText(/Dana Ops/)).toBeInTheDocument()
    expect(screen.getByText(/2h ago/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^replace$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^clear$/i })).toBeInTheDocument()
  })

  it('never implies a workspace credential needs its own app review', () => {
    renderPanel(GSC_DEPLOYMENT, { connectorId: 'gsc', connectorName: 'Google Search Console' })
    openOwnAppDisclosure()
    fireEvent.click(screen.getByRole('button', { name: /use a credential for this workspace instead/i }))

    expect(screen.getByText(/doesn't need its own app review/i)).toBeInTheDocument()
  })

  it('warns that clearing takes the connector offline when no deployment app can take over', () => {
    renderPanel(YOUTUBE_PROJECT, { connectorId: 'youtube', connectorName: 'YouTube' })
    fireEvent.click(screen.getByRole('button', { name: /^clear$/i }))

    expect(screen.getByText(/Remove this workspace's YouTube app\?/i)).toBeInTheDocument()
    expect(screen.getByText(/Nobody can connect YouTube until another app is entered/i)).toBeInTheDocument()
  })
})

describe('ConnectorAppCredentialPanel: DEPLOYMENT_ONLY', () => {
  it('renders a compact read-only note instead of the credentials card', () => {
    renderPanel(META_DEPLOYMENT_ONLY, { connectorId: 'meta', connectorName: 'Meta' })

    expect(screen.getByText(/publishes through Conductor's own reviewed app/i)).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Client ID')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Client secret')).not.toBeInTheDocument()
    // The backend sends null for both, so nothing masked is ever rendered either.
    expect(screen.queryByText(/••••/)).not.toBeInTheDocument()
  })

  it('never renders a client secret input for a DEPLOYMENT_ONLY connector', () => {
    const { container } = renderPanel(META_DEPLOYMENT_ONLY, { connectorId: 'meta', connectorName: 'Meta' })

    expect(container.querySelector('input[type="password"]')).toBeNull()
    expect(container.querySelectorAll('input')).toHaveLength(0)
  })

  it('explains that the deployment has not configured the platform app yet when unconfigured', () => {
    renderPanel(META_DEPLOYMENT_ONLY_UNCONFIGURED, { connectorId: 'meta', connectorName: 'Meta' })

    expect(screen.getByText(/has not configured it yet/i)).toBeInTheDocument()
    expect(screen.getByText('META_APP_ID')).toBeInTheDocument()
    expect(screen.getByText('META_APP_SECRET')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('ConnectorAppCredentialPanel — permissions', () => {
  it('offers no write controls to a CREATOR', () => {
    mockRole = 'CREATOR'
    renderPanel(GSC_PROJECT, { connectorId: 'gsc', connectorName: 'Google Search Console' })

    expect(screen.queryByRole('button', { name: /^replace$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^clear$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /credential for this workspace/i })).not.toBeInTheDocument()
    // Verify stays available — the endpoint allows ADMIN or CREATOR.
    expect(screen.getByRole('button', { name: /^verify$/i })).toBeInTheDocument()
  })

  it('offers no write controls when nothing is configured and the viewer is not an ADMIN', () => {
    mockRole = 'CREATOR'
    renderPanel(YOUTUBE_NONE, { connectorId: 'youtube', connectorName: 'YouTube' })

    expect(screen.queryByRole('button', { name: /credential for this workspace/i })).not.toBeInTheDocument()
    expect(screen.getByText(/needs this workspace's own app/i)).toBeInTheDocument()
    // It says who can, rather than telling a CREATOR to use a form that is not there.
    expect(screen.getByText(/only a workspace admin can enter/i)).toBeInTheDocument()
    expect(screen.queryByText(/secret below/i)).not.toBeInTheDocument()
  })
})

describe('ConnectorAppCredentialPanel — set, replace and clear', () => {
  it('PUTs the client id and secret and re-renders from the response', async () => {
    vi.mocked(api.apiPut).mockResolvedValue({ ...GSC_PROJECT, clientId: 'new-client', clientSecretLast4: '4321' })
    renderStatefulPanel(GSC_DEPLOYMENT, { connectorId: 'gsc', connectorName: 'Google Search Console' })

    openOwnAppDisclosure()
    fireEvent.click(screen.getByRole('button', { name: /use a credential for this workspace instead/i }))
    fireEvent.change(screen.getByLabelText('Client ID'), { target: { value: 'new-client' } })
    fireEvent.change(screen.getByLabelText('Client secret'), { target: { value: 'sup3r-s3cret-value' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))

    await waitFor(() =>
      expect(api.apiPut).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/integrations/gsc/app-credentials',
        { clientId: 'new-client', clientSecret: 'sup3r-s3cret-value' },
        'test-token'
      )
    )

    expect(await screen.findByText('••••4321')).toBeInTheDocument()
    expect(screen.getByText('Set on this workspace')).toBeInTheDocument()
  })

  it('never keeps the client secret in the DOM after a successful save', async () => {
    vi.mocked(api.apiPut).mockResolvedValue({ ...GSC_PROJECT, clientId: 'new-client', clientSecretLast4: '4321' })
    const { container } = renderStatefulPanel(GSC_DEPLOYMENT, { connectorId: 'gsc', connectorName: 'Google Search Console' })

    openOwnAppDisclosure()
    fireEvent.click(screen.getByRole('button', { name: /use a credential for this workspace instead/i }))
    fireEvent.change(screen.getByLabelText('Client ID'), { target: { value: 'new-client' } })
    fireEvent.change(screen.getByLabelText('Client secret'), { target: { value: 'sup3r-s3cret-value' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))

    expect(await screen.findByText('••••4321')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('sup3r-s3cret-value')).not.toBeInTheDocument()
    expect(container.innerHTML).not.toContain('sup3r-s3cret-value')
  })

  it('masks the secret input so it is never readable on screen', () => {
    renderPanel(YOUTUBE_NONE, { connectorId: 'youtube', connectorName: 'YouTube' })
    fireEvent.click(screen.getByRole('button', { name: /credential for this workspace/i }))

    expect(screen.getByLabelText('Client secret')).toHaveAttribute('type', 'password')
  })

  it('clears with DELETE and falls back to the deployment credential', async () => {
    vi.mocked(api.apiDelete).mockResolvedValue(undefined)
    vi.mocked(api.apiGet).mockResolvedValue(GSC_DEPLOYMENT)
    renderStatefulPanel(GSC_PROJECT, { connectorId: 'gsc', connectorName: 'Google Search Console' })

    fireEvent.click(screen.getByRole('button', { name: /^clear$/i }))
    fireEvent.click(within(screen.getByTestId('modal')).getByRole('button', { name: /^clear$/i }))

    await waitFor(() =>
      expect(api.apiDelete).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/integrations/gsc/app-credentials',
        'test-token'
      )
    )
    expect(await screen.findByText('Inherited from the deployment')).toBeInTheDocument()
  })

  it('reports a failed save instead of swallowing it', async () => {
    vi.mocked(api.apiPut).mockRejectedValue({ detail: 'Client id is not valid' })
    renderStatefulPanel(YOUTUBE_NONE, { connectorId: 'youtube', connectorName: 'YouTube' })

    fireEvent.click(screen.getByRole('button', { name: /credential for this workspace/i }))
    fireEvent.change(screen.getByLabelText('Client ID'), { target: { value: 'x' } })
    fireEvent.change(screen.getByLabelText('Client secret'), { target: { value: 'y' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('Client id is not valid', 'error'))
  })
})

describe('ConnectorAppCredentialPanel — verify', () => {
  function report(
    status: ConnectorAppCredentialVerificationReport['status'],
    checks: ConnectorAppCredentialVerificationReport['checks']
  ): ConnectorAppCredentialVerificationReport {
    return { connectorId: 'gsc', status, checkedAt: new Date().toISOString(), checks }
  }

  async function verifyWith(r: ConnectorAppCredentialVerificationReport) {
    vi.mocked(api.apiPost).mockResolvedValue(r)
    renderPanel(GSC_DEPLOYMENT, { connectorId: 'gsc', connectorName: 'Google Search Console' })
    fireEvent.click(screen.getByRole('button', { name: /^verify$/i }))
    return await screen.findByTestId('app-credential-verify-result')
  }

  it('renders a verified report as its own state, quoting each check message verbatim', async () => {
    const result = await verifyWith(
      report('verified', [
        {
          name: 'token',
          status: 'pass',
          message:
            'Google accepted the client id and secret. This proves the app credentials only — it does not prove any connection works.',
        },
      ])
    )

    expect(api.apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/integrations/gsc/app-credentials/verify',
      {},
      'test-token'
    )
    expect(result).toHaveAttribute('data-status', 'verified')
    expect(within(result).getByText('Credentials verified')).toBeInTheDocument()
    expect(
      within(result).getByText(
        'Google accepted the client id and secret. This proves the app credentials only — it does not prove any connection works.'
      )
    ).toBeInTheDocument()
  })

  it('renders an error report as its own state', async () => {
    const result = await verifyWith(
      report('error', [{ name: 'token', status: 'fail', message: 'Google rejected the client secret.' }])
    )

    expect(result).toHaveAttribute('data-status', 'error')
    expect(within(result).getByText('Credentials rejected')).toBeInTheDocument()
    expect(within(result).getByText('Google rejected the client secret.')).toBeInTheDocument()
  })

  it('renders an unknown report as "could not determine", never as success or failure', async () => {
    const result = await verifyWith(
      report('unknown', [{ name: 'token', status: 'warn', message: 'The provider could not be reached.' }])
    )

    expect(result).toHaveAttribute('data-status', 'unknown')
    expect(within(result).getByText('Could not determine')).toBeInTheDocument()
    expect(within(result).queryByText('Credentials verified')).not.toBeInTheDocument()
    expect(within(result).queryByText('Credentials rejected')).not.toBeInTheDocument()
    expect(within(result).getByText('The provider could not be reached.')).toBeInTheDocument()
  })

  it('gives the three outcomes visually distinct treatments', async () => {
    const classesFor = async (r: ConnectorAppCredentialVerificationReport) => {
      const el = await verifyWith(r)
      const className = el.className
      cleanup()
      return className
    }

    const verified = await classesFor(report('verified', []))
    const errored = await classesFor(report('error', []))
    const unknown = await classesFor(report('unknown', []))

    expect(new Set([verified, errored, unknown]).size).toBe(3)
  })

  it('reports a verify request that could not be made at all', async () => {
    vi.mocked(api.apiPost).mockRejectedValue({ detail: 'Verification is unavailable' })
    renderPanel(GSC_DEPLOYMENT, { connectorId: 'gsc', connectorName: 'Google Search Console' })

    fireEvent.click(screen.getByRole('button', { name: /^verify$/i }))

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('Verification is unavailable', 'error'))
  })
})

describe('appCredentialOf', () => {
  const entry = (appCredential: ConnectorAppCredentialStatus | null): IntegrationListItem => ({
    connectorId: 'discord',
    name: 'Discord',
    category: 'Communication',
    authType: 'API_KEY',
    capabilities: [],
    singleInstance: true,
    description: 'Post messages to a Discord channel',
    iconLabel: 'DC',
    connected: false,
    configFields: [],
    connections: [],
    appCredential,
  })

  it('returns null for a connector the catalog reports no app credential for', () => {
    // The field is optional on the DTO, so "absent" and "explicitly null" both have to read null.
    const withoutField: IntegrationListItem = { ...entry(null) }
    delete withoutField.appCredential
    expect(appCredentialOf(withoutField)).toBeNull()
    expect(appCredentialOf(entry(null))).toBeNull()
    expect(appCredentialOf(undefined)).toBeNull()
    expect(appCredentialOf(null)).toBeNull()
  })

  it('returns the status when the catalog carries one', () => {
    expect(appCredentialOf(entry(YOUTUBE_NONE))).toEqual(YOUTUBE_NONE)
  })
})

describe('ConnectorCard readiness', () => {
  it('withholds the connect affordance, explains why, and leads to the page that fixes it', () => {
    render(
      <ConnectorCard
        icon={<span />}
        name="YouTube"
        description="Marketing"
        href="/app/projects/proj-1/integrations/youtube"
        unavailableReason="Enter this workspace's app credentials to let members connect."
      />
    )

    // No connect handler exists at all, so no click can reach a flow that would fail.
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.getByRole('link')).toHaveAttribute('href', '/app/projects/proj-1/integrations/youtube')
    expect(
      screen.getByText("Enter this workspace's app credentials to let members connect.")
    ).toBeInTheDocument()
  })

  it('stays a plain connect button when nothing blocks it', () => {
    render(<ConnectorCard icon={<span />} name="YouTube" description="Marketing" onClick={() => {}} />)
    expect(screen.getByRole('button')).toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })
})

describe('GenericConnectorPage app credential readiness: WORKSPACE_ONLY', () => {
  const youtubeConnector = (appCredential: ConnectorAppCredentialStatus | null) => ({
    connectorId: 'youtube',
    name: 'YouTube',
    category: 'Marketing',
    authType: 'OAUTH2' as const,
    capabilities: ['publish'],
    singleInstance: true,
    description: 'Publish video to YouTube',
    iconLabel: 'YT',
    connected: false,
    configFields: [],
    connections: [],
    appCredential,
  })

  function renderPage(item: unknown) {
    vi.mocked(api.listIntegrations).mockResolvedValue([item as never])
    return render(
      <ConnectorCatalogProvider projectId="proj-1" connectorId="youtube">
        <GenericConnectorPage projectId="proj-1" connectorId="youtube" />
      </ConnectorCatalogProvider>
    )
  }

  it('does not offer Connect when the platform app is not configured', async () => {
    renderPage(youtubeConnector(YOUTUBE_NONE))

    expect(await screen.findByRole('button', { name: /^authorize$/i })).toBeDisabled()
    expect(screen.getByText(/needs this workspace's own app/i)).toBeInTheDocument()
  })

  // The browse grid sends a blocked connector here instead of into a doomed consent flow, so this
  // page has to actually carry the fix — otherwise that redirect is just a nicer dead end.
  it('renders the credential panel the browse grid routes a blocked connector to', async () => {
    renderPage(youtubeConnector(YOUTUBE_NONE))

    expect(await screen.findByText('Platform app credentials')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /set a credential for this workspace/i })).toBeInTheDocument()
  })

  it('unblocks the connector once an admin sets the credential, without a reload', async () => {
    renderPage(youtubeConnector(YOUTUBE_NONE))

    expect(await screen.findByRole('button', { name: /^authorize$/i })).toBeDisabled()

    vi.mocked(api.apiPut).mockResolvedValue(YOUTUBE_PROJECT)
    fireEvent.click(screen.getByRole('button', { name: /set a credential for this workspace/i }))
    fireEvent.change(screen.getByLabelText('Client ID'), { target: { value: 'proj-client-5678' } })
    fireEvent.change(screen.getByLabelText('Client secret'), { target: { value: 'sup3r-s3cret' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))

    await waitFor(() => expect(screen.getByRole('button', { name: /^authorize$/i })).toBeEnabled())
  })

  it('renders no credential panel for a connector with no app credential', async () => {
    renderPage({ ...youtubeConnector(null), authType: 'API_KEY' as const })

    await screen.findByRole('heading', { name: 'YouTube' })
    expect(screen.queryByText('Platform app credentials')).not.toBeInTheDocument()
  })
})

describe('GenericConnectorPage app credential readiness: WORKSPACE_OR_DEPLOYMENT', () => {
  const gscConnector = (appCredential: ConnectorAppCredentialStatus | null) => ({
    connectorId: 'gsc',
    name: 'Google Search Console',
    category: 'Marketing',
    authType: 'OAUTH2' as const,
    capabilities: ['read'],
    singleInstance: true,
    description: 'Read organic search performance',
    iconLabel: 'GS',
    connected: false,
    configFields: [],
    connections: [],
    appCredential,
  })

  function renderPage(item: unknown) {
    vi.mocked(api.listIntegrations).mockResolvedValue([item as never])
    return render(
      <ConnectorCatalogProvider projectId="proj-1" connectorId="gsc">
        <GenericConnectorPage projectId="proj-1" connectorId="gsc" />
      </ConnectorCatalogProvider>
    )
  }

  it('offers Connect once a deployment credential is inherited', async () => {
    renderPage(gscConnector(GSC_DEPLOYMENT))

    expect(await screen.findByRole('button', { name: /^authorize$/i })).toBeEnabled()
    expect(screen.getByText('Inherited from the deployment')).toBeInTheDocument()
  })
})

describe('GenericConnectorPage app credential readiness: DEPLOYMENT_ONLY', () => {
  const metaConnector = (appCredential: ConnectorAppCredentialStatus | null) => ({
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
    appCredential,
  })

  function renderPage(item: unknown) {
    vi.mocked(api.listIntegrations).mockResolvedValue([item as never])
    return render(
      <ConnectorCatalogProvider projectId="proj-1" connectorId="meta">
        <GenericConnectorPage projectId="proj-1" connectorId="meta" />
      </ConnectorCatalogProvider>
    )
  }

  it('offers Connect once Conductor\'s central app is configured, with no credential inputs anywhere', async () => {
    renderPage(metaConnector(META_DEPLOYMENT_ONLY))

    expect(await screen.findByRole('button', { name: /^authorize$/i })).toBeEnabled()
    expect(screen.queryByLabelText('Client ID')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Client secret')).not.toBeInTheDocument()
  })

  it('blocks Connect and blames the deployment when the central app is not configured', async () => {
    renderPage(metaConnector(META_DEPLOYMENT_ONLY_UNCONFIGURED))

    expect(await screen.findByRole('button', { name: /^authorize$/i })).toBeDisabled()
    expect(screen.getByText(/this deployment's platform app is configured/i)).toBeInTheDocument()
  })
})
