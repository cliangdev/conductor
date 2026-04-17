import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({ activeProject: { id: 'proj-1', name: 'Test Project', orgId: 'org-1' } }),
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

const orgOnlyMember = {
  userId: 'user-org-only',
  name: 'Org Member',
  email: 'orgmember@example.com',
  role: 'MEMBER',
  joinedAt: '2024-01-01T00:00:00Z',
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
      if (path.includes('/orgs/')) return Promise.resolve([adminMember, regularMember, orgOnlyMember])
      return Promise.resolve([adminMember, regularMember])
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

  it('admin sees Add Member button', async () => {
    render(<MembersPage />)
    await screen.findByText('Creator User')
    expect(screen.getByRole('button', { name: /add member/i })).toBeInTheDocument()
  })

  it('non-admin does not see Add Member button', async () => {
    mockAuthContext = {
      user: { id: 'user-creator', name: 'Creator User', email: 'creator@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    render(<MembersPage />)
    await screen.findByText('Admin User')
    expect(screen.queryByRole('button', { name: /add member/i })).not.toBeInTheDocument()
  })

  it('Add Member modal shows org members not already in project', async () => {
    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /add member/i }))

    const modal = await screen.findByTestId('modal')
    await waitFor(() => {
      expect(within(modal).getByText(/org member \(orgmember@example\.com\)/i)).toBeInTheDocument()
    })
    expect(within(modal).queryByText(/admin user/i)).not.toBeInTheDocument()
    expect(within(modal).queryByText(/creator user/i)).not.toBeInTheDocument()
  })

  it('successful add member posts to members endpoint and shows toast', async () => {
    vi.mocked(api.apiPost).mockResolvedValue({})

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /add member/i }))
    const modal = await screen.findByTestId('modal')

    await waitFor(() => {
      expect(within(modal).getByRole('option', { name: /org member/i })).toBeInTheDocument()
    })

    const select = within(modal).getByLabelText(/org member/i)
    fireEvent.change(select, { target: { value: 'user-org-only' } })

    fireEvent.click(within(modal).getByRole('button', { name: /^add member$/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1/members',
        { userId: 'user-org-only', role: 'CREATOR' },
        'test-token',
      )
    })
    expect(mockShowToast).toHaveBeenCalledWith('Member added')
  })

  it('shows 409 error when user is already a project member', async () => {
    const err = Object.assign(new Error('API error: 409'), { status: 409 })
    vi.mocked(api.apiPost).mockRejectedValue(err)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /add member/i }))
    const modal = await screen.findByTestId('modal')

    await waitFor(() => {
      expect(within(modal).getByRole('option', { name: /org member/i })).toBeInTheDocument()
    })

    const select = within(modal).getByLabelText(/org member/i)
    fireEvent.change(select, { target: { value: 'user-org-only' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^add member$/i }))

    expect(await screen.findByText(/user is already a project member/i)).toBeInTheDocument()
  })

  it('shows 403 error when caller is not a project admin', async () => {
    const err = Object.assign(new Error('API error: 403'), { status: 403 })
    vi.mocked(api.apiPost).mockRejectedValue(err)

    render(<MembersPage />)
    await screen.findByText('Creator User')

    fireEvent.click(screen.getByRole('button', { name: /add member/i }))
    const modal = await screen.findByTestId('modal')

    await waitFor(() => {
      expect(within(modal).getByRole('option', { name: /org member/i })).toBeInTheDocument()
    })

    const select = within(modal).getByLabelText(/org member/i)
    fireEvent.change(select, { target: { value: 'user-org-only' } })
    fireEvent.click(within(modal).getByRole('button', { name: /^add member$/i }))

    expect(await screen.findByText(/only project admins can add members/i)).toBeInTheDocument()
  })
})
