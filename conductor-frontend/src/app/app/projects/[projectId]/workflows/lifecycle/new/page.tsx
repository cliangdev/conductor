'use client'

// COND-18: create a lifecycle (statechart) Workflow. Seeds from an empty template, or — when
// ?from=<SLUG> is present — clones a built-in's definition (e.g. ENGINEERING) as a starting point.

import { useEffect, useMemo, useState } from 'react'
import { useParams, useRouter, useSearchParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { createLifecycleWorkflow, fetchWorkflowView } from '@/lib/workflows'
import {
  definitionFromWorkflowView,
  emptyDefinition,
  type StatechartDefinition,
} from '@/lib/workflowDefinition'
import type { WorkflowValidationWarning } from '@/types/workflow'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { StatechartEditor } from '@/components/workflow/lifecycle/StatechartEditor'
import { StatechartDiagram } from '@/components/workflow/lifecycle/StatechartDiagram'

export default function NewLifecycleWorkflowPage() {
  const { projectId } = useParams<{ projectId: string }>()
  const { accessToken } = useAuth()
  const { can, loading: roleLoading } = usePermissions()
  const router = useRouter()
  const searchParams = useSearchParams()
  const cloneFrom = searchParams.get('from')

  const [name, setName] = useState('')
  const [def, setDef] = useState<StatechartDefinition>(() => emptyDefinition())
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<WorkflowValidationWarning[]>([])

  // Seed from a built-in when cloning.
  useEffect(() => {
    if (!cloneFrom || !accessToken) return
    fetchWorkflowView(projectId, cloneFrom, accessToken)
      .then((view) => {
        const seeded = definitionFromWorkflowView(view)
        setDef(seeded)
        setName(`${view.noun || cloneFrom} (copy)`)
      })
      .catch(() => {})
  }, [cloneFrom, projectId, accessToken])

  const base = `/app/projects/${projectId}/workflows`
  const canManage = can('workflow.manage')

  const handleSave = async () => {
    if (!accessToken) return
    setSaving(true)
    setError(null)
    setWarnings([])
    try {
      const res = await createLifecycleWorkflow(
        projectId,
        { name: name || def.id || 'Untitled Workflow', area: def.area || undefined, definition: def },
        accessToken,
      )
      setWarnings(res.warnings ?? [])
      router.push(`${base}/lifecycle/${res.workflow.id}`)
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to create workflow'))
    } finally {
      setSaving(false)
    }
  }

  const diagram = useMemo(
    () => <StatechartDiagram statuses={def.statuses} transitions={def.transitions} />,
    [def.statuses, def.transitions],
  )

  if (!roleLoading && !canManage) {
    return (
      <PageContainer>
        <PageHeader title="New lifecycle workflow" breadcrumbs={[{ label: 'Workflows', href: base }, { label: 'New' }]} />
        <p className="text-sm text-muted-foreground">You don&apos;t have permission to create workflows.</p>
      </PageContainer>
    )
  }

  return (
    <PageContainer>
      <PageHeader
        title="New lifecycle workflow"
        breadcrumbs={[{ label: 'Workflows', href: base }, { label: 'New lifecycle' }]}
        actions={
          <div className="flex items-center gap-2">
            {error && <span className="text-sm text-destructive">{error}</span>}
            <Button variant="outline" onClick={() => router.push(base)} disabled={saving}>
              Discard
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {saving ? 'Saving…' : 'Save draft'}
            </Button>
          </div>
        }
      />

      {warnings.length > 0 && (
        <div className="mb-4 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-800">
          <p className="font-medium mb-1">Saved with warnings:</p>
          <ul className="list-disc pl-5">
            {warnings.map((w, i) => (
              <li key={i}>{w.message}</li>
            ))}
          </ul>
        </div>
      )}

      <div className="mb-4">
        <label className="block text-xs font-medium text-muted-foreground mb-1">Workflow name</label>
        <input
          className="w-full max-w-md px-3 py-1.5 text-sm border border-border rounded-md bg-background text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g. Content Review"
        />
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-4">
        <StatechartEditor value={def} onChange={setDef} creating />
        <div className="lg:sticky lg:top-4 self-start border border-border rounded-lg overflow-hidden h-[420px]">
          <div className="px-4 py-2 border-b border-border bg-muted/30">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Diagram preview</p>
          </div>
          <div className="h-[calc(100%-37px)]">{diagram}</div>
        </div>
      </div>
    </PageContainer>
  )
}
