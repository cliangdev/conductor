'use client'

import { useParams, usePathname } from 'next/navigation'
import { PageContainer } from '@/components/layout/PageContainer'
import { Breadcrumb, type Crumb } from '@/components/layout/PageHeader'
import { WorkflowProvider, useWorkflow } from '@/contexts/WorkflowContext'

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

export default function WorkflowLayout({ children }: { children: React.ReactNode }) {
  return (
    <WorkflowProvider>
      <PageContainer>
        <WorkflowBreadcrumb />
        {children}
      </PageContainer>
    </WorkflowProvider>
  )
}
