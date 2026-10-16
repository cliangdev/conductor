// The target line under a metric: progress toward the target and, where a person may change it, the
// edit button. A target a connector feed owns (the in-app channel's) is read-only: it names where it is
// set and never offers an edit button.

import { readOnlyTargetSource, targetLine } from './format'
import type { OverviewMetric } from './types'

export function TargetNote({
  metric,
  canEdit,
  onEdit,
  buttonClassName = '',
}: {
  metric: OverviewMetric
  canEdit: boolean
  onEdit: () => void
  buttonClassName?: string
}) {
  const line = targetLine(metric)
  if (metric.target?.readOnly) {
    return (
      <p className="text-xs text-muted-foreground">
        {line}
        {' · '}
        <span>{readOnlyTargetSource(metric.target)}</span>
      </p>
    )
  }
  return (
    <p className="text-xs text-muted-foreground">
      {line ?? 'No target set'}
      {canEdit && (
        <>
          {' · '}
          <button
            type="button"
            onClick={onEdit}
            className={`rounded-sm font-medium text-primary hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${buttonClassName}`}
            aria-label={`${metric.target ? 'Edit' : 'Set'} target for ${metric.label}`}
          >
            {metric.target ? 'Edit target' : 'Set target'}
          </button>
        </>
      )}
    </p>
  )
}
