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

  const agentsBase = `/app/projects/${projectId}/agents`

  async function handleSubmit(body: CreateAgentBody) {
    if (!accessToken) return
    setSaving(true)
    setError(null)
    try {
      const created = await createAgent(projectId, body, accessToken)
      router.push(`${agentsBase}/${created.id}`)
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to create agent.'))
      setSaving(false)
    }
  }

  return (
    <PageContainer>
      <PageHeader
        breadcrumbs={[
          { label: 'Agents', href: agentsBase },
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
