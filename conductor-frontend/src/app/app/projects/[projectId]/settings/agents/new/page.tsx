'use client'

export const dynamic = 'force-dynamic'

import { useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { createAgent, apiErrorMessage, type CreateAgentBody } from '@/lib/api'
import { AgentForm } from '@/components/agents/AgentForm'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'

export default function NewAgentPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const settingsAgents = `/app/projects/${projectId}/settings/agents`

  async function handleSubmit(body: CreateAgentBody) {
    if (!accessToken) return
    setSaving(true)
    setError(null)
    try {
      await createAgent(projectId, body, accessToken)
      router.push(settingsAgents)
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to create agent.'))
      setSaving(false)
    }
  }

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[
          { label: 'Settings', href: `/app/projects/${projectId}/settings/general` },
          { label: 'Agents', href: settingsAgents },
          { label: 'New' },
        ]}
        title="New Agent"
      />
      <AgentForm
        projectId={projectId}
        submitLabel="Create agent"
        saving={saving}
        error={error}
        onSubmit={handleSubmit}
      />
    </PageContainer>
  )
}
