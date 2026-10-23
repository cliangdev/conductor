// The target line under a metric: progress toward the target and, where a person may change it, the
// edit button. A target a connector feed owns (the in-app channel's) is read-only: it names where it is
// set and never offers an edit button.

import { TARGET_STATUS_CLASS, readOnlyTargetSource, targetStatusParts } from './format'
import type { OverviewMetric } from './types'

/** "On target · Target 200" with the status label coloured; plain text when there is no status to colour. */
export function TargetStatusLine({ metric }: { metric: OverviewMetric }) {
  const parts = targetStatusParts(metric)
  if (!parts) return null
  if (!parts.label) return <>{parts.detail}</>
  return (
    <>
      <span className={`font-medium ${parts.status ? TARGET_STATUS_CLASS[parts.status] : ''}`}>{parts.label}</span>
      {' · '}
      {parts.detail}
    </>
  )
}

export function TargetNote({
  metric,
  canEdit,
  onEdit,
  buttonClassName = '',
  editable = true,
  ownerLabel,
}: {
  metric: OverviewMetric
  canEdit: boolean
  onEdit: () => void
  buttonClassName?: string
  /** False when the channel's connection owns its targets: no edit button for any metric. */
  editable?: boolean
  /** The connection that owns the targets, named when they cannot be edited here. */
  ownerLabel?: string
}) {
  const line = metric.target ? <TargetStatusLine metric={metric} /> : null
  if (metric.target?.readOnly) {
    return (
      <p className="text-xs text-muted-foreground">
        {line}
        {' · '}
        <span>{readOnlyTargetSource(metric.target)}</span>
      </p>
    )
  }
  if (!editable) {
    return (
      <p className="text-xs text-muted-foreground">
        {line ?? 'No target set'}
        {' · '}
        {ownerLabel ? `set in ${ownerLabel}` : 'set in the connected source'}
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
