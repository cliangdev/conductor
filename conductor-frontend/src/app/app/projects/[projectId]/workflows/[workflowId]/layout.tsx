'use client'

import { useState } from 'react'
import { useParams, usePathname, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiPost, apiPatch, apiErrorMessage } from '@/lib/api'
import { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader, type Crumb } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Switch } from '@/components/ui/switch'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { Alert } from '@/components/ui/alert'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { CopyableId } from '@/components/ui/copyable-id'
import { PlayIcon } from 'lucide-react'
import { Can } from '@/components/auth/Can'
import { WorkflowStatusBadge } from '@/components/workflow/WorkflowStatusBadge'
import { TriggerBadges } from '@/components/workflow/TriggerBadges'
import { WorkflowProvider, useWorkflow } from '@/contexts/WorkflowContext'
import { useCan } from '@/contexts/PermissionsContext'
import { allowsManualDispatch } from '@/lib/workflows'
import { useToast } from '@/components/ui/toast'
import { timeAgo } from '@/lib/format'

/** Visible Enable/Disable control for the header — a `Switch`, not a menu item, so the most
 *  frequent lifecycle action doesn't require opening anything. Enable is instant (non-destructive,
 *  reversible in one click, consistent with the workflows list page's own instant enable and the
 *  auto-pause banner's instant "Re-enable"); Disable confirms through the shared `ConfirmModal`,
 *  never a hand-rolled modal or native `confirm()`. Delete moved to the Definition tab's Danger
 *  zone — see `definition/page.tsx` — since it's rare/destructive/config, not a daily control. */
function WorkflowEnabledToggle() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const { accessToken } = useAuth()
  const { showToast } = useToast()
  const { workflow, setWorkflow } = useWorkflow()
  const [confirmDisable, setConfirmDisable] = useState(false)
  const [toggling, setToggling] = useState(false)

  if (!workflow) return null

  const setEnabled = async (enabled: boolean) => {
    if (!accessToken) return
    setToggling(true)
    try {
      const updated = await apiPatch<WorkflowDefinitionDto>(
        `/api/v1/projects/${projectId}/workflows/${workflowId}/enabled`,
        { enabled },
        accessToken,
      )
      if (updated) setWorkflow(updated)
      showToast(`Workflow ${enabled ? 'enabled' : 'disabled'}.`, 'success')
      setConfirmDisable(false)
    } catch (e) {
      showToast(apiErrorMessage(e, `Couldn't ${enabled ? 'enable' : 'disable'} workflow — try again.`), 'error')
    } finally {
      setToggling(false)
    }
  }

  return (
    <Can do="workflow.manage" fallback={<WorkflowStatusBadge workflow={workflow} />}>
      <div className="flex items-center gap-1.5">
        <Switch
          checked={workflow.enabled}
          onCheckedChange={(next) => (next ? setEnabled(true) : setConfirmDisable(true))}
          disabled={toggling}
          aria-label={workflow.enabled ? 'Disable workflow' : 'Enable workflow'}
        />
        <span className="text-sm text-muted-foreground">{workflow.enabled ? 'Enabled' : 'Disabled'}</span>
        {workflow.autoPausedAt && <Badge variant="status-failed">Auto-paused</Badge>}
      </div>

      <ConfirmModal
        open={confirmDisable}
        title="Disable workflow"
        confirmLabel="Disable workflow"
        busyLabel="Disabling…"
        destructive={false}
        busy={toggling}
        onConfirm={() => setEnabled(false)}
        onCancel={() => setConfirmDisable(false)}
      >
        <p className="text-sm text-foreground">
          No new runs will start until <strong>{workflow.name}</strong> is re-enabled. Runs already in progress won&apos;t be affected.
        </p>
      </ConfirmModal>
    </Can>
  )
}

/** Workflow identity + breadcrumb + the single canonical Run action, shared across all tabs. */
function WorkflowDetailHeader() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const pathname = usePathname()
  const { accessToken } = useAuth()
  const router = useRouter()
  const { workflow } = useWorkflow()
  const [dispatching, setDispatching] = useState(false)

  const base = `/app/projects/${projectId}/workflows`
  const wfBase = `${base}/${workflowId}`
  const onRunDetail = /\/runs\/[^/]+$/.test(pathname)

  // The two tabs (Runs / Definition) are marked by the tab bar, so only the deeper run-detail page
  // adds extra crumbs (Runs → Run Detail) to navigate back up.
  const crumbs: Crumb[] = [
    { label: 'Workflows', href: base },
    { label: workflow?.name ?? 'Workflow', ...(onRunDetail ? { href: `${wfBase}/runs` } : {}) },
  ]
  if (onRunDetail) {
    crumbs.push({ label: 'Runs', href: `${wfBase}/runs` })
    crumbs.push({ label: 'Run Detail' })
  }

  if (!workflow) {
    return <PageHeader breadcrumbs={crumbs} title={<span className="text-muted-foreground">Loading…</span>} />
  }

  const canDispatch = allowsManualDispatch(workflow.yaml)

  const handleRun = async () => {
    if (!accessToken) return
    setDispatching(true)
    try {
      const run = await apiPost<WorkflowRunDto>(
        `/api/v1/projects/${projectId}/workflows/${workflowId}/dispatch`,
        {},
        accessToken,
      )
      router.push(`/app/projects/${projectId}/workflows/${workflowId}/runs/${run.id}`)
    } finally {
      setDispatching(false)
    }
  }

  return (
    <PageHeader
      breadcrumbs={crumbs}
      title={workflow.name}
      status={
        <>
          <WorkflowEnabledToggle />
          <TriggerBadges yaml={workflow.yaml ?? ''} />
        </>
      }
      actions={
        canDispatch && (
          <Can do="workflow.run">
            <Button onClick={handleRun} disabled={!workflow.enabled || dispatching} className="gap-1.5">
              {dispatching ? 'Starting…' : (<><PlayIcon className="h-3.5 w-3.5" /> Run</>)}
            </Button>
          </Can>
        )
      }
    />
  )
}

/** Explains why a workflow stopped running (auto-paused by WorkflowFailureCircuitBreaker vs a human
 *  disabling it) and lets a manager clear the pause in one click — shown on every tab so it's visible
 *  no matter where someone lands. */
function WorkflowAutoPauseBanner() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const { accessToken } = useAuth()
  const { workflow, setWorkflow } = useWorkflow()
  const { showToast } = useToast()
  const canManage = useCan('workflow.manage')
  const [resuming, setResuming] = useState(false)

  if (!workflow?.autoPausedAt) return null

  const handleResume = async () => {
    if (!accessToken) return
    setResuming(true)
    try {
      const updated = await apiPatch<WorkflowDefinitionDto>(
        `/api/v1/projects/${projectId}/workflows/${workflowId}/enabled`,
        { enabled: true },
        accessToken,
      )
      if (updated) setWorkflow(updated)
      showToast('Workflow re-enabled.', 'success')
    } catch (e) {
      showToast(apiErrorMessage(e, "Couldn't re-enable workflow — try again."), 'error')
    } finally {
      setResuming(false)
    }
  }

  return (
    <Alert variant="warning" className="mb-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p>
          Auto-paused after <strong>{workflow.consecutiveFailures ?? 5}</strong> consecutive failed runs,{' '}
          {timeAgo(workflow.autoPausedAt)}
          {workflow.autoPausedRunId && (
            <>
              {' — '}
              <a
                href={`/app/projects/${projectId}/workflows/${workflowId}/runs/${workflow.autoPausedRunId}`}
                className="underline underline-offset-2"
              >
                see the failing run
              </a>{' '}
              (<CopyableId id={workflow.autoPausedRunId} />)
            </>
          )}
          . No new runs will start until it&apos;s re-enabled.
        </p>
        {canManage && (
          <Button size="sm" variant="outline" onClick={handleResume} disabled={resuming} className="shrink-0">
            {resuming ? 'Re-enabling…' : 'Re-enable'}
          </Button>
        )}
      </div>
    </Alert>
  )
}

function WorkflowTabs() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const pathname = usePathname()

  const wfBase = `/app/projects/${projectId}/workflows/${workflowId}`
  // Definition only matches its own subtree; everything else on this route (bare index, /runs, and
  // /runs/[runId]) highlights Runs — the default landing tab.
  const active = pathname.startsWith(`${wfBase}/definition`) ? 'definition' : 'runs'

  const items: TabItem[] = [
    { value: 'runs', label: 'Runs', href: `${wfBase}/runs` },
    { value: 'definition', label: 'Definition', href: `${wfBase}/definition` },
  ]

  return <Tabs items={items} value={active} className="mb-4" />
}

export default function WorkflowLayout({ children }: { children: React.ReactNode }) {
  return (
    <WorkflowProvider>
      <PageContainer>
        <WorkflowDetailHeader />
        <WorkflowAutoPauseBanner />
        <WorkflowTabs />
        {children}
      </PageContainer>
    </WorkflowProvider>
  )
}
