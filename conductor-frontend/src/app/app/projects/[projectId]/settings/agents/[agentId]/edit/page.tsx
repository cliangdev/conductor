'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { getAgent, updateAgent, apiErrorMessage, type Agent, type CreateAgentBody } from '@/lib/api'
import { AgentForm } from '@/components/agents/AgentForm'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'

export default function EditAgentPage() {
  const { projectId, agentId } = useParams<{ projectId: string; agentId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const [agent, setAgent] = useState<Agent | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const settingsAgents = `/app/projects/${projectId}/settings/agents`

  useEffect(() => {
    if (!accessToken || !projectId || !agentId) return
    getAgent(projectId, agentId, accessToken)
      .then(setAgent)
      .catch((e) => setLoadError(apiErrorMessage(e, 'Failed to load agent.')))
      .finally(() => setLoading(false))
  }, [projectId, agentId, accessToken])

  async function handleSubmit(body: CreateAgentBody) {
    if (!accessToken) return
    setSaving(true)
    setError(null)
    try {
      await updateAgent(projectId, agentId, body, accessToken)
      router.push(settingsAgents)
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to update agent.'))
      setSaving(false)
    }
  }

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[
          { label: 'Settings', href: `/app/projects/${projectId}/settings/general` },
          { label: 'Agents', href: settingsAgents },
          { label: agent?.name ?? 'Edit' },
        ]}
        title={agent ? `Edit ${agent.name}` : 'Edit Agent'}
      />
      {loading ? (
        <div className="text-muted-foreground">Loading...</div>
      ) : loadError ? (
        <p className="text-sm text-destructive">{loadError}</p>
      ) : agent ? (
        <AgentForm
          projectId={projectId}
          initial={agent}
          submitLabel="Save changes"
          saving={saving}
          error={error}
          onSubmit={handleSubmit}
        />
      ) : null}
    </PageContainer>
  )
}
