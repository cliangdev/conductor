'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { BotIcon } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { listAgents, updateAgent, deleteAgent, apiErrorMessage, type Agent } from '@/lib/api'
import { ProviderKeysPanel } from '@/components/agents/ProviderKeysPanel'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Modal } from '@/components/ui/modal'
import { Tabs } from '@/components/ui/tabs'
import { RowActionsMenu } from '@/components/ui/RowActionsMenu'
import { Can } from '@/components/auth/Can'
import { useToast } from '@/components/ui/toast'

export default function AgentsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const { showToast } = useToast()
  const { can, loading: roleLoading } = usePermissions()
  const [tab, setTab] = useState<'agents' | 'providers'>('agents')
  const [agents, setAgents] = useState<Agent[]>([])
  const [loading, setLoading] = useState(true)
  const [deleteTarget, setDeleteTarget] = useState<Agent | null>(null)
  const [deleting, setDeleting] = useState(false)

  const canManage = can('agent.manage')
  const base = `/app/projects/${projectId}/agents`

  useEffect(() => {
    if (!accessToken || !projectId) return
    listAgents(projectId, accessToken)
      .then(setAgents)
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [projectId, accessToken])

  async function handleToggleState(agent: Agent) {
    if (!accessToken) return
    const nextState = agent.state === 'ACTIVE' ? 'DRAFT' : 'ACTIVE'
    try {
      // State-only patch — must NOT include toolIds (would clear bindings).
      const updated = await updateAgent(projectId, agent.id, { state: nextState }, accessToken)
      setAgents((prev) => prev.map((a) => (a.id === updated.id ? updated : a)))
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to update agent.'), 'error')
    }
  }

  async function handleDelete() {
    if (!accessToken || !deleteTarget) return
    setDeleting(true)
    try {
      await deleteAgent(projectId, deleteTarget.id, accessToken)
      setAgents((prev) => prev.filter((a) => a.id !== deleteTarget.id))
      setDeleteTarget(null)
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to delete agent.'), 'error')
    } finally {
      setDeleting(false)
    }
  }

  const newAgentAction = (
    <Can do="agent.manage">
      <Button onClick={() => router.push(`${base}/new`)}>New agent</Button>
    </Can>
  )

  return (
    <PageContainer>
      <PageHeader
        title="Agents"
        description="Named AI agents that can analyze data and run tools inside your workflows."
        actions={tab === 'agents' ? newAgentAction : undefined}
      />

      <Tabs
        className="mb-4"
        value={tab}
        onValueChange={(v) => setTab(v as 'agents' | 'providers')}
        items={[
          { value: 'agents', label: 'Agents' },
          { value: 'providers', label: 'Providers' },
        ]}
      />

      {tab === 'providers' ? (
        <ProviderKeysPanel projectId={projectId} canMutate={canManage} roleLoading={roleLoading} />
      ) : loading ? (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {[0, 1, 2].map((i) => <div key={i} className="h-32 bg-muted rounded-lg animate-pulse" />)}
        </div>
      ) : agents.length === 0 ? (
        <div className="bg-card rounded-lg border border-border p-12 text-center">
          <BotIcon className="h-8 w-8 mx-auto text-muted-foreground" />
          <h2 className="mt-3 text-lg font-medium text-foreground">No agents yet</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Create an agent to analyze data and automate work in your workflows.
          </p>
          <Can do="agent.manage">
            <Button className="mt-4" onClick={() => router.push(`${base}/new`)}>New agent</Button>
          </Can>
        </div>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {agents.map((agent) => (
            <div
              key={agent.id}
              onClick={() => router.push(`${base}/${agent.id}`)}
              className="bg-card rounded-lg border border-border p-5 h-full flex flex-col gap-2 hover:border-primary/50 transition-colors cursor-pointer"
            >
              <div className="flex items-start justify-between gap-2">
                <div className="flex items-center gap-2 min-w-0">
                  <BotIcon className="h-4 w-4 shrink-0 text-muted-foreground" />
                  <span className="font-medium text-foreground truncate">{agent.name}</span>
                </div>
                <div className="flex items-center gap-1.5" onClick={(e) => e.stopPropagation()}>
                  {canManage ? (
                    <button
                      role="switch"
                      aria-checked={agent.state === 'ACTIVE'}
                      onClick={() => handleToggleState(agent)}
                      aria-label={agent.state === 'ACTIVE' ? 'Set to draft' : 'Set to active'}
                      className={`relative inline-flex h-5 w-10 items-center rounded-full transition-colors ${
                        agent.state === 'ACTIVE' ? 'bg-green-500' : 'bg-gray-300'
                      }`}
                    >
                      <span className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                        agent.state === 'ACTIVE' ? 'translate-x-5' : 'translate-x-1'
                      }`} />
                    </button>
                  ) : (
                    <Badge variant={agent.state === 'ACTIVE' ? 'status-approved' : 'status-draft'}>
                      {agent.state === 'ACTIVE' ? 'Active' : 'Draft'}
                    </Badge>
                  )}
                  {canManage && (
                    <RowActionsMenu
                      onEdit={() => router.push(`${base}/${agent.id}/settings`)}
                      onDelete={() => setDeleteTarget(agent)}
                    />
                  )}
                </div>
              </div>
              {agent.description && (
                <p className="text-sm text-muted-foreground line-clamp-2">{agent.description}</p>
              )}
              <div className="mt-auto pt-2 flex items-center gap-3 text-xs text-muted-foreground">
                <span>{agent.provider}{agent.model ? ` · ${agent.model}` : ''}</span>
                <span>·</span>
                <span>{agent.toolIds.length} tool{agent.toolIds.length === 1 ? '' : 's'}</span>
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal
        open={!!deleteTarget}
        onOpenChange={(o) => { if (!o) setDeleteTarget(null) }}
        title="Delete agent"
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{deleteTarget?.name}</strong>? This cannot be undone.
        </p>
        <div className="flex gap-3 mt-4">
          <Button variant="destructive" onClick={handleDelete} disabled={deleting}>
            {deleting ? 'Deleting…' : 'Delete agent'}
          </Button>
          <Button variant="outline" onClick={() => setDeleteTarget(null)} disabled={deleting}>
            Cancel
          </Button>
        </div>
      </Modal>
    </PageContainer>
  )
}
