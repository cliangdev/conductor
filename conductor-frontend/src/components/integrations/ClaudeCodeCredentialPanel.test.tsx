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

vi.mock('@/lib/api', () => ({
  apiErrorMessage: (err: unknown, fallback: string) =>
    err && typeof err === 'object' && 'detail' in err ? String((err as { detail: unknown }).detail) : fallback,
  getProviderCredentialStatus: vi.fn().mockResolvedValue({ provider: 'claude-code', configured: false }),
  setProviderCredential: () => setCredentialBehavior(),
  deleteProviderCredential: vi.fn().mockResolvedValue(undefined),
}))

import ClaudeCodeCredentialPanel from './ClaudeCodeCredentialPanel'
import { getProviderCredentialStatus, deleteProviderCredential } from '@/lib/api'

const mockGetStatus = vi.mocked(getProviderCredentialStatus)
const mockDelete = vi.mocked(deleteProviderCredential)

let mockCanMutate = true

describe('ClaudeCodeCredentialPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
    mockGetStatus.mockResolvedValue({ provider: 'claude-code', configured: false })
    setCredentialBehavior = () => Promise.resolve({ provider: 'claude-code', configured: true })
  })

  it('queries credential status for the claude-code provider id', async () => {
    render(<ClaudeCodeCredentialPanel projectId="proj-1" />)

    await waitFor(() => {
      expect(mockGetStatus).toHaveBeenCalledWith('proj-1', 'claude-code', 'test-token')
    })
    expect(screen.getByText('Claude Code (subscription)')).toBeInTheDocument()
    expect(screen.getByText(/claude setup-token/)).toBeInTheDocument()
    expect(await screen.findByText('Not configured')).toBeInTheDocument()
  })

  it('saves a pasted subscription token under the claude-code provider', async () => {
    const user = userEvent.setup()
    render(<ClaudeCodeCredentialPanel projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter subscription token')
    await user.type(input, 'cc-oauth-token-xyz')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Subscription token saved.', 'success')
    })
    expect(screen.getByText('Configured')).toBeInTheDocument()
  })

  it('surfaces a save failure via the toast', async () => {
    setCredentialBehavior = () => Promise.reject(Object.assign(new Error('nope'), { detail: 'Token rejected' }))
    const user = userEvent.setup()
    render(<ClaudeCodeCredentialPanel projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter subscription token')
    await user.type(input, 'bad-token')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Token rejected', 'error')
    })
  })

  it('offers Replace and Remove when a token is already configured', async () => {
    mockGetStatus.mockResolvedValue({ provider: 'claude-code', configured: true })
    const user = userEvent.setup()
    render(<ClaudeCodeCredentialPanel projectId="proj-1" />)

    expect(await screen.findByText('Configured')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Replace' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Remove' }))
    await waitFor(() => {
      expect(mockDelete).toHaveBeenCalledWith('proj-1', 'claude-code', 'test-token')
      expect(showToast).toHaveBeenCalledWith('Subscription token removed.', 'success')
    })
    expect(screen.getByText('Not configured')).toBeInTheDocument()
  })

  it('hides the input and shows a read-only notice when the viewer cannot mutate', async () => {
    mockCanMutate = false
    render(<ClaudeCodeCredentialPanel projectId="proj-1" />)

    expect(await screen.findByText('Claude Code (subscription)')).toBeInTheDocument()
    expect(screen.queryByPlaceholderText('Enter subscription token')).not.toBeInTheDocument()
    expect(screen.getByText('Only admins and creators can manage this credential.')).toBeInTheDocument()
  })
})
