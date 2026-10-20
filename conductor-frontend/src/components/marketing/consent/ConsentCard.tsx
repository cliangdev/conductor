'use client'

// One Post on the bulk TikTok consent page. TikTok requires the creator to choose the audience and each
// interaction personally, per post, so every card has its own controls and its own Confirm; nothing is
// copied between cards and nothing is preselected.

import { useState } from 'react'
import { CheckCircle2 } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { apiErrorMessage } from '@/lib/api'
import {
  EMPTY_TIKTOK_OPTIONS,
  TikTokPublishOptions,
  tiktokOptionsProblem,
  type TikTokPublishOptionValues,
} from '@/components/marketing/TikTokPublishOptions'
import {
  TikTokConsentPreview,
  TikTokDeclaration,
  type TikTokConsentTarget,
} from '@/components/marketing/TikTokConsentStep'
import { isVideoContentType, type MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { recordTikTokConsent, type ConsentChannel, type ConsentPageCreator, type ConsentPagePost } from './api'

interface ConsentCardProps {
  projectId: string
  token: string
  post: ConsentPagePost
  creators: ConsentPageCreator[]
  /** The Post's media with signed preview URLs; null while loading. */
  assets: MediaAsset[] | null
  requestId?: string | null
  currentUserId?: string | null
  /** The Post changed since the page was read; the card has just been reloaded. */
  stale?: boolean
  /** Fired with the new consent state after a successful Confirm. */
  onConsented: (workItemId: string, consentedAt: string | null, consentedByName: string | null) => void
  /** The server answered 412: reload this card from the server. */
  onStale: (workItemId: string) => void
}

const CHANNEL: ConsentChannel = 'BULK_PAGE'

function initialChoices(post: ConsentPagePost): Record<string, TikTokPublishOptionValues> {
  const out: Record<string, TikTokPublishOptionValues> = {}
  for (const t of post.targets) {
    out[t.connectionId] = t.consented
      ? { ...EMPTY_TIKTOK_OPTIONS, ...t.consented }
      : { ...EMPTY_TIKTOK_OPTIONS }
  }
  return out
}

/** The nickname as TikTok shows it, plus @username when known; the nickname is never prefixed with "@". */
function creatorName(c: ConsentPageCreator | undefined): string {
  const nickname = c?.nickname?.trim()
  const username = c?.username?.trim().replace(/^@/, '')
  if (nickname) return username ? `${nickname} (@${username})` : nickname
  return username ? `@${username}` : 'this TikTok account'
}

export function ConsentCard({
  projectId,
  token,
  post,
  creators,
  assets,
  requestId,
  currentUserId,
  stale,
  onConsented,
  onStale,
}: ConsentCardProps) {
  const [choices, setChoices] = useState(() => initialChoices(post))
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (post.verdict === 'VALID') {
    const by = currentUserId && post.consentedByUserId === currentUserId ? 'you' : post.consentedByName
    return (
      <li
        data-testid={`consent-card-${post.displayId}`}
        className="flex items-center gap-2 rounded-md border border-border bg-surface-raised px-4 py-3 text-sm"
      >
        <CheckCircle2 className="h-4 w-4 shrink-0 text-status-done" aria-hidden />
        <span className="min-w-0 truncate font-medium text-foreground">{post.title}</span>
        <span className="shrink-0 text-muted-foreground">
          Consented{by ? ` by ${by}` : ''}
          {post.consentedAt ? ` · ${new Date(post.consentedAt).toLocaleString()}` : ''}
        </span>
      </li>
    )
  }

  const creatorFor = (connectionId: string) => creators.find((c) => c.connectionId === connectionId)
  const allAssets = assets ?? []

  // A card is only confirmable once every account on it can post and every choice is whole.
  const blocking = post.targets
    .map((t) => {
      const creator = creatorFor(t.connectionId)
      if (!creator) return `${creatorName(creator)} could not be reached.`
      if (creator.reconnectRequired) {
        return `${creatorName(creator)} needs to be reconnected in Integrations before this post can go out.`
      }
      if (!creator.canPost) {
        return `${creatorName(creator)} can’t post right now${creator.cannotPostReason ? `: ${creator.cannotPostReason}` : '.'}`
      }
      return null
    })
    .find(Boolean)
  const problem = post.targets.map((t) => tiktokOptionsProblem(choices[t.connectionId])).find(Boolean) ?? null
  const needsPrivacy = post.targets.some((t) => !choices[t.connectionId]?.privacyLevel)
  const branded = post.targets.some((t) => choices[t.connectionId]?.brandContentToggle)

  async function confirm() {
    setSaving(true)
    setError(null)
    try {
      const state = await recordTikTokConsent(
        projectId,
        post.workItemId,
        post.etag,
        {
          channel: CHANNEL,
          ...(requestId ? { requestId } : {}),
          targets: post.targets.map((t) => {
            const o = choices[t.connectionId]
            return {
              connectionId: t.connectionId,
              privacyLevel: o.privacyLevel ?? '',
              disableComment: o.disableComment,
              disableDuet: o.disableDuet,
              disableStitch: o.disableStitch,
              brandContentToggle: o.brandContentToggle,
              brandOrganicToggle: o.brandOrganicToggle,
            }
          }),
        },
        token
      )
      onConsented(post.workItemId, state.consentedAt ?? new Date().toISOString(), state.consentedByName ?? null)
    } catch (err) {
      if ((err as { status?: number })?.status === 412) {
        onStale(post.workItemId)
      } else {
        setError(apiErrorMessage(err, 'Could not record your consent.'))
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <li
      data-testid={`consent-card-${post.displayId}`}
      className="space-y-4 rounded-lg border border-border bg-card p-4"
    >
      <div>
        <p className="text-xs text-muted-foreground">{post.displayId}</p>
        <h2 className="text-base font-semibold text-foreground">{post.title}</h2>
        {post.scheduledAt && (
          <p className="text-xs text-muted-foreground">
            Scheduled for {new Date(post.scheduledAt).toLocaleString()}
          </p>
        )}
      </div>

      {stale && (
        <Alert variant="warning">
          This post changed since you opened the page. Review it again — your choices were cleared.
        </Alert>
      )}

      {post.targets.map((t) => {
        const creator = creatorFor(t.connectionId)
        const options = choices[t.connectionId] ?? EMPTY_TIKTOK_OPTIONS
        const targetAssets = allAssets.filter((a) => t.effectiveAssetIds.includes(a.id))
        const previewTarget: TikTokConsentTarget = {
          connectionId: t.connectionId,
          label: creatorName(creator),
          creatorNickname: creator?.nickname ?? creator?.username ?? null,
          displayName: creatorName(creator),
          avatarUrl: creator?.avatarUrl ?? null,
          options,
          problem: null,
          caption: t.effectiveCaption ?? t.captionOverride ?? post.caption ?? null,
          assetIds: t.effectiveAssetIds,
        }
        return (
          <div key={t.connectionId} className="space-y-3">
            <TikTokConsentPreview target={previewTarget} assets={allAssets} compact />

            {creator && creator.canPost && !creator.reconnectRequired && (
              <div className="overflow-hidden rounded-md border border-border">
                <TikTokPublishOptions
                  idPrefix={`consent-${post.displayId}-${t.connectionId}`}
                  accountLabel={creatorName(creator)}
                  privacyLevelOptions={creator.privacyLevelOptions}
                  isVideo={targetAssets.some((a) => isVideoContentType(a.contentType))}
                  choicesOnly
                  creatorDisabled={{
                    comment: creator.commentDisabled,
                    duet: creator.duetDisabled,
                    stitch: creator.stitchDisabled,
                  }}
                  value={options}
                  disabled={saving}
                  onChange={(next) => setChoices((c) => ({ ...c, [t.connectionId]: next }))}
                />
              </div>
            )}
          </div>
        )
      })}

      {blocking ? (
        <Alert variant="warning">{blocking}</Alert>
      ) : (
        <div className="space-y-3 border-t border-border pt-3">
          <TikTokDeclaration branded={branded} />
          {/* Only the missing audience is spelled out here; the options panel shows the other problems itself. */}
          {problem && needsPrivacy && <p className="text-xs text-muted-foreground">{problem}</p>}
          {error && <Alert variant="destructive">{error}</Alert>}
          <Button onClick={() => void confirm()} disabled={saving || Boolean(problem)}>
            {saving ? 'Confirming…' : 'Confirm and consent'}
          </Button>
        </div>
      )}
    </li>
  )
}
