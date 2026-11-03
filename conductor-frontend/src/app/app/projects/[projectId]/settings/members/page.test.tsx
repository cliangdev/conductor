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

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({
    roles: mockCanManageMembers ? ['ADMIN'] : ['CREATOR'],
    loading: false,
    can: (cap: string) => (cap === 'members.manage' ? mockCanManageMembers : false),
    refresh: vi.fn(),
  }),
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
let mockCanManageMembers = true

const adminMember = {
  userId: 'user-admin',
  name: 'Admin User',
  email: 'admin@example.com',
  avatarUrl: null,
  role: 'ADMIN',
  roles: ['ADMIN'],
  joinedAt: '2024-01-01T00:00:00Z',
}

const regularMember = {
  userId: 'user-creator',
  name: 'Creator User',
  email: 'creator@example.com',
  avatarUrl: null,
  role: 'CREATOR',
  roles: ['CREATOR'],
  joinedAt: '2024-01-15T00:00:00Z',
}

const pendingInvite = {
  id: 'invite-1',
  email: 'pending@example.com',
  role: 'REVIEWER',
  roles: ['REVIEWER'],
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
    mockCanManageMembers = true
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

  it('admin user sees role checkboxes and remove button for other members', async () => {
    render(<MembersPage />)
    await screen.findByText('Creator User')
    expect(screen.getByLabelText('Admin role for Creator User')).toBeInTheDocument()
    expect(screen.getByLabelText('Editor role for Creator User')).toBeChecked()
    expect(screen.getByLabelText('Reviewer role for Creator User')).not.toBeChecked()
    expect(screen.getByRole('button', { name: /remove creator user/i })).toBeInTheDocument()
  })

  it('non-admin user does not see role dropdown or invite button', async () => {
    mockCanManageMembers = false
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

  it('ticking Reviewer sends the whole set as { roles }', async () => {
    vi.mocked(api.apiPatch).mockResolvedValue({ ...regularMember, roles: ['CREATOR', 'REVIEWER'] })

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByLabelText('Reviewer role for Creator User'))

    await waitFor(() => {
      expect(api.apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/members/user-creator',
        { roles: ['CREATOR', 'REVIEWER'] },
        'test-token',
      )
    })
    expect(mockShowToast).toHaveBeenCalledWith('Role updated successfully')
    await waitFor(() => expect(screen.getByLabelText('Reviewer role for Creator User')).toBeChecked())
    expect(screen.getByLabelText('Editor role for Creator User')).toBeChecked()
  })

  it('unticking the last role leaves a read-only Viewer and says so', async () => {
    vi.mocked(api.apiPatch).mockResolvedValue({ ...regularMember, roles: [] })

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByLabelText('Editor role for Creator User'))

    await waitFor(() => {
      expect(api.apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/members/user-creator',
        { roles: [] },
        'test-token',
      )
    })
    expect(await screen.findByText('Viewer (read-only)')).toBeInTheDocument()
  })

  it('Admin implies the other two: they show checked and disabled', async () => {
    vi.mocked(api.apiPatch).mockResolvedValue({ ...regularMember, roles: ['ADMIN'] })

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByLabelText('Admin role for Creator User'))

    await waitFor(() => {
      expect(api.apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/members/user-creator',
        { roles: ['ADMIN', 'CREATOR'] },
        'test-token',
      )
    })
    await waitFor(() => expect(screen.getByLabelText('Admin role for Creator User')).toBeChecked())
    const editor = screen.getByLabelText('Editor role for Creator User')
    const reviewer = screen.getByLabelText('Reviewer role for Creator User')
    expect(editor).toBeChecked()
    expect(editor).toBeDisabled()
    expect(reviewer).toBeChecked()
    expect(reviewer).toBeDisabled()
  })

  it('unticking Admin keeps the Editor and Reviewer that were shown as implied', async () => {
    const onlyAdmin = { ...regularMember, role: 'ADMIN', roles: ['ADMIN'] }
    vi.mocked(api.apiGet).mockImplementation((path: string) =>
      Promise.resolve(path.includes('/invites') ? [] : [adminMember, onlyAdmin]),
    )
    vi.mocked(api.apiPatch).mockResolvedValue({ ...onlyAdmin, roles: ['CREATOR', 'REVIEWER'] })
    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByLabelText('Admin role for Creator User'))

    await waitFor(() => {
      expect(api.apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/members/user-creator',
        { roles: ['CREATOR', 'REVIEWER'] },
        'test-token',
      )
    })
    await waitFor(() => expect(screen.getByLabelText('Editor role for Creator User')).not.toBeDisabled())
    expect(screen.getByLabelText('Editor role for Creator User')).toBeChecked()
    expect(screen.getByLabelText('Reviewer role for Creator User')).toBeChecked()
  })

  it('two quick clicks are sent one after the other, the second carrying both changes', async () => {
    let releaseFirst: (m: unknown) => void = () => {}
    vi.mocked(api.apiPatch)
      .mockImplementationOnce(() => new Promise((res) => { releaseFirst = res }))
      .mockResolvedValue({ ...regularMember, roles: ['ADMIN', 'CREATOR', 'REVIEWER'] })
    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByLabelText('Reviewer role for Creator User'))
    fireEvent.click(screen.getByLabelText('Admin role for Creator User'))

    // Both show at once, before any request has come back.
    expect(screen.getByLabelText('Reviewer role for Creator User')).toBeChecked()
    expect(screen.getByLabelText('Admin role for Creator User')).toBeChecked()
    await waitFor(() => expect(api.apiPatch).toHaveBeenCalledTimes(1))

    releaseFirst({ ...regularMember, roles: ['CREATOR', 'REVIEWER'] })
    await waitFor(() => expect(api.apiPatch).toHaveBeenCalledTimes(2))
    expect(vi.mocked(api.apiPatch).mock.calls[0][1]).toEqual({ roles: ['CREATOR', 'REVIEWER'] })
    expect(vi.mocked(api.apiPatch).mock.calls[1][1]).toEqual({ roles: ['ADMIN', 'CREATOR', 'REVIEWER'] })
  })

  it('rolls back to the last confirmed roles and says so when a change is refused', async () => {
    vi.mocked(api.apiPatch).mockRejectedValue(Object.assign(new Error('no'), { detail: 'Cannot remove the last admin.' }))
    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByLabelText('Reviewer role for Creator User'))
    expect(screen.getByLabelText('Reviewer role for Creator User')).toBeChecked()

    await waitFor(() => expect(mockShowToast).toHaveBeenCalledWith('Cannot remove the last admin.', 'error'))
    expect(screen.getByLabelText('Reviewer role for Creator User')).not.toBeChecked()
    expect(screen.getByLabelText('Editor role for Creator User')).toBeChecked()
  })

  it('does not offer Admin on an invite', async () => {
    render(<MembersPage />)
    await screen.findByText('Creator User')
    fireEvent.click(screen.getByRole('button', { name: /invite member/i }))
    const modal = await screen.findByTestId('modal')
    expect(within(modal).queryByLabelText('Admin')).not.toBeInTheDocument()
    expect(within(modal).getByLabelText('Editor')).toBeInTheDocument()
    expect(within(modal).getByLabelText('Reviewer')).toBeInTheDocument()
  })

  it('a member from a server that sends only the legacy role still shows their role', async () => {
    const legacy = { ...regularMember, roles: undefined, role: 'REVIEWER' }
    vi.mocked(api.apiGet).mockImplementation((path: string) =>
      Promise.resolve(path.includes('/invites') ? [] : [adminMember, legacy]),
    )
    render(<MembersPage />)
    await screen.findByText('Creator User')
    expect(screen.getByLabelText('Reviewer role for Creator User')).toBeChecked()
    expect(screen.getByLabelText('Editor role for Creator User')).not.toBeChecked()
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

  it('the invite dialog defaults to Editor + Reviewer and lets the roles be changed', async () => {
    vi.mocked(api.apiPost).mockResolvedValue({ id: 'i3', email: 'a@b.co', roles: ['REVIEWER'], expiresAt: 'x', token: 't' })

    render(<MembersPage />)
    await screen.findByText('Creator User')
    fireEvent.click(screen.getByRole('button', { name: /invite member/i }))
    const modal = await screen.findByTestId('modal')

    expect(within(modal).getByLabelText('Editor')).toBeChecked()
    expect(within(modal).getByLabelText('Reviewer')).toBeChecked()

    fireEvent.click(within(modal).getByLabelText('Editor'))
    fireEvent.change(within(modal).getByLabelText(/email/i), { target: { value: 'a@b.co' } })
    fireEvent.click(within(modal).getByRole('button', { name: /send invite/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/invites',
        { email: 'a@b.co', roles: ['REVIEWER'] },
        'test-token',
      )
    })
  })

  it('pending invitations show the label for their role set', async () => {
    mockApiGet([{ ...pendingInvite, roles: ['CREATOR', 'REVIEWER'] }])
    render(<MembersPage />)
    expect(await screen.findByText('Editor · Reviewer')).toBeInTheDocument()
  })

  it('email invite posts to invites endpoint and shows shareable link', async () => {
    vi.mocked(api.apiPost).mockResolvedValue({
      id: 'invite-2',
      email: 'new@example.com',
      roles: ['CREATOR', 'REVIEWER'],
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
        { email: 'new@example.com', roles: ['CREATOR', 'REVIEWER'] },
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
