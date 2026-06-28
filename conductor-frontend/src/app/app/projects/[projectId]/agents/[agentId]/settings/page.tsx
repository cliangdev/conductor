'use client'

import { useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { updateAgent, deleteAgent, apiErrorMessage, type CreateAgentBody } from '@/lib/api'
import { useAgent } from '@/contexts/AgentContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { AgentForm } from '@/components/agents/AgentForm'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { useToast } from '@/components/ui/toast'

export default function AgentSettingsPage() {
  const { projectId, agentId } = useParams<{ projectId: string; agentId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const { showToast } = useToast()
  const { agent, loading, error: loadError, setAgent } = useAgent()
  const { can, loading: roleLoading } = usePermissions()
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showDelete, setShowDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)

  if (loading) return <div className="text-muted-foreground">Loading…</div>
  if (loadError) return <p className="text-sm text-destructive">{loadError}</p>
  if (!agent) return null
  if (!roleLoading && !can('agent.manage')) {
    return <p className="text-sm text-muted-foreground">You don&apos;t have permission to edit this agent.</p>
  }

  async function handleSubmit(body: CreateAgentBody) {
    if (!accessToken) return
    setSaving(true)
    setError(null)
    try {
      const updated = await updateAgent(projectId, agentId, body, accessToken)
      setAgent(updated)
      showToast('Agent saved.', 'success')
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to update agent.'))
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    if (!accessToken) return
    setDeleting(true)
    try {
      await deleteAgent(projectId, agentId, accessToken)
      router.push(`/app/projects/${projectId}/agents`)
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to delete agent.'), 'error')
      setDeleting(false)
    }
  }

  return (
    <div className="space-y-8">
      <AgentForm
        projectId={projectId}
        initial={agent}
        submitLabel="Save changes"
        saving={saving}
        error={error}
        onSubmit={handleSubmit}
      />

      <div className="border-t pt-6">
        <h3 className="text-sm font-semibold text-destructive">Danger zone</h3>
        <p className="text-sm text-muted-foreground mt-1">Permanently delete this agent. This cannot be undone.</p>
        <Button
          variant="outline"
          className="mt-3 text-destructive hover:text-destructive"
          onClick={() => setShowDelete(true)}
        >
          Delete agent
        </Button>
      </div>

      <Modal open={showDelete} onOpenChange={setShowDelete} title="Delete agent">
        <p className="text-sm text-foreground">
          Permanently delete <strong>{agent.name}</strong>? This cannot be undone.
        </p>
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleDelete} disabled={deleting}>
            {deleting ? 'Deleting…' : 'Delete agent'}
          </Button>
          <Button variant="outline" onClick={() => setShowDelete(false)} disabled={deleting}>
            Cancel
          </Button>
        </div>
      </Modal>
    </div>
  )
}
