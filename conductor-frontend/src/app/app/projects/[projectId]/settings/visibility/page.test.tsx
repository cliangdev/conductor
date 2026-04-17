import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPatch: vi.fn(),
}))

import * as api from '@/lib/api'
import VisibilitySettingsPage from './page'

const mockShowToast = vi.fn()

const adminMember = {
  userId: 'user-admin',
  name: 'Admin User',
  email: 'admin@example.com',
  avatarUrl: null,
  role: 'ADMIN',
  joinedAt: '2024-01-01T00:00:00Z',
}

const sampleProject = {
  id: 'proj-1',
  name: 'Test Project',
  description: null,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
  visibility: 'PRIVATE',
  teamId: null,
}

const sampleProjectWithTeam = {
  ...sampleProject,
  visibility: 'TEAM',
  teamId: 'team-1',
}

let mockAuthContext = {
  user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
  accessToken: 'test-token',
  loading: false,
}

describe('VisibilitySettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockAuthContext = {
      user: { id: 'user-admin', name: 'Admin User', email: 'admin@example.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
    vi.mocked(api.apiGet).mockImplementation((path: string) => {
      if (path.includes('/members')) return Promise.resolve([adminMember])
      if (path.includes('/projects/proj-1') && !path.includes('/members')) return Promise.resolve(sampleProject)
      return Promise.resolve(sampleProject)
    })
  })

  it('renders Visibility heading and all four options', async () => {
    render(<VisibilitySettingsPage />)
    expect(await screen.findByText('Visibility')).toBeInTheDocument()
    expect(screen.getByLabelText(/private/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/org/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/team/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/public/i)).toBeInTheDocument()
  })

  it('selects the current project visibility', async () => {
    render(<VisibilitySettingsPage />)
    await screen.findByText('Visibility')
    const privateRadio = screen.getByLabelText(/private/i) as HTMLInputElement
    expect(privateRadio.checked).toBe(true)
  })

  it('TEAM option is disabled when project has no teamId', async () => {
    render(<VisibilitySettingsPage />)
    await screen.findByText('Visibility')
    const teamRadio = screen.getByLabelText(/team/i) as HTMLInputElement
    expect(teamRadio.disabled).toBe(true)
  })

  it('TEAM option is enabled when project has a teamId', async () => {
    vi.mocked(api.apiGet).mockImplementation((path: string) => {
      if (path.includes('/members')) return Promise.resolve([adminMember])
      return Promise.resolve(sampleProjectWithTeam)
    })
    render(<VisibilitySettingsPage />)
    await screen.findByText('Visibility')
    const teamRadio = screen.getByLabelText(/team/i) as HTMLInputElement
    expect(teamRadio.disabled).toBe(false)
  })

  it('changing visibility calls apiPatch and shows success toast', async () => {
    vi.mocked(api.apiPatch).mockResolvedValue({ ...sampleProject, visibility: 'ORG' })
    render(<VisibilitySettingsPage />)
    await screen.findByText('Visibility')

    fireEvent.click(screen.getByLabelText(/^org$/i))

    await waitFor(() => {
      expect(api.apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1',
        { visibility: 'ORG' },
        'test-token',
      )
    })

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith('Visibility updated', 'success')
    })
  })

  it('reverts selection and shows error toast on patch failure', async () => {
    const err = Object.assign(new Error('API error: 500'), { status: 500 })
    vi.mocked(api.apiPatch).mockRejectedValue(err)

    render(<VisibilitySettingsPage />)
    await screen.findByText('Visibility')

    fireEvent.click(screen.getByLabelText(/^org$/i))

    await waitFor(() => {
      expect(mockShowToast).toHaveBeenCalledWith('Failed to update visibility. Please try again.', 'error')
    })

    await waitFor(() => {
      const privateRadio = screen.getByLabelText(/private/i) as HTMLInputElement
      expect(privateRadio.checked).toBe(true)
    })
  })

  it('non-admin sees permission denied message', async () => {
    vi.mocked(api.apiGet).mockImplementation((path: string) => {
      if (path.includes('/members')) return Promise.resolve([{ ...adminMember, userId: 'someone-else' }])
      return Promise.resolve(sampleProject)
    })
    render(<VisibilitySettingsPage />)
    expect(await screen.findByText(/you don't have permission to manage settings/i)).toBeInTheDocument()
  })

  it('shows loading state initially', () => {
    vi.mocked(api.apiGet).mockImplementation(() => new Promise(() => {}))
    render(<VisibilitySettingsPage />)
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })
})
