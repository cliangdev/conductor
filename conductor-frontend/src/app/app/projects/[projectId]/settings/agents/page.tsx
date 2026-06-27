'use client'

export const dynamic = 'force-dynamic'

import { useEffect, useRef, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { listAgents, updateAgent, deleteAgent, apiErrorMessage, type Agent } from '@/lib/api'
import { useCanMutate } from '@/components/agents/useCanMutate'
import { ProviderKeysPanel } from '@/components/agents/ProviderKeysPanel'
import { Button } from '@/components/ui/button'
import { Modal } from '@/components/ui/modal'
import { Badge } from '@/components/ui/badge'
import { useToast } from '@/components/ui/toast'
import { PageHeader } from '@/components/layout/PageHeader'

function KebabMenu({ onEdit, onDelete }: { onEdit: () => void; onDelete: () => void }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [open])

  return (
    <div className="relative" ref={ref}>
      <button
        className="px-2 py-1 rounded text-muted-foreground hover:bg-muted/50 text-base leading-none"
        onClick={(e) => { e.stopPropagation(); setOpen((v) => !v) }}
        aria-label="More actions"
      >
        ···
      </button>
      {open && (
        <div className="absolute right-0 z-20 mt-1 w-36 rounded-md border bg-background shadow-md">
          <button
            className="flex w-full items-center px-3 py-2 text-sm hover:bg-muted/50"
            onClick={(e) => { e.stopPropagation(); setOpen(false); onEdit() }}
          >
            Edit
          </button>
          <button
            className="flex w-full items-center px-3 py-2 text-sm text-destructive hover:bg-muted/50"
            onClick={(e) => { e.stopPropagation(); setOpen(false); onDelete() }}
          >
            Delete
          </button>
        </div>
      )}
    </div>
  )
}

export default function ManageAgentsPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const { showToast } = useToast()
  const { canMutate } = useCanMutate(projectId)
  const [agents, setAgents] = useState<Agent[]>([])
  const [loading, setLoading] = useState(true)
  const [deleteTarget, setDeleteTarget] = useState<Agent | null>(null)
  const [deleting, setDeleting] = useState(false)

  useEffect(() => {
    if (!accessToken || !projectId) return
    listAgents(projectId, accessToken)
      .then(setAgents)
      .finally(() => setLoading(false))
  }, [projectId, accessToken])

  const editHref = (id: string) => `/app/projects/${projectId}/settings/agents/${id}/edit`

  async function handleToggleState(agent: Agent) {
    if (!accessToken) return
    const nextState = agent.state === 'ACTIVE' ? 'DRAFT' : 'ACTIVE'
    try {
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

  return (
    <>
      <PageHeader
        title="Agents"
        description="Create and configure named AI agents. Browse and use them from the Agents tab."
        actions={canMutate ? (
          <Button onClick={() => router.push(`/app/projects/${projectId}/settings/agents/new`)}>
            New Agent
          </Button>
        ) : undefined}
      />

      <div className="space-y-6">
        <ProviderKeysPanel projectId={projectId} canMutate={canMutate} />

        {loading ? (
          <div className="text-muted-foreground">Loading...</div>
        ) : agents.length === 0 ? (
          <div className="text-center py-12 text-muted-foreground">
            No agents yet. Create one to automate analysis in your workflows.
          </div>
        ) : (
          <div className="border rounded-lg overflow-x-auto">
            <table className="w-full min-w-[560px]">
              <thead className="bg-muted/50">
                <tr>
                  <th className="text-left p-3 font-medium">Name</th>
                  <th className="text-left p-3 font-medium">Model</th>
                  <th className="text-left p-3 font-medium">Tools</th>
                  <th className="text-left p-3 font-medium">Active</th>
                  <th className="p-3 font-medium w-10" />
                </tr>
              </thead>
              <tbody>
                {agents.map((agent) => (
                  <tr
                    key={agent.id}
                    className="border-t hover:bg-muted/25 cursor-pointer"
                    onClick={() => router.push(editHref(agent.id))}
                  >
                    <td className="p-3">
                      <div className="font-medium">{agent.name}</div>
                      {agent.description && (
                        <div className="text-xs text-muted-foreground line-clamp-1">{agent.description}</div>
                      )}
                    </td>
                    <td className="p-3 text-sm text-muted-foreground">
                      {agent.provider}{agent.model ? ` · ${agent.model}` : ''}
                    </td>
                    <td className="p-3 text-sm text-muted-foreground">{agent.toolIds.length}</td>
                    <td className="p-3" onClick={(e) => e.stopPropagation()}>
                      {canMutate ? (
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
                    </td>
                    <td className="p-3 text-right" onClick={(e) => e.stopPropagation()}>
                      {canMutate && (
                        <KebabMenu
                          onEdit={() => router.push(editHref(agent.id))}
                          onDelete={() => setDeleteTarget(agent)}
                        />
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

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
    </>
  )
}
