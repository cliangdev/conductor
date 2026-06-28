import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => mockRouter,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => mockAuthContext,
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({
    activeProject: { id: 'proj-1', name: 'Test Workspace', description: null, createdAt: '', updatedAt: '' },
    updateProject: mockUpdateProject,
    removeProject: mockRemoveProject,
  }),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  usePermissions: () => ({
    role: mockCanManageWorkspace ? 'ADMIN' : 'CREATOR',
    loading: false,
    can: (cap: string) => (cap === 'workspace.manage' ? mockCanManageWorkspace : false),
    refresh: vi.fn(),
  }),
}))

vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast: mockShowToast }),
}))

vi.mock('@/components/ui/modal', () => ({
  Modal: ({ open, children, title }: { open: boolean; children: React.ReactNode; title: string }) =>
    open ? (
      <div data-testid="modal" data-title={title}>
        {children}
      </div>
    ) : null,
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
}))

import * as api from '@/lib/api'
import GeneralSettingsPage from './page'

const mockRouter = { push: vi.fn() }
const mockUpdateProject = vi.fn()
const mockRemoveProject = vi.fn()
const mockShowToast = vi.fn()

let mockAuthContext = {
  user: { id: 'user-admin', name: 'Admin', email: 'a@x.com', avatarUrl: null, displayName: null },
  accessToken: 'test-token',
  loading: false,
}

let mockCanManageWorkspace = true

describe('GeneralSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCanManageWorkspace = true
    mockAuthContext = {
      user: { id: 'user-admin', name: 'Admin', email: 'a@x.com', avatarUrl: null, displayName: null },
      accessToken: 'test-token',
      loading: false,
    }
  })

  it('renders the workspace name in an editable field', async () => {
    render(<GeneralSettingsPage />)
    await waitFor(() => {
      expect((screen.getByLabelText(/workspace name/i) as HTMLInputElement).value).toBe('Test Workspace')
    })
  })

  it('admin can rename the workspace via PATCH', async () => {
    vi.mocked(api.apiPatch).mockResolvedValue({
      id: 'proj-1', name: 'Renamed', description: null, createdAt: '', updatedAt: '',
    })

    render(<GeneralSettingsPage />)
    const input = await screen.findByLabelText(/workspace name/i)
    fireEvent.change(input, { target: { value: 'Renamed' } })
    fireEvent.click(screen.getByRole('button', { name: /^save$/i }))

    await waitFor(() => {
      expect(api.apiPatch).toHaveBeenCalledWith(
        '/api/v1/projects/proj-1',
        { name: 'Renamed' },
        'test-token',
      )
    })
    expect(mockUpdateProject).toHaveBeenCalled()
    expect(mockShowToast).toHaveBeenCalledWith('Workspace renamed')
  })

  it('admin sees both Leave and Delete in the danger zone', async () => {
    render(<GeneralSettingsPage />)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^leave$/i })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /^delete$/i })).toBeInTheDocument()
    })
  })

  it('non-admin sees Leave but not Delete and cannot edit the name', async () => {
    mockCanManageWorkspace = false

    render(<GeneralSettingsPage />)
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /^leave$/i })).toBeInTheDocument()
    })
    expect(screen.queryByRole('button', { name: /^delete$/i })).not.toBeInTheDocument()
    expect(screen.getByLabelText(/workspace name/i)).toBeDisabled()
  })

  it('deleting calls DELETE and routes to /app/projects', async () => {
    vi.mocked(api.apiDelete).mockResolvedValue(undefined)

    render(<GeneralSettingsPage />)
    await waitFor(() => screen.getByRole('button', { name: /^delete$/i }))
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }))

    const modal = await screen.findByTestId('modal')
    fireEvent.click(within(modal).getByRole('button', { name: /delete workspace/i }))

    await waitFor(() => {
      expect(api.apiDelete).toHaveBeenCalledWith('/api/v1/projects/proj-1', 'test-token')
    })
    expect(mockRemoveProject).toHaveBeenCalledWith('proj-1')
    expect(mockRouter.push).toHaveBeenCalledWith('/app/projects')
  })

  it('leaving calls DELETE on members/me and routes away', async () => {
    vi.mocked(api.apiDelete).mockResolvedValue(undefined)

    render(<GeneralSettingsPage />)
    await waitFor(() => screen.getByRole('button', { name: /^leave$/i }))
    fireEvent.click(screen.getByRole('button', { name: /^leave$/i }))

    const modal = await screen.findByTestId('modal')
    fireEvent.click(within(modal).getByRole('button', { name: /^leave$/i }))

    await waitFor(() => {
      expect(api.apiDelete).toHaveBeenCalledWith('/api/v1/projects/proj-1/members/user-admin', 'test-token')
    })
    expect(mockRouter.push).toHaveBeenCalledWith('/app/projects')
  })
})
