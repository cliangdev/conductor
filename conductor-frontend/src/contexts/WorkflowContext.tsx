'use client'

import { createContext, useContext, useEffect, useState, useCallback, ReactNode } from 'react'
import { useParams } from 'next/navigation'
import { apiGet } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'
import type { WorkflowDefinitionDto } from '@/types/workflow'

interface WorkflowContextValue {
  workflow: WorkflowDefinitionDto | null
  loading: boolean
  refetch: () => void
  setWorkflow: (workflow: WorkflowDefinitionDto) => void
}

const WorkflowContext = createContext<WorkflowContextValue | null>(null)

export function useWorkflow(): WorkflowContextValue {
  const ctx = useContext(WorkflowContext)
  if (!ctx) throw new Error('useWorkflow must be used within WorkflowProvider')
  return ctx
}

/**
 * Fetches the workflow once for the `[workflowId]` route and shares it with the
 * section layout (breadcrumb) and child pages. Because the provider lives in the
 * persistent `[workflowId]/layout.tsx`, it does NOT re-fetch when navigating
 * between the workflow's detail / Run History / Run Detail pages.
 */
export function WorkflowProvider({ children }: { children: ReactNode }) {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const { accessToken } = useAuth()
  const [workflow, setWorkflow] = useState<WorkflowDefinitionDto | null>(null)
  const [loading, setLoading] = useState(true)

  const fetchWorkflow = useCallback(() => {
    if (!accessToken || !workflowId) return
    setLoading(true)
    apiGet<WorkflowDefinitionDto>(`/api/v1/projects/${projectId}/workflows/${workflowId}`, accessToken)
      .then(setWorkflow)
      .catch(() => {})
      .finally(() => setLoading(false))
  }, [accessToken, projectId, workflowId])

  useEffect(() => {
    fetchWorkflow()
  }, [fetchWorkflow])

  return (
    <WorkflowContext.Provider value={{ workflow, loading, refetch: fetchWorkflow, setWorkflow }}>
      {children}
    </WorkflowContext.Provider>
  )
}
