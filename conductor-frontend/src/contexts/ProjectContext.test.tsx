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
    const ref: { current: ReturnType<typeof useProject> | null } = { current: null }

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(ref.current?.loading).toBe(false))

    expect(ref.current?.projects).toEqual(mockProjects)
    expect(api.apiGet).toHaveBeenCalledWith('/api/v1/projects', 'test-token')
  })

  it('restores active project from localStorage on mount', async () => {
    localStorage.setItem('active_project_id', 'proj-2')
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    const ref: { current: ReturnType<typeof useProject> | null } = { current: null }

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(ref.current?.loading).toBe(false))

    expect(ref.current?.activeProject?.id).toBe('proj-2')
    expect(ref.current?.activeProject?.name).toBe('Project Beta')
  })

  it('sets active project and persists to localStorage', async () => {
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    const ref: { current: ReturnType<typeof useProject> | null } = { current: null }

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(ref.current?.loading).toBe(false))

    act(() => {
      ref.current?.setActiveProject(mockProjects[0])
    })

    expect(ref.current?.activeProject?.id).toBe('proj-1')
    expect(localStorage.getItem('active_project_id')).toBe('proj-1')
  })

  it('does not restore active project if id not in fetched projects', async () => {
    localStorage.setItem('active_project_id', 'proj-999')
    vi.mocked(api.apiGet).mockResolvedValue(mockProjects)
    const ref: { current: ReturnType<typeof useProject> | null } = { current: null }

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(ref.current?.loading).toBe(false))

    expect(ref.current?.activeProject).toBeNull()
  })

  it('addProject appends a new project without refetching', async () => {
    vi.mocked(api.apiGet).mockResolvedValue([mockProjects[0]])
    const ref: { current: ReturnType<typeof useProject> | null } = { current: null }

    render(
      <ProjectProvider>
        <TestConsumer onValues={(v) => { ref.current = v }} />
      </ProjectProvider>
    )

    await waitFor(() => expect(ref.current?.loading).toBe(false))
    expect(ref.current?.projects).toHaveLength(1)

    act(() => { ref.current?.addProject(mockProjects[1]) })

    expect(ref.current?.projects).toHaveLength(2)
    expect(ref.current?.projects[1].id).toBe('proj-2')
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
