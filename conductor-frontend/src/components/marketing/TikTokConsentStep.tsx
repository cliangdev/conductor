'use client'

// TIK-2: the consent TikTok's audit requires.
//
// TikTok's Content Sharing Guidelines are explicit that a creator must see a preview of the content
// and the account nickname it will post to, and must expressly consent, *before* anything is
// uploaded — a consent flow that doesn't match this is the most commonly cited reason for audit
// rejection. So this is a gate, not a notice: until the creator has seen the preview, read the
// destination handle, and ticked the box, a Post carrying a TikTok target can't be sent for review.
//
// The gate is published through a context rather than drilled down the properties panel, so every
// status control (the rail's, the header's More menu) can read it wherever it happens to be rendered.
//
// MKT-1: the consent itself lives on the server. The backend records what was consented to (the
// accounts, their publish options, the media) and refuses the review-gated transition without it.
// usePostDestinations reads and writes it; the TikTok row's details render the preview and the box
// from the two components at the bottom of this file.

import { createContext, useContext, type ReactNode } from 'react'
import { AtSign, ImageOff } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Checkbox } from '@/components/ui/checkbox'
import { apiGet, apiPut } from '@/lib/api'
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
  /**
   * The copy that will actually go out to this account — its own override, or the Post's caption. The
   * creator has to consent to the words that publish, not to a different field that happens to be near
   * them; before per-target content this preview showed the Post's *title* while its *description* was
   * what TikTok received.
   */
  caption?: string | null
  /** The ids of the media that will actually go out here, in order. */
  assetIds?: string[]
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
export function destinationName(target: TikTokConsentTarget): string {
  const nickname = target.creatorNickname?.trim()
  if (!nickname) return target.label
  return nickname.startsWith('@') ? nickname : `@${nickname}`
}

/** What the post will carry, in the same words the options panel used. */
export function optionsSummary(options: TikTokPublishOptionValues): string {
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

// ── the pieces, for a destination row ───────────────────────────────────────

/**
 * What one TikTok account will receive — the handle, the options, the media that actually goes there
 * and the caption — for the creator to look at before consenting. Rendering the Post's whole set for a
 * destination that chose a subset would ask the creator to consent to media that never goes there.
 */
export function TikTokConsentPreview({
  target,
  assets,
}: {
  target: TikTokConsentTarget
  assets: TikTokPreviewAsset[]
}) {
  const targetAssets = target.assetIds
    ? target.assetIds
        .map((id) => assets.find((asset) => asset.id === id))
        .filter((asset): asset is TikTokPreviewAsset => Boolean(asset))
    : assets
  return (
    <div className="overflow-hidden rounded-md border border-border bg-surface-raised">
      <div className="px-3 py-2.5">
        <span className="flex items-center gap-2">
          <AtSign className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
          <span className="text-xs text-muted-foreground">You are posting to</span>
          <span className="truncate text-sm font-semibold text-foreground">{destinationName(target)}</span>
        </span>
        <span className="mt-1 block text-xs text-muted-foreground">{optionsSummary(target.options)}</span>
      </div>

      <div className="border-t border-border">
        {targetAssets.length === 0 ? (
          <p className="flex items-center gap-2 px-3 py-4 text-sm text-muted-foreground">
            <ImageOff className="h-4 w-4 shrink-0" aria-hidden />
            No media has been chosen for this account — TikTok needs it before this post can go out.
          </p>
        ) : (
          <ul className="divide-y divide-border">
            {targetAssets.map((asset) => (
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
        {target.caption && (
          <p className="border-t border-border px-3 py-2 text-sm text-foreground">{target.caption}</p>
        )}
      </div>
    </div>
  )
}

/** The consent itself: the box, who ticked it and when, the policy line, and any trouble recording it. */
export function TikTokConsentCheckbox({
  given,
  unresolved,
  disabled,
  saving,
  consentedAt,
  consentedByName,
  anyPaidPartnership,
  error,
  onChange,
}: {
  given: boolean
  /** An option problem stands on some account; consent can't be given until it is fixed. */
  unresolved: boolean
  disabled?: boolean
  saving?: boolean
  consentedAt?: string | null
  consentedByName?: string | null
  anyPaidPartnership: boolean
  error?: string | null
  onChange: (next: boolean) => void
}) {
  return (
    <div className="space-y-3">
      <Checkbox
        checked={given}
        disabled={disabled || unresolved || saving}
        onCheckedChange={onChange}
        label="I have reviewed this preview and the destination account, and I consent to publishing this post to TikTok."
        disabledReason={
          unresolved
            ? 'Resolve the option problem above before you can consent.'
            : disabled
              ? 'Editing is locked while this post is under review.'
              : undefined
        }
      />

      {given && consentedAt && (
        <p className="text-xs text-muted-foreground">
          Consented{consentedByName ? ` by ${consentedByName}` : ''} on {new Date(consentedAt).toLocaleString()}.
        </p>
      )}

      <p className="text-sm text-muted-foreground">
        {anyPaidPartnership
          ? 'By posting, you agree to TikTok’s Branded Content Policy and Music Usage Confirmation.'
          : 'By posting, you agree to TikTok’s Music Usage Confirmation.'}
      </p>

      {error && <Alert variant="destructive">{error}</Alert>}
    </div>
  )
}
