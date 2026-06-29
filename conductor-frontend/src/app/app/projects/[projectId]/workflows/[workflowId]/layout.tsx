'use client'

import { useState } from 'react'
import { useParams, usePathname, useRouter } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { apiPost } from '@/lib/api'
import { WorkflowRunDto } from '@/types/workflow'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader, type Crumb } from '@/components/layout/PageHeader'
import { Button } from '@/components/ui/button'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { Can } from '@/components/auth/Can'
import { WorkflowStatusBadge } from '@/components/workflow/WorkflowStatusBadge'
import { WorkflowProvider, useWorkflow } from '@/contexts/WorkflowContext'
import { useCan } from '@/contexts/PermissionsContext'

function parseTriggers(yaml: string): string[] {
  const triggers: string[] = []
  if (yaml.includes('conductor.issue.status_changed')) triggers.push('issue')
  if (yaml.includes('webhook:')) triggers.push('webhook')
  if (yaml.includes('workflow_dispatch')) triggers.push('manual')
  return triggers
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

  // The three tabs (Overview / Runs / Settings) are marked by the tab bar, so only the deeper
  // run-detail page adds extra crumbs (Runs → Run Detail) to navigate back up.
  const crumbs: Crumb[] = [
    { label: 'Workflows', href: base },
    { label: workflow?.name ?? 'Workflow', ...(onRunDetail ? { href: `${wfBase}/overview` } : {}) },
  ]
  if (onRunDetail) {
    crumbs.push({ label: 'Runs', href: `${wfBase}/runs` })
    crumbs.push({ label: 'Run Detail' })
  }

  if (!workflow) {
    return <PageHeader breadcrumbs={crumbs} title={<span className="text-muted-foreground">Loading…</span>} />
  }

  const triggers = parseTriggers(workflow.yaml ?? '')

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
      status={<WorkflowStatusBadge workflow={workflow} />}
      description={triggers.length > 0 ? `Triggers: ${triggers.join(', ')}` : undefined}
      actions={
        <Can do="workflow.run">
          <Button onClick={handleRun} disabled={!workflow.enabled || dispatching}>
            {dispatching ? 'Starting…' : '▶ Run'}
          </Button>
        </Can>
      }
    />
  )
}

function WorkflowTabs() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const pathname = usePathname()
  const canManage = useCan('workflow.manage')

  const wfBase = `/app/projects/${projectId}/workflows/${workflowId}`
  const active = pathname.includes(`${wfBase}/runs`)
    ? 'runs'
    : pathname.includes(`${wfBase}/settings`)
      ? 'settings'
      : 'overview'

  const items: TabItem[] = [
    { value: 'overview', label: 'Overview', href: `${wfBase}/overview` },
    { value: 'runs', label: 'Runs', href: `${wfBase}/runs` },
    ...(canManage ? [{ value: 'settings', label: 'Settings', href: `${wfBase}/settings` }] : []),
  ]

  return <Tabs items={items} value={active} className="mb-4" />
}

export default function WorkflowLayout({ children }: { children: React.ReactNode }) {
  return (
    <WorkflowProvider>
      <PageContainer>
        <WorkflowDetailHeader />
        <WorkflowTabs />
        {children}
      </PageContainer>
    </WorkflowProvider>
  )
}
