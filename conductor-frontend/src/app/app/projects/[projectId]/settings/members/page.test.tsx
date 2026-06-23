import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({ activeProject: { id: 'proj-1', name: 'Test Workspace' } }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => {
    const detail = (err as { detail?: unknown })?.detail
    return typeof detail === 'string' && detail.trim() ? detail : fallback
  },
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

const pendingInvite = {
  id: 'invite-1',
  email: 'pending@example.com',
  role: 'REVIEWER',
  expiresAt: '2024-02-01T00:00:00Z',
}

let mockAuthContext = {
  user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
  accessToken: 'test-token',
  loading: false,
}

function mockApiGet(invites = [pendingInvite]) {
  vi.mocked(api.apiGet).mockImplementation((path: string) => {
    if (path.includes('/invites')) return Promise.resolve(invites)
    return Promise.resolve([adminMember, regularMember])
  })
}

describe('MembersPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuthContext = {
      user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    mockApiGet()
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

  it('non-admin user does not see role dropdown or invite button', async () => {
    mockAuthContext = {
      user: { id: 'user-creator', name: 'Creator User', email: 'creator@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    render(<MembersPage />)
    await screen.findByText('Admin User')
    expect(screen.queryByLabelText(/role for/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /invite member/i })).not.toBeInTheDocument()
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

  it('admin sees Invite member button', async () => {
    render(<MembersPage />)
    await screen.findByText('Creator User')
    expect(screen.getByRole('button', { name: /invite member/i })).toBeInTheDocument()
  })

  it('renders pending invitations list for admins', async () => {
    render(<MembersPage />)
    expect(await screen.findByText('pending@example.com')).toBeInTheDocument()
    expect(screen.getByText(/pending invitations/i)).toBeInTheDocument()
  })

  it('email invite posts to invites endpoint and shows shareable link', async () => {
    vi.mocked(api.apiPost).mockResolvedValue({
      id: 'invite-2',
      email: 'new@example.com',
      role: 'CREATOR',
      expiresAt: '2024-02-01T00:00:00Z',
      token: 'tok-abc',
    })

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /invite member/i }))
    const modal = await screen.findByTestId('modal')

    const emailInput = within(modal).getByLabelText(/email/i)
    fireEvent.change(emailInput, { target: { value: 'new@example.com' } })
    fireEvent.click(within(modal).getByRole('button', { name: /send invite/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/invites',
        { email: 'new@example.com', role: 'CREATOR' },
        'test-token',
      )
    })

    expect(await within(modal).findByDisplayValue(/\/invites\/tok-abc\/accept$/)).toBeInTheDocument()
    expect(mockShowToast).toHaveBeenCalledWith('Invitation sent')
  })

  it('surfaces the backend error message when the invitee is already a member or invited', async () => {
    // The backend (ConflictException) sends a human message in ProblemDetail.detail; the UI surfaces it.
    const err = Object.assign(new Error('conflict'), {
      status: 409,
      detail: 'That person is already a member or has a pending invite.',
    })
    vi.mocked(api.apiPost).mockRejectedValue(err)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /invite member/i }))
    const modal = await screen.findByTestId('modal')

    fireEvent.change(within(modal).getByLabelText(/email/i), { target: { value: 'dup@example.com' } })
    fireEvent.click(within(modal).getByRole('button', { name: /send invite/i }))

    expect(await screen.findByText(/already a member or has a pending invite/i)).toBeInTheDocument()
  })

  it('cancelling a pending invite calls DELETE and removes it', async () => {
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
