'use client'

// One status indicator for both workflow kinds, so the lifecycle and automation detail headers read
// the same. Lifecycle → PUBLISHED/DRAFT (+ version); automation → Enabled/Disabled. Always the shared
// <Badge>, never an ad-hoc colored dot.

import { Badge } from '@/components/ui/badge'
import { isLifecycleWorkflow } from '@/lib/workflows'
import type { WorkflowDefinitionDto } from '@/types/workflow'

export function WorkflowStatusBadge({ workflow }: { workflow: WorkflowDefinitionDto }) {
  if (isLifecycleWorkflow(workflow)) {
    const published = workflow.state === 'PUBLISHED'
    return (
      <span className="flex items-center gap-2">
        <Badge variant={published ? 'status-done' : 'status-draft'}>{workflow.state ?? 'DRAFT'}</Badge>
        {workflow.version != null && (
          <span className="text-xs text-muted-foreground">v{workflow.version}</span>
        )}
      </span>
    )
  }
  return (
    <Badge variant={workflow.enabled ? 'status-done' : 'secondary'}>
      {workflow.enabled ? 'Enabled' : 'Disabled'}
    </Badge>
  )
}

export default WorkflowStatusBadge
