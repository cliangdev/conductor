'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { ActivityIcon, ArrowRightIcon } from 'lucide-react'
import { useAgent } from '@/contexts/AgentContext'
import { useAuth } from '@/contexts/AuthContext'
import { providerDisplayName } from '@/lib/providers'
import { listWorkflows } from '@/lib/workflows'

// Matches KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME on the backend — the only knowledge
// domain agent today. Kept as a literal here (rather than importing a shared constant) since the
// frontend has no equivalent domain module; if a second default agent gains a runs cross-link this
// should become a small map keyed by agent slug instead.
const LIBRARIAN_WORKFLOW_NAME = 'knowledge-librarian'

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 text-sm text-foreground">{children}</dd>
    </div>
  )
}

/** Cross-links to the knowledge-librarian workflow's Runs tab. Hides itself entirely if the
 *  workflow can't be resolved (not provisioned yet, or the lookup fails) — never a broken link. */
function LibrarianRecentActivity({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const [workflowId, setWorkflowId] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken) return
    let cancelled = false
    listWorkflows(projectId, accessToken)
      .then((workflows) => {
        if (cancelled) return
        setWorkflowId(workflows.find((w) => w.name === LIBRARIAN_WORKFLOW_NAME)?.id ?? null)
      })
      .catch(() => {
        if (!cancelled) setWorkflowId(null)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken])

  if (!workflowId) return null

  return (
    <section>
      <h2 className="text-sm font-semibold mb-2">Recent activity</h2>
      <Link
        href={`/app/projects/${projectId}/workflows/${workflowId}/runs`}
        className="flex items-center justify-between gap-2 text-sm border rounded-lg p-4 text-foreground hover:border-primary/50 transition-colors"
      >
        <span className="flex items-center gap-2">
          <ActivityIcon className="h-4 w-4 text-muted-foreground" />
          View knowledge-librarian runs
        </span>
        <ArrowRightIcon className="h-3.5 w-3.5 text-muted-foreground" />
      </Link>
    </section>
  )
}

export default function AgentOverviewPage() {
  const { agent, loading, error } = useAgent()

  if (loading) return <div className="text-muted-foreground">Loading…</div>
  if (error) return <p className="text-sm text-destructive">{error}</p>
  if (!agent) return null

  const cfg = agent.config

  return (
    <div className="space-y-6">
      <dl className="grid grid-cols-2 sm:grid-cols-4 gap-4 border rounded-lg p-4">
        <Field label="Provider">{providerDisplayName(agent.provider)}</Field>
        <Field label="Model">{agent.model ?? <span className="text-muted-foreground">Provider default</span>}</Field>
        <Field label="Tools">{agent.toolIds.length}</Field>
        <Field label="Slug"><span className="font-mono text-xs">{agent.slug}</span></Field>
      </dl>

      {agent.isDefault && agent.slug === 'knowledge-librarian' && (
        <LibrarianRecentActivity projectId={agent.projectId} />
      )}

      {agent.systemPrompt && (
        <section>
          <h2 className="text-sm font-semibold mb-2">System prompt</h2>
          <pre className="text-sm bg-muted/40 border rounded-lg p-4 whitespace-pre-wrap">{agent.systemPrompt}</pre>
        </section>
      )}

      {cfg && (cfg.temperature != null || cfg.maxTokens != null || cfg.maxToolTurns != null) && (
        <section>
          <h2 className="text-sm font-semibold mb-2">Guardrails</h2>
          <dl className="grid grid-cols-2 sm:grid-cols-3 gap-4 border rounded-lg p-4">
            {cfg.temperature != null && <Field label="Temperature">{cfg.temperature}</Field>}
            {cfg.maxTokens != null && <Field label="Max tokens">{cfg.maxTokens}</Field>}
            {cfg.maxToolTurns != null && <Field label="Max tool turns">{cfg.maxToolTurns}</Field>}
          </dl>
        </section>
      )}

      {agent.toolIds.length > 0 && (
        <section>
          <h2 className="text-sm font-semibold mb-2">Bound tools</h2>
          <ul className="flex flex-wrap gap-2">
            {agent.toolIds.map((id) => (
              <li key={id} className="font-mono text-xs bg-muted/40 border rounded px-2 py-1">{id}</li>
            ))}
          </ul>
        </section>
      )}
    </div>
  )
}
