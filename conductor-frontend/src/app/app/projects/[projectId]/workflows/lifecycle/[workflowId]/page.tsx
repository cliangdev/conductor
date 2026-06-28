'use client'

// COND-18: edit a project-authored lifecycle Workflow — form editor + live diagram, version history,
// and Publish (DRAFT → PUBLISHED, gated on workflow.manage). Built-in workflows have no DB row and
// are viewed read-only elsewhere; this page only handles DB-backed lifecycle definitions.

import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'next/navigation'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiGet, apiErrorMessage } from '@/lib/api'
import { publishWorkflow, updateLifecycleWorkflow } from '@/lib/workflows'
import type { StatechartDefinition } from '@/lib/workflowDefinition'
import type { WorkflowDefinitionDto, WorkflowValidationWarning } from '@/types/workflow'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { useToast } from '@/components/ui/toast'
import { StatechartEditor } from '@/components/workflow/lifecycle/StatechartEditor'
import { StatechartDiagram } from '@/components/workflow/lifecycle/StatechartDiagram'
import { WorkflowVersionHistory } from '@/components/workflow/lifecycle/WorkflowVersionHistory'

export default function EditLifecycleWorkflowPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  const { showToast } = useToast()

  const [workflow, setWorkflow] = useState<WorkflowDefinitionDto | null>(null)
  const [def, setDef] = useState<StatechartDefinition | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [saving, setSaving] = useState(false)
  const [publishing, setPublishing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<WorkflowValidationWarning[]>([])
  const [versionsKey, setVersionsKey] = useState(0)

  const base = `/app/projects/${projectId}/workflows`
  const canManage = can('workflow.manage')

  useEffect(() => {
    if (!accessToken) return
    apiGet<WorkflowDefinitionDto>(`/api/v1/projects/${projectId}/workflows/${workflowId}`, accessToken)
      .then((wf) => {
        setWorkflow(wf)
        if (wf.definition) setDef(wf.definition as unknown as StatechartDefinition)
      })
      .catch((e) => setError(apiErrorMessage(e, 'Failed to load workflow')))
      .finally(() => setLoaded(true))
  }, [projectId, workflowId, accessToken])

  const diagram = useMemo(
    () =>
      def ? <StatechartDiagram statuses={def.statuses} transitions={def.transitions} /> : null,
    [def],
  )

  const handleSave = async () => {
    if (!accessToken || !def) return
    setSaving(true)
    setError(null)
    setWarnings([])
    try {
      const res = await updateLifecycleWorkflow(
        projectId,
        workflowId,
        { name: workflow?.name, area: def.area || undefined, definition: def },
        accessToken,
      )
      setWorkflow(res.workflow)
      if (res.workflow.definition) setDef(res.workflow.definition as unknown as StatechartDefinition)
      setWarnings(res.warnings ?? [])
      showToast('Workflow saved.', 'success')
    } catch (e) {
      setError(apiErrorMessage(e, 'Failed to save workflow'))
    } finally {
      setSaving(false)
    }
  }

  const handlePublish = async () => {
    if (!accessToken) return
    setPublishing(true)
    setError(null)
    try {
      const published = await publishWorkflow(projectId, workflowId, accessToken)
      setWorkflow(published)
      if (published.definition) setDef(published.definition as unknown as StatechartDefinition)
      setVersionsKey((k) => k + 1)
      showToast('Workflow published.', 'success')
    } catch (e) {
      // 422 surfaces the semantic-validation detail; 403 surfaces the permission message.
      setError(apiErrorMessage(e, 'Failed to publish workflow'))
    } finally {
      setPublishing(false)
    }
  }

  if (!loaded) {
    return (
      <PageContainer>
        <PageHeader title="Lifecycle workflow" breadcrumbs={[{ label: 'Workflows', href: base }]} />
        <p className="text-muted-foreground">Loading…</p>
      </PageContainer>
    )
  }

  if (!workflow) {
    return (
      <PageContainer>
        <PageHeader title="Lifecycle workflow" breadcrumbs={[{ label: 'Workflows', href: base }]} />
        <p className="text-sm text-destructive">{error ?? 'Workflow not found.'}</p>
      </PageContainer>
    )
  }

  if (!def) {
    // A YAML automation, not a statechart — send the user to the automation editor.
    return (
      <PageContainer>
        <PageHeader title={workflow.name} breadcrumbs={[{ label: 'Workflows', href: base }]} />
        <p className="text-sm text-muted-foreground">
          This is an automation workflow.{' '}
          <Link className="text-primary hover:underline" href={`${base}/${workflowId}/overview`}>
            Open it in the automation editor.
          </Link>
        </p>
      </PageContainer>
    )
  }

  const isPublished = workflow.state === 'PUBLISHED'

  return (
    <PageContainer>
      <PageHeader
        title={workflow.name}
        breadcrumbs={[{ label: 'Workflows', href: base }, { label: workflow.name }]}
        status={
          <span className="flex items-center gap-2">
            <Badge variant={isPublished ? 'status-done' : 'status-draft'}>
              {workflow.state ?? 'DRAFT'}
            </Badge>
            {workflow.version != null && (
              <span className="text-xs text-muted-foreground">v{workflow.version}</span>
            )}
          </span>
        }
        actions={
          <div className="flex items-center gap-2">
            {error && <span className="text-sm text-destructive max-w-xs truncate" title={error}>{error}</span>}
            {canManage && (
              <>
                <Button variant="outline" onClick={handleSave} disabled={saving || publishing}>
                  {saving ? 'Saving…' : 'Save draft'}
                </Button>
                <Button onClick={handlePublish} disabled={publishing || saving}>
                  {publishing ? 'Publishing…' : 'Publish'}
                </Button>
              </>
            )}
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

      <div className="grid grid-cols-1 lg:grid-cols-[1fr_360px] gap-4">
        <div className="space-y-4">
          {canManage ? (
            <StatechartEditor value={def} onChange={setDef} />
          ) : (
            <p className="text-sm text-muted-foreground">
              You have read-only access to this workflow.
            </p>
          )}
        </div>

        <div className="space-y-4 lg:sticky lg:top-4 self-start">
          <div className="border border-border rounded-lg overflow-hidden h-[360px]">
            <div className="px-4 py-2 border-b border-border bg-muted/30">
              <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Diagram</p>
            </div>
            <div className="h-[calc(100%-37px)]">{diagram}</div>
          </div>

          <div>
            <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2">
              Version history
            </p>
            <WorkflowVersionHistory
              projectId={projectId}
              workflowId={workflowId}
              token={accessToken!}
              refreshKey={versionsKey}
            />
          </div>
        </div>
      </div>
    </PageContainer>
  )
}
