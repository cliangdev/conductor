'use client'

// Persistent shell for a lifecycle Workflow's detail — mirrors the automation [workflowId] layout so
// both kinds share the same chrome: WorkflowProvider (one fetch shared across tabs), one PageHeader
// (breadcrumb + title + WorkflowStatusBadge), and a tab bar. Tabs: Overview / Versions / Editor
// (Editor gated on workflow.manage; lifecycle's mutations live there, the way automation's live in
// Settings).

import Link from 'next/link'
import { useParams, usePathname } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader, type Crumb } from '@/components/layout/PageHeader'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { WorkflowStatusBadge } from '@/components/workflow/WorkflowStatusBadge'
import { WorkflowProvider, useWorkflow } from '@/contexts/WorkflowContext'
import { useCan } from '@/contexts/PermissionsContext'
import { isLifecycleWorkflow } from '@/lib/workflows'

function LifecycleShell({ children }: { children: React.ReactNode }) {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const pathname = usePathname()
  const { workflow, loading } = useWorkflow()
  const canManage = useCan('workflow.manage')

  const base = `/app/projects/${projectId}/workflows`
  const wfBase = `${base}/lifecycle/${workflowId}`
  const crumbs: Crumb[] = [
    { label: 'Workflows', href: base },
    { label: workflow?.name ?? 'Workflow' },
  ]

  // Opened an automation under the lifecycle route — send the user to the automation editor.
  if (!loading && workflow && !isLifecycleWorkflow(workflow)) {
    return (
      <>
        <PageHeader breadcrumbs={crumbs} title={workflow.name} />
        <p className="text-sm text-muted-foreground">
          This is an automation workflow.{' '}
          <Link className="text-primary hover:underline" href={`${base}/${workflowId}/overview`}>
            Open it in the automation editor.
          </Link>
        </p>
      </>
    )
  }

  const active = pathname.includes(`${wfBase}/versions`)
    ? 'versions'
    : pathname.includes(`${wfBase}/editor`)
      ? 'editor'
      : 'overview'

  const items: TabItem[] = [
    { value: 'overview', label: 'Overview', href: `${wfBase}/overview` },
    { value: 'versions', label: 'Versions', href: `${wfBase}/versions` },
    ...(canManage ? [{ value: 'editor', label: 'Editor', href: `${wfBase}/editor` }] : []),
  ]

  return (
    <>
      <PageHeader
        breadcrumbs={crumbs}
        title={workflow?.name ?? <span className="text-muted-foreground">Loading…</span>}
        status={workflow ? <WorkflowStatusBadge workflow={workflow} /> : undefined}
      />
      <Tabs items={items} value={active} className="mb-4" />
      {children}
    </>
  )
}

export default function LifecycleWorkflowLayout({ children }: { children: React.ReactNode }) {
  return (
    <WorkflowProvider>
      <PageContainer>
        <LifecycleShell>{children}</LifecycleShell>
      </PageContainer>
    </WorkflowProvider>
  )
}
