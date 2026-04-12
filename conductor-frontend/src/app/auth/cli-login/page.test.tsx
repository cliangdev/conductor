import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('next/navigation', () => ({
  useSearchParams: () => ({ get: (key: string) => (key === 'port' ? '9876' : null) }),
}))

vi.mock('@/lib/firebase', () => ({
  getFirebaseAuth: vi.fn().mockReturnValue({}),
}))

vi.mock('firebase/auth', () => ({
  GoogleAuthProvider: class MockGoogleAuthProvider {},
  signInWithPopup: vi.fn(),
  getIdToken: vi.fn(),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

import * as firebaseAuth from 'firebase/auth'
import * as api from '@/lib/api'
import CliLoginPage from './page'

const mockWindowLocation = { href: '' }
Object.defineProperty(window, 'location', { value: mockWindowLocation, writable: true })

const CLI_KEYS_WITH_VALUE = [
  { id: 'key-1', key: 'uk_existing5678', maskedKey: '****5678', label: 'CLI key', createdAt: '2024-06-01T00:00:00Z' },
]
const NO_CLI_KEYS: never[] = []
const PROJECTS = [{ id: 'proj-1', name: 'My Project' }]
const ME = { email: 'user@example.com' }
const NEW_KEY = { id: 'new-key-id', key: 'uk_newkey1234', maskedKey: '****1234', label: 'CLI key', createdAt: '2024-01-01T00:00:00Z' }

function mockApiGetWith(cliKeys: typeof CLI_KEYS_WITH_VALUE | never[]) {
  vi.mocked(api.apiGet).mockImplementation(async (url: string) => {
    if (url === '/api/v1/api-keys') return cliKeys
    if (url === '/api/v1/projects') return PROJECTS
    if (url === '/api/v1/auth/me') return ME
    return {}
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  mockWindowLocation.href = ''

  vi.mocked(firebaseAuth.signInWithPopup).mockResolvedValue({
    user: {},
  } as Awaited<ReturnType<typeof firebaseAuth.signInWithPopup>>)

  vi.mocked(firebaseAuth.getIdToken).mockResolvedValue('firebase-id-token')

  vi.mocked(api.apiPost).mockImplementation(async (url: string) => {
    if (url === '/api/v1/auth/firebase') return { accessToken: 'test-access-token' }
    if (url === '/api/v1/api-keys') return NEW_KEY
    return {}
  })

  mockApiGetWith(NO_CLI_KEYS)
})

describe('CliLoginPage', () => {
  it('renders sign-in button initially', () => {
    render(<CliLoginPage />)
    expect(screen.getByRole('button', { name: /sign in with google/i })).toBeInTheDocument()
  })

  it('auto-creates key and redirects when no existing CLI keys', async () => {
    render(<CliLoginPage />)
    await userEvent.click(screen.getByRole('button', { name: /sign in with google/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith('/api/v1/api-keys', { label: 'CLI key' }, 'test-access-token')
    })

    await waitFor(() => {
      expect(mockWindowLocation.href).toContain('localhost:9876')
      expect(mockWindowLocation.href).toContain('apiKey=uk_newkey1234')
      expect(mockWindowLocation.href).toContain('projectId=proj-1')
    })
  })

  it('shows key picker when existing CLI keys are present', async () => {
    mockApiGetWith(CLI_KEYS_WITH_VALUE)

    render(<CliLoginPage />)
    await userEvent.click(screen.getByRole('button', { name: /sign in with google/i }))

    await waitFor(() => {
      expect(screen.getByText(/select an api key/i)).toBeInTheDocument()
    })

    expect(screen.getByText('****5678')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^use$/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create a new key/i })).toBeInTheDocument()
  })

  it('completes login when Use button is clicked', async () => {
    mockApiGetWith(CLI_KEYS_WITH_VALUE)

    render(<CliLoginPage />)
    await userEvent.click(screen.getByRole('button', { name: /sign in with google/i }))
    await waitFor(() => screen.getByRole('button', { name: /^use$/i }))

    await userEvent.click(screen.getByRole('button', { name: /^use$/i }))

    await waitFor(() => {
      expect(mockWindowLocation.href).toContain('apiKey=uk_existing5678')
    })
  })

  it('shows unavailable for keys with null key value', async () => {
    vi.mocked(api.apiGet).mockImplementation(async (url: string) => {
      if (url === '/api/v1/api-keys') return [
        { id: 'key-1', key: null, maskedKey: '****abcd', label: 'CLI key', createdAt: '2024-01-01T00:00:00Z' },
      ]
      return {}
    })

    render(<CliLoginPage />)
    await userEvent.click(screen.getByRole('button', { name: /sign in with google/i }))

    await waitFor(() => {
      expect(screen.getByText(/unavailable/i)).toBeInTheDocument()
    })

    expect(screen.queryByRole('button', { name: /^use$/i })).not.toBeInTheDocument()
  })

  it('creates new key without revoking when Create a new key is clicked', async () => {
    mockApiGetWith(CLI_KEYS_WITH_VALUE)

    render(<CliLoginPage />)
    await userEvent.click(screen.getByRole('button', { name: /sign in with google/i }))
    await waitFor(() => screen.getByRole('button', { name: /create a new key/i }))

    await userEvent.click(screen.getByRole('button', { name: /create a new key/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith('/api/v1/api-keys', { label: 'CLI key' }, 'test-access-token')
    })

    await waitFor(() => {
      expect(mockWindowLocation.href).toContain('apiKey=uk_newkey1234')
    })
  })

  it('shows error message when sign in fails', async () => {
    vi.mocked(firebaseAuth.signInWithPopup).mockRejectedValue(new Error('Popup closed'))

    render(<CliLoginPage />)
    await userEvent.click(screen.getByRole('button', { name: /sign in with google/i }))

    await waitFor(() => {
      expect(screen.getByText(/popup closed/i)).toBeInTheDocument()
    })
  })
})
