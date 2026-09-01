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

import { createContext, useContext, type ReactNode } from 'react'
import { AtSign, ImageOff } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Card, CardHeader } from '@/components/ui/card'
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

/**
 * Why a Post carrying TikTok targets can't be submitted for approval yet — null when it can, and
 * always null for a Post with no TikTok target at all.
 */
export function tiktokSubmissionBlockedReason(
  targets: TikTokConsentTarget[],
  consented: boolean
): string | null {
  if (targets.length === 0) return null
  const unresolved = targets.find((t) => t.problem)
  if (unresolved) return `${unresolved.label}: ${unresolved.problem}`
  return consented ? null : CONSENT_PROMPT
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
  consented: boolean
  onConsentChange: (consented: boolean) => void
  disabled?: boolean
}

export function TikTokConsentStep({
  targets,
  assets,
  caption,
  consented,
  onConsentChange,
  disabled,
}: TikTokConsentStepProps) {
  if (targets.length === 0) return null

  const blockedReason = tiktokSubmissionBlockedReason(targets, consented)
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
            checked={consented}
            disabled={disabled || unresolved}
            onChange={(e) => onConsentChange(e.target.checked)}
          />
          <span className="text-sm text-foreground">
            I have reviewed this preview and the destination account, and I consent to publishing
            this post to TikTok.
          </span>
        </label>

        <p className="text-xs text-muted-foreground">
          {anyPaidPartnership
            ? 'By posting, you agree to TikTok’s Branded Content Policy and Music Usage Confirmation.'
            : 'By posting, you agree to TikTok’s Music Usage Confirmation.'}
        </p>

        {blockedReason && <Alert variant="warning">{blockedReason}</Alert>}
      </div>
    </Card>
  )
}
