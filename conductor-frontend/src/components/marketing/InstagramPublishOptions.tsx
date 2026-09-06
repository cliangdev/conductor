'use client'

// The per-target publish options an Instagram destination carries, beyond caption and media.
//
// Every field here is optional and rides in the same freeform `publishOptions` bag TikTok's do:
// leaving one untouched omits it rather than sending a false/empty value, so an older target that
// never saw this editor keeps reading exactly as it did before. Which fields apply depends on the
// chosen format and the target's own media — `shareToFeed`, `audioName` and the cover picker are
// reels-only, and `altText` only makes sense on a single-image feed post.

import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import { isVideoContentType, type MediaAsset } from '@/components/workitems/MediaUploadPanel'
import type { PostFormat } from './PostFormatSelector'

export interface InstagramPublishOptionValues {
  /** Reels only: also post it to Feed. */
  shareToFeed?: boolean
  /** Up to 3 usernames tagged as collaborators. */
  collaborators?: string[]
  /** Single feed images only, ≤ 1000 characters. */
  altText?: string
  /** Reels only: one of the Post's image assets, used as the reel's cover. */
  coverAssetId?: string
  /** Reels only. */
  audioName?: string
}

const MAX_COLLABORATORS = 3

/** Fills a stored (or absent) payload out without inventing values for fields nobody set. */
export function normalizeInstagramOptions(
  raw: Partial<InstagramPublishOptionValues> | null | undefined
): InstagramPublishOptionValues {
  return { ...raw }
}

/** Parses the comma-separated collaborators box back into a trimmed list, dropping blanks. */
export function parseCollaborators(text: string): string[] {
  return text
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

interface InstagramPublishOptionsProps {
  idPrefix: string
  format: PostFormat
  /** The Post's own image assets, for the reel cover picker. */
  images: MediaAsset[]
  /** Whether this destination's effective media is exactly one non-video asset. */
  isSingleImage: boolean
  value: InstagramPublishOptionValues
  onChange: (next: InstagramPublishOptionValues) => void
  disabled?: boolean
}

export function InstagramPublishOptions({
  idPrefix,
  format,
  images,
  isSingleImage,
  value,
  onChange,
  disabled,
}: InstagramPublishOptionsProps) {
  const set = (patch: Partial<InstagramPublishOptionValues>) => onChange({ ...value, ...patch })
  const isReel = format === 'reel'
  const collaboratorsText = (value.collaborators ?? []).join(', ')
  const tooManyCollaborators = (value.collaborators?.length ?? 0) > MAX_COLLABORATORS

  return (
    <div className="space-y-4 border-t border-border bg-surface-raised px-4 py-3">
      {isReel && (
        <div className="flex items-start justify-between gap-3">
          <span className="min-w-0">
            <span id={`${idPrefix}-share-feed-label`} className="block text-sm text-foreground">
              Also share to Feed
            </span>
          </span>
          <Switch
            id={`${idPrefix}-share-feed`}
            checked={value.shareToFeed ?? false}
            disabled={disabled}
            onCheckedChange={(checked) => set({ shareToFeed: checked })}
            aria-labelledby={`${idPrefix}-share-feed-label`}
          />
        </div>
      )}

      <div className="space-y-1">
        <Label htmlFor={`${idPrefix}-collaborators`}>Collaborators</Label>
        <Input
          id={`${idPrefix}-collaborators`}
          placeholder="username, username"
          disabled={disabled}
          value={collaboratorsText}
          onChange={(e) => {
            const parsed = parseCollaborators(e.target.value)
            set({ collaborators: parsed.length === 0 ? undefined : parsed })
          }}
        />
        <p className="text-xs text-muted-foreground">
          Up to {MAX_COLLABORATORS} usernames, comma-separated.
        </p>
        {tooManyCollaborators && (
          <p className="text-xs text-destructive">Instagram allows at most {MAX_COLLABORATORS} collaborators.</p>
        )}
      </div>

      {format === 'feed' && isSingleImage && (
        <div className="space-y-1">
          <Label htmlFor={`${idPrefix}-alt-text`}>Alt text</Label>
          <Input
            id={`${idPrefix}-alt-text`}
            maxLength={1000}
            placeholder="Describe the image for screen readers"
            disabled={disabled}
            value={value.altText ?? ''}
            onChange={(e) => set({ altText: e.target.value === '' ? undefined : e.target.value })}
          />
        </div>
      )}

      {isReel && (
        <>
          <div className="space-y-1">
            <Label htmlFor={`${idPrefix}-cover`}>Cover image</Label>
            <Select
              id={`${idPrefix}-cover`}
              disabled={disabled}
              value={value.coverAssetId ?? ''}
              onChange={(e) => set({ coverAssetId: e.target.value || undefined })}
            >
              <option value="">Use the reel&apos;s own first frame</option>
              {images.map((asset) => (
                <option key={asset.id} value={asset.id}>
                  {asset.label || asset.type}
                </option>
              ))}
            </Select>
          </div>

          <div className="space-y-1">
            <Label htmlFor={`${idPrefix}-audio-name`}>Audio name</Label>
            <Input
              id={`${idPrefix}-audio-name`}
              placeholder="Original audio, a song title, …"
              disabled={disabled}
              value={value.audioName ?? ''}
              onChange={(e) => set({ audioName: e.target.value === '' ? undefined : e.target.value })}
            />
          </div>
        </>
      )}
    </div>
  )
}

/** True when a target's effective media is exactly one image (not a carousel, not a video). */
export function isSingleImageTarget(effectiveAssets: MediaAsset[]): boolean {
  return effectiveAssets.length === 1 && !isVideoContentType(effectiveAssets[0]?.contentType)
}
