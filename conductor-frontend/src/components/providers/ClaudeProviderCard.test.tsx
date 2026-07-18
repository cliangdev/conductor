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

interface StubVerification {
  status: 'verified' | 'error'
  checkedAt: string
  error?: string | null
}

interface StubStatus {
  provider: string
  configured: boolean
  verification?: StubVerification | null
}

interface StubReport {
  provider: string
  status: 'verified' | 'error'
  checkedAt: string
  checks: { name: string; status: 'pass' | 'fail' | 'warn'; message: string }[]
}

// vitest 4 flags a vi.fn() mock whose implementation returns a rejected promise as an unhandled
// rejection even when the component awaits/catches it — drive rejections through a plain,
// per-test behavior variable instead (see reference_vitest_rejected_promise_mock memory).
let setCredentialBehavior: () => Promise<StubStatus> = () =>
  Promise.resolve({ provider: 'claude-code', configured: true })

let listStatusesBehavior: () => Promise<StubStatus[]> = () =>
  Promise.resolve([
    { provider: 'claude-code', configured: false },
    { provider: 'claude', configured: false },
  ])

let verifyBehavior: () => Promise<StubReport> = () =>
  Promise.resolve({ provider: 'claude-code', status: 'verified', checkedAt: '2026-01-01T00:00:00Z', checks: [] })

// ClaudeRuntimeSection (rendered under the claude-code row) does its own fetching — stub its calls
// to harmless empty defaults so it doesn't affect these ClaudeProviderCard-focused tests.
vi.mock('@/lib/api', () => ({
  apiErrorMessage: (err: unknown, fallback: string) =>
    err && typeof err === 'object' && 'detail' in err ? String((err as { detail: unknown }).detail) : fallback,
  listProviderCredentialStatuses: () => listStatusesBehavior(),
  setProviderCredential: () => setCredentialBehavior(),
  deleteProviderCredential: vi.fn().mockResolvedValue(undefined),
  verifyProviderCredential: () => verifyBehavior(),
  getClaudeRuntime: () =>
    Promise.resolve({ source: 'builtin', runtimeTargetId: null, runtimeTarget: null, builtinConfigured: true }),
  listRuntimeTargets: () => Promise.resolve([]),
  listConnections: () => Promise.resolve([]),
  setClaudeRuntime: vi.fn(),
  createRuntimeTarget: vi.fn(),
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
    verifyBehavior = () =>
      Promise.resolve({ provider: 'claude-code', status: 'verified', checkedAt: '2026-01-01T00:00:00Z', checks: [] })
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

  // ---- three-state verification badges ----

  it('shows "Connected · not yet verified" when configured but never verified', async () => {
    listStatusesBehavior = () =>
      Promise.resolve([
        { provider: 'claude-code', configured: true },
        { provider: 'claude', configured: false },
      ])

    render(<ClaudeProviderCard projectId="proj-1" />)

    expect(await screen.findByText('Connected · not yet verified')).toBeInTheDocument()
  })

  it('shows a Verified badge with the checked-at relative time when the last probe succeeded', async () => {
    listStatusesBehavior = () =>
      Promise.resolve([
        {
          provider: 'claude-code',
          configured: true,
          verification: { status: 'verified', checkedAt: new Date().toISOString() },
        },
        { provider: 'claude', configured: false },
      ])

    render(<ClaudeProviderCard projectId="proj-1" />)

    expect(await screen.findByText(/^Verified ·/)).toBeInTheDocument()
  })

  it('shows an Error badge with the inline reason when the last probe failed', async () => {
    listStatusesBehavior = () =>
      Promise.resolve([
        {
          provider: 'claude-code',
          configured: true,
          verification: { status: 'error', checkedAt: '2026-01-01T00:00:00Z', error: 'Cloud Run Job not found' },
        },
        { provider: 'claude', configured: false },
      ])

    render(<ClaudeProviderCard projectId="proj-1" />)

    expect(await screen.findByText('Error')).toBeInTheDocument()
    expect(screen.getByText('Cloud Run Job not found')).toBeInTheDocument()
  })

  // ---- Verify button ----

  it('a Verify click re-probes, updates the badge, and toasts the outcome', async () => {
    listStatusesBehavior = () => bothConfigured(true, false)
    verifyBehavior = () =>
      Promise.resolve({
        provider: 'claude-code',
        status: 'error',
        checkedAt: '2026-01-01T00:00:00Z',
        checks: [{ name: 'runtime-config', status: 'fail', message: 'No Claude runtime configured' }],
      })
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    await user.click(await screen.findByRole('button', { name: 'Verify Claude Code subscription' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Verification failed — see details below.', 'error')
    })
    expect(screen.getByText('No Claude runtime configured')).toBeInTheDocument()
  })

  it('does not show a Verify button for an unconfigured provider', async () => {
    listStatusesBehavior = () => bothConfigured(false, false)
    render(<ClaudeProviderCard projectId="proj-1" />)

    await screen.findByText('Claude Code subscription')
    expect(screen.queryByRole('button', { name: 'Verify Claude Code subscription' })).not.toBeInTheDocument()
  })

  // ---- PUT carries verification ----

  it('a save response carrying a verified result updates the badge without a second fetch', async () => {
    setCredentialBehavior = () =>
      Promise.resolve({
        provider: 'claude-code',
        configured: true,
        verification: { status: 'verified', checkedAt: new Date().toISOString() },
      })
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter subscription token')
    await user.type(input, 'cc-oauth-token-xyz')
    await user.click(screen.getByRole('button', { name: 'Save Claude Code subscription' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Subscription token saved. Verified.', 'success')
    })
    expect(await screen.findByText(/^Verified ·/)).toBeInTheDocument()
  })

  it('a save response carrying a verification error surfaces it in the toast and badge', async () => {
    setCredentialBehavior = () =>
      Promise.resolve({
        provider: 'claude-code',
        configured: true,
        verification: { status: 'error', checkedAt: '2026-01-01T00:00:00Z', error: '401 unauthorized' },
      })
    const user = userEvent.setup()
    render(<ClaudeProviderCard projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter subscription token')
    await user.type(input, 'bad-token')
    await user.click(screen.getByRole('button', { name: 'Save Claude Code subscription' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('Subscription token saved. Verification failed: 401 unauthorized', 'error')
    })
    expect(await screen.findByText('401 unauthorized')).toBeInTheDocument()
  })

  // ---- claude-code honesty copy ----

  it('shows the honest preflight-scope note on the claude-code row', async () => {
    render(<ClaudeProviderCard projectId="proj-1" />)

    expect(
      await screen.findByText(
        'Preflight checks runtime configuration and cloud access. Subscription token validity is confirmed on the first run.',
      ),
    ).toBeInTheDocument()
  })
})
