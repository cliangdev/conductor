'use client'

import { useState } from 'react'
import { useParams, usePathname, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiPost, apiPatch, apiDelete, apiErrorMessage } from '@/lib/api'
import { WorkflowDefinitionDto, WorkflowRunDto } from '@/types/workflow'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader, type Crumb } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { Alert } from '@/components/ui/alert'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { CopyableId } from '@/components/ui/copyable-id'
import { MoreHorizontalIcon, PlayIcon, BanIcon, CheckCircleIcon, Trash2Icon } from 'lucide-react'
import { Can } from '@/components/auth/Can'
import { WorkflowStatusBadge } from '@/components/workflow/WorkflowStatusBadge'
import { TriggerBadges } from '@/components/workflow/TriggerBadges'
import { WorkflowProvider, useWorkflow } from '@/contexts/WorkflowContext'
import { useCan } from '@/contexts/PermissionsContext'
import { allowsManualDispatch } from '@/lib/workflows'
import { useToast } from '@/components/ui/toast'
import { timeAgo } from '@/lib/format'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'

/** The single overflow ("…") menu holding the two lifecycle actions that used to live in the
 *  deleted Settings tab. Enable is instant (non-destructive, reversible in one click, and this
 *  keeps it consistent with the workflows list page's own instant enable and the auto-pause
 *  banner's instant "Re-enable") — Disable and Delete confirm through the shared `ConfirmModal`,
 *  never a hand-rolled modal or native `confirm()`. */
function WorkflowOverflowMenu() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const { accessToken } = useAuth()
  const router = useRouter()
  const { showToast } = useToast()
  const { workflow, setWorkflow } = useWorkflow()
  const [confirmDisable, setConfirmDisable] = useState(false)
  const [toggling, setToggling] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)

  if (!workflow) return null
  const willEnable = !workflow.enabled

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

  const handleDelete = async () => {
    if (!accessToken) return
    setDeleting(true)
    try {
      await apiDelete(`/api/v1/projects/${projectId}/workflows/${workflowId}`, accessToken)
      router.push(`/app/projects/${projectId}/workflows`)
    } catch (e) {
      showToast(apiErrorMessage(e, 'Failed to delete workflow.'), 'error')
      setDeleting(false)
    }
  }

  return (
    <Can do="workflow.manage">
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            aria-label="More actions"
            className="rounded p-2 text-muted-foreground hover:bg-muted/50 hover:text-foreground focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <MoreHorizontalIcon className="h-4 w-4" />
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            onSelect={() => (willEnable ? setEnabled(true) : setConfirmDisable(true))}
            disabled={toggling}
            className="gap-2"
          >
            {willEnable ? <CheckCircleIcon className="h-4 w-4" /> : <BanIcon className="h-4 w-4" />}
            {willEnable ? 'Enable workflow' : 'Disable workflow'}
          </DropdownMenuItem>
          <DropdownMenuItem
            onSelect={() => setConfirmDelete(true)}
            className="gap-2 text-destructive focus:text-destructive"
          >
            <Trash2Icon className="h-4 w-4" />
            Delete workflow
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>

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

      <ConfirmModal
        open={confirmDelete}
        title="Delete workflow"
        confirmLabel="Delete workflow"
        busyLabel="Deleting…"
        busy={deleting}
        onConfirm={handleDelete}
        onCancel={() => setConfirmDelete(false)}
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{workflow.name}</strong>? Its run history will be removed and this cannot be undone.
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
          <WorkflowStatusBadge workflow={workflow} />
          <TriggerBadges yaml={workflow.yaml ?? ''} />
        </>
      }
      actions={
        <>
          {canDispatch && (
            <Can do="workflow.run">
              <Button onClick={handleRun} disabled={!workflow.enabled || dispatching} className="gap-1.5">
                {dispatching ? 'Starting…' : (<><PlayIcon className="h-3.5 w-3.5" /> Run</>)}
              </Button>
            </Can>
          )}
          <WorkflowOverflowMenu />
        </>
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
