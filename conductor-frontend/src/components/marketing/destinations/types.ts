// The wire shapes behind a Post's destinations: what the project offers, what the Post has picked, and
// what a selection sends back. Kept apart from any component so the picker, the compose flow and the
// format selector can share them without importing each other.

import type { InstagramPublishOptionValues } from '@/components/marketing/InstagramPublishOptions'
import type { PostFormat } from '@/components/marketing/PostFormatSelector'
import type { TikTokPublishOptionValues } from '@/components/marketing/TikTokPublishOptions'
import type { YouTubePublishOptionValues } from '@/components/marketing/YouTubePublishOptions'

export type PublishPlatform = 'facebook' | 'instagram' | 'youtube' | 'tiktok'
export type PublishLane = 'NATIVE' | 'APP_MANAGED' | 'MANUAL'

/**
 * One selectable destination.
 *
 * Automated options are derived from an ACTIVE connection. The MANUAL option for each platform is not
 * derived from anything — it is always offered, with `connectionId` null, and is what a project with no
 * social integration publishes through: a human posts it and pastes the link back.
 */
export interface PublishTargetOption {
  platform: PublishPlatform
  connectorId: string | null
  /** Null on the MANUAL lane: there is no account, and one manual destination per platform. */
  connectionId: string | null
  label: string
  lane: PublishLane
  healthStatus?: string | null
  healthMessage?: string | null
  /** Extra detail behind `healthMessage` — the platform's own words, shown only on request. */
  healthDetail?: string | null
  /**
   * TIK-2. TikTok reports a different set of privacy levels per creator (a private account is
   * offered fewer than a public one), so the choices come from the connection rather than from a
   * table here. Absent/empty means TikTok has told us nothing — which is a broken connection, not
   * permission to guess.
   */
  privacyLevelOptions?: string[] | null
  /** The handle the creator would recognise, for the consent step's "you are posting to @…". */
  creatorNickname?: string | null
  /** The `publishOptions` keys a target on this platform accepts, from the server's platform registry. */
  optionKeys?: string[]
  /** The formats this platform offers, e.g. `['feed', 'reel', 'story']`. Every platform lists `feed`. */
  formats?: string[]
}

/** The union of every platform's own option bag. Field names never collide across platforms. */
export type PublishOptionsBag = Partial<TikTokPublishOptionValues> &
  Partial<InstagramPublishOptionValues> &
  Partial<YouTubePublishOptionValues>

/** One destination actually selected on this Post (a persisted post_publish_target row). */
export interface SelectedPublishTarget {
  id: string
  workItemId: string
  platform: PublishPlatform
  connectorId: string | null
  connectionId: string | null
  label?: string | null
  lane: PublishLane
  state: string
  platformPostId?: string | null
  /** The shape this destination publishes in. Absent reads as `feed`, same as the API default. */
  format?: PostFormat | null
  /** Per-target publish options, keyed by platform. Partial — an older row carries nothing. */
  publishOptions?: PublishOptionsBag | null
  /** This destination's own copy, or null when it uses the Post's caption. */
  captionOverride?: string | null
  /** Its own ordered media, or null when it inherits the Post's whole set. */
  assetIds?: string[] | null
  /** What will actually go out here, whichever of the two above applies. */
  effectiveAssetIds?: string[]
  effectiveCaption?: string | null
  /** Set once the platform (or a human) confirms this went live. */
  permalink?: string | null
  /** The platform's own words, verbatim, when `state` is FAILED — or the hand-off note on AWAITING_MANUAL. */
  errorMessage?: string | null
  /** Extra detail behind `errorMessage` — shown only on request, via a "Show details" disclosure. */
  errorDetail?: string | null
  /** Human words for `state`, from the server. Falls back to the local STATE_LABELS map when absent. */
  stateLabel?: string | null
  fireTime?: string | null
}

/** What a selection sends back. `publishOptions` rides along only where the platform has any. */
export interface PublishTargetSelectionPayload {
  platform: PublishPlatform
  /** Omitted (null) selects the platform's manual destination. */
  connectionId: string | null
  format?: PostFormat
  publishOptions?: PublishOptionsBag
  captionOverride?: string | null
  assetIds?: string[]
}

export interface RetryPublishResponse {
  workItemId: string
  status: string
  retriedCount: number
  targets: SelectedPublishTarget[]
}
