import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render } from '@testing-library/react'
import type { Project } from '@/types'

let mockParams: { projectId?: string } = {}
vi.mock('next/navigation', () => ({
  useParams: () => mockParams,
}))

const mockSetActiveProject = vi.fn()
const projectCtx = {
  projects: [] as Project[],
  activeProject: null as Project | null,
  setActiveProject: mockSetActiveProject,
}

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => projectCtx,
}))

// ProjectLayout now mounts PermissionsProvider, which reads auth. No token → it skips
// the members fetch, so a minimal stub is enough for these URL-sync tests.
vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: null, user: null }),
}))

import ProjectLayout from './layout'

const project: Project = {
  id: 'proj-1',
  name: 'Workspace One',
  description: null,
  createdAt: '2024-01-01',
  updatedAt: '2024-01-01',
}

describe('ProjectLayout URL sync', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockParams = {}
    projectCtx.projects = []
    projectCtx.activeProject = null
  })

  it('syncs activeProject when URL projectId matches a known workspace', () => {
    mockParams = { projectId: 'proj-1' }
    projectCtx.projects = [project]

    render(<ProjectLayout>child</ProjectLayout>)

    expect(mockSetActiveProject).toHaveBeenCalledWith(project)
  })

  it('does nothing when the URL projectId is not in the loaded list', () => {
    mockParams = { projectId: 'proj-unknown' }
    projectCtx.projects = [project]

    render(<ProjectLayout>child</ProjectLayout>)

    expect(mockSetActiveProject).not.toHaveBeenCalled()
  })

  it('does not call setActiveProject when it already matches', () => {
    mockParams = { projectId: 'proj-1' }
    projectCtx.projects = [project]
    projectCtx.activeProject = project

    render(<ProjectLayout>child</ProjectLayout>)

    expect(mockSetActiveProject).not.toHaveBeenCalled()
  })

  it('renders children unchanged', () => {
    mockParams = { projectId: 'proj-1' }
    projectCtx.projects = [project]

    const { getByText } = render(<ProjectLayout><span>hello</span></ProjectLayout>)
    expect(getByText('hello')).toBeTruthy()
  })
})
