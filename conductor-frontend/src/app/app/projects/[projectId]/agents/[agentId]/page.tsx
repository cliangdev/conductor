'use client'

import { useAgent } from '@/contexts/AgentContext'
import { Badge } from '@/components/ui/badge'

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 text-sm text-foreground">{children}</dd>
    </div>
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
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl sm:text-2xl font-semibold">{agent.name}</h1>
        <Badge variant={agent.state === 'ACTIVE' ? 'status-approved' : 'status-draft'}>
          {agent.state === 'ACTIVE' ? 'Active' : 'Draft'}
        </Badge>
      </div>
      {agent.description && <p className="text-sm text-muted-foreground">{agent.description}</p>}

      <dl className="grid grid-cols-2 sm:grid-cols-4 gap-4 border rounded-lg p-4">
        <Field label="Provider">{agent.provider}</Field>
        <Field label="Model">{agent.model ?? <span className="text-muted-foreground">Provider default</span>}</Field>
        <Field label="Tools">{agent.toolIds.length}</Field>
        <Field label="Slug"><span className="font-mono text-xs">{agent.slug}</span></Field>
      </dl>

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
