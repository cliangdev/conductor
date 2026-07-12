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
  Promise.resolve({ provider: 'claude', configured: true })

vi.mock('@/lib/api', () => ({
  apiErrorMessage: (err: unknown, fallback: string) =>
    err && typeof err === 'object' && 'detail' in err ? String((err as { detail: unknown }).detail) : fallback,
  listAgentProviders: vi.fn().mockResolvedValue([{ id: 'claude' }]),
  getProviderCredentialStatus: vi.fn().mockResolvedValue({ provider: 'claude', configured: false }),
  setProviderCredential: () => setCredentialBehavior(),
  deleteProviderCredential: vi.fn().mockResolvedValue(undefined),
}))

import { ProviderKeysPanel } from './ProviderKeysPanel'
import { getProviderCredentialStatus } from '@/lib/api'

const mockGetStatus = vi.mocked(getProviderCredentialStatus)

describe('ProviderKeysPanel — model provider keys', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetStatus.mockResolvedValue({ provider: 'claude', configured: false })
    setCredentialBehavior = () => Promise.resolve({ provider: 'claude', configured: true })
  })

  it('renders a row per model provider and no Claude Code (subscription) row', async () => {
    render(<ProviderKeysPanel projectId="proj-1" canMutate={true} />)

    await waitFor(() => {
      expect(screen.getByText('claude')).toBeInTheDocument()
    })
    // Moved to the GCP integration page (ClaudeCodeCredentialPanel) — must not render here.
    expect(screen.queryByText('Claude Code (subscription)')).not.toBeInTheDocument()
    expect(screen.queryByPlaceholderText('Enter subscription token')).not.toBeInTheDocument()
  })

  it('queries credential status only for listed model providers', async () => {
    render(<ProviderKeysPanel projectId="proj-1" canMutate={true} />)

    await waitFor(() => {
      expect(mockGetStatus).toHaveBeenCalledWith('proj-1', 'claude', 'test-token')
    })
    expect(mockGetStatus).not.toHaveBeenCalledWith('proj-1', 'claude-code', 'test-token')
  })

  it('saves an entered API key for a provider', async () => {
    const user = userEvent.setup()
    render(<ProviderKeysPanel projectId="proj-1" canMutate={true} />)

    await waitFor(() => {
      expect(screen.getByText('claude')).toBeInTheDocument()
    })

    const input = screen.getByPlaceholderText('Enter API key')
    await user.type(input, 'sk-ant-xyz')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('API key saved.', 'success')
    })
  })

  it('surfaces a save failure via the toast', async () => {
    setCredentialBehavior = () => Promise.reject(Object.assign(new Error('nope'), { detail: 'Key rejected' }))
    const user = userEvent.setup()
    render(<ProviderKeysPanel projectId="proj-1" canMutate={true} />)

    await waitFor(() => {
      expect(screen.getByText('claude')).toBeInTheDocument()
    })

    const input = screen.getByPlaceholderText('Enter API key')
    await user.type(input, 'bad-key')
    await user.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Key rejected', 'error')
    })
  })

  it('hides the input and shows a read-only notice when the viewer cannot mutate', async () => {
    render(<ProviderKeysPanel projectId="proj-1" canMutate={false} />)

    await waitFor(() => {
      expect(screen.getByText('claude')).toBeInTheDocument()
    })
    expect(screen.queryByPlaceholderText('Enter API key')).not.toBeInTheDocument()
    expect(screen.getAllByText('Only admins and creators can manage provider keys.').length).toBeGreaterThan(0)
  })
})
