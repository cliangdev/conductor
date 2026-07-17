'use client'

// The manage-gated editing surface for a lifecycle Workflow: form editor + live diagram, plus the
// Save draft / Publish actions (co-located with the editor state, the way automation's Save lives in
// its Settings tab). Reads/writes the shared WorkflowContext so the header badge + version update.

import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { isLifecycleWorkflow, publishWorkflow, updateLifecycleWorkflow } from '@/lib/workflows'
import type { StatechartDefinition } from '@/lib/workflowDefinition'
import type { WorkflowDefinitionDto, WorkflowValidationWarning } from '@/types/workflow'
import { Button } from '@/components/ui/button'
import { Alert } from '@/components/ui/alert'
import { useToast } from '@/components/ui/toast'
import { useWorkflow } from '@/contexts/WorkflowContext'
import { StatechartEditor } from '@/components/workflow/lifecycle/StatechartEditor'
import { StatechartDiagram } from '@/components/workflow/lifecycle/StatechartDiagram'

function statechartOf(wf: WorkflowDefinitionDto | null): StatechartDefinition | null {
  if (!wf || !isLifecycleWorkflow(wf) || !wf.definition) return null
  const def = wf.definition as unknown as StatechartDefinition
  return { ...def, statuses: def.statuses ?? [], transitions: def.transitions ?? [] }
}

export default function LifecycleEditorPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  const { showToast } = useToast()
  const { workflow, setWorkflow, loading } = useWorkflow()
  const canManage = can('workflow.manage')

  const [def, setDef] = useState<StatechartDefinition | null>(null)
  const [saving, setSaving] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [warnings, setWarnings] = useState<WorkflowValidationWarning[]>([])
  const [saveError, setSaveError] = useState<string | null>(null)

  // Seed the local editor state once the workflow loads (re-seeds when the bound workflow changes,
  // e.g. after a save/publish writes back to the context).
  useEffect(() => {
    setDef(statechartOf(workflow))
  }, [workflow])

  const diagram = useMemo(
    () => (def ? <StatechartDiagram statuses={def.statuses} transitions={def.transitions} /> : null),
    [def],
  )

  const handleSave = async () => {
    if (!accessToken || !def) return
    setSaving(true)
    setWarnings([])
    setSaveError(null)
    try {
      const res = await updateLifecycleWorkflow(
        projectId,
        workflowId,
        { name: workflow?.name, area: def.area || undefined, definition: def },
        accessToken,
      )
      setWorkflow(res.workflow)
      setDef(statechartOf(res.workflow))
      setWarnings(res.warnings ?? [])
      showToast('Workflow saved.', 'success')
    } catch (e) {
      // 422 surfaces the semantic-validation detail — kept on-screen (not just a toast) since it
      // can be long and the toast auto-dismisses before it's fully read.
      setSaveError(apiErrorMessage(e, 'Failed to save workflow'))
    } finally {
      setSaving(false)
    }
  }

  const handlePublish = async () => {
    if (!accessToken) return
    setPublishing(true)
    setSaveError(null)
    try {
      const published = await publishWorkflow(projectId, workflowId, accessToken)
      setWorkflow(published)
      setDef(statechartOf(published))
      showToast('Workflow published.', 'success')
    } catch (e) {
      // 422 surfaces the semantic-validation detail; 403 surfaces the permission message.
      setSaveError(apiErrorMessage(e, 'Failed to publish workflow'))
    } finally {
      setPublishing(false)
    }
  }

  if (loading && !workflow) return <div className="text-muted-foreground">Loading…</div>
  if (workflow && !isLifecycleWorkflow(workflow)) return null // layout renders the automation notice
  if (!canManage) {
    return <p className="text-sm text-muted-foreground">You have read-only access to this workflow.</p>
  }
  if (!def) return <div className="text-muted-foreground">Loading…</div>

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-end gap-2">
        <Button variant="outline" onClick={handleSave} disabled={saving || publishing}>
          {saving ? 'Saving…' : 'Save draft'}
        </Button>
        <Button onClick={handlePublish} disabled={publishing || saving}>
          {publishing ? 'Publishing…' : 'Publish'}
        </Button>
      </div>

      {saveError && (
        <Alert variant="destructive">
          <p>{saveError}</p>
        </Alert>
      )}

      {warnings.length > 0 && (
        <Alert variant="warning">
          <p className="font-medium mb-1">Saved with warnings:</p>
          <ul className="list-disc pl-5">
            {warnings.map((w, i) => (
              <li key={i}>{w.message}</li>
            ))}
          </ul>
        </Alert>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-4">
        <StatechartEditor value={def} onChange={setDef} />

        <div className="lg:sticky lg:top-4 self-start border border-border rounded-lg overflow-hidden h-[360px]">
          <div className="px-4 py-2 border-b border-border bg-muted/30">
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Diagram</p>
          </div>
          <div className="h-[calc(100%-37px)]">{diagram}</div>
        </div>
      </div>
    </div>
  )
}
