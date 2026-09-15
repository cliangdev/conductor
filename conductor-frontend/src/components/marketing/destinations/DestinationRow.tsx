'use client'

// One destination, one line: who it goes to, where it is, what it did, and the one thing it offers to
// do. Below `sm` the line wraps into two — account and action first, then state, time and numbers — so
// nothing is hidden on a phone. Everything below the line (a note about the account, a blocker the
// preflight raised, the expanded editors or outcome) is the row's details, opened one row at a time by
// the panel.

import { AlertCircle, ChevronDown, ChevronRight, ExternalLink, RotateCw, TriangleAlert } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { ShowDetails } from '@/components/ui/show-details'
import { StatusBadge } from '@/components/ui/status-badge'
import { FormatBadge } from '@/components/marketing/PostFormatSelector'
import { cn } from '@/lib/utils'
import { DestinationMetrics } from './DestinationMetrics'
import { PlatformIcon } from './PlatformIcon'
import type { DestinationMode, DestinationRowModel } from './model'
import { permalinkText } from './publishState'

export interface DestinationRowProps {
  row: DestinationRowModel
  mode: DestinationMode
  /** Whether this row's details are the open ones. */
  expanded: boolean
  /** Whether this row has details to open at all — the panel decides, since it renders them. */
  hasDetails: boolean
  detailsId: string
  retrying: boolean
  onToggle: () => void
  onExpand: () => void
  onRetry: () => void
  onMarkPublished: () => void
  /** The details region, rendered by the panel; only mounted while `expanded`. */
  children?: React.ReactNode
}

export function DestinationRow({
  row,
  mode,
  expanded,
  hasDetails,
  detailsId,
  retrying,
  onToggle,
  onExpand,
  onRetry,
  onMarkPublished,
  children,
}: DestinationRowProps) {
  const { option, target } = row
  const picking = mode === 'pick' && !row.settled
  // An unhealthy account can't be added — its credentials no longer work — but one already on the
  // Post stays actionable, or a human could never take it back off.
  const disabled = row.unhealthy && !row.checked
  const noteId = `${detailsId}-note`

  const identity = (
    <span className="flex min-w-0 items-center gap-2">
      <PlatformIcon platform={option.platform} />
      <span className="truncate text-sm font-medium text-foreground">{option.label}</span>
      <FormatBadge format={row.format} />
      {row.manual && (
        <span className="rounded-full border border-border px-1.5 text-[10px] font-medium leading-4 text-muted-foreground">
          by hand
        </span>
      )}
    </span>
  )

  const action =
    row.action === 'retry' ? (
      <Button variant="outline" size="sm" onClick={onRetry} disabled={retrying}>
        <RotateCw className={cn('mr-1.5 h-3.5 w-3.5', retrying && 'animate-spin')} aria-hidden />
        {retrying ? 'Retrying…' : 'Retry'}
      </Button>
    ) : row.action === 'mark-published' ? (
      <Button variant="outline" size="sm" onClick={onMarkPublished}>
        Mark published
      </Button>
    ) : row.action === 'review-consent' ? (
      <Button variant="outline" size="sm" onClick={onExpand} aria-expanded={expanded} aria-controls={detailsId}>
        Review &amp; consent
      </Button>
    ) : null

  return (
    <li className={cn(disabled && 'opacity-60')} data-testid={`destination-row-${row.key.replace(/\s+/g, '-')}`}>
      <div className={cn('flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2', !disabled && 'hover:bg-muted/40')}>
        <div className="flex min-w-0 flex-1 basis-full items-center sm:basis-auto">
          {picking ? (
            <Checkbox
              checked={row.checked}
              disabled={disabled}
              onCheckedChange={onToggle}
              aria-describedby={row.note ? noteId : undefined}
              label={identity}
              className="min-w-0 flex-1 items-center"
            />
          ) : (
            identity
          )}
        </div>

        <div
          className={cn(
            'flex basis-full flex-wrap items-center gap-x-2.5 gap-y-1 text-xs text-muted-foreground sm:basis-auto sm:pl-0',
            picking ? 'pl-[26px]' : 'pl-7'
          )}
        >
          {row.settled && row.stateLabel && (
            <StatusBadge status={target?.state ?? ''} hue={row.hue ?? undefined} label={row.stateLabel} />
          )}
          {row.timeText && <span className="whitespace-nowrap">{row.timeText}</span>}
          {row.metrics && <DestinationMetrics metrics={row.metrics} />}
          {row.settled && target?.permalink && (
            <a
              href={target.permalink}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-primary hover:underline"
              aria-label={`Open on ${permalinkText(target.permalink)}`}
            >
              <ExternalLink className="h-3.5 w-3.5" aria-hidden />
            </a>
          )}
          {!row.settled && row.customized && mode === 'pick' && (
            <span className="whitespace-nowrap">customized</span>
          )}
        </div>

        <div className="ml-auto flex shrink-0 items-center gap-1.5">
          {action}
          {hasDetails && (
            <button
              type="button"
              onClick={onExpand}
              aria-expanded={expanded}
              aria-controls={detailsId}
              aria-label={expanded ? 'Hide details' : 'Show details'}
              className="inline-flex h-7 w-7 items-center justify-center rounded-md text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              {expanded ? <ChevronDown className="h-4 w-4" aria-hidden /> : <ChevronRight className="h-4 w-4" aria-hidden />}
            </button>
          )}
        </div>
      </div>

      {(row.note || row.blockers.length > 0 || row.warnings.length > 0) && (
        <div className={cn('space-y-1 px-4 pb-2', picking ? 'sm:pl-[42px]' : 'sm:pl-11')}>
          {row.note && (
            <div>
              <span
                id={noteId}
                className={cn('block text-sm', row.noteTone === 'amber' ? 'text-status-progress' : 'text-muted-foreground')}
              >
                {row.note}
              </span>
              <ShowDetails detail={row.noteDetail} message={row.note} />
            </div>
          )}
          {row.blockers.map((f) => (
            <p key={f.code + f.message} className="flex items-start gap-1.5 text-sm text-status-failed">
              <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>{f.message}</span>
            </p>
          ))}
          {row.warnings.map((f) => (
            <p key={f.code + f.message} className="flex items-start gap-1.5 text-sm text-status-progress">
              <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
              <span>{f.message}</span>
            </p>
          ))}
        </div>
      )}

      {expanded && children && (
        <div id={detailsId} className={cn('px-4 pb-3', picking ? 'sm:pl-[42px]' : 'sm:pl-11')}>
          {children}
        </div>
      )}
    </li>
  )
}
