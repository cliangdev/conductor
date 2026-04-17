import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  listProjectRepositories: vi.fn(),
  addProjectRepository: vi.fn(),
  updateProjectRepository: vi.fn(),
  deleteProjectRepository: vi.fn(),
}))

vi.mock('@/components/ui/modal', () => ({
  Modal: ({
    open,
    children,
    title,
  }: {
    open: boolean
    children: React.ReactNode
    title: string
  }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
      </div>
    ) : null,
}))

import * as api from '@/lib/api'
import GitHubSettingsPage from './page'

const mockShowToast = vi.fn()

const adminMember = {
  userId: 'user-admin',
  name: 'Admin User',
  email: 'admin@example.com',
  avatarUrl: null,
  role: 'ADMIN',
  joinedAt: '2024-01-01T00:00:00Z',
}

const sampleRepo = {
  id: 'repo-1',
  label: 'Frontend',
  repoUrl: 'https://github.com/org/frontend',
  repoFullName: 'org/frontend',
  webhookSecretConfigured: true,
  connectedAt: '2024-01-10T00:00:00Z',
}

const sampleRepoNoSecret = {
  id: 'repo-2',
  label: 'Backend',
  repoUrl: 'https://github.com/org/backend',
  repoFullName: 'org/backend',
  webhookSecretConfigured: false,
  connectedAt: '2024-01-11T00:00:00Z',
}

let mockAuthContext = {
  user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
  accessToken: 'test-token',
  loading: false,
}

describe('GitHubSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuthContext = {
      user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    vi.mocked(api.apiGet).mockResolvedValue([adminMember])
    vi.mocked(api.listProjectRepositories).mockResolvedValue([sampleRepo, sampleRepoNoSecret])
  })

  it('renders webhook URL with copy button', async () => {
    render(<GitHubSettingsPage />)
    const urlInput = await screen.findByDisplayValue(/\/projects\/proj-1\/github\/webhook/)
    expect(urlInput).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /copy/i })).toBeInTheDocument()
  })

  it('lists all registered repositories with label and URL', async () => {
    render(<GitHubSettingsPage />)
    expect(await screen.findByText('Frontend')).toBeInTheDocument()
    expect(screen.getByText('Backend')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'https://github.com/org/frontend' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'https://github.com/org/backend' })).toBeInTheDocument()
  })

  it('shows Configured status for repos with webhook secret', async () => {
    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')
    expect(screen.getByText(/✓/)).toBeInTheDocument()
    expect(screen.getAllByText(/configured/i).length).toBeGreaterThanOrEqual(1)
  })

  it('shows Not configured status for repos without webhook secret', async () => {
    render(<GitHubSettingsPage />)
    await screen.findByText('Backend')
    expect(screen.getByText(/not configured/i)).toBeInTheDocument()
  })

  it('shows empty state when no repositories', async () => {
    vi.mocked(api.listProjectRepositories).mockResolvedValue([])
    render(<GitHubSettingsPage />)
    expect(await screen.findByText(/no repositories registered yet/i)).toBeInTheDocument()
  })

  it('delete button calls deleteProjectRepository and removes repo from list', async () => {
    vi.mocked(api.deleteProjectRepository).mockResolvedValue(undefined)
    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /delete frontend/i }))

    await waitFor(() => {
      expect(api.deleteProjectRepository).toHaveBeenCalledWith('proj-1', 'repo-1', 'test-token')
    })

    await waitFor(() => {
      expect(screen.queryByText('Frontend')).not.toBeInTheDocument()
    })

    expect(screen.getByText('Backend')).toBeInTheDocument()
  })

  it('shows error toast when delete fails', async () => {
    const err = Object.assign(new Error('API error: 500'), { status: 500 })
    vi.mocked(api.deleteProjectRepository).mockRejectedValue(err)
    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /delete frontend/i }))

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'Failed to remove repository. Please try again.',
        'error',
      )
    })
    expect(screen.getByText('Frontend')).toBeInTheDocument()
  })

  it('Add Repository button opens modal', async () => {
    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /add repository/i }))

    expect(await screen.findByTestId('modal')).toBeInTheDocument()
    expect(screen.getAllByText('Add Repository').length).toBeGreaterThanOrEqual(1)
  })

  it('add modal has label, URL, and secret fields', async () => {
    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /add repository/i }))
    const modal = await screen.findByTestId('modal')

    expect(within(modal).getByLabelText(/label/i)).toBeInTheDocument()
    expect(within(modal).getByLabelText(/github repository url/i)).toBeInTheDocument()
    expect(within(modal).getByLabelText(/webhook secret/i)).toBeInTheDocument()
  })

  it('Generate button fills webhook secret field', async () => {
    const mockGetRandomValues = vi.fn((arr: Uint8Array) => {
      arr.fill(0xab)
      return arr
    })
    Object.defineProperty(globalThis, 'crypto', {
      value: { getRandomValues: mockGetRandomValues },
      configurable: true,
    })

    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /add repository/i }))
    const modal = await screen.findByTestId('modal')

    fireEvent.click(within(modal).getByRole('button', { name: /generate/i }))

    const secretInput = within(modal).getByLabelText(/webhook secret/i) as HTMLInputElement
    expect(secretInput.value).toMatch(/^[0-9a-f]{64}$/)
  })

  it('successful add appends repo to list and closes modal', async () => {
    const newRepo = {
      id: 'repo-3',
      label: 'New Service',
      repoUrl: 'https://github.com/org/new-service',
      repoFullName: 'org/new-service',
      webhookSecretConfigured: true,
      connectedAt: '2024-02-01T00:00:00Z',
    }
    vi.mocked(api.addProjectRepository).mockResolvedValue(newRepo)

    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /add repository/i }))
    const modal = await screen.findByTestId('modal')

    await userEvent.type(within(modal).getByLabelText(/label/i), 'New Service')
    await userEvent.type(within(modal).getByLabelText(/github repository url/i), 'https://github.com/org/new-service')
    await userEvent.type(within(modal).getByLabelText(/webhook secret/i), 'mysecret')

    fireEvent.click(within(modal).getByRole('button', { name: /^save$/i }))

    await waitFor(() => {
      expect(api.addProjectRepository).toHaveBeenCalledWith(
        'proj-1',
        { label: 'New Service', repoUrl: 'https://github.com/org/new-service', webhookSecret: 'mysecret' },
        'test-token',
      )
    })

    await waitFor(() => {
      expect(screen.queryByTestId('modal')).not.toBeInTheDocument()
    })

    expect(screen.getByText('New Service')).toBeInTheDocument()
  })

  it('shows validation error when label is empty on submit', async () => {
    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /add repository/i }))
    const modal = await screen.findByTestId('modal')

    fireEvent.click(within(modal).getByRole('button', { name: /^save$/i }))

    expect(await within(modal).findByText(/label is required/i)).toBeInTheDocument()
  })

  it('Cancel button closes modal without saving', async () => {
    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /add repository/i }))
    expect(await screen.findByTestId('modal')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))

    await waitFor(() => {
      expect(screen.queryByTestId('modal')).not.toBeInTheDocument()
    })

    expect(api.addProjectRepository).not.toHaveBeenCalled()
  })

  it('non-admin sees permission denied message', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([
      { ...adminMember, userId: 'someone-else' },
    ])

    render(<GitHubSettingsPage />)
    expect(
      await screen.findByText(/you don't have permission to manage settings/i),
    ).toBeInTheDocument()
  })

  it('shows access denied when repos fetch returns 403', async () => {
    const err = Object.assign(new Error('API error: 403'), { status: 403 })
    vi.mocked(api.listProjectRepositories).mockRejectedValue(err)

    render(<GitHubSettingsPage />)
    expect(
      await screen.findByText(/access denied/i),
    ).toBeInTheDocument()
  })

  it('Edit button opens edit modal pre-filled with repo data', async () => {
    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /edit frontend/i }))

    const modal = await screen.findByTestId('modal')
    expect(within(modal).getByLabelText(/label/i)).toHaveValue('Frontend')
  })

  it('successful edit updates repo in list and closes modal', async () => {
    const updated = { ...sampleRepo, label: 'Frontend v2' }
    vi.mocked(api.updateProjectRepository).mockResolvedValue(updated)

    render(<GitHubSettingsPage />)
    await screen.findByText('Frontend')

    fireEvent.click(screen.getByRole('button', { name: /edit frontend/i }))
    const modal = await screen.findByTestId('modal')

    const labelInput = within(modal).getByLabelText(/label/i)
    fireEvent.change(labelInput, { target: { value: 'Frontend v2' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^save$/i }))

    await waitFor(() => {
      expect(api.updateProjectRepository).toHaveBeenCalledWith(
        'proj-1', 'repo-1', { label: 'Frontend v2' }, 'test-token',
      )
    })

    await waitFor(() => expect(screen.queryByTestId('modal')).not.toBeInTheDocument())
    expect(screen.getByText('Frontend v2')).toBeInTheDocument()
  })
})
