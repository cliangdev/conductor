import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => mockCanMutate,
}))

const showToast = vi.fn()
vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast }),
}))

// vitest 4 flags a vi.fn() mock whose implementation returns a rejected promise as an unhandled
// rejection even when the component awaits/catches it — drive rejections through a plain,
// per-test behavior variable instead (see reference_vitest_rejected_promise_mock memory).
let setCredentialBehavior: () => Promise<{ provider: string; configured: boolean }> = () =>
  Promise.resolve({ provider: 'claude-code', configured: true })

let listStatusesBehavior: () => Promise<{ provider: string; configured: boolean }[]> = () =>
  Promise.resolve([
    { provider: 'claude-code', configured: false },
    { provider: 'claude', configured: false },
  ])

vi.mock('@/lib/api', () => ({
  apiErrorMessage: (err: unknown, fallback: string) =>
    err && typeof err === 'object' && 'detail' in err ? String((err as { detail: unknown }).detail) : fallback,
  listProviderCredentialStatuses: () => listStatusesBehavior(),
  setProviderCredential: () => setCredentialBehavior(),
  deleteProviderCredential: vi.fn().mockResolvedValue(undefined),
}))

import { ClaudeProviderCard } from './ClaudeProviderCard'
import { deleteProviderCredential } from '@/lib/api'

const mockDelete = vi.mocked(deleteProviderCredential)

let mockCanMutate = true

function bothConfigured(claudeCode: boolean, claude: boolean) {
  return Promise.resolve([
    { provider: 'claude-code', configured: claudeCode },
    { provider: 'claude', configured: claude },
  ])
}

describe('ClaudeProviderCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
    listStatusesBehavior = () => bothConfigured(false, false)
    setCredentialBehavior = () => Promise.resolve({ provider: 'claude-code', configured: true })
  })

  it('renders both peer methods as Not connected when nothing is configured', async () => {
    render(<ClaudeProviderCard projectId="proj-1" />)

    expect(screen.getByText('Claude Code subscription')).toBeInTheDocument()
    expect(screen.getByText('Anthropic API key')).toBeInTheDocument()
    expect(screen.getByText('Recommended')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getAllByText('Not connected')).toHaveLength(2)
    })
  })

  it('saves a pasted subscription token under the claude-code provider', async () => {
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter subscription token')
    await user.type(input, 'cc-oauth-token-xyz')
    await user.click(screen.getByRole('button', { name: 'Save Claude Code subscription' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Subscription token saved.', 'success')
    })
  })

  it('surfaces a claude-code save failure via the toast', async () => {
    setCredentialBehavior = () => Promise.reject(Object.assign(new Error('nope'), { detail: 'Token rejected' }))
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter subscription token')
    await user.type(input, 'bad-token')
    await user.click(screen.getByRole('button', { name: 'Save Claude Code subscription' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Token rejected', 'error')
    })
  })

  it('offers Replace and Remove for the claude-code row once configured, and removing it works', async () => {
    listStatusesBehavior = () => bothConfigured(true, false)
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Replace Claude Code subscription' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Remove Claude Code subscription' }))
    await waitFor(() => {
      expect(mockDelete).toHaveBeenCalledWith('proj-1', 'claude-code', 'test-token')
      expect(showToast).toHaveBeenCalledWith('Subscription token removed.', 'success')
    })
  })

  it('saves a pasted key under the claude (API) provider', async () => {
    setCredentialBehavior = () => Promise.resolve({ provider: 'claude', configured: true })
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter API key')
    await user.type(input, 'sk-ant-xyz')
    await user.click(screen.getByRole('button', { name: 'Save Anthropic API key' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('API key saved.', 'success')
    })
  })

  it('surfaces an API key save failure via the toast', async () => {
    setCredentialBehavior = () => Promise.reject(Object.assign(new Error('nope'), { detail: 'Key rejected' }))
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter API key')
    await user.type(input, 'bad-key')
    await user.click(screen.getByRole('button', { name: 'Save Anthropic API key' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Key rejected', 'error')
    })
  })

  it('offers Replace and Remove for the claude row once configured, and removing it works', async () => {
    listStatusesBehavior = () => bothConfigured(false, true)
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Replace Anthropic API key' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: 'Remove Anthropic API key' }))
    await waitFor(() => {
      expect(mockDelete).toHaveBeenCalledWith('proj-1', 'claude', 'test-token')
      expect(showToast).toHaveBeenCalledWith('API key removed.', 'success')
    })
  })

  it('hides both inputs and shows a read-only notice when the viewer cannot mutate', async () => {
    mockCanMutate = false
    render(<ClaudeProviderCard projectId="proj-1" />)

    expect(await screen.findByText('Claude Code subscription')).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('Enter subscription token')).not.toBeInTheDocument()
    expect(screen.queryByPlaceholderText('Enter API key')).not.toBeInTheDocument()
    expect(screen.getAllByText('Only admins and creators can manage this credential.')).toHaveLength(2)
  })

  it('shows the both-connected info line only when both methods are configured', async () => {
    listStatusesBehavior = () => bothConfigured(false, true)
    const { unmount } = render(<ClaudeProviderCard projectId="proj-1" />)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Replace Anthropic API key' })).toBeInTheDocument()
    })
    expect(screen.queryByText(/agents default to claude code/i)).not.toBeInTheDocument()
    unmount()

    listStatusesBehavior = () => bothConfigured(true, true)
    render(<ClaudeProviderCard projectId="proj-1" />)
    expect(await screen.findByText(/agents default to claude code/i)).toBeInTheDocument()
  })
})
