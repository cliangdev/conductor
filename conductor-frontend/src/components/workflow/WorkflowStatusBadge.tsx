'use client'

// One status indicator for both workflow kinds, so the lifecycle and automation detail headers read
// the same. Lifecycle → PUBLISHED/DRAFT (+ version); automation → Enabled/Disabled. Always the shared
// <Badge>, never an ad-hoc colored dot.

import { Badge } from '@/components/ui/badge'
import { isLifecycleWorkflow } from '@/lib/workflows'
import type { WorkflowDefinitionDto } from '@/types/workflow'

export function WorkflowStatusBadge({ workflow }: { workflow: WorkflowDefinitionDto }) {
  if (isLifecycleWorkflow(workflow)) {
    const state = workflow.state ?? 'DRAFT'
    const variant =
      state === 'PUBLISHED' ? 'status-done' : state === 'DISABLED' ? 'secondary' : 'status-draft'
    return (
      <span className="flex items-center gap-2">
        <Badge variant={variant}>{state}</Badge>
        {workflow.version != null && (
          <span className="text-xs text-muted-foreground">v{workflow.version}</span>
        )}
      </span>
    )
  }
  if (workflow.autoPausedAt) {
    return <Badge variant="status-failed">Auto-paused</Badge>
  }
  return (
    <Badge variant={workflow.enabled ? 'status-done' : 'secondary'}>
      {workflow.enabled ? 'Enabled' : 'Disabled'}
    </Badge>
  )
}

export default WorkflowStatusBadge
