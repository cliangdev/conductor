'use client'

import { cn } from '@/lib/utils'
import { statusHueClasses } from '@/components/ui/status-badge'
import { statusHue } from '@/lib/workflows'
import type { StepRunStatus } from './StepNode'

export interface JobFrameNodeData {
  jobId: string
  runsOn?: string
  iteration?: { current?: number; max?: number }
  status?: StepRunStatus
  [key: string]: unknown
}

/** The background swimlane a job's step nodes sit inside (xyflow parent/child: step nodes carry
 * parentId + extent: 'parent'). Renders behind its children (frames are added to the node array
 * before their steps, and xyflow z-orders by array position), so it must never intercept clicks
 * meant for a step card. */
export function JobFrameNode({ data }: { data: JobFrameNodeData }) {
  const { jobId, runsOn, iteration, status } = data
  const tint = status ? statusHueClasses(statusHue(status)) : undefined

  return (
    <div
      className={cn(
        'h-full w-full rounded-xl border border-dashed bg-surface-2/60',
        tint ? tint.border : 'border-border',
      )}
      style={{ pointerEvents: 'none' }}
    >
      <div className="flex items-center gap-2 px-3 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-muted-foreground">
        <span className="truncate">{jobId}</span>
        {runsOn && (
          <span className="rounded-full bg-surface-3 px-1.5 py-0.5 text-[10px] font-medium normal-case text-foreground-subtle">
            {runsOn}
          </span>
        )}
        {iteration && (
          <span className="rounded-full bg-surface-3 px-1.5 py-0.5 text-[10px] font-medium normal-case text-foreground-subtle">
            {iteration.current ?? 0}{iteration.max != null ? `/${iteration.max}` : ''}
          </span>
        )}
      </div>
    </div>
  )
}
