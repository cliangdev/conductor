'use client'

// The properties panel's progress block. A PRD with child Work Items shows the server rollup (tasks done,
// criteria met); one without falls back to the legacy tasks.json checklist, unchanged.

import { TaskProgressPanel } from '@/components/issues/TaskProgressPanel'
import { PanelSection } from '@/components/workitems/PanelSection'
import type { WorkItemProgress } from '@/types/workItem'

function Bar({ label, done, total }: { label: string; done: number; total: number }) {
  const pct = total === 0 ? 0 : Math.round((done / total) * 100)
  return (
    <div>
      <div className="mb-1 flex items-center justify-between text-xs text-muted-foreground">
        <span>{label}</span>
        <span className="tabular-nums">
          {done}/{total}
        </span>
      </div>
      <div
        role="progressbar"
        aria-label={label}
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        className="h-1.5 w-full overflow-hidden rounded-full bg-muted"
      >
        <div className="h-full rounded-full bg-primary transition-all" style={{ width: `${pct}%` }} />
      </div>
    </div>
  )
}

export function ChildProgressPanel({
  projectId,
  issueId,
  childCount,
  progress,
}: {
  projectId: string
  issueId: string
  childCount?: number
  progress?: WorkItemProgress | null
}) {
  if (!childCount || childCount <= 0 || !progress) {
    return <TaskProgressPanel issueId={issueId} projectId={projectId} />
  }
  return (
    <PanelSection label="Progress">
      <div className="space-y-3">
        <Bar label="Tasks done" done={progress.done} total={progress.total} />
        {progress.criteriaTotal > 0 && <Bar label="Criteria met" done={progress.criteriaMet} total={progress.criteriaTotal} />}
      </div>
    </PanelSection>
  )
}
