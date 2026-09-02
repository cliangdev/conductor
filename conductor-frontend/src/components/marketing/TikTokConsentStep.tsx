'use client'

// TIK-2: the consent TikTok's audit requires.
//
// TikTok's Content Sharing Guidelines are explicit that a creator must see a preview of the content
// and the account nickname it will post to, and must expressly consent, *before* anything is
// uploaded — a consent flow that doesn't match this is the most commonly cited reason for audit
// rejection. So this is a gate, not a notice: until the creator has seen the preview, read the
// destination handle, and ticked the box, a Post carrying a TikTok target can't be sent for review.
//
// The gate is published through a context rather than drilled down the properties panel, so the one
// status control (StatusDropdown) can read it wherever it happens to be rendered.
//
// MKT-1: the consent itself lives on the server, not in this component. It used to be a boolean in
// React state, which meant it did not survive a reload and — much worse — did not exist at all for
// any client that is not this one. The backend now records what was consented to (the accounts, their
// publish options, the media) and refuses the review-gated transition without it, so this component's
// job is to show the preview, PUT the creator's answer, and render back what the server says. Pass
// `projectId`/`workItemId`/`token` to get that; without them it stays the controlled component it was.

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react'
import { AtSign, ImageOff } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Card, CardHeader } from '@/components/ui/card'
import { apiErrorMessage, apiGet, apiPut } from '@/lib/api'
import { isVideoContentType } from '@/components/workitems/MediaUploadPanel'
import {
  privacyLevelLabel,
  type TikTokPublishOptionValues,
} from '@/components/marketing/TikTokPublishOptions'

/** One selected TikTok destination, as the picker reports it. */
export interface TikTokConsentTarget {
  connectionId: string
  label: string
  /** The handle TikTok reports for the account; null when the connection predates that field. */
  creatorNickname: string | null
  options: TikTokPublishOptionValues
  /** Why this target isn't postable yet (see tiktokOptionsProblem); null when it is. */
  problem: string | null
}

export interface TikTokPreviewAsset {
  id: string
  label?: string
  contentType?: string | null
  previewUrl?: string | null
}

const CONSENT_PROMPT =
  'Review the preview and the destination account, then consent, before this can be sent for review.'

const CONSENT_SUPERSEDED_PROMPT =
  'The accounts, their options or the media have changed since you consented. Review the preview and consent again before this can be sent for review.'

// ── the persisted consent ───────────────────────────────────────────────────

/**
 * Why consent does or doesn't stand right now, as the backend reports it. `SUPERSEDED` is the one
 * worth telling apart: the creator is looking at a box they know they ticked, and "please consent"
 * alone would read as a bug rather than as "this is not the post you agreed to any more".
 */
export type PublishConsentVerdict = 'NOT_REQUIRED' | 'VALID' | 'NEVER_GIVEN' | 'SUPERSEDED'

export interface PublishConsentState {
  workItemId: string
  /** True when the Post carries at least one TikTok target. */
  required: boolean
  /** True only while consent covers the Post exactly as it is now. */
  valid: boolean
  verdict: PublishConsentVerdict
  consentedAt?: string | null
  consentedByUserId?: string | null
  consentedByName?: string | null
}

function consentPath(projectId: string, workItemId: string): string {
  return `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-consent`
}

export function fetchPublishConsent(
  projectId: string,
  workItemId: string,
  token: string
): Promise<PublishConsentState> {
  return apiGet<PublishConsentState>(consentPath(projectId, workItemId), token)
}

export function recordPublishConsent(
  projectId: string,
  workItemId: string,
  consented: boolean,
  token: string
): Promise<PublishConsentState> {
  return apiPut<PublishConsentState>(consentPath(projectId, workItemId), { consented }, token)
}

/**
 * Why a Post carrying TikTok targets can't be submitted for approval yet — null when it can, and
 * always null for a Post with no TikTok target at all.
 */
export function tiktokSubmissionBlockedReason(
  targets: TikTokConsentTarget[],
  consented: boolean,
  verdict?: PublishConsentVerdict
): string | null {
  if (targets.length === 0) return null
  const unresolved = targets.find((t) => t.problem)
  if (unresolved) return `${unresolved.label}: ${unresolved.problem}`
  if (consented) return null
  return verdict === 'SUPERSEDED' ? CONSENT_SUPERSEDED_PROMPT : CONSENT_PROMPT
}

// ── the gate, for the status control ────────────────────────────────────────

const TikTokPublishGateContext = createContext<string | null>(null)

export function TikTokPublishGateProvider({
  reason,
  children,
}: {
  reason: string | null
  children: ReactNode
}) {
  return (
    <TikTokPublishGateContext.Provider value={reason}>{children}</TikTokPublishGateContext.Provider>
  )
}

/** The blocking explanation a status control should refuse an approval-bound move with, or null. */
export function useTikTokPublishGate(): string | null {
  return useContext(TikTokPublishGateContext)
}

// ── the step ────────────────────────────────────────────────────────────────

/** The handle a creator would recognise, with exactly one leading `@`. */
function destinationName(target: TikTokConsentTarget): string {
  const nickname = target.creatorNickname?.trim()
  if (!nickname) return target.label
  return nickname.startsWith('@') ? nickname : `@${nickname}`
}

/** What the post will carry, in the same words the options panel used. */
function optionsSummary(options: TikTokPublishOptionValues): string {
  const parts: string[] = [
    options.privacyLevel ? privacyLevelLabel(options.privacyLevel) : 'No privacy level chosen',
  ]
  if (options.disableComment) parts.push('Comments off')
  if (options.disableDuet) parts.push('Duet off')
  if (options.disableStitch) parts.push('Stitch off')
  if (options.brandOrganicToggle) parts.push('Promotional content')
  if (options.brandContentToggle) parts.push('Paid partnership')
  return parts.join(' · ')
}

interface TikTokConsentStepProps {
  targets: TikTokConsentTarget[]
  assets: TikTokPreviewAsset[]
  /** The Post's own words, shown with the media so the preview is the whole thing being consented to. */
  caption?: string
  /**
   * The three that make consent persistent. Supply all of them and the step reads and writes the
   * creator's consent through the API — which is what makes it survive a reload and, far more
   * importantly, what makes it the same consent the backend gates the transition on. Omit them and
   * the step stays a controlled component driven by `consented`/`onConsentChange`.
   */
  projectId?: string
  workItemId?: string
  token?: string
  consented?: boolean
  /** Told the current answer whenever it changes, including the first read back from the server. */
  onConsentChange?: (consented: boolean) => void
  disabled?: boolean
}

/**
 * Renders nothing for a Post with no TikTok target — and, because the hooks live in the inner
 * component, asks the server nothing about it either. A Facebook-only Post is untouched by all of
 * this, exactly as the gate that reads it is.
 */
export function TikTokConsentStep(props: TikTokConsentStepProps) {
  if (props.targets.length === 0) return null
  return <TikTokConsentStepBody {...props} />
}

function TikTokConsentStepBody({
  targets,
  assets,
  caption,
  projectId,
  workItemId,
  token,
  consented = false,
  onConsentChange,
  disabled,
}: TikTokConsentStepProps) {
  const persisted = Boolean(projectId && workItemId && token)

  const [server, setServer] = useState<PublishConsentState | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The parent's handler is an inline arrow, so it is a new function every render; holding it in a
  // ref keeps it out of the effect's dependencies instead of re-fetching on every render.
  const notify = useRef(onConsentChange)
  useEffect(() => {
    notify.current = onConsentChange
  }, [onConsentChange])

  const publish = useCallback((state: PublishConsentState | null) => {
    setServer(state)
    notify.current?.(Boolean(state?.valid))
  }, [])

  // What the creator is being asked to consent to. When it changes — an account swapped, a privacy
  // level edited, a different cut uploaded — the server's answer changes with it, so re-read rather
  // than keeping a stale "yes" on screen.
  const subject = JSON.stringify([targets, assets.map((asset) => asset.id)])

  useEffect(() => {
    if (!persisted) return
    let cancelled = false
    fetchPublishConsent(projectId!, workItemId!, token!)
      .then((state) => {
        if (cancelled) return
        setError(null)
        publish(state)
      })
      .catch((e) => {
        if (cancelled) return
        // Fail closed: an unreadable consent is not a given one.
        setError(apiErrorMessage(e, 'Could not read this post’s TikTok consent.'))
        publish(null)
      })
    return () => {
      cancelled = true
    }
  }, [persisted, projectId, workItemId, token, subject, publish])

  const given = persisted ? Boolean(server?.valid) : consented

  async function changeConsent(next: boolean) {
    if (!persisted) {
      onConsentChange?.(next)
      return
    }
    setSaving(true)
    try {
      publish(await recordPublishConsent(projectId!, workItemId!, next, token!))
      setError(null)
    } catch (e) {
      setError(apiErrorMessage(e, 'Could not record your TikTok consent.'))
    } finally {
      setSaving(false)
    }
  }

  const blockedReason = tiktokSubmissionBlockedReason(targets, given, server?.verdict)
  const unresolved = targets.some((t) => t.problem)
  const anyPaidPartnership = targets.some((t) => t.options.brandContentToggle)

  return (
    <Card>
      <CardHeader>
        <h2 className="text-sm font-medium text-foreground">Confirm your TikTok post</h2>
      </CardHeader>

      <div className="space-y-4 p-4">
        <ul className="space-y-2">
          {targets.map((target) => (
            <li
              key={target.connectionId}
              className="rounded-md border border-border bg-surface-raised px-3 py-2.5"
            >
              <span className="flex items-center gap-2">
                <AtSign className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
                <span className="text-xs text-muted-foreground">You are posting to</span>
                <span className="truncate text-sm font-semibold text-foreground">
                  {destinationName(target)}
                </span>
              </span>
              <span className="mt-1 block text-xs text-muted-foreground">
                {optionsSummary(target.options)}
              </span>
            </li>
          ))}
        </ul>

        <div className="overflow-hidden rounded-md border border-border">
          {assets.length === 0 ? (
            <p className="flex items-center gap-2 px-3 py-4 text-sm text-muted-foreground">
              <ImageOff className="h-4 w-4 shrink-0" aria-hidden />
              No media has been uploaded yet — TikTok needs the video before this post can go out.
            </p>
          ) : (
            <ul className="divide-y divide-border">
              {assets.map((asset) => (
                <li key={asset.id}>
                  {asset.previewUrl ? (
                    isVideoContentType(asset.contentType) ? (
                      <video
                        controls
                        src={asset.previewUrl}
                        className="block max-h-80 w-full bg-surface-3"
                        aria-label={asset.label || 'TikTok video'}
                      />
                    ) : (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img
                        src={asset.previewUrl}
                        alt={asset.label || 'TikTok post media'}
                        className="block max-h-80 w-full bg-surface-3 object-contain"
                      />
                    )
                  ) : (
                    <p className="px-3 py-4 text-sm text-muted-foreground">
                      {asset.label} is still uploading — no preview yet.
                    </p>
                  )}
                </li>
              ))}
            </ul>
          )}
          {caption && (
            <p className="border-t border-border px-3 py-2 text-sm text-foreground">{caption}</p>
          )}
        </div>

        <label className="flex items-start gap-2.5">
          <input
            type="checkbox"
            className="mt-0.5 rounded border-border"
            checked={given}
            disabled={disabled || unresolved || saving}
            onChange={(e) => changeConsent(e.target.checked)}
          />
          <span className="text-sm text-foreground">
            I have reviewed this preview and the destination account, and I consent to publishing
            this post to TikTok.
          </span>
        </label>

        {given && server?.consentedAt && (
          <p className="text-xs text-muted-foreground">
            Consented{server.consentedByName ? ` by ${server.consentedByName}` : ''} on{' '}
            {new Date(server.consentedAt).toLocaleString()}.
          </p>
        )}

        <p className="text-xs text-muted-foreground">
          {anyPaidPartnership
            ? 'By posting, you agree to TikTok’s Branded Content Policy and Music Usage Confirmation.'
            : 'By posting, you agree to TikTok’s Music Usage Confirmation.'}
        </p>

        {error && <Alert variant="destructive">{error}</Alert>}
        {blockedReason && <Alert variant="warning">{blockedReason}</Alert>}
      </div>
    </Card>
  )
}
