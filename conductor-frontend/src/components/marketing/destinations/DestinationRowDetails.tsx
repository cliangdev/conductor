'use client'

// What opens under a row. While picking: the format, the platform's options, the TikTok consent, and
// this destination's own caption and media. Once settled: the platform's own words about a failure,
// what to post by hand and the form to record it, the consent that stands, and any content of its own.

import { Check, ImageOff } from 'lucide-react'
import { ShowDetails } from '@/components/ui/show-details'
import { isVideoContentType, type MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { InstagramPublishOptions, isSingleImageTarget } from '@/components/marketing/InstagramPublishOptions'
import { PostFormatSelector } from '@/components/marketing/PostFormatSelector'
import { TargetContentEditor } from '@/components/marketing/TargetContentEditor'
import { TikTokConsentCheckbox, TikTokConsentPreview, optionsSummary } from '@/components/marketing/TikTokConsentStep'
import { TikTokPublishOptions } from '@/components/marketing/TikTokPublishOptions'
import { YouTubePublishOptions } from '@/components/marketing/YouTubePublishOptions'
import { MediaThumb } from '@/components/marketing/MediaThumb'
import { ManualPublishForm } from './ManualPublishForm'
import type { DestinationActions, DestinationMode, DestinationRowModel } from './model'
import { awaitsAHuman } from './publishState'

export interface DestinationRowDetailsProps {
  row: DestinationRowModel
  mode: DestinationMode
  assets: MediaAsset[]
  caption: string | null
  saving: boolean
  locked: boolean
  /** This row's "mark published" form is open. */
  completing: boolean
  consentSaving: boolean
  consentError: string | null
  actions: DestinationActions
  onCancelComplete: () => void
}

/** Whether a row has anything to open. The panel asks before drawing a chevron. */
export function rowHasDetails(row: DestinationRowModel, mode: DestinationMode): boolean {
  if (mode === 'pick' && !row.settled) return row.checked && !row.unavailable
  const t = row.target
  if (!t) return false
  return Boolean(
    awaitsAHuman(t) || t.errorMessage || t.errorDetail || row.consent || row.customized || row.option.platform === 'tiktok'
  )
}

export function DestinationRowDetails({
  row,
  mode,
  assets,
  caption,
  saving,
  locked,
  completing,
  consentSaving,
  consentError,
  actions,
  onCancelComplete,
}: DestinationRowDetailsProps) {
  const { option, target } = row
  const idPrefix = `${option.platform}-${option.connectionId ?? 'manual'}`
  const postImages = assets.filter((a) => !isVideoContentType(a.contentType))
  // Platform options are only meaningful for an API target: they are the payload we send the
  // platform, and on the manual lane the creator sets all of it in the platform's own composer.
  const showOptions = !row.manual

  if (mode === 'pick' && !row.settled) {
    return (
      <div className="space-y-3">
        <PostFormatSelector
          idPrefix={idPrefix}
          platform={option.platform}
          formats={option.formats}
          value={row.format}
          disabled={saving || locked}
          onChange={(next) => actions.setFormat(row, next)}
        />
        {showOptions && option.platform === 'tiktok' && (
          <TikTokPublishOptions
            idPrefix={`tiktok-${option.connectionId}`}
            accountLabel={option.label}
            privacyLevelOptions={option.privacyLevelOptions ?? []}
            isVideo={row.effectiveAssets.some((a) => isVideoContentType(a.contentType))}
            images={row.effectiveAssets.filter((a) => !isVideoContentType(a.contentType))}
            value={row.tiktokOptions}
            disabled={saving || locked}
            onChange={(next) => actions.setTikTokOptions(row, next)}
          />
        )}
        {showOptions && option.platform === 'instagram' && (
          <InstagramPublishOptions
            idPrefix={idPrefix}
            format={row.format}
            images={postImages}
            isSingleImage={isSingleImageTarget(row.effectiveAssets)}
            value={row.instagramOptions}
            disabled={saving || locked}
            onChange={(next) => actions.setInstagramOptions(row, next)}
          />
        )}
        {showOptions && option.platform === 'youtube' && (
          <YouTubePublishOptions
            idPrefix={idPrefix}
            images={postImages}
            value={row.youtubeOptions}
            disabled={saving || locked}
            onChange={(next) => actions.setYouTubeOptions(row, next)}
          />
        )}
        <TargetContentEditor
          assets={assets}
          postCaption={caption}
          value={row.content}
          disabled={saving || locked}
          onChange={(next) => actions.setContent(row, next)}
        />
        {row.consent && (
          <div className="space-y-3 border-t border-border pt-3">
            <TikTokConsentPreview
              target={{
                connectionId: option.connectionId ?? '',
                label: option.label,
                creatorNickname: option.creatorNickname ?? null,
                options: row.tiktokOptions,
                problem: row.consent.problem,
                caption: row.content.captionOverride ?? caption,
                ...(row.content.assetIds === null ? {} : { assetIds: row.content.assetIds }),
              }}
              assets={assets}
            />
            <TikTokConsentCheckbox
              given={row.consent.given}
              unresolved={Boolean(row.consent.problem)}
              disabled={locked}
              saving={consentSaving}
              consentedAt={row.consent.consentedAt}
              consentedByName={row.consent.consentedByName}
              anyPaidPartnership={Boolean(row.tiktokOptions.brandContentToggle)}
              error={consentError}
              onChange={(next) => void actions.setConsent(next)}
            />
            <p className="text-xs text-muted-foreground">Covers every TikTok destination on this post.</p>
          </div>
        )}
      </div>
    )
  }

  if (!target) return null
  const awaiting = awaitsAHuman(target)

  return (
    <div className="space-y-3">
      {awaiting && (
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">
            {row.manual
              ? 'Nothing is publishing this one — post it yourself, then record the link.'
              : (target.errorMessage ??
                'The platform handed this one to a person — finish it there, then record the link.')}
          </p>
          {/* What to post, not just that something must be posted: this destination may carry copy
              and media of its own, and a person told only "post it" would go looking for them. */}
          {target.effectiveCaption && (
            <p className="whitespace-pre-wrap rounded-md border border-border bg-surface-2 px-2.5 py-1.5 text-sm text-foreground">
              {target.effectiveCaption}
            </p>
          )}
          {target.effectiveAssetIds && target.effectiveAssetIds.length > 0 && (
            <p className="text-sm text-muted-foreground">
              {target.effectiveAssetIds.length === 1
                ? 'Post the file attached to this Post.'
                : `Post ${target.effectiveAssetIds.length} files, in the order shown on the Post.`}
            </p>
          )}
          {completing && (
            <ManualPublishForm
              target={target}
              onCancel={onCancelComplete}
              onComplete={(permalink, publishedAt) => actions.completeManual(row, permalink, publishedAt)}
            />
          )}
        </div>
      )}

      {target.errorMessage && !awaiting && (
        <div>
          <p className="text-sm text-status-failed">{target.errorMessage}</p>
          <ShowDetails detail={target.errorDetail} message={target.errorMessage} />
        </div>
      )}

      {option.platform === 'tiktok' && !row.manual && (
        <p className="flex items-start gap-1.5 text-sm text-muted-foreground">
          {row.consent?.given ? (
            <>
              <Check className="mt-0.5 h-3.5 w-3.5 shrink-0 text-status-done" aria-hidden />
              <span>
                {optionsSummary(row.tiktokOptions)} · consented
                {row.consent.consentedByName ? ` by ${row.consent.consentedByName}` : ''}
                {row.consent.consentedAt ? ` on ${new Date(row.consent.consentedAt).toLocaleDateString()}` : ''}.
                Covers every TikTok destination on this post.
              </span>
            </>
          ) : (
            <span>{optionsSummary(row.tiktokOptions)}</span>
          )}
        </p>
      )}

      {row.customized && (
        <div className="space-y-2">
          {row.content.captionOverride !== null && (
            <div>
              <span className="block text-xs font-medium text-muted-foreground">Caption for this destination</span>
              <p className="mt-0.5 whitespace-pre-wrap text-sm text-foreground">{row.content.captionOverride}</p>
            </div>
          )}
          {row.content.assetIds !== null && (
            <div>
              <span className="block text-xs font-medium text-muted-foreground">Media for this destination</span>
              {row.effectiveAssets.length === 0 ? (
                <p className="mt-1 flex items-center gap-1.5 text-sm text-muted-foreground">
                  <ImageOff className="h-3.5 w-3.5" aria-hidden /> Nothing selected.
                </p>
              ) : (
                <div className="mt-1 flex flex-wrap gap-1.5">
                  {row.effectiveAssets.map((a) => (
                    <MediaThumb key={a.id} asset={a} size="md" />
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
