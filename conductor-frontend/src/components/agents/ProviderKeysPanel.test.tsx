import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
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
  listAgentProviders: vi.fn().mockResolvedValue([{ id: 'claude' }]),
  getProviderCredentialStatus: vi.fn().mockResolvedValue({ provider: 'claude', configured: false }),
  setProviderCredential: (...args: unknown[]) => setCredentialBehavior(),
  deleteProviderCredential: vi.fn().mockResolvedValue(undefined),
}))

import { ProviderKeysPanel } from './ProviderKeysPanel'
import { getProviderCredentialStatus } from '@/lib/api'

const mockGetStatus = vi.mocked(getProviderCredentialStatus)

describe('ProviderKeysPanel — Claude Code subscription credential', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetStatus.mockResolvedValue({ provider: 'claude', configured: false })
    setCredentialBehavior = () => Promise.resolve({ provider: 'claude-code', configured: true })
  })

  it('renders a fixed Claude Code (subscription) row alongside model providers', async () => {
    render(<ProviderKeysPanel projectId="proj-1" canMutate={true} />)

    await waitFor(() => {
      expect(screen.getByText('Claude Code (subscription)')).toBeInTheDocument()
    })
    expect(screen.getByText('claude')).toBeInTheDocument()
    expect(screen.getByText(/claude setup-token/)).toBeInTheDocument()
  })

  it('queries credential status for the claude-code provider id', async () => {
    render(<ProviderKeysPanel projectId="proj-1" canMutate={true} />)

    await waitFor(() => {
      expect(mockGetStatus).toHaveBeenCalledWith('proj-1', 'claude-code', 'test-token')
    })
  })

  it('saves a pasted subscription token under the claude-code provider', async () => {
    const user = userEvent.setup()
    render(<ProviderKeysPanel projectId="proj-1" canMutate={true} />)

    await waitFor(() => {
      expect(screen.getByText('Claude Code (subscription)')).toBeInTheDocument()
    })

    const input = screen.getByPlaceholderText('Enter subscription token')
    await user.type(input, 'cc-oauth-token-xyz')
    await user.click(screen.getAllByRole('button', { name: 'Save' }).at(-1)!)

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('API key saved.', 'success')
    })
  })

  it('surfaces a save failure via the toast', async () => {
    setCredentialBehavior = () => Promise.reject(Object.assign(new Error('nope'), { detail: 'Token rejected' }))
    const user = userEvent.setup()
    render(<ProviderKeysPanel projectId="proj-1" canMutate={true} />)

    await waitFor(() => {
      expect(screen.getByText('Claude Code (subscription)')).toBeInTheDocument()
    })

    const input = screen.getByPlaceholderText('Enter subscription token')
    await user.type(input, 'bad-token')
    await user.click(screen.getAllByRole('button', { name: 'Save' }).at(-1)!)

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Token rejected', 'error')
    })
  })

  it('hides the input and shows a read-only notice when the viewer cannot mutate', async () => {
    render(<ProviderKeysPanel projectId="proj-1" canMutate={false} />)

    await waitFor(() => {
      expect(screen.getByText('Claude Code (subscription)')).toBeInTheDocument()
    })
    expect(screen.queryByPlaceholderText('Enter subscription token')).not.toBeInTheDocument()
    expect(screen.getAllByText('Only admins and creators can manage provider keys.').length).toBeGreaterThan(0)
  })
})
