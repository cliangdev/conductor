import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({ activeProject: { id: 'proj-1', name: 'Test Project' } }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('@/components/ui/modal', () => ({
  Modal: ({ open, children, title }: { open: boolean; children: React.ReactNode; title: string }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
      </div>
    ) : null,
}))

import * as api from '@/lib/api'
import MembersPage from './page'

const mockShowToast = vi.fn()

const adminMember = {
  userId: 'user-admin',
  name: 'Admin User',
  email: 'admin@example.com',
  avatarUrl: null,
  role: 'ADMIN',
  joinedAt: '2024-01-01T00:00:00Z',
}

const regularMember = {
  userId: 'user-creator',
  name: 'Creator User',
  email: 'creator@example.com',
  avatarUrl: null,
  role: 'CREATOR',
  joinedAt: '2024-01-15T00:00:00Z',
}

let mockAuthContext = {
  user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
  accessToken: 'test-token',
  loading: false,
}

describe('MembersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuthContext = {
      user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    vi.mocked(api.apiGet).mockImplementation((path: string) => {
      if (path.includes('/members')) return Promise.resolve([adminMember, regularMember])
      if (path.includes('/invites')) return Promise.resolve([])
      return Promise.resolve([])
    })
  })

  it('renders member list', async () => {
    render(<MembersPage />)
    expect(await screen.findByText('Admin User')).toBeInTheDocument()
    expect(await screen.findByText('Creator User')).toBeInTheDocument()
  })

  it('admin user sees role dropdown and remove button for other members', async () => {
    render(<MembersPage />)
    await screen.findByText('Creator User')
    expect(screen.getByLabelText(/role for creator user/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /remove creator user/i })).toBeInTheDocument()
  })

  it('non-admin user does not see role dropdown or remove button', async () => {
    mockAuthContext = {
      user: { id: 'user-creator', name: 'Creator User', email: 'creator@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    render(<MembersPage />)
    await screen.findByText('Admin User')
    expect(screen.queryByLabelText(/role for/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /remove/i })).not.toBeInTheDocument()
  })

  it('role change calls PATCH endpoint and updates UI', async () => {
    const updatedMember = { ...regularMember, role: 'REVIEWER' }
    vi.mocked(api.apiPatch).mockResolvedValue(updatedMember)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    const roleSelect = screen.getByLabelText(/role for creator user/i)
    fireEvent.change(roleSelect, { target: { value: 'REVIEWER' } })

    await waitFor(() => {
      expect(api.apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/members/user-creator',
        { role: 'REVIEWER' },
        'test-token',
      )
    })

    expect(mockShowToast).toHaveBeenCalledWith('Role updated successfully')
  })

  it('shows error toast when role change returns 403', async () => {
    const err = Object.assign(new Error('API error: 403'), { status: 403 })
    vi.mocked(api.apiPatch).mockRejectedValue(err)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    const roleSelect = screen.getByLabelText(/role for creator user/i)
    fireEvent.change(roleSelect, { target: { value: 'REVIEWER' } })

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith(
        'You do not have permission to change roles.',
        'error',
      )
    })
  })

  it('admin sees Invite Member button', async () => {
    render(<MembersPage />)
    await screen.findByText('Creator User')
    expect(screen.getByRole('button', { name: /invite member/i })).toBeInTheDocument()
  })

  it('non-admin does not see Invite Member button', async () => {
    mockAuthContext = {
      user: { id: 'user-creator', name: 'Creator User', email: 'creator@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    render(<MembersPage />)
    await screen.findByText('Admin User')
    expect(screen.queryByRole('button', { name: /invite member/i })).not.toBeInTheDocument()
  })

  it('duplicate invite shows 409 error in UI', async () => {
    const err = Object.assign(new Error('API error: 409'), { status: 409 })
    vi.mocked(api.apiPost).mockRejectedValue(err)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /invite member/i }))

    const modal = await screen.findByTestId('modal')
    const emailInput = within(modal).getByLabelText(/email address/i)
    await userEvent.type(emailInput, 'test@example.com')
    fireEvent.click(within(modal).getByRole('button', { name: /send invite/i }))

    expect(
      await screen.findByText(/an invite is already pending for this email/i),
    ).toBeInTheDocument()
  })

  it('successful invite shows link panel with invite URL', async () => {
    const newInvite = {
      id: 'invite-1',
      email: 'new@example.com',
      role: 'CREATOR',
      expiresAt: '2024-02-01T00:00:00Z',
      token: 'abc-token-123',
    }
    vi.mocked(api.apiPost).mockResolvedValue(newInvite)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /invite member/i }))
    const modal = await screen.findByTestId('modal')
    await userEvent.type(within(modal).getByLabelText(/email address/i), 'new@example.com')
    fireEvent.click(within(modal).getByRole('button', { name: /send invite/i }))

    await waitFor(() => {
      expect(screen.getByText(/invite created/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/new@example\.com/)).toBeInTheDocument()
    expect(screen.getByDisplayValue(/\/invites\/abc-token-123\/accept/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /copy/i })).toBeInTheDocument()
  })

  it('Done button closes link panel after invite creation', async () => {
    const newInvite = {
      id: 'invite-1',
      email: 'new@example.com',
      role: 'CREATOR',
      expiresAt: '2024-02-01T00:00:00Z',
      token: 'abc-token-123',
    }
    vi.mocked(api.apiPost).mockResolvedValue(newInvite)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /invite member/i }))
    const modal = await screen.findByTestId('modal')
    await userEvent.type(within(modal).getByLabelText(/email address/i), 'new@example.com')
    fireEvent.click(within(modal).getByRole('button', { name: /send invite/i }))

    await waitFor(() => {
      expect(screen.getByText(/invite created/i)).toBeInTheDocument()
    })

    fireEvent.click(screen.getByRole('button', { name: /done/i }))
    await waitFor(() => {
      expect(screen.queryByTestId('modal')).not.toBeInTheDocument()
    })
  })

  it('shows 403 error message when user lacks admin access', async () => {
    const err = Object.assign(new Error('API error: 403'), { status: 403 })
    vi.mocked(api.apiPost).mockRejectedValue(err)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /invite member/i }))
    const modal = await screen.findByTestId('modal')
    await userEvent.type(within(modal).getByLabelText(/email address/i), 'test@example.com')
    fireEvent.click(within(modal).getByRole('button', { name: /send invite/i }))

    expect(
      await screen.findByText(/you need admin access to invite members/i),
    ).toBeInTheDocument()
  })

  it('pending invite with token shows Copy Link button', async () => {
    const pendingInvite = {
      id: 'invite-2',
      email: 'pending@example.com',
      role: 'REVIEWER',
      expiresAt: '2024-02-01T00:00:00Z',
      token: 'pending-token-456',
    }
    vi.mocked(api.apiGet).mockImplementation((path: string) => {
      if (path.includes('/members')) return Promise.resolve([adminMember, regularMember])
      if (path.includes('/invites')) return Promise.resolve([pendingInvite])
      return Promise.resolve([])
    })

    render(<MembersPage />)
    await screen.findByText('pending@example.com')

    expect(screen.getByRole('button', { name: /copy link/i })).toBeInTheDocument()
  })

  it('Copy Link button in pending invites copies URL to clipboard', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.assign(navigator, { clipboard: { writeText } })

    const pendingInvite = {
      id: 'invite-2',
      email: 'pending@example.com',
      role: 'REVIEWER',
      expiresAt: '2024-02-01T00:00:00Z',
      token: 'pending-token-456',
    }
    vi.mocked(api.apiGet).mockImplementation((path: string) => {
      if (path.includes('/members')) return Promise.resolve([adminMember, regularMember])
      if (path.includes('/invites')) return Promise.resolve([pendingInvite])
      return Promise.resolve([])
    })

    render(<MembersPage />)
    await screen.findByText('pending@example.com')

    fireEvent.click(screen.getByRole('button', { name: /copy link/i }))

    await waitFor(() => {
      expect(writeText).toHaveBeenCalledWith(expect.stringContaining('/invites/pending-token-456/accept'))
    })
    expect(mockShowToast).toHaveBeenCalledWith('Invite link copied to clipboard')
  })

  it('shows pending invites for admin', async () => {
    const pendingInvite = {
      id: 'invite-1',
      email: 'pending@example.com',
      role: 'REVIEWER',
      expiresAt: '2024-02-01T00:00:00Z',
    }
    vi.mocked(api.apiGet).mockImplementation((path: string) => {
      if (path.includes('/members')) return Promise.resolve([adminMember, regularMember])
      if (path.includes('/invites')) return Promise.resolve([pendingInvite])
      return Promise.resolve([])
    })

    render(<MembersPage />)
    expect(await screen.findByText('pending@example.com')).toBeInTheDocument()
    expect(screen.getByText(/pending invites/i)).toBeInTheDocument()
  })

  it('cancel invite calls DELETE and removes from list', async () => {
    const pendingInvite = {
      id: 'invite-1',
      email: 'pending@example.com',
      role: 'REVIEWER',
      expiresAt: '2024-02-01T00:00:00Z',
    }
    vi.mocked(api.apiGet).mockImplementation((path: string) => {
      if (path.includes('/members')) return Promise.resolve([adminMember, regularMember])
      if (path.includes('/invites')) return Promise.resolve([pendingInvite])
      return Promise.resolve([])
    })
    vi.mocked(api.apiDelete).mockResolvedValue(undefined)

    render(<MembersPage />)
    await screen.findByText('pending@example.com')

    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))

    await waitFor(() => {
      expect(api.apiDelete).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/invites/invite-1',
        'test-token',
      )
    })

    await waitFor(() => {
      expect(screen.queryByText('pending@example.com')).not.toBeInTheDocument()
    })
  })
})
