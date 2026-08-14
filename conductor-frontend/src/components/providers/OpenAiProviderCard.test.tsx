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

// vitest 4 flags a vi.fn() mock whose implementation returns a rejected promise as an unhandled
// rejection even when the component awaits/catches it — drive rejections through a plain,
// per-test behavior variable instead (see reference_vitest_rejected_promise_mock memory).
let setCredentialBehavior: () => Promise<StubStatus> = () =>
  Promise.resolve({ provider: 'openai', configured: true })

let listStatusesBehavior: () => Promise<StubStatus[]> = () =>
  Promise.resolve([{ provider: 'openai', configured: false }])

vi.mock('@/lib/api', () => ({
  apiErrorMessage: (err: unknown, fallback: string) =>
    err && typeof err === 'object' && 'detail' in err ? String((err as { detail: unknown }).detail) : fallback,
  listProviderCredentialStatuses: () => listStatusesBehavior(),
  setProviderCredential: () => setCredentialBehavior(),
  deleteProviderCredential: vi.fn().mockResolvedValue(undefined),
  verifyProviderCredential: vi.fn(),
}))

import { OpenAiProviderCard } from './OpenAiProviderCard'

let mockCanMutate = true

describe('OpenAiProviderCard', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanMutate = true
    listStatusesBehavior = () => Promise.resolve([{ provider: 'openai', configured: false }])
    setCredentialBehavior = () => Promise.resolve({ provider: 'openai', configured: true })
  })

  it('renders the OpenAI API key row as Not connected when nothing is configured', async () => {
    render(<OpenAiProviderCard projectId="proj-1" />)

    expect(screen.getByText('OpenAI')).toBeInTheDocument()
    expect(screen.getByText('OpenAI API key')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByText('Not connected')).toBeInTheDocument()
    })
  })

  it('saves a pasted key under the openai provider', async () => {
    const user = userEvent.setup()
    render(<OpenAiProviderCard projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter API key')
    await user.type(input, 'sk-openai-xyz')
    await user.click(screen.getByRole('button', { name: 'Save OpenAI API key' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('API key saved.', 'success')
    })
  })

  it('shows a Verified badge after saving when the response carries a verification summary', async () => {
    setCredentialBehavior = () =>
      Promise.resolve({
        provider: 'openai',
        configured: true,
        verification: { status: 'verified', checkedAt: new Date().toISOString() },
      })
    const user = userEvent.setup()
    render(<OpenAiProviderCard projectId="proj-1" />)

    const input = await screen.findByPlaceholderText('Enter API key')
    await user.type(input, 'sk-openai-xyz')
    await user.click(screen.getByRole('button', { name: 'Save OpenAI API key' }))

    await waitFor(() => {
      expect(showToast).toHaveBeenCalledWith('API key saved. Verified.', 'success')
    })
    expect(await screen.findByText(/^Verified ·/)).toBeInTheDocument()
  })
})
