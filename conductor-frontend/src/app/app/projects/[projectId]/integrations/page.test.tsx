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
