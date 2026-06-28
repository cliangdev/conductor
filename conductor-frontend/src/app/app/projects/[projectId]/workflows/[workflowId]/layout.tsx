'use client'

import { useParams, usePathname } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { Breadcrumb, type Crumb } from '@/components/layout/PageHeader'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { WorkflowProvider, useWorkflow } from '@/contexts/WorkflowContext'
import { useCan } from '@/contexts/PermissionsContext'

/**
 * Persistent breadcrumb for the whole `[workflowId]` sub-tree. Derived from the
 * URL + the once-fetched workflow name, so it stays mounted (no flicker) while
 * navigating between the workflow detail, Run History, and Run Detail pages —
 * only the page body below re-renders.
 */
function WorkflowBreadcrumb() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const pathname = usePathname()
  const { workflow } = useWorkflow()

  const base = `/app/projects/${projectId}/workflows`
  const wfBase = `${base}/${workflowId}`
  const onRuns = pathname.includes(`${wfBase}/runs`)
  const onRunDetail = /\/runs\/[^/]+$/.test(pathname)

  // The Breadcrumb component renders the final crumb as non-interactive, so
  // every crumb can carry an href safely.
  const crumbs: Crumb[] = [
    { label: 'Workflows', href: base },
    { label: workflow?.name ?? 'Workflow', href: wfBase },
  ]
  if (onRuns) crumbs.push({ label: 'Run History', href: `${wfBase}/runs` })
  if (onRunDetail) crumbs.push({ label: 'Run Detail' })

  return <Breadcrumb items={crumbs} className="mb-2" />
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
    { value: 'overview', label: 'Overview', href: wfBase },
    { value: 'runs', label: 'Runs', href: `${wfBase}/runs` },
    ...(canManage ? [{ value: 'settings', label: 'Settings', href: `${wfBase}/settings` }] : []),
  ]

  return <Tabs items={items} value={active} className="mb-4" />
}

export default function WorkflowLayout({ children }: { children: React.ReactNode }) {
  return (
    <WorkflowProvider>
      <PageContainer>
        <WorkflowBreadcrumb />
        <WorkflowTabs />
        {children}
      </PageContainer>
    </WorkflowProvider>
  )
}
