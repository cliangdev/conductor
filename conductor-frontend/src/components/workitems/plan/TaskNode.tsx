'use client'

// One node of the dependency graph: display id, title, status. A blocker outside the branch being drawn
// (`external`) is dashed so it reads as "somebody else's task".

import { Handle, Position } from '@xyflow/react'
import { cn } from '@/lib/utils'
import { StatusBadge } from '@/components/ui/status-badge'

export const TASK_NODE_W = 200
export const TASK_NODE_H = 64

export interface TaskNodeData {
  displayId: string
  title: string
  status: string
  statusCategory?: string | null
  external: boolean
  [key: string]: unknown
}

export function TaskNode({ data }: { data: TaskNodeData }) {
  return (
    <div
      data-external={data.external || undefined}
      className={cn(
        'rounded-lg border bg-surface px-3 py-2 text-xs',
        data.external ? 'border-dashed border-border-strong' : 'border-border'
      )}
      style={{ width: TASK_NODE_W, minHeight: TASK_NODE_H }}
    >
      <Handle type="target" position={Position.Left} className="!bg-border-strong" />
      <div className="font-mono text-[11px] text-muted-foreground">{data.displayId}</div>
      <div className="truncate text-[13px] font-medium text-foreground" title={data.title}>
        {data.title}
      </div>
      <div className="mt-1">
        <StatusBadge status={data.status} category={data.statusCategory ?? undefined} className="text-[11px]" />
      </div>
      <Handle type="source" position={Position.Right} className="!bg-border-strong" />
    </div>
  )
}
