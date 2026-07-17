'use client'

// Read-friendly landing tab for a lifecycle Workflow: identity summary + the statechart diagram.
// Mirrors the automation Overview (stats + diagram) so both kinds land on a consistent first screen.

import { useMemo } from 'react'
import { useWorkflow } from '@/contexts/WorkflowContext'
import { StatechartDiagram } from '@/components/workflow/lifecycle/StatechartDiagram'
import { isLifecycleWorkflow } from '@/lib/workflows'
import type { StatechartDefinition } from '@/lib/workflowDefinition'
import type { WorkflowDefinitionDto } from '@/types/workflow'
import { Badge } from '@/components/ui/badge'

function statechartOf(wf: WorkflowDefinitionDto | null): StatechartDefinition | null {
  if (!wf || !isLifecycleWorkflow(wf) || !wf.definition) return null
  const def = wf.definition as unknown as StatechartDefinition
  return { ...def, statuses: def.statuses ?? [], transitions: def.transitions ?? [] }
}

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <p className="text-xs text-muted-foreground mb-0.5">{label}</p>
      <p className={`text-sm text-foreground ${mono ? 'font-mono' : ''}`}>{value}</p>
    </div>
  )
}

export default function LifecycleOverviewPage() {
  const { workflow, loading } = useWorkflow()
  const def = useMemo(() => statechartOf(workflow), [workflow])

  if (loading && !workflow) return <div className="text-muted-foreground">Loading…</div>
  if (!def) return null // layout renders the automation/missing notice

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[280px_1fr] gap-4">
      <div className="border rounded-lg p-4 space-y-3">
        <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Details</p>
        <Field label="Slug" value={def.id || '—'} mono />
        <Field label="Area" value={def.area || '—'} mono />
        <Field label="Noun" value={def.noun || '—'} />
        <Field label="Default view" value={def.default_view} />
        <div>
          <p className="text-xs text-muted-foreground mb-1">Types</p>
          <div className="flex flex-wrap gap-1">
            {def.types.length ? (
              def.types.map((t) => <Badge key={t} variant="outline">{t}</Badge>)
            ) : (
              <span className="text-sm text-muted-foreground">—</span>
            )}
          </div>
        </div>
        {def.asset_types && def.asset_types.length > 0 && (
          <div>
            <p className="text-xs text-muted-foreground mb-1">Asset types</p>
            <div className="flex flex-wrap gap-1">
              {def.asset_types.map((t) => <Badge key={t} variant="outline">{t}</Badge>)}
            </div>
          </div>
        )}
      </div>

      <div className="flex flex-col h-[360px] border border-border rounded-lg overflow-hidden">
        <div className="shrink-0 px-4 py-2.5 border-b border-border bg-muted">
          <p className="text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground">Diagram</p>
        </div>
        <div className="flex-1 min-h-0">
          <StatechartDiagram statuses={def.statuses} transitions={def.transitions} />
        </div>
      </div>
    </div>
  )
}
