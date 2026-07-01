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

vi.mock('@/lib/api', () => ({
  apiPost: vi.fn(),
}))

import * as api from '@/lib/api'
import NewWorkspacePage from './page'

const mockRouter = { push: vi.fn(), back: vi.fn(), replace: vi.fn() }
const mockAddProject = vi.fn()
const mockSetActiveProject = vi.fn()

describe('NewWorkspacePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders form fields', () => {
    render(<NewWorkspacePage />)
    expect(screen.getByLabelText(/workspace name/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/description/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /create workspace/i })).toBeInTheDocument()
  })

  it('shows error when name is empty on submit', async () => {
    render(<NewWorkspacePage />)
    fireEvent.click(screen.getByRole('button', { name: /create workspace/i }))
    expect(await screen.findByText(/workspace name is required/i)).toBeInTheDocument()
    expect(api.apiPost).not.toHaveBeenCalled()
  })

  it('shows error when name exceeds 100 characters', async () => {
    render(<NewWorkspacePage />)
    const longName = 'a'.repeat(101)
    await userEvent.type(screen.getByLabelText(/workspace name/i), longName)
    fireEvent.click(screen.getByRole('button', { name: /create workspace/i }))
    expect(await screen.findByText(/100 characters or fewer/i)).toBeInTheDocument()
    expect(api.apiPost).not.toHaveBeenCalled()
  })

  it('successful creation posts without orgId, adds, sets active, and navigates', async () => {
    const created = {
      id: 'proj-1',
      name: 'My Workspace',
      description: null,
      createdAt: '2024-01-01T00:00:00Z',
      updatedAt: '2024-01-01T00:00:00Z',
    }
    vi.mocked(api.apiPost).mockResolvedValue(created)

    render(<NewWorkspacePage />)
    await userEvent.type(screen.getByLabelText(/workspace name/i), 'My Workspace')
    fireEvent.click(screen.getByRole('button', { name: /create workspace/i }))

    await waitFor(() => {
      expect(api.apiPost).toHaveBeenCalledWith(
        '/api/v1/projects',
        { name: 'My Workspace' },
        'test-token',
      )
    })

    expect(mockAddProject).toHaveBeenCalledWith(created)
    expect(mockSetActiveProject).toHaveBeenCalledWith(created)
    expect(mockRouter.push).toHaveBeenCalledWith('/app/projects/proj-1/engineering/issues')
  })

  it('shows server error message when API call fails', async () => {
    vi.mocked(api.apiPost).mockRejectedValue(new Error('Network error'))

    render(<NewWorkspacePage />)
    await userEvent.type(screen.getByLabelText(/workspace name/i), 'My Workspace')
    fireEvent.click(screen.getByRole('button', { name: /create workspace/i }))

    expect(await screen.findByText(/failed to create workspace/i)).toBeInTheDocument()
    expect(mockRouter.push).not.toHaveBeenCalled()
  })
})
