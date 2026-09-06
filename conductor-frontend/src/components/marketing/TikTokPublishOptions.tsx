'use client'

// TIK-2: the per-target publish options a TikTok post carries.
//
// TikTok's Content Sharing Guidelines make most of this mandatory rather than nice-to-have, and an
// app that skips it fails audit:
//   * the privacy level is the creator's own choice out of the list TikTok reports for *that*
//     account — a private account and a public one are offered different sets, so nothing here is
//     hardcoded and nothing is preselected (a silent default is how every post ends up SELF_ONLY);
//   * commercial content is disclosed through two distinct toggles, "Your Brand" and
//     "Branded Content", which TikTok renders as *Promotional content* and *Paid partnership*;
//   * branded content and a private privacy level is a combination TikTok rejects outright, so the
//     UI says why instead of quietly removing the option.
//
// The values map 1:1 onto the connector's publishOptions payload, so the names here are TikTok's
// (`disableComment`, `brandContentToggle`, …) rather than a local rewording that would have to be
// translated at the boundary.

import { useState } from 'react'
import { Alert } from '@/components/ui/alert'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'

export interface TikTokPublishOptionValues {
  /** null until the creator picks one — TikTok will not accept a post without an explicit choice. */
  privacyLevel: string | null
  disableComment: boolean
  disableDuet: boolean
  disableStitch: boolean
  /** "Branded Content" — a paid partnership with a third party. */
  brandContentToggle: boolean
  /** "Your Brand" — the creator promoting their own business. */
  brandOrganicToggle: boolean
  /** Discloses the post as AI-generated or AI-edited content. */
  isAigc: boolean
  /** Video posts only: where TikTok freezes the cover frame, in milliseconds from the start. Omitted
   *  rather than 0 when nobody has picked one. */
  videoCoverTimestampMs?: number | null
  /** Photo posts only: adds one of TikTok's own royalty-free tracks. */
  autoAddMusic?: boolean
  /** Photo posts only: index into this target's own images for the cover TikTok shows. */
  photoCoverIndex?: number | null
}

export const EMPTY_TIKTOK_OPTIONS: TikTokPublishOptionValues = {
  privacyLevel: null,
  disableComment: false,
  disableDuet: false,
  disableStitch: false,
  brandContentToggle: false,
  brandOrganicToggle: false,
  isAigc: false,
}

/** TikTok's own wording for the privacy levels its creator-info endpoint reports. */
const PRIVACY_LABELS: Record<string, string> = {
  PUBLIC_TO_EVERYONE: 'Everyone',
  MUTUAL_FOLLOW_FRIENDS: 'Friends',
  FOLLOWER_OF_CREATOR: 'Followers',
  SELF_ONLY: 'Only me (private)',
}

/** A level TikTok adds later still reads as words rather than as a constant. */
export function privacyLevelLabel(level: string): string {
  const known = PRIVACY_LABELS[level]
  if (known) return known
  const words = level.toLowerCase().replace(/_/g, ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

/** The one level that hides a post from every audience — TikTok's `SELF_ONLY`. */
export function isPrivatePrivacyLevel(level: string | null | undefined): boolean {
  return level === 'SELF_ONLY'
}

export const BRANDED_PRIVATE_EXPLANATION =
  'Branded content can’t be posted privately. TikTok rejects a paid partnership that only the ' +
  'creator can see — choose a privacy level other than “Only me”, or turn Branded Content off.'

const NO_PRIVACY_LEVEL_EXPLANATION = 'Choose who can see this TikTok post.'

/**
 * Why this target isn't ready to post, in the creator's words — null when it is. The same rules the
 * connector enforces at approval time, so a human hears about them while they can still act.
 */
export function tiktokOptionsProblem(options: TikTokPublishOptionValues): string | null {
  if (!options.privacyLevel) return NO_PRIVACY_LEVEL_EXPLANATION
  if (options.brandContentToggle && isPrivatePrivacyLevel(options.privacyLevel)) {
    return BRANDED_PRIVATE_EXPLANATION
  }
  return null
}

/** Fills a stored (or absent) payload out to a whole value set without inventing a privacy level. */
export function normalizeTikTokOptions(
  raw: Partial<TikTokPublishOptionValues> | null | undefined
): TikTokPublishOptionValues {
  return {
    privacyLevel: raw?.privacyLevel ?? null,
    disableComment: raw?.disableComment ?? false,
    disableDuet: raw?.disableDuet ?? false,
    disableStitch: raw?.disableStitch ?? false,
    brandContentToggle: raw?.brandContentToggle ?? false,
    brandOrganicToggle: raw?.brandOrganicToggle ?? false,
    isAigc: raw?.isAigc ?? false,
    ...(raw?.videoCoverTimestampMs != null ? { videoCoverTimestampMs: raw.videoCoverTimestampMs } : {}),
    ...(raw?.autoAddMusic !== undefined ? { autoAddMusic: raw.autoAddMusic } : {}),
    ...(raw?.photoCoverIndex != null ? { photoCoverIndex: raw.photoCoverIndex } : {}),
  }
}

const SECTION_LABEL =
  'text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground'

interface SwitchRowProps {
  id: string
  label: string
  description?: string
  checked: boolean
  disabled?: boolean
  onCheckedChange: (checked: boolean) => void
}

function SwitchRow({ id, label, description, checked, disabled, onCheckedChange }: SwitchRowProps) {
  return (
    <div className="flex items-start justify-between gap-3">
      <span className="min-w-0">
        <span id={`${id}-label`} className="block text-sm text-foreground">
          {label}
        </span>
        {description && (
          <span id={`${id}-desc`} className="block text-xs text-muted-foreground">
            {description}
          </span>
        )}
      </span>
      <Switch
        id={id}
        checked={checked}
        disabled={disabled}
        onCheckedChange={onCheckedChange}
        aria-labelledby={`${id}-label`}
        {...(description ? { 'aria-describedby': `${id}-desc` } : {})}
      />
    </div>
  )
}

interface TikTokPublishOptionsProps {
  /** Scopes every control id to one target, so two accounts on a Post never share a label. */
  idPrefix: string
  accountLabel: string
  /** Exactly what TikTok reported for this creator. An empty list is a broken connection, not "all". */
  privacyLevelOptions: string[]
  /** Whether this target's effective media is a video (vs. a photo set) — TikTok never mixes the two. */
  isVideo?: boolean
  /** This target's own images, in publish order, for the photo-post cover picker. Empty for a video post. */
  images?: MediaAsset[]
  value: TikTokPublishOptionValues
  onChange: (next: TikTokPublishOptionValues) => void
  disabled?: boolean
}

export function TikTokPublishOptions({
  idPrefix,
  accountLabel,
  privacyLevelOptions,
  isVideo = false,
  images = [],
  value,
  onChange,
  disabled,
}: TikTokPublishOptionsProps) {
  // The disclosure section opens on its own once the Post already carries a disclosure, so a saved
  // "Paid partnership" is visible without the creator having to go looking for it.
  const [opened, setOpened] = useState(false)
  const disclosing = opened || value.brandContentToggle || value.brandOrganicToggle
  const brandedPrivately = value.brandContentToggle && isPrivatePrivacyLevel(value.privacyLevel)

  const set = (patch: Partial<TikTokPublishOptionValues>) => onChange({ ...value, ...patch })

  function toggleDisclosure(next: boolean) {
    setOpened(next)
    if (!next) set({ brandContentToggle: false, brandOrganicToggle: false })
  }

  return (
    <div className="space-y-4 border-t border-border bg-surface-raised px-4 py-3">
      <div>
        <Label htmlFor={`${idPrefix}-privacy`} className={SECTION_LABEL}>
          Who can view this video
        </Label>
        {privacyLevelOptions.length === 0 ? (
          <Alert variant="warning">
            TikTok reported no privacy options for {accountLabel}. Reconnect the account in
            Integrations before this post can go out.
          </Alert>
        ) : (
          <Select
            id={`${idPrefix}-privacy`}
            value={value.privacyLevel ?? ''}
            disabled={disabled}
            onChange={(e) => set({ privacyLevel: e.target.value || null })}
          >
            <option value="">Select who can view this video…</option>
            {privacyLevelOptions.map((level) => (
              <option key={level} value={level}>
                {privacyLevelLabel(level)}
              </option>
            ))}
          </Select>
        )}
      </div>

      <div className="space-y-2">
        <p className={SECTION_LABEL}>Allow users to</p>
        <SwitchRow
          id={`${idPrefix}-comment`}
          label="Comment"
          checked={!value.disableComment}
          disabled={disabled}
          onCheckedChange={(allowed) => set({ disableComment: !allowed })}
        />
        <SwitchRow
          id={`${idPrefix}-duet`}
          label="Duet"
          checked={!value.disableDuet}
          disabled={disabled}
          onCheckedChange={(allowed) => set({ disableDuet: !allowed })}
        />
        <SwitchRow
          id={`${idPrefix}-stitch`}
          label="Stitch"
          checked={!value.disableStitch}
          disabled={disabled}
          onCheckedChange={(allowed) => set({ disableStitch: !allowed })}
        />
      </div>

      <div className="space-y-2">
        <SwitchRow
          id={`${idPrefix}-disclose`}
          label="Disclose commercial content"
          description="Turn this on if the post promotes a brand, product or service."
          checked={disclosing}
          disabled={disabled}
          onCheckedChange={toggleDisclosure}
        />
        {disclosing && (
          <div className="space-y-2 border-l-2 border-border pl-3">
            <SwitchRow
              id={`${idPrefix}-your-brand`}
              label="Your Brand"
              description="You are promoting yourself or your own business. TikTok labels the post Promotional content."
              checked={value.brandOrganicToggle}
              disabled={disabled}
              onCheckedChange={(v) => set({ brandOrganicToggle: v })}
            />
            <SwitchRow
              id={`${idPrefix}-branded-content`}
              label="Branded Content"
              description="You are promoting another brand or a third party. TikTok labels the post Paid partnership."
              checked={value.brandContentToggle}
              disabled={disabled}
              onCheckedChange={(v) => set({ brandContentToggle: v })}
            />
            {brandedPrivately && <Alert variant="destructive">{BRANDED_PRIVATE_EXPLANATION}</Alert>}
          </div>
        )}
      </div>

      <SwitchRow
        id={`${idPrefix}-aigc`}
        label="AI-generated or AI-edited content"
        description="Discloses that this post was made or altered with AI."
        checked={value.isAigc}
        disabled={disabled}
        onCheckedChange={(checked) => set({ isAigc: checked })}
      />

      {isVideo ? (
        <div className="space-y-1">
          <Label htmlFor={`${idPrefix}-cover-timestamp`}>Cover frame</Label>
          <Input
            id={`${idPrefix}-cover-timestamp`}
            type="number"
            min={0}
            step={100}
            placeholder="Milliseconds from the start"
            disabled={disabled}
            value={value.videoCoverTimestampMs ?? ''}
            onChange={(e) => {
              const parsed = e.target.value === '' ? undefined : Number(e.target.value)
              set({ videoCoverTimestampMs: parsed === undefined || Number.isNaN(parsed) ? undefined : parsed })
            }}
          />
          <p className="text-xs text-muted-foreground">
            Where TikTok freezes the cover, in milliseconds from the start of the video.
          </p>
        </div>
      ) : (
        <>
          <SwitchRow
            id={`${idPrefix}-auto-music`}
            label="Add TikTok music automatically"
            checked={value.autoAddMusic ?? false}
            disabled={disabled}
            onCheckedChange={(checked) => set({ autoAddMusic: checked })}
          />
          <div className="space-y-1">
            <Label htmlFor={`${idPrefix}-cover-index`}>Cover image</Label>
            <Select
              id={`${idPrefix}-cover-index`}
              disabled={disabled || images.length === 0}
              value={value.photoCoverIndex != null ? String(value.photoCoverIndex) : ''}
              onChange={(e) =>
                set({ photoCoverIndex: e.target.value === '' ? undefined : Number(e.target.value) })
              }
            >
              <option value="">Use the first image</option>
              {images.map((asset, index) => (
                <option key={asset.id} value={index}>
                  {asset.label || asset.type}
                </option>
              ))}
            </Select>
          </div>
        </>
      )}
    </div>
  )
}
