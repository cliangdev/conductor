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
  connectorId: string
  connectionId: string
  /** Account label captured at selection time. `label` is the wire name; both are accepted. */
  platformAccountLabel?: string | null
  label?: string | null
  lane: PublishLane
  state: string
  permalink?: string | null
  errorMessage?: string | null
  platformPostId?: string | null
  fireTime?: string | null
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
  PUBLISHED: 'green',
  FAILED: 'red',
  REVOKED: 'slate',
}

/** Human words for the wire states — the design system's "translate at the UI boundary" rule. */
const STATE_LABELS: Record<string, string> = {
  PENDING: 'Waiting',
  HANDED_OFF: 'Handed off',
  PUBLISHING: 'Publishing',
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
  return target.platformAccountLabel ?? target.label ?? target.connectionId
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
              <OutcomeRow key={target.id} target={target} />
            ))}
          </div>
        ))}
      </div>
    </Card>
  )
}

function OutcomeRow({ target }: { target: PublishOutcome }) {
  const hue = statusHueClasses(stateHue(target.state))

  return (
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
        {target.errorMessage && (
          <p className={cn('mt-0.5 ml-3.5 text-xs', statusHueClasses('red').text)}>
            {target.errorMessage}
          </p>
        )}
      </div>
      <span
        className={cn(
          'shrink-0 rounded-full px-2 py-0.5 text-xs font-medium',
          hue.bg,
          hue.text
        )}
      >
        {stateLabel(target.state)}
      </span>
    </div>
  )
}
