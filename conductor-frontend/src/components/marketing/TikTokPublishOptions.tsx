'use client'

// TIK-2: the per-target publish options a TikTok post carries.
//
// TikTok's Content Sharing Guidelines make most of this mandatory rather than nice-to-have, and an
// app that skips it fails audit:
//   * the privacy level is the creator's own choice out of the list TikTok reports for *that*
//     account — a private account and a public one are offered different sets, so nothing here is
//     hardcoded and nothing is preselected (a silent default is how every post ends up SELF_ONLY);
//   * Comment, Duet and Stitch start switched off (not allowed) until the creator allows them, and
//     are greyed out when the creator's own TikTok settings disable them. Duet and Stitch don't
//     exist on photo posts, so they aren't shown there;
//   * commercial content is disclosed through two distinct toggles, "Your Brand" and
//     "Branded Content", which TikTok renders as *Promotional content* and *Paid partnership*;
//   * branded content and a private privacy level is a combination TikTok rejects outright, so the
//     UI says why instead of quietly removing the option.
//
// The values map 1:1 onto the connector's publishOptions payload, so the names here are TikTok's
// (`disableComment`, `brandContentToggle`, …) rather than a local rewording that would have to be
// translated at the boundary.

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
  /**
   * UI only, never sent to the server: "Disclose commercial content" is switched on. TikTok requires
   * one of Your Brand / Branded Content to be chosen once it is, so this is what lets
   * `tiktokOptionsProblem` refuse a disclosure with no type instead of silently dropping it.
   */
  discloseCommercial?: boolean
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

/** A new target: no privacy level, and every interaction NOT allowed until the creator allows it. */
export const EMPTY_TIKTOK_OPTIONS: TikTokPublishOptionValues = {
  privacyLevel: null,
  disableComment: true,
  disableDuet: true,
  disableStitch: true,
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

export const DISCLOSURE_CHOICE_EXPLANATION = 'Choose Your Brand or Branded Content'

/** Milliseconds a `videoCoverTimestampMs` value converts to/from, for a person who thinks in seconds. */
function msToSeconds(ms: number | null | undefined): number | '' {
  return ms == null ? '' : ms / 1000
}

function secondsToMs(seconds: string): number | undefined {
  if (seconds === '') return undefined
  const parsed = Number(seconds)
  return Number.isNaN(parsed) ? undefined : Math.round(parsed * 1000)
}

/**
 * Why this target isn't ready to post, in the creator's words — null when it is. The same rules the
 * connector enforces at approval time, so a human hears about them while they can still act.
 */
export function tiktokOptionsProblem(options: TikTokPublishOptionValues): string | null {
  if (!options.privacyLevel) return NO_PRIVACY_LEVEL_EXPLANATION
  if (options.discloseCommercial && !options.brandContentToggle && !options.brandOrganicToggle) {
    return DISCLOSURE_CHOICE_EXPLANATION
  }
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
    disableComment: raw?.disableComment ?? true,
    disableDuet: raw?.disableDuet ?? true,
    disableStitch: raw?.disableStitch ?? true,
    brandContentToggle: raw?.brandContentToggle ?? false,
    brandOrganicToggle: raw?.brandOrganicToggle ?? false,
    isAigc: raw?.isAigc ?? false,
    ...(raw?.discloseCommercial ? { discloseCommercial: true } : {}),
    ...(raw?.videoCoverTimestampMs != null ? { videoCoverTimestampMs: raw.videoCoverTimestampMs } : {}),
    ...(raw?.autoAddMusic !== undefined ? { autoAddMusic: raw.autoAddMusic } : {}),
    ...(raw?.photoCoverIndex != null ? { photoCoverIndex: raw.photoCoverIndex } : {}),
  }
}

const OFF_IN_TIKTOK = 'Turned off in this account’s TikTok settings.'

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
  /** Interactions the creator has switched off in their own TikTok settings; shown greyed and off. */
  creatorDisabled?: { comment?: boolean; duet?: boolean; stitch?: boolean }
  /** Only the choices the creator must make personally (audience, interactions, disclosure) — no AI label, cover or music. */
  choicesOnly?: boolean
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
  creatorDisabled,
  choicesOnly = false,
}: TikTokPublishOptionsProps) {
  // The disclosure section opens on its own once the Post already carries a disclosure, so a saved
  // "Paid partnership" is visible without the creator having to go looking for it.
  const disclosing = Boolean(value.discloseCommercial) || value.brandContentToggle || value.brandOrganicToggle
  const brandedPrivately = value.brandContentToggle && isPrivatePrivacyLevel(value.privacyLevel)

  const set = (patch: Partial<TikTokPublishOptionValues>) => onChange({ ...value, ...patch })

  // Nothing is preselected: TikTok requires the creator to choose the audience themselves.
  const displayedPrivacyLevel = value.privacyLevel ?? ''

  function toggleDisclosure(next: boolean) {
    set(
      next
        ? { discloseCommercial: true }
        : { discloseCommercial: false, brandContentToggle: false, brandOrganicToggle: false }
    )
  }

  return (
    <div className="space-y-4 border-t border-border bg-surface-raised px-4 py-3">
      <div>
        <Label htmlFor={`${idPrefix}-privacy`} className={SECTION_LABEL}>
          Who can view this {isVideo ? 'video' : 'post'}
        </Label>
        {privacyLevelOptions.length === 0 ? (
          <Alert variant="warning">
            TikTok reported no privacy options for {accountLabel}. Reconnect the account in
            Integrations before this post can go out.
          </Alert>
        ) : (
          <Select
            id={`${idPrefix}-privacy`}
            value={displayedPrivacyLevel}
            disabled={disabled}
            onChange={(e) => set({ privacyLevel: e.target.value || null })}
          >
            <option value="">Choose who can view</option>
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
          description={creatorDisabled?.comment ? OFF_IN_TIKTOK : undefined}
          checked={!value.disableComment && !creatorDisabled?.comment}
          disabled={disabled || creatorDisabled?.comment}
          onCheckedChange={(allowed) => set({ disableComment: !allowed })}
        />
        {isVideo && (
          <SwitchRow
            id={`${idPrefix}-duet`}
            label="Duet"
            description={creatorDisabled?.duet ? OFF_IN_TIKTOK : undefined}
            checked={!value.disableDuet && !creatorDisabled?.duet}
            disabled={disabled || creatorDisabled?.duet}
            onCheckedChange={(allowed) => set({ disableDuet: !allowed })}
          />
        )}
        {isVideo && (
          <SwitchRow
            id={`${idPrefix}-stitch`}
            label="Stitch"
            description={creatorDisabled?.stitch ? OFF_IN_TIKTOK : undefined}
            checked={!value.disableStitch && !creatorDisabled?.stitch}
            disabled={disabled || creatorDisabled?.stitch}
            onCheckedChange={(allowed) => set({ disableStitch: !allowed })}
          />
        )}
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
            {!value.brandContentToggle && !value.brandOrganicToggle && (
              <Alert variant="warning">{DISCLOSURE_CHOICE_EXPLANATION}</Alert>
            )}
            {brandedPrivately && <Alert variant="destructive">{BRANDED_PRIVATE_EXPLANATION}</Alert>}
          </div>
        )}
      </div>

      {!choicesOnly && (
        <SwitchRow
          id={`${idPrefix}-aigc`}
          label="AI-generated or AI-edited content"
          description="Discloses that this post was made or altered with AI."
          checked={value.isAigc}
          disabled={disabled}
          onCheckedChange={(checked) => set({ isAigc: checked })}
        />
      )}

      {choicesOnly ? null : isVideo ? (
        <div className="space-y-1">
          <Label htmlFor={`${idPrefix}-cover-timestamp`}>Cover frame</Label>
          <Input
            id={`${idPrefix}-cover-timestamp`}
            type="number"
            min={0}
            step={0.1}
            placeholder="Seconds from the start"
            disabled={disabled}
            value={msToSeconds(value.videoCoverTimestampMs)}
            onChange={(e) => set({ videoCoverTimestampMs: secondsToMs(e.target.value) })}
          />
          <p className="text-xs text-muted-foreground">
            Which second of the video TikTok freezes as the cover.
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
