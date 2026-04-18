import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render } from '@testing-library/react'
import type { Project, Org } from '@/types'

let mockParams: { projectId?: string } = {}
vi.mock('next/navigation', () => ({
  useParams: () => mockParams,
}))

const mockSetActiveProject = vi.fn()
const mockSetActiveOrg = vi.fn()
const projectCtx = {
  projects: [] as Project[],
  activeProject: null as Project | null,
  setActiveProject: mockSetActiveProject,
}
const orgCtx = {
  orgs: [] as Org[],
  activeOrg: null as Org | null,
  setActiveOrg: mockSetActiveOrg,
}

vi.mock('@/contexts/ProjectContext', () => ({
  useProject: () => projectCtx,
}))
vi.mock('@/contexts/OrgContext', () => ({
  useOrg: () => orgCtx,
}))

import ProjectLayout from './layout'

const orgAlpha: Org = { id: 'org-alpha', name: 'Alpha', slug: 'alpha', createdAt: '2024-01-01' }
const orgBeta: Org = { id: 'org-beta', name: 'Beta', slug: 'beta', createdAt: '2024-01-01' }
const projectInAlpha: Project = {
  id: 'proj-1',
  name: 'Alpha Project',
  description: null,
  createdAt: '2024-01-01',
  updatedAt: '2024-01-01',
  orgId: 'org-alpha',
}

describe('ProjectLayout URL sync', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockParams = {}
    projectCtx.projects = []
    projectCtx.activeProject = null
    orgCtx.orgs = []
    orgCtx.activeOrg = null
  })

  it('syncs activeProject and activeOrg when URL projectId matches a known project', () => {
    mockParams = { projectId: 'proj-1' }
    projectCtx.projects = [projectInAlpha]
    orgCtx.orgs = [orgAlpha, orgBeta]
    orgCtx.activeOrg = orgBeta

    render(<ProjectLayout>child</ProjectLayout>)

    expect(mockSetActiveProject).toHaveBeenCalledWith(projectInAlpha)
    expect(mockSetActiveOrg).toHaveBeenCalledWith(orgAlpha)
  })

  it('does nothing when the URL projectId is not in the loaded projects list', () => {
    mockParams = { projectId: 'proj-unknown' }
    projectCtx.projects = [projectInAlpha]
    orgCtx.orgs = [orgAlpha]

    render(<ProjectLayout>child</ProjectLayout>)

    expect(mockSetActiveProject).not.toHaveBeenCalled()
    expect(mockSetActiveOrg).not.toHaveBeenCalled()
  })

  it('does not call setActiveOrg when the active org already matches', () => {
    mockParams = { projectId: 'proj-1' }
    projectCtx.projects = [projectInAlpha]
    orgCtx.orgs = [orgAlpha]
    orgCtx.activeOrg = orgAlpha
    projectCtx.activeProject = projectInAlpha

    render(<ProjectLayout>child</ProjectLayout>)

    expect(mockSetActiveProject).not.toHaveBeenCalled()
    expect(mockSetActiveOrg).not.toHaveBeenCalled()
  })

  it('does not call setActiveOrg when the project has no orgId', () => {
    mockParams = { projectId: 'proj-legacy' }
    const legacyProject: Project = { ...projectInAlpha, id: 'proj-legacy', orgId: null }
    projectCtx.projects = [legacyProject]
    orgCtx.orgs = [orgAlpha]
    orgCtx.activeOrg = orgAlpha

    render(<ProjectLayout>child</ProjectLayout>)

    expect(mockSetActiveProject).toHaveBeenCalledWith(legacyProject)
    expect(mockSetActiveOrg).not.toHaveBeenCalled()
  })

  it('renders children unchanged', () => {
    mockParams = { projectId: 'proj-1' }
    projectCtx.projects = [projectInAlpha]
    orgCtx.orgs = [orgAlpha]

    const { getByText } = render(<ProjectLayout><span>hello</span></ProjectLayout>)
    expect(getByText('hello')).toBeTruthy()
  })
})
