// Pure facts about a destination: how a wire state reads and colours, what makes a target "settled",
// how a target is keyed, and the one Workflow-level question every Post surface asks first (does this
// Workflow publish at all?). No React, no fetches — everything here is unit-testable on its own.

import { statusHue, type StatusHue } from '@/components/ui/status-badge'
import type { TikTokPublishOptionValues } from '@/components/marketing/TikTokPublishOptions'
import type { WorkflowView } from '@/types/workItem'
import type { PublishPlatform, PublishTargetOption, SelectedPublishTarget } from './types'

/** Render order, so the groups don't reshuffle as connections come and go. */
export const PLATFORM_ORDER: PublishPlatform[] = ['facebook', 'instagram', 'youtube', 'tiktok']

export const PLATFORM_LABELS: Record<PublishPlatform, string> = {
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
export const STATE_HUES: Record<string, StatusHue> = {
  PENDING: 'gray',
  HANDED_OFF: 'blue',
  PUBLISHING: 'blue',
  // Amber, not blue: this is the one state that is waiting on the person reading the screen. Every
  // other in-flight state is waiting on a machine and needs nothing from anybody.
  AWAITING_MANUAL: 'amber',
  PUBLISHED: 'green',
  FAILED: 'red',
  REVOKED: 'slate',
}

/** Human words for the wire states — the design system's "translate at the UI boundary" rule. */
export const STATE_LABELS: Record<string, string> = {
  PENDING: 'Waiting',
  HANDED_OFF: 'Handed off',
  PUBLISHING: 'Publishing',
  AWAITING_MANUAL: 'Post it now',
  PUBLISHED: 'Published',
  FAILED: 'Failed',
  REVOKED: 'Taken back',
}

export function stateHue(state: string): StatusHue {
  return STATE_HUES[state] ?? statusHue(state)
}

/** The server's own `stateLabel` wins when present; the local map is the fallback for an older backend. */
export function stateLabelFor(target: SelectedPublishTarget): string {
  return target.stateLabel ?? STATE_LABELS[target.state] ?? target.state
}

/**
 * A destination waiting on the person reading this: a manual one whose fire time has passed, or an
 * automated one the platform handed back (TikTok's pre-audit inbox upload). Either way the next step is a
 * human's, and the link they record is the outcome.
 */
export function awaitsAHuman(target: SelectedPublishTarget): boolean {
  return target.state === 'AWAITING_MANUAL'
}

/** Strip the scheme so a permalink reads as a destination rather than a wall of URL. */
export function permalinkText(permalink: string): string {
  return permalink.replace(/^https?:\/\//, '')
}

/**
 * Client-side mirror of PostScheduleValidator.declaresPublishTargets: a Workflow treats publishing as
 * a concept when one of its declared asset types is named for a publishable platform
 * (`instagram_post`, `youtube_video`, or a bare `tiktok`). It is the gate for offering the destination
 * surfaces at all — an ENGINEERING item declares `github_pr` and never sees them.
 */
export function workflowDeclaresPublishTargets(view: WorkflowView | undefined): boolean {
  return (view?.assetTypes ?? []).some((assetType) => {
    const head = assetType.trim().toLowerCase().split('_')[0]
    return (PLATFORM_ORDER as string[]).includes(head)
  })
}

/**
 * (platform, connection) is a target's identity — the same pair the backend's uniqueness is on. A manual
 * destination has no connection, so it keys on the same `manual` sentinel the backend uses, which is also
 * what makes "one manual destination per platform" fall out for free.
 */
export function targetKey(platform: string, connectionId: string | null): string {
  return `${platform} ${connectionId ?? 'manual'}`
}

export function isManual(option: PublishTargetOption | SelectedPublishTarget): boolean {
  return option.lane === 'MANUAL'
}

/** Whether this platform group offers any connected account. */
export function hasAccount(targets: PublishTargetOption[]): boolean {
  return targets.some((o) => !isManual(o))
}

export function isUnhealthy(option: PublishTargetOption): boolean {
  return option.healthStatus === 'UNHEALTHY'
}

/**
 * A TikTok target's options, with a missing privacy level backfilled from the account's own first
 * allowed level — the same default TikTokPublishOptions shows pre-selected, so the picker never saves
 * (or gates approval on) "no privacy level chosen" for a row the audience select already shows as
 * decided. Applied at save time, in one place, rather than as a per-row effect on mount: two TikTok
 * rows defaulting in the same tick would otherwise race each other's save.
 */
export function withTikTokDefault(
  option: PublishTargetOption,
  values: TikTokPublishOptionValues
): TikTokPublishOptionValues {
  if (values.privacyLevel) return values
  const fallback = option.privacyLevelOptions?.[0]
  return fallback ? { ...values, privacyLevel: fallback } : values
}

/**
 * Whether this selected target's row shows its outcome (chip, permalink, error, actions) instead of the
 * editable checkbox/format/options UI: "the item has been scheduled or published" (`approvedOrLater` —
 * a Post commits its bundle at approval, the step right before scheduling, so a target here is done
 * being edited even if it hasn't fired yet), or this particular target already has — a retry resets a
 * FAILED target back to PENDING, the same wire value an unscheduled selection carries, so `state` alone
 * cannot tell the two apart; the item-level signal can.
 */
export function isSettled(
  target: SelectedPublishTarget | undefined,
  approvedOrLater: boolean
): target is SelectedPublishTarget {
  if (!target) return false
  return approvedOrLater || target.state !== 'PENDING'
}
