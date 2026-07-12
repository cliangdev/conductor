import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({
    role: mockCanManageWorkflows ? 'ADMIN' : 'REVIEWER',
    loading: false,
    can: (cap: string) => (cap === 'workflow.manage' ? mockCanManageWorkflows : false),
    refresh: vi.fn(),
  }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/components/ui/modal', () => ({
  Modal: ({ open, children, title }: { open: boolean; children: React.ReactNode; title: string }) =>
    open ? (
      <div data-testid="modal" data-title={title}>
        <h2>{title}</h2>
        {children}
      </div>
    ) : null,
}))

vi.mock('@/lib/api', async () => {
  const actual = await vi.importActual<typeof import('@/lib/api')>('@/lib/api')
  return {
    ...actual,
    listWorkflowSecrets: vi.fn(),
    createWorkflowSecret: vi.fn(),
    updateWorkflowSecret: vi.fn(),
    deleteWorkflowSecret: vi.fn(),
  }
})

import * as api from '@/lib/api'
import WorkflowSecretsPage from './page'

const mockShowToast = vi.fn()
let mockCanManageWorkflows = true

let mockAuthContext = {
  user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
  accessToken: 'test-token',
  loading: false,
}

const existingSecret = {
  key: 'DISCORD_WEBHOOK_URL',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

describe('WorkflowSecretsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanManageWorkflows = true
    mockAuthContext = {
      user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    vi.mocked(api.listWorkflowSecrets).mockResolvedValue([existingSecret])
  })

  it('renders the list of secret keys (no values)', async () => {
    render(<WorkflowSecretsPage />)
    expect(await screen.findByText('DISCORD_WEBHOOK_URL')).toBeInTheDocument()
    expect(screen.queryByText(/whsec_|xoxb-/i)).not.toBeInTheDocument()
  })

  it('shows an empty state when there are no secrets', async () => {
    vi.mocked(api.listWorkflowSecrets).mockResolvedValue([])
    render(<WorkflowSecretsPage />)
    expect(await screen.findByText(/no secrets yet/i)).toBeInTheDocument()
  })

  it('non-manager does not see Add secret, Update, or Delete actions', async () => {
    mockCanManageWorkflows = false
    render(<WorkflowSecretsPage />)
    await screen.findByText('DISCORD_WEBHOOK_URL')
    expect(screen.queryByRole('button', { name: /add secret/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /update value/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /delete secret/i })).not.toBeInTheDocument()
  })

  it('rejects an invalid key client-side without calling the API', async () => {
    render(<WorkflowSecretsPage />)
    await screen.findByText('DISCORD_WEBHOOK_URL')

    fireEvent.click(screen.getByRole('button', { name: /add secret/i }))
    const modal = await screen.findByTestId('modal')

    fireEvent.change(within(modal).getByLabelText(/^key$/i), { target: { value: 'not-a-valid-key!' } })
    fireEvent.change(within(modal).getByLabelText(/^value$/i), { target: { value: 'some-value' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^add secret$/i }))

    expect(await within(modal).findByRole('alert')).toHaveTextContent(/uppercase letters/i)
    expect(api.createWorkflowSecret).not.toHaveBeenCalled()
  })

  it('adding a secret POSTs key + value and shows it in the list', async () => {
    vi.mocked(api.createWorkflowSecret).mockResolvedValue({
      key: 'API_TOKEN',
      createdAt: '2026-02-01T00:00:00Z',
      updatedAt: '2026-02-01T00:00:00Z',
    })

    render(<WorkflowSecretsPage />)
    await screen.findByText('DISCORD_WEBHOOK_URL')

    fireEvent.click(screen.getByRole('button', { name: /add secret/i }))
    const modal = await screen.findByTestId('modal')

    fireEvent.change(within(modal).getByLabelText(/^key$/i), { target: { value: 'API_TOKEN' } })
    fireEvent.change(within(modal).getByLabelText(/^value$/i), { target: { value: 'tok_abc123' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^add secret$/i }))

    await waitFor(() => {
      expect(api.createWorkflowSecret).toHaveBeenCalledWith(
        'proj-1',
        { key: 'API_TOKEN', value: 'tok_abc123' },
        'test-token',
      )
    })
    expect(await screen.findByText('API_TOKEN')).toBeInTheDocument()
    expect(mockShowToast).toHaveBeenCalledWith('Secret created')
  })

  it('updating a secret PUTs the new value keyed by the existing key', async () => {
    vi.mocked(api.updateWorkflowSecret).mockResolvedValue({
      key: 'DISCORD_WEBHOOK_URL',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-03-01T00:00:00Z',
    })

    render(<WorkflowSecretsPage />)
    await screen.findByText('DISCORD_WEBHOOK_URL')

    fireEvent.click(screen.getByRole('button', { name: /update value/i }))
    const modal = await screen.findByTestId('modal')

    fireEvent.change(within(modal).getByLabelText(/new value/i), { target: { value: 'https://discord.example/new' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^save$/i }))

    await waitFor(() => {
      expect(api.updateWorkflowSecret).toHaveBeenCalledWith(
        'proj-1',
        'DISCORD_WEBHOOK_URL',
        'https://discord.example/new',
        'test-token',
      )
    })
    expect(mockShowToast).toHaveBeenCalledWith('Secret updated')
  })

  it('deleting a secret calls DELETE after confirmation and removes it from the list', async () => {
    vi.mocked(api.deleteWorkflowSecret).mockResolvedValue(undefined)

    render(<WorkflowSecretsPage />)
    await screen.findByText('DISCORD_WEBHOOK_URL')

    fireEvent.click(screen.getByRole('button', { name: /delete secret discord_webhook_url/i }))
    const modal = await screen.findByTestId('modal')
    fireEvent.click(within(modal).getByRole('button', { name: /^delete$/i }))

    await waitFor(() => {
      expect(api.deleteWorkflowSecret).toHaveBeenCalledWith('proj-1', 'DISCORD_WEBHOOK_URL', 'test-token')
    })
    await waitFor(() => {
      expect(screen.queryByText('DISCORD_WEBHOOK_URL')).not.toBeInTheDocument()
    })
    expect(mockShowToast).toHaveBeenCalledWith('Secret deleted')
  })

  it('surfaces the backend error message when create fails', async () => {
    const err = Object.assign(new Error('conflict'), { status: 409, detail: 'A secret with that key already exists.' })
    vi.mocked(api.createWorkflowSecret).mockRejectedValue(err)

    render(<WorkflowSecretsPage />)
    await screen.findByText('DISCORD_WEBHOOK_URL')

    fireEvent.click(screen.getByRole('button', { name: /add secret/i }))
    const modal = await screen.findByTestId('modal')
    fireEvent.change(within(modal).getByLabelText(/^key$/i), { target: { value: 'DUPLICATE_KEY' } })
    fireEvent.change(within(modal).getByLabelText(/^value$/i), { target: { value: 'x' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^add secret$/i }))

    expect(await within(modal).findByText(/already exists/i)).toBeInTheDocument()
  })
})
