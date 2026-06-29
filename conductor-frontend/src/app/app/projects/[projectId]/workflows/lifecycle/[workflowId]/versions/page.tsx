'use client'

// Published version history for a lifecycle Workflow (the analogue of automation's Runs tab).

import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { WorkflowVersionHistory } from '@/components/workflow/lifecycle/WorkflowVersionHistory'

export default function LifecycleVersionsPage() {
  const { projectId, workflowId } = useParams<{ projectId: string; workflowId: string }>()
  const { accessToken } = useAuth()

  if (!accessToken) return <div className="text-muted-foreground">Loading…</div>

  return (
    <WorkflowVersionHistory projectId={projectId} workflowId={workflowId} token={accessToken} />
  )
}
