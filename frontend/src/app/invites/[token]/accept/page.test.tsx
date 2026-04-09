import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ token: 'invite-token-abc' }),
  useRouter: () => mockRouter,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

vi.mock('@/lib/api', () => ({
  apiPost: vi.fn(),
}))

import * as api from '@/lib/api'
import AcceptInvitePage from './page'

const mockRouter = { push: vi.fn(), replace: vi.fn() }

let mockAuthContext = {
  user: { id: 'user-1', name: 'Test User', email: 'test@example.com', avatarUrl: null, displayName: null },
  accessToken: 'test-token',
  loading: false,
}

describe('AcceptInvitePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuthContext = {
      user: { id: 'user-1', name: 'Test User', email: 'test@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
  })

  it('calls POST /api/v1/invites/{token}/accept with auth token', async () => {
    vi.mocked(api.apiPost).mockResolvedValue({
      projectId: 'proj-1',
      projectName: 'Cool Project',
      role: 'CREATOR',
    })

    render(<AcceptInvitePage />)

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/invites/invite-token-abc/accept',
        {},
        'test-token',
      )
    })
  })

  it('valid token shows success message with project name and role', async () => {
    vi.mocked(api.apiPost).mockResolvedValue({
      projectId: 'proj-1',
      projectName: 'Cool Project',
      role: 'CREATOR',
    })

    render(<AcceptInvitePage />)

    expect(await screen.findByText(/you've joined cool project/i)).toBeInTheDocument()
  })

  it('redirects to project after delay on success', async () => {
    const setTimeoutSpy = vi.spyOn(globalThis, 'setTimeout')

    vi.mocked(api.apiPost).mockResolvedValue({
      projectId: 'proj-1',
      projectName: 'Cool Project',
      role: 'CREATOR',
    })

    render(<AcceptInvitePage />)
    await screen.findByText(/you've joined cool project/i)

    // Verify setTimeout was scheduled with a redirect callback
    const redirectCall = setTimeoutSpy.mock.calls.find(
      ([, delay]) => delay === 2000,
    )
    expect(redirectCall).toBeDefined()

    setTimeoutSpy.mockRestore()
  })

  it('unauthenticated user is redirected to login with next param', async () => {
    mockAuthContext = {
      user: null,
      accessToken: null,
      loading: false,
    } as unknown as typeof mockAuthContext

    render(<AcceptInvitePage />)

    await waitFor(() => {
      expect(mockRouter.replace).toHaveBeenCalledWith(
        '/login?next=/invites/invite-token-abc/accept',
      )
    })

    expect(api.apiPost).not.toHaveBeenCalled()
  })

  it('shows "This invite has expired" on 410', async () => {
    const err = Object.assign(new Error('API error: 410'), { status: 410 })
    vi.mocked(api.apiPost).mockRejectedValue(err)

    render(<AcceptInvitePage />)

    expect(await screen.findByText(/this invite has expired/i)).toBeInTheDocument()
  })

  it('shows "This invite has already been used" on 409', async () => {
    const err = Object.assign(new Error('API error: 409'), { status: 409 })
    vi.mocked(api.apiPost).mockRejectedValue(err)

    render(<AcceptInvitePage />)

    expect(await screen.findByText(/this invite has already been used/i)).toBeInTheDocument()
  })

  it('shows "Invite not found" on 404', async () => {
    const err = Object.assign(new Error('API error: 404'), { status: 404 })
    vi.mocked(api.apiPost).mockRejectedValue(err)

    render(<AcceptInvitePage />)

    expect(await screen.findByText(/invite not found/i)).toBeInTheDocument()
  })

  it('does not call API while auth is still loading', () => {
    mockAuthContext = {
      ...mockAuthContext,
      loading: true,
    }

    render(<AcceptInvitePage />)

    expect(api.apiPost).not.toHaveBeenCalled()
  })
})
