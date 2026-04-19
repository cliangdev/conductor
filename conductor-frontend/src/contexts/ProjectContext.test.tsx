import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, act, waitFor } from '@testing-library/react'
import { ProjectProvider, useProject } from './ProjectContext'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

import * as api from '@/lib/api'

const mockProjects = [
  { id: 'proj-1', name: 'Project Alpha', description: null, createdAt: '2024-01-01', updatedAt: '2024-01-01' },
  { id: 'proj-2', name: 'Project Beta', description: null, createdAt: '2024-01-02', updatedAt: '2024-01-02' },
]

function TestConsumer({ onValues }: { onValues: (v: ReturnType<typeof useProject>) => void }) {
  const values = useProject()
  onValues(values)
  return null
}

describe('ProjectContext', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  it('fetches projects on mount when accessToken is available', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    let captured: ReturnType<typeof useProject> | null = null

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(captured?.loading).toBe(false))

    expect(captured?.projects).toEqual(mockProjects)
    expect(api.apiGet).toHaveBeenCalledWith('/api/v1/projects', 'test-token')
  })

  it('restores active project from localStorage on mount', async () => {
    localStorage.setItem('active_project_id', 'proj-2')
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    let captured: ReturnType<typeof useProject> | null = null

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(captured?.loading).toBe(false))

    expect(captured?.activeProject?.id).toBe('proj-2')
    expect(captured?.activeProject?.name).toBe('Project Beta')
  })

  it('sets active project and persists to localStorage', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    let captured: ReturnType<typeof useProject> | null = null

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(captured?.loading).toBe(false))

    act(() => {
      captured?.setActiveProject(mockProjects[0])
    })

    expect(captured?.activeProject?.id).toBe('proj-1')
    expect(localStorage.getItem('active_project_id')).toBe('proj-1')
  })

  it('does not restore active project if id not in fetched projects', async () => {
    localStorage.setItem('active_project_id', 'proj-999')
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    let captured: ReturnType<typeof useProject> | null = null

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(captured?.loading).toBe(false))

    expect(captured?.activeProject).toBeNull()
  })

  it('addProject appends a new project without refetching', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([mockProjects[0]])
    let captured: ReturnType<typeof useProject> | null = null

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { captured = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(captured?.loading).toBe(false))
    expect(captured?.projects).toHaveLength(1)

    act(() => { captured?.addProject(mockProjects[1]) })

    expect(captured?.projects).toHaveLength(2)
    expect(captured?.projects[1].id).toBe('proj-2')
    expect(api.apiGet).toHaveBeenCalledTimes(1)
  })

  it('setActiveProject reference is stable across re-renders', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    const refs: Array<ReturnType<typeof useProject>['setActiveProject']> = []

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { refs.push(v.setActiveProject) }} />
      </ProjectProvider>
    )

    await waitFor(() => refs.length >= 2)

    expect(refs[0]).toBe(refs[refs.length - 1])
  })

  it('addProject reference is stable across re-renders', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    const refs: Array<ReturnType<typeof useProject>['addProject']> = []

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { refs.push(v.addProject) }} />
      </ProjectProvider>
    )

    await waitFor(() => refs.length >= 2)

    expect(refs[0]).toBe(refs[refs.length - 1])
  })
})
