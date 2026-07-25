import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => true,
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: vi.fn() }),
}))

vi.mock('@/lib/api', () => ({
  listConnections: vi.fn(),
  deleteConnection: vi.fn(),
  listConnectionWebhookEvents: vi.fn(async () => []),
  installGitHubApp: vi.fn(),
  listGitHubRepositories: vi.fn(),
  bindGitHubPat: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => fallback,
}))

import * as api from '@/lib/api'
import GitHubConnectorPage, { getPatExpiryStatus } from './GitHubConnectorPage'

function daysFromNow(days: number): string {
  return new Date(Date.now() + days * 24 * 60 * 60 * 1000).toISOString()
}

describe('getPatExpiryStatus', () => {
  it('reports no expiration when tokenExpiresAt is absent', () => {
    expect(getPatExpiryStatus(null)).toEqual({ label: 'No expiration', className: 'text-status-done' })
    expect(getPatExpiryStatus(undefined)).toEqual({ label: 'No expiration', className: 'text-status-done' })
  })

  it('marks a past date as expired', () => {
    expect(getPatExpiryStatus(daysFromNow(-1))).toEqual({ label: 'Expired', className: 'text-destructive' })
  })

  it('marks inside 7 days as destructive, including today', () => {
    expect(getPatExpiryStatus(daysFromNow(0))).toEqual({ label: 'Expires today', className: 'text-destructive' })
    expect(getPatExpiryStatus(daysFromNow(7)).className).toBe('text-destructive')
  })

  it('marks inside 30 days (but beyond 7) as amber progress', () => {
    const status = getPatExpiryStatus(daysFromNow(30))
    expect(status.className).toBe('text-status-progress')
    expect(status.label).toBe('Expires in 30 days')
  })

  it('marks far-out expirations as done (green)', () => {
    const status = getPatExpiryStatus(daysFromNow(31))
    expect(status.className).toBe('text-status-done')
  })
})

describe('GitHubConnectorPage', () => {
  it('renders the empty state with a PAT option and validates an empty token client-side', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([])

    render(<GitHubConnectorPage projectId="proj-1" />)

    await waitFor(() => expect(screen.getByText('Connect GitHub')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('button', { name: 'Use a Personal Access Token instead' }))
    fireEvent.click(screen.getByRole('button', { name: 'Save token' }))

    expect(await screen.findByText('Token is required.')).toBeInTheDocument()
    expect(api.bindGitHubPat).not.toHaveBeenCalled()
  })

  it('renders a bound PAT connection with an expiry indicator', async () => {
    vi.mocked(api.listConnections).mockResolvedValue([
      {
        id: 'conn-pat',
        label: 'Deploy token',
        status: 'ACTIVE',
        authType: 'PAT',
        tokenExpiresAt: daysFromNow(3),
      },
    ])

    render(<GitHubConnectorPage projectId="proj-1" />)

    await waitFor(() => expect(screen.getByText('Deploy token')).toBeInTheDocument())
    expect(screen.getByText(/Expires in 3 days/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Replace token' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument()
  })
})
