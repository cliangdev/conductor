import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('next/navigation', () => ({
  useRouter: () => mockRouter,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token', user: null, loading: false }),
}))

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => ({
    addProject: mockAddProject,
    setActiveProject: mockSetActiveProject,
    projects: [],
    activeProject: null,
    loading: false,
  }),
}))

vi.mock('@/contexts/OrgContext', () => ({
  useOrg: () => ({ activeOrg: mockActiveOrg }),
}))

vi.mock('@/lib/api', () => ({
  apiPost: vi.fn(),
}))

import * as api from '@/lib/api'
import NewProjectPage from './page'

const mockRouter = { push: vi.fn(), back: vi.fn(), replace: vi.fn() }
const mockAddProject = vi.fn()
const mockSetActiveProject = vi.fn()
let mockActiveOrg: { id: string; name: string } | null = { id: 'org-1', name: 'Test Org' }

describe('NewProjectPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockActiveOrg = { id: 'org-1', name: 'Test Org' }
  })

  it('renders form fields', () => {
    render(<NewProjectPage />)
    expect(screen.getByLabelText(/project name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/description/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create project/i })).toBeInTheDocument()
  })

  it('shows error when name is empty on submit', async () => {
    render(<NewProjectPage />)
    fireEvent.click(screen.getByRole('button', { name: /create project/i }))
    expect(await screen.findByText(/project name is required/i)).toBeInTheDocument()
    expect(api.apiPost).not.toHaveBeenCalled()
  })

  it('shows error when name exceeds 100 characters', async () => {
    render(<NewProjectPage />)
    const longName = 'a'.repeat(101)
    await userEvent.type(screen.getByLabelText(/project name/i), longName)
    fireEvent.click(screen.getByRole('button', { name: /create project/i }))
    expect(await screen.findByText(/100 characters or fewer/i)).toBeInTheDocument()
    expect(api.apiPost).not.toHaveBeenCalled()
  })

  it('successful creation calls addProject, setActiveProject, and navigates', async () => {
    const createdProject = {
      id: 'proj-1',
      name: 'My Project',
      description: null,
      orgId: 'org-1',
      createdBy: 'user-1',
      createdAt: '2024-01-01T00:00:00Z',
    }
    vi.mocked(api.apiPost).mockResolvedValue(createdProject)

    render(<NewProjectPage />)
    await userEvent.type(screen.getByLabelText(/project name/i), 'My Project')
    fireEvent.click(screen.getByRole('button', { name: /create project/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/projects',
        { name: 'My Project', orgId: 'org-1' },
        'test-token',
      )
    })

    expect(mockAddProject).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'proj-1', name: 'My Project', orgId: 'org-1' }),
    )
    expect(mockSetActiveProject).toHaveBeenCalledWith(
      expect.objectContaining({ id: 'proj-1', name: 'My Project', orgId: 'org-1' }),
    )
    expect(mockRouter.push).toHaveBeenCalledWith('/app/projects/proj-1/issues')
  })

  it('sends orgId from activeOrg so the project appears in the correct org dropdown', async () => {
    mockActiveOrg = { id: 'org-42', name: 'My Org' }
    vi.mocked(api.apiPost).mockResolvedValue({
      id: 'proj-2',
      name: 'Scoped Project',
      description: null,
      orgId: 'org-42',
      createdBy: 'user-1',
      createdAt: '2024-01-01T00:00:00Z',
    })

    render(<NewProjectPage />)
    await userEvent.type(screen.getByLabelText(/project name/i), 'Scoped Project')
    fireEvent.click(screen.getByRole('button', { name: /create project/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/projects',
        expect.objectContaining({ orgId: 'org-42' }),
        'test-token',
      )
    })
    expect(mockAddProject).toHaveBeenCalledWith(expect.objectContaining({ orgId: 'org-42' }))
  })

  it('omits orgId from request when no org is active', async () => {
    mockActiveOrg = null
    vi.mocked(api.apiPost).mockResolvedValue({
      id: 'proj-3',
      name: 'Unscoped',
      description: null,
      orgId: null,
      createdBy: 'user-1',
      createdAt: '2024-01-01T00:00:00Z',
    })

    render(<NewProjectPage />)
    await userEvent.type(screen.getByLabelText(/project name/i), 'Unscoped')
    fireEvent.click(screen.getByRole('button', { name: /create project/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/projects',
        expect.objectContaining({ name: 'Unscoped' }),
        'test-token',
      )
    })
  })

  it('shows server error message when API call fails', async () => {
    vi.mocked(api.apiPost).mockRejectedValue(new Error('Network error'))

    render(<NewProjectPage />)
    await userEvent.type(screen.getByLabelText(/project name/i), 'My Project')
    fireEvent.click(screen.getByRole('button', { name: /create project/i }))

    expect(await screen.findByText(/failed to create project/i)).toBeInTheDocument()
    expect(mockRouter.push).not.toHaveBeenCalled()
  })
})
