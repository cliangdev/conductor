'use client'

// COND-23 T6.3: what actually happened to a Post, per destination.
//
// The panel exists because a Post's single roll-up status cannot describe a partial send. A Post
// that reached Instagram and was refused by YouTube is neither "Published" nor "Failed" — it is
// *published to one account and needing attention on another*, and both halves have to be on screen
// at once. So every target keeps its own row: a success keeps its permalink, a failure keeps the
// platform's own words, and a retry re-fires only the rows that failed. Never collapse the
// successes because the roll-up says Failed — the permalink is the only proof a human has that the
// post is live, and hiding it invites a duplicate manual post.
//
// The error text is rendered verbatim. It is written by the platform ("The user has exceeded the
// number of videos they may upload"), and paraphrasing it into house language would lose the one
// detail that tells a human whether to retry now, retry tomorrow, or go fix something.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { ExternalLink, RotateCw } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { statusHue, statusHueClasses, type StatusHue } from '@/components/ui/status-badge'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiGet, apiPost } from '@/lib/api'
import { cn } from '@/lib/utils'
import {
  workflowDeclaresPublishTargets,
  type PublishLane,
  type PublishPlatform,
} from '@/components/marketing/PostTargetPicker'
import type { WorkflowView } from '@/types/workItem'

/** One publish target's outcome (a post_publish_target row as the v2 API returns it). */
export interface PublishOutcome {
  id: string
  workItemId: string
  platform: PublishPlatform
  connectorId: string | null
  /** Null on the MANUAL lane, which publishes through a human rather than an account. */
  connectionId: string | null
  /** Account label captured at selection time. `label` is the wire name; both are accepted. */
  platformAccountLabel?: string | null
  label?: string | null
  lane: PublishLane
  state: string
  permalink?: string | null
  errorMessage?: string | null
  platformPostId?: string | null
  fireTime?: string | null
  /** The copy that goes out here — this destination's own, or the Post's. */
  effectiveCaption?: string | null
  /** The ids of the media that goes out here, in order. */
  effectiveAssetIds?: string[]
}

interface RetryPublishResponse {
  workItemId: string
  status: string
  retriedCount: number
  targets: PublishOutcome[]
}

/** Render order, so the groups don't reshuffle between polls. Mirrors PostTargetPicker. */
const PLATFORM_ORDER: PublishPlatform[] = ['facebook', 'instagram', 'youtube', 'tiktok']

const PLATFORM_LABELS: Record<PublishPlatform, string> = {
  facebook: 'Facebook',
  instagram: 'Instagram',
  youtube: 'YouTube',
  tiktok: 'TikTok',
}

/**
 * Publish state → status-ramp hue. Explicit rather than left to `statusHue`, which only knows
 * PENDING and FAILED out of the six; the rest would silently land on gray and stop being
 * distinguishable. REVOKED is deliberately **slate** (the ramp's Closed/Skipped hue), not red: a
 * revocation is Conductor taking the post back off a platform after an approval stopped applying,
 * which is a withdrawal, not a failure — colouring it red would send someone hunting for a platform
 * error that never happened.
 */
const STATE_HUES: Record<string, StatusHue> = {
  PENDING: 'gray',
  HANDED_OFF: 'blue',
  PUBLISHING: 'blue',
  // Amber, not blue: this is the one state that is waiting on the person reading the screen. Every
  // other in-flight state is waiting on a machine and needs nothing from anybody.
  AWAITING_MANUAL: 'amber',
  PUBLISHED: 'green',
  FAILED: 'red',
  REVOKED: 'slate',
}

/** Human words for the wire states — the design system's "translate at the UI boundary" rule. */
const STATE_LABELS: Record<string, string> = {
  PENDING: 'Waiting',
  HANDED_OFF: 'Handed off',
  PUBLISHING: 'Publishing',
  AWAITING_MANUAL: 'Post it now',
  PUBLISHED: 'Published',
  FAILED: 'Failed',
  REVOKED: 'Taken back',
}

function stateHue(state: string): StatusHue {
  return STATE_HUES[state] ?? statusHue(state)
}

function stateLabel(state: string): string {
  return STATE_LABELS[state] ?? state
}

function accountLabel(target: PublishOutcome): string {
  return target.platformAccountLabel ?? target.label ?? target.connectionId ?? 'Manual'
}

function isManual(target: PublishOutcome): boolean {
  return target.lane === 'MANUAL'
}

/** A manual destination whose fire time has passed: it is waiting on the person reading this. */
function awaitsAHuman(target: PublishOutcome): boolean {
  return isManual(target) && target.state === 'AWAITING_MANUAL'
}

/** Strip the scheme so a permalink reads as a destination rather than a wall of URL. */
function permalinkText(permalink: string): string {
  return permalink.replace(/^https?:\/\//, '')
}

interface PublishOutcomePanelProps {
  projectId: string
  workItemId: string
  token: string
  workflowView?: WorkflowView
  /** Fired after a successful retry — the Post's own status moves too, so the parent must refresh. */
  onRetried?: () => void
}

export function PublishOutcomePanel({
  projectId,
  workItemId,
  token,
  workflowView,
  onRetried,
}: PublishOutcomePanelProps) {
  // Same gate as the picker, from the picker — an ENGINEERING item declares `github_pr`, has no
  // publish targets, and must not even ask for them.
  const declaresTargets = workflowDeclaresPublishTargets(workflowView)

  const [targets, setTargets] = useState<PublishOutcome[]>([])
  // Only ever "loading" when there is in fact a fetch to wait for; a gated-off panel starts settled
  // so the effect never has to set state just to say "nothing to do here".
  const [loading, setLoading] = useState(declaresTargets)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [retrying, setRetrying] = useState(false)
  /** The manual target whose "mark published" form is open, if any. One at a time. */
  const [completing, setCompleting] = useState<string | null>(null)

  useEffect(() => {
    if (!declaresTargets) return
    let cancelled = false
    apiGet<PublishOutcome[]>(
      `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets`,
      token
    )
      .then((current) => {
        if (cancelled) return
        setTargets(current)
        setLoadError(null)
      })
      .catch((err) => {
        if (cancelled) return
        setLoadError(apiErrorMessage(err, 'Could not load publishing results'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [declaresTargets, projectId, workItemId, token])

  const groups = useMemo(
    () =>
      PLATFORM_ORDER.map((platform) => ({
        platform,
        targets: targets.filter((t) => t.platform === platform),
      })).filter((group) => group.targets.length > 0),
    [targets]
  )

  const failedCount = targets.filter((t) => t.state === 'FAILED').length
  const publishedCount = targets.filter((t) => t.state === 'PUBLISHED').length
  const awaitingCount = targets.filter(awaitsAHuman).length

  /**
   * Records that a human posted a manual destination by hand.
   *
   * The response is the target as the server now holds it, so it is also the refresh — the same
   * reasoning as retry: no second GET, and no window where the panel shows a state already moved past.
   * The Post's own status can roll up on this call (the last outstanding target settling it), so the
   * parent is told to refresh exactly as it is after a retry.
   */
  const completeManual = useCallback(
    async (targetId: string, permalink: string, publishedAt: string | null) => {
      const updated = await apiPost<PublishOutcome>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets/${targetId}/manual-publish`,
        { permalink, publishedAt },
        token
      )
      setTargets((current) => current.map((t) => (t.id === updated.id ? updated : t)))
      setCompleting(null)
      onRetried?.()
    },
    [projectId, workItemId, token, onRetried]
  )

  const retry = useCallback(async () => {
    setRetrying(true)
    try {
      // The response carries every target, so the retry is also the refresh — no second GET, and no
      // window where the panel shows a state the server has already moved past.
      const result = await apiPost<RetryPublishResponse>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets/retry`,
        {},
        token
      )
      setTargets(result.targets)
      onRetried?.()
    } catch (err) {
      // Never swallow: the outcomes stay exactly as they were and the reason is said out loud.
      toastError(apiErrorMessage(err, 'Could not retry the failed accounts'))
    } finally {
      setRetrying(false)
    }
  }, [projectId, workItemId, token, onRetried])

  if (!declaresTargets) return null

  if (loading) {
    return (
      <Card>
        <CardHeader>
          <h2 className="text-sm font-medium text-foreground">Publishing results</h2>
        </CardHeader>
        <div className="space-y-2.5 px-4 py-3">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-4 w-32" />
        </div>
      </Card>
    )
  }

  if (loadError) {
    return (
      <Card>
        <CardHeader>
          <h2 className="text-sm font-medium text-foreground">Publishing results</h2>
        </CardHeader>
        <div className="px-4 py-3">
          <Alert variant="destructive">{loadError}</Alert>
        </div>
      </Card>
    )
  }

  // Nothing selected means nothing to report — the picker above already says so.
  if (targets.length === 0) return null

  return (
    <Card>
      <CardHeader>
        <h2 className="text-sm font-medium text-foreground">Publishing results</h2>
        {failedCount > 0 && (
          <Button variant="outline" size="sm" onClick={retry} disabled={retrying}>
            <RotateCw className={cn('mr-1.5 h-3.5 w-3.5', retrying && 'animate-spin')} />
            {retrying ? 'Retrying…' : 'Retry failed targets'}
          </Button>
        )}
      </CardHeader>

      {awaitingCount > 0 && (
        <div className="px-4 pt-3">
          <Alert variant="warning">
            {awaitingCount === 1
              ? 'One destination is due and publishes by hand. Post it, then paste the link back below.'
              : `${awaitingCount} destinations are due and publish by hand. Post each one, then paste its link back below.`}
          </Alert>
        </div>
      )}

      {failedCount > 0 && (
        <div className="px-4 pt-3">
          <Alert variant="warning">
            {publishedCount > 0
              ? `${publishedCount} account${publishedCount === 1 ? '' : 's'} published and ${failedCount} did not. Retrying re-sends only the failed ${failedCount === 1 ? 'one' : 'ones'} — what is already live stays live.`
              : `${failedCount} account${failedCount === 1 ? '' : 's'} could not publish.`}
          </Alert>
        </div>
      )}

      <div className="divide-y divide-border">
        {groups.map((group) => (
          <div key={group.platform} className="py-1.5">
            <div className="px-4 py-1 text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
              {PLATFORM_LABELS[group.platform]}
            </div>
            {group.targets.map((target) => (
              <OutcomeRow
                key={target.id}
                target={target}
                open={completing === target.id}
                onOpen={() => setCompleting(target.id)}
                onCancel={() => setCompleting(null)}
                onComplete={(permalink, publishedAt) =>
                  completeManual(target.id, permalink, publishedAt)
                }
              />
            ))}
          </div>
        ))}
      </div>
    </Card>
  )
}

interface OutcomeRowProps {
  target: PublishOutcome
  /** True when this row's "mark published" form is the open one. */
  open: boolean
  onOpen: () => void
  onCancel: () => void
  onComplete: (permalink: string, publishedAt: string | null) => Promise<void>
}

function OutcomeRow({ target, open, onOpen, onCancel, onComplete }: OutcomeRowProps) {
  const hue = statusHueClasses(stateHue(target.state))
  const awaiting = awaitsAHuman(target)

  return (
    <div className={cn(awaiting && 'bg-muted/40')}>
      <div className="flex items-start justify-between gap-3 px-4 py-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className={cn('h-1.5 w-1.5 shrink-0 rounded-full', hue.dot)} />
            <span className="truncate text-sm text-foreground">{accountLabel(target)}</span>
          </div>
          {target.permalink && (
            <a
              href={target.permalink}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-0.5 ml-3.5 inline-flex items-center gap-1 text-xs text-primary hover:underline"
            >
              <span className="truncate">{permalinkText(target.permalink)}</span>
              <ExternalLink className="h-3 w-3 shrink-0" aria-hidden="true" />
            </a>
          )}
          {awaiting && !open && (
            <>
              <p className="mt-0.5 ml-3.5 text-xs text-muted-foreground">
                Nothing is publishing this one — post it yourself, then record the link.
              </p>
              {/* What to post, not just that something must be posted: this destination may carry copy
                  and media of its own, and a person told only "post it" would go looking for them. */}
              {target.effectiveCaption && (
                <p className="mt-1 ml-3.5 whitespace-pre-wrap rounded-md border border-border bg-surface-2 px-2 py-1.5 text-xs text-foreground">
                  {target.effectiveCaption}
                </p>
              )}
              {target.effectiveAssetIds && target.effectiveAssetIds.length > 0 && (
                <p className="mt-1 ml-3.5 text-xs text-muted-foreground">
                  {target.effectiveAssetIds.length === 1
                    ? 'Post the file attached to this Post.'
                    : `Post ${target.effectiveAssetIds.length} files, in the order shown on the Post.`}
                </p>
              )}
            </>
          )}
          {target.errorMessage && (
            <p className={cn('mt-0.5 ml-3.5 text-xs', statusHueClasses('red').text)}>
              {target.errorMessage}
            </p>
          )}
        </div>
        <div className="flex shrink-0 items-center gap-2">
          {awaiting && !open && (
            <Button variant="outline" size="sm" onClick={onOpen}>
              Mark published
            </Button>
          )}
          <span className={cn('rounded-full px-2 py-0.5 text-xs font-medium', hue.bg, hue.text)}>
            {stateLabel(target.state)}
          </span>
        </div>
      </div>
      {open && <ManualPublishForm target={target} onCancel={onCancel} onComplete={onComplete} />}
    </div>
  )
}

/**
 * Records what a human already did: the link to the post they published by hand, and when.
 *
 * The link is required and the reason is not pedantry — there is no platform to ask, so it is the only
 * record this destination ever went out, and the thing the calendar, the Asset library and any later
 * reader all read. The time defaults to now but is editable, because the common case for filling this
 * in is a few hours after the fact and a wrong timestamp on a published post is quietly misleading.
 */
function ManualPublishForm({
  target,
  onCancel,
  onComplete,
}: {
  target: PublishOutcome
  onCancel: () => void
  onComplete: (permalink: string, publishedAt: string | null) => Promise<void>
}) {
  const [permalink, setPermalink] = useState('')
  const [publishedAt, setPublishedAt] = useState(() => localDateTimeValue(new Date()))
  const [saving, setSaving] = useState(false)
  const linkId = `manual-link-${target.id}`
  const timeId = `manual-time-${target.id}`

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!permalink.trim() || saving) return
    setSaving(true)
    try {
      await onComplete(permalink.trim(), publishedAt ? new Date(publishedAt).toISOString() : null)
    } catch (err) {
      // Never swallow: the row stays exactly as it was and the reason is said out loud.
      toastError(apiErrorMessage(err, 'Could not record this as published'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={submit} className="space-y-2.5 border-t border-border px-4 py-3">
      <div className="space-y-1">
        <label htmlFor={linkId} className="block text-xs font-medium text-foreground">
          Link to the published post
        </label>
        <input
          id={linkId}
          type="url"
          required
          autoFocus
          value={permalink}
          onChange={(e) => setPermalink(e.target.value)}
          placeholder="https://…"
          className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        />
      </div>
      <div className="space-y-1">
        <label htmlFor={timeId} className="block text-xs font-medium text-foreground">
          When it went out
        </label>
        <input
          id={timeId}
          type="datetime-local"
          value={publishedAt}
          onChange={(e) => setPublishedAt(e.target.value)}
          className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        />
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" size="sm" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={saving || !permalink.trim()}>
          {saving ? 'Recording…' : 'Record as published'}
        </Button>
      </div>
    </form>
  )
}

/**
 * `new Date()` as the value a `datetime-local` input accepts: local wall-clock, no zone, no seconds.
 * `toISOString` would be wrong here — it is UTC, and the input would show a time the user did not mean.
 */
function localDateTimeValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
