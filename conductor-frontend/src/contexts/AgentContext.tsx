'use client'

import { createContext, useContext, useEffect, useState, useCallback, ReactNode } from 'react'
import { useParams } from 'next/navigation'
import { getAgent, apiErrorMessage, type Agent } from '@/lib/api'
import { useAuth } from '@/contexts/AuthContext'

interface AgentContextValue {
  agent: Agent | null
  loading: boolean
  error: string | null
  refetch: () => void
  setAgent: (agent: Agent) => void
}

const AgentContext = createContext<AgentContextValue | null>(null)

export function useAgent(): AgentContextValue {
  const ctx = useContext(AgentContext)
  if (!ctx) throw new Error('useAgent must be used within AgentProvider')
  return ctx
}

/**
 * Fetches one agent once for the `[agentId]` route and shares it with the detail layout
 * (breadcrumb + tabs) and the Overview / Settings tab pages, so switching tabs does not
 * re-fetch. Mirrors WorkflowContext.
 */
export function AgentProvider({ children }: { children: ReactNode }) {
  const { projectId, agentId } = useParams<{ projectId: string; agentId: string }>()
  const { accessToken } = useAuth()
  const [agent, setAgent] = useState<Agent | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const fetchAgent = useCallback(() => {
    if (!accessToken || !projectId || !agentId) return
    setLoading(true)
    getAgent(projectId, agentId, accessToken)
      .then((a) => {
        setAgent(a)
        setError(null)
      })
      .catch((e) => setError(apiErrorMessage(e, 'Failed to load agent.')))
      .finally(() => setLoading(false))
  }, [accessToken, projectId, agentId])

  useEffect(() => {
    fetchAgent()
  }, [fetchAgent])

  return (
    <AgentContext.Provider value={{ agent, loading, error, refetch: fetchAgent, setAgent }}>
      {children}
    </AgentContext.Provider>
  )
}
