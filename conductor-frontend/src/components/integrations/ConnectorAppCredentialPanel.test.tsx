import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react'
import { useState, type ReactNode } from 'react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

let mockRole: 'ADMIN' | 'CREATOR' | 'REVIEWER' = 'ADMIN'
vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({ role: mockRole, loading: false, can: () => mockRole !== 'REVIEWER', refresh: () => {} }),
  useCan: () => mockRole !== 'REVIEWER',
}))

const showToast = vi.fn()
vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast }),
}))

// Flatten the modal so ConfirmModal's content is in the DOM whenever it is open.
vi.mock('@/components/ui/modal', () => ({
  Modal: ({ open, children, title, footer }: { open: boolean; children: ReactNode; title: string; footer: ReactNode }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
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

const NONE: ConnectorAppCredentialStatus = {
  connectorId: 'meta',
  credentialSource: 'NONE',
  configured: false,
  clientId: null,
  clientSecretLast4: null,
  missingProperties: ['META_APP_ID', 'META_APP_SECRET'],
  updatedBy: null,
  updatedAt: null,
}

const DEPLOYMENT: ConnectorAppCredentialStatus = {
  connectorId: 'meta',
  credentialSource: 'DEPLOYMENT',
  configured: true,
  clientId: 'dep-client-1234',
  clientSecretLast4: '9999',
  missingProperties: [],
  updatedBy: null,
  updatedAt: null,
}

const PROJECT: ConnectorAppCredentialStatus = {
  connectorId: 'meta',
  credentialSource: 'PROJECT',
  configured: true,
  clientId: 'proj-client-5678',
  clientSecretLast4: '8888',
  missingProperties: [],
  updatedBy: 'user-7',
  updatedAt: new Date(Date.now() - 2 * 60 * 60_000).toISOString(),
}

function renderPanel(status: ConnectorAppCredentialStatus) {
  return render(
    <ConnectorAppCredentialPanel
      projectId="proj-1"
      connectorId="meta"
      connectorName="Meta"
      status={status}
      onChange={vi.fn()}
    />
  )
}

/** Re-renders the panel with whatever status the component last handed back through `onChange`. */
function renderStatefulPanel(initial: ConnectorAppCredentialStatus) {
  function Harness() {
    const [status, setStatus] = useState(initial)
    return (
      <ConnectorAppCredentialPanel
        projectId="proj-1"
        connectorId="meta"
        connectorName="Meta"
        status={status}
        onChange={setStatus}
      />
    )
  }
  return render(<Harness />)
}

beforeEach(() => {
  vi.clearAllMocks()
  mockRole = 'ADMIN'
  mockFetchMembers = vi.fn(async () => members())
})

describe('ConnectorAppCredentialPanel — readiness states', () => {
  it('names every missing property when no credential is configured', () => {
    renderPanel(NONE)

    expect(screen.getByText('Not configured')).toBeInTheDocument()
    expect(screen.getByText('META_APP_ID')).toBeInTheDocument()
    expect(screen.getByText('META_APP_SECRET')).toBeInTheDocument()
  })

  it('says a deployment credential is inherited and shared, and offers no clear action', () => {
    renderPanel(DEPLOYMENT)

    expect(screen.getByText('Inherited from the deployment')).toBeInTheDocument()
    expect(screen.getByText(/shared with every workspace on this deployment/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^clear$/i })).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /use a credential for this workspace instead/i })
    ).toBeInTheDocument()
  })

  it('shows the workspace credential masked, with who set it and when, plus replace and clear', async () => {
    renderPanel(PROJECT)

    expect(screen.getByText('Set on this workspace')).toBeInTheDocument()
    expect(screen.getByText('proj-client-5678')).toBeInTheDocument()
    expect(screen.getByText('••••8888')).toBeInTheDocument()
    expect(await screen.findByText(/Dana Ops/)).toBeInTheDocument()
    expect(screen.getByText(/2h ago/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^replace$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^clear$/i })).toBeInTheDocument()
  })

  it('never implies a workspace credential needs its own app review', () => {
    renderPanel(DEPLOYMENT)
    fireEvent.click(screen.getByRole('button', { name: /use a credential for this workspace instead/i }))

    expect(screen.getByText(/doesn't need its own app review/i)).toBeInTheDocument()
  })
})

describe('ConnectorAppCredentialPanel — permissions', () => {
  it('offers no write controls to a CREATOR', () => {
    mockRole = 'CREATOR'
    renderPanel(PROJECT)

    expect(screen.queryByRole('button', { name: /^replace$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^clear$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /credential for this workspace/i })).not.toBeInTheDocument()
    // Verify stays available — the endpoint allows ADMIN or CREATOR.
    expect(screen.getByRole('button', { name: /^verify$/i })).toBeInTheDocument()
  })

  it('offers no write controls when nothing is configured and the viewer is not an ADMIN', () => {
    mockRole = 'CREATOR'
    renderPanel(NONE)

    expect(screen.queryByRole('button', { name: /credential for this workspace/i })).not.toBeInTheDocument()
    expect(screen.getByText('META_APP_ID')).toBeInTheDocument()
  })
})

describe('ConnectorAppCredentialPanel — set, replace and clear', () => {
  it('PUTs the client id and secret and re-renders from the response', async () => {
    vi.mocked(api.apiPut).mockResolvedValue({ ...PROJECT, clientId: 'new-client', clientSecretLast4: '4321' })
    renderStatefulPanel(DEPLOYMENT)

    fireEvent.click(screen.getByRole('button', { name: /use a credential for this workspace instead/i }))
    fireEvent.change(screen.getByLabelText('Client ID'), { target: { value: 'new-client' } })
    fireEvent.change(screen.getByLabelText('Client secret'), { target: { value: 'sup3r-s3cret-value' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))

    await waitFor(() =>
      expect(api.apiPut).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/integrations/meta/app-credentials',
        { clientId: 'new-client', clientSecret: 'sup3r-s3cret-value' },
        'test-token'
      )
    )

    expect(await screen.findByText('••••4321')).toBeInTheDocument()
    expect(screen.getByText('Set on this workspace')).toBeInTheDocument()
  })

  it('never keeps the client secret in the DOM after a successful save', async () => {
    vi.mocked(api.apiPut).mockResolvedValue({ ...PROJECT, clientId: 'new-client', clientSecretLast4: '4321' })
    const { container } = renderStatefulPanel(DEPLOYMENT)

    fireEvent.click(screen.getByRole('button', { name: /use a credential for this workspace instead/i }))
    fireEvent.change(screen.getByLabelText('Client ID'), { target: { value: 'new-client' } })
    fireEvent.change(screen.getByLabelText('Client secret'), { target: { value: 'sup3r-s3cret-value' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))

    expect(await screen.findByText('••••4321')).toBeInTheDocument()
    expect(screen.queryByDisplayValue('sup3r-s3cret-value')).not.toBeInTheDocument()
    expect(container.innerHTML).not.toContain('sup3r-s3cret-value')
  })

  it('masks the secret input so it is never readable on screen', () => {
    renderPanel(NONE)
    fireEvent.click(screen.getByRole('button', { name: /credential for this workspace/i }))

    expect(screen.getByLabelText('Client secret')).toHaveAttribute('type', 'password')
  })

  it('clears with DELETE and falls back to the deployment credential', async () => {
    vi.mocked(api.apiDelete).mockResolvedValue(undefined)
    vi.mocked(api.apiGet).mockResolvedValue(DEPLOYMENT)
    renderStatefulPanel(PROJECT)

    fireEvent.click(screen.getByRole('button', { name: /^clear$/i }))
    fireEvent.click(within(screen.getByTestId('modal')).getByRole('button', { name: /^clear$/i }))

    await waitFor(() =>
      expect(api.apiDelete).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/integrations/meta/app-credentials',
        'test-token'
      )
    )
    expect(await screen.findByText('Inherited from the deployment')).toBeInTheDocument()
  })

  it('reports a failed save instead of swallowing it', async () => {
    vi.mocked(api.apiPut).mockRejectedValue({ detail: 'Client id is not valid' })
    renderStatefulPanel(NONE)

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
    return { connectorId: 'meta', status, checkedAt: new Date().toISOString(), checks }
  }

  async function verifyWith(r: ConnectorAppCredentialVerificationReport) {
    vi.mocked(api.apiPost).mockResolvedValue(r)
    renderPanel(DEPLOYMENT)
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
      '/api/v1/projects/proj-1/integrations/meta/app-credentials/verify',
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
      report('error', [{ name: 'token', status: 'fail', message: 'Meta rejected the client secret.' }])
    )

    expect(result).toHaveAttribute('data-status', 'error')
    expect(within(result).getByText('Credentials rejected')).toBeInTheDocument()
    expect(within(result).getByText('Meta rejected the client secret.')).toBeInTheDocument()
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
    renderPanel(DEPLOYMENT)

    fireEvent.click(screen.getByRole('button', { name: /^verify$/i }))

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('Verification is unavailable', 'error'))
  })
})

describe('appCredentialOf', () => {
  it('returns null for a connector the catalog reports no app credential for', () => {
    expect(appCredentialOf({ connectorId: 'discord' })).toBeNull()
    expect(appCredentialOf({ connectorId: 'discord', appCredential: null })).toBeNull()
    expect(appCredentialOf(undefined)).toBeNull()
    expect(appCredentialOf(null)).toBeNull()
  })

  it('returns the status when the catalog carries one', () => {
    expect(appCredentialOf({ appCredential: NONE })).toEqual(NONE)
  })
})

describe('ConnectorCard readiness', () => {
  it('withholds the connect affordance and explains why when disabled', () => {
    render(
      <ConnectorCard
        icon={<span />}
        name="Meta"
        description="Marketing"
        onClick={() => {}}
        disabled
        disabledReason="Set META_APP_ID and META_APP_SECRET before connecting."
      />
    )

    expect(screen.queryByRole('button')).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('Set META_APP_ID and META_APP_SECRET before connecting.')).toBeInTheDocument()
  })

  it('stays interactive when it is not disabled', () => {
    render(<ConnectorCard icon={<span />} name="Meta" description="Marketing" onClick={() => {}} />)
    expect(screen.getByRole('button')).toBeInTheDocument()
  })
})

describe('GenericConnectorPage app credential readiness', () => {
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

  it('does not offer Connect when the platform app is not configured', async () => {
    renderPage(metaConnector(NONE))

    expect(await screen.findByRole('button', { name: /^authorize$/i })).toBeDisabled()
    expect(screen.getByText('META_APP_ID')).toBeInTheDocument()
  })

  it('offers Connect once a deployment credential is inherited', async () => {
    renderPage(metaConnector(DEPLOYMENT))

    expect(await screen.findByRole('button', { name: /^authorize$/i })).toBeEnabled()
    expect(screen.getByText('Inherited from the deployment')).toBeInTheDocument()
  })

  it('renders no credential panel for a connector with no app credential', async () => {
    renderPage({ ...metaConnector(null), authType: 'API_KEY' as const })

    await screen.findByRole('heading', { name: 'Meta' })
    expect(screen.queryByText('Platform app credentials')).not.toBeInTheDocument()
  })
})
