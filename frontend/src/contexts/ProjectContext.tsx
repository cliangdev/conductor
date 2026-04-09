'use client'

import { createContext, useContext, useEffect, useState, ReactNode } from 'react'
import { apiGet } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import type { Project } from '@/types'

const ACTIVE_PROJECT_KEY = 'active_project_id'

interface ProjectContextValue {
  projects: Project[]
  activeProject: Project | null
  setActiveProject: (project: Project) => void
  addProject: (project: Project) => void
  loading: boolean
}

const ProjectContext = createContext<ProjectContextValue | null>(null)

export function useProject(): ProjectContextValue {
  const ctx = useContext(ProjectContext)
  if (!ctx) throw new Error('useProject must be used within ProjectProvider')
  return ctx
}

export function ProjectProvider({ children }: { children: ReactNode }) {
  const { accessToken } = useAuth()
  const [projects, setProjects] = useState<Project[]>([])
  const [activeProject, setActiveProjectState] = useState<Project | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    if (!accessToken) {
      setLoading(false)
      return
    }

    async function fetchProjects() {
      try {
        const data = await apiGet<Project[]>('/api/v1/projects', accessToken!)
        setProjects(data)

        const storedId = localStorage.getItem(ACTIVE_PROJECT_KEY)
        if (storedId) {
          const found = data.find((p) => p.id === storedId) ?? null
          setActiveProjectState(found)
        }
      } catch {
        // Leave projects empty on error
      } finally {
        setLoading(false)
      }
    }

    fetchProjects()
  }, [accessToken])

  function setActiveProject(project: Project) {
    setActiveProjectState(project)
    localStorage.setItem(ACTIVE_PROJECT_KEY, project.id)
  }

  function addProject(project: Project) {
    setProjects((prev) => [...prev, project])
  }

  return (
    <ProjectContext.Provider value={{ projects, activeProject, setActiveProject, addProject, loading }}>
      {children}
    </ProjectContext.Provider>
  )
}
