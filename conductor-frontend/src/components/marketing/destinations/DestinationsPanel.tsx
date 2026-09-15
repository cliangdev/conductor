'use client'

// The Post page's hero: one card that says where the Post goes, where each destination is right now,
// and what needs a person — replacing the picker, the readiness card and the performance table that
// used to say those three things in three places. The header carries the schedule (a Post's "when"
// belongs with its "where"), the one summary strip, and at most a line or two of Post-level notices;
// everything about a single destination lives on its row.

import { useState } from 'react'
import { AlertCircle, Lock, Share2, TriangleAlert } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardHeader } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { DestinationTotals } from './DestinationMetrics'
import { DestinationRow } from './DestinationRow'
import { DestinationRowDetails, rowHasDetails } from './DestinationRowDetails'
import type { DestinationsState } from './model'

export interface DestinationsPanelProps {
  state: DestinationsState
  /** The schedule line, rendered under the title — the page owns the editor and its PATCH. */
  headerSlot?: React.ReactNode
  assets: MediaAsset[]
  caption: string | null
  /** False for a reader: no "Edit destinations" on an approved Post. */
  canEdit: boolean
  noun: string
  /** What the item is right now, for the locked line. */
  statusLabel: string
  title?: string
}

export function DestinationsPanel({
  state,
  headerSlot,
  assets,
  caption,
  canEdit,
  noun,
  statusLabel,
  title = 'Destinations',
}: DestinationsPanelProps) {
  const { rows, mode, summary, actions } = state
  /** The one row whose details are open, and the one whose "mark published" form is. */
  const [expandedKey, setExpandedKey] = useState<string | null>(null)
  const [completingKey, setCompletingKey] = useState<string | null>(null)

  // A row that stops existing (unticked, revoked) simply no longer matches its key; nothing to reset.
  const visible = rows.filter((r) => !r.hidden)
  const lowerNoun = noun.toLowerCase()

  const notices: React.ReactNode[] = []
  if (state.locked) {
    notices.push(
      <span key="locked" className="flex items-center gap-1.5">
        <Lock className="h-3.5 w-3.5 shrink-0" aria-hidden /> Locked while this {lowerNoun} is {statusLabel.toLowerCase()}.
      </span>
    )
  } else if (state.revertsOnEdit && state.editing) {
    notices.push(
      <span key="reverts" className="flex items-center gap-1.5 text-status-progress">
        <TriangleAlert className="h-3.5 w-3.5 shrink-0" aria-hidden /> Changing destinations sends this {lowerNoun} back
        for review and takes back anything already scheduled on a platform.
      </span>
    )
  } else if (state.revertsOnEdit && canEdit) {
    notices.push(
      <span key="edit" className="flex items-center gap-1.5">
        Changing destinations sends this {lowerNoun} back for review.
        <Button variant="link" size="sm" className="h-auto p-0 text-sm" onClick={actions.editDestinations}>
          Edit destinations
        </Button>
      </span>
    )
  }

  return (
    <Card data-testid="destinations-panel">
      <CardHeader className="items-start">
        <div className="min-w-0 space-y-1.5">
          <h2 className="text-sm font-semibold text-foreground">{title}</h2>
          {headerSlot}
          {notices.length > 0 && (
            <div className="space-y-1 text-sm text-muted-foreground">{notices}</div>
          )}
          {(state.postLevel.blockers.length > 0 || state.postLevel.warnings.length > 0 || state.consentError) && (
            <div className="space-y-1" data-testid="publish-readiness">
              {state.postLevel.blockers.map((f) => (
                <p key={f.code + f.message} className="flex items-start gap-1.5 text-sm text-status-failed">
                  <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                  <span>{f.message}</span>
                </p>
              ))}
              {state.postLevel.warnings.map((f) => (
                <p key={f.code + f.message} className="flex items-start gap-1.5 text-sm text-status-progress">
                  <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                  <span>{f.message}</span>
                </p>
              ))}
              {state.consentError && (
                <p className="flex items-start gap-1.5 text-sm text-status-failed">
                  <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                  <span>{state.consentError}</span>
                </p>
              )}
            </div>
          )}
        </div>
        {!state.loading && (
          <span className="shrink-0 whitespace-nowrap text-sm text-muted-foreground" data-testid="destinations-summary">
            {summary.text}
          </span>
        )}
      </CardHeader>

      {state.loading ? (
        <div className="space-y-2.5 px-4 py-3">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-4 w-32" />
        </div>
      ) : state.loadError ? (
        <div className="px-4 py-3">
          <Alert variant="destructive">{state.loadError}</Alert>
        </div>
      ) : visible.length === 0 ? (
        // Unreachable against a current backend, which always offers a manual destination per platform.
        // Kept as the honest rendering of an empty list rather than removed.
        <EmptyState
          icon={Share2}
          title="Nowhere to publish"
          description={`Connect a Facebook Page, Instagram, YouTube or TikTok account in Integrations to choose where this ${lowerNoun} publishes.`}
        />
      ) : (
        <>
          <fieldset disabled={state.saving || state.locked} className="min-w-0">
            <legend className="sr-only">Destinations</legend>
            <ul role="list" className="divide-y divide-border">
              {visible.map((row) => {
                const detailsId = `destination-${row.key.replace(/\s+/g, '-')}-details`
                const hasDetails = rowHasDetails(row, mode)
                const expanded = hasDetails && expandedKey === row.key
                return (
                  <DestinationRow
                    key={row.key}
                    row={row}
                    mode={mode}
                    expanded={expanded}
                    hasDetails={hasDetails}
                    detailsId={detailsId}
                    retrying={state.retrying}
                    onToggle={() => actions.toggle(row)}
                    onExpand={() => setExpandedKey((k) => (k === row.key ? null : row.key))}
                    onRetry={() => void actions.retry()}
                    onMarkPublished={() => {
                      setCompletingKey(row.key)
                      setExpandedKey(row.key)
                    }}
                  >
                    <DestinationRowDetails
                      row={row}
                      mode={mode}
                      assets={assets}
                      caption={caption}
                      saving={state.saving}
                      locked={state.locked}
                      completing={completingKey === row.key}
                      consentSaving={state.consentSaving}
                      consentError={state.consentError}
                      actions={actions}
                      onCancelComplete={() => setCompletingKey(null)}
                    />
                  </DestinationRow>
                )
              })}
            </ul>
          </fieldset>
          {summary.failed > 1 && (
            <div className="flex items-center justify-between gap-3 border-t border-border px-4 py-2 text-sm text-muted-foreground">
              <span>
                {summary.failed} destinations failed. Retrying re-sends only those — what is already live stays live.
              </span>
              <Button variant="outline" size="sm" onClick={() => void actions.retry()} disabled={state.retrying}>
                {state.retrying ? 'Retrying…' : 'Retry all failed'}
              </Button>
            </div>
          )}
          {(state.totals || state.unreportedNote) && (
            <div className="border-t border-border">
              <DestinationTotals totals={state.totals} observedAt={state.observedAt} note={state.unreportedNote} />
            </div>
          )}
        </>
      )}
    </Card>
  )
}
