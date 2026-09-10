'use client'

// COND-23 T3.6, T6.3: one card about where a Post goes — what is picked, and what happened.
//
// This used to be three cards on the page: this picker (choosing accounts), the TikTok consent step
// (a separate card directly under it), and a "Publishing results" panel showing outcomes — all about
// the same set of destinations, all visible on screen at once regardless of whether the Post had even
// been scheduled yet. They are one card now: a row is a destination, full stop. Before anything has
// gone out, a row is checkbox + format + options. Once its state leaves PENDING (the backend hands a
// target off only at or after scheduling — see PostPublishTargetState), the row switches to its outcome:
// a status chip, its permalink if it has one, the platform's own error verbatim, and the actions that
// belong to that destination (retry, or record a manual publish). A TikTok row additionally carries the
// audit-required consent step as a disclosure directly beneath it.
//
// The list of choices is derived by the backend from the project's ACTIVE social connections
// (GET /projects/{id}/publish-targets) — a platform with no connection is simply absent, because
// there is nothing a human could pick it for. The Post's own choice is a set-replace
// (PUT .../work-items/{id}/publish-targets): every toggle sends the whole selection, so the server
// diffs it and touches only the rows that changed. That matters — a row may already be handed off to
// a platform and carry the id of a post that is live.
//
// Two states get explicit faces rather than a silent drop:
//   * an UNHEALTHY connection is offered disabled, with the platform's own explanation, so a human
//     sees why the account they expected cannot be chosen (reconnecting it is a Settings trip);
//   * a selected account whose connection has since gone away is shown checked with a note, and is
//     dropped from the payload on the next save rather than being sent back to a server that would
//     refuse it.
//
// The error text on a failed row is rendered verbatim. It is written by the platform ("The user has
// exceeded the number of videos they may upload"), and paraphrasing it into house language would lose
// the one detail that tells a human whether to retry now, retry tomorrow, or go fix something.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { ExternalLink, RotateCw, Share2 } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardHeader } from '@/components/ui/card'
import { DateTimePicker } from '@/components/ui/date-time-picker'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { statusHue, statusHueClasses, type StatusHue } from '@/components/ui/status-badge'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiGet, apiPost, apiPut } from '@/lib/api'
import {
  INHERITED_CONTENT,
  TargetContentEditor,
  type TargetContent,
} from './TargetContentEditor'
import { cn } from '@/lib/utils'
import { statusMeta } from '@/lib/workflows'
import {
  isApprovedOrLater,
  isUnderReviewOrLater,
  isVideoContentType,
  type MediaAsset,
} from '@/components/workitems/MediaUploadPanel'
import {
  EMPTY_TIKTOK_OPTIONS,
  TikTokPublishOptions,
  normalizeTikTokOptions,
  tiktokOptionsProblem,
  type TikTokPublishOptionValues,
} from '@/components/marketing/TikTokPublishOptions'
import { TikTokConsentStep, type TikTokConsentTarget } from '@/components/marketing/TikTokConsentStep'
import {
  InstagramPublishOptions,
  isSingleImageTarget,
  normalizeInstagramOptions,
  type InstagramPublishOptionValues,
} from '@/components/marketing/InstagramPublishOptions'
import {
  YouTubePublishOptions,
  normalizeYouTubeOptions,
  type YouTubePublishOptionValues,
} from '@/components/marketing/YouTubePublishOptions'
import {
  FormatBadge,
  PostFormatSelector,
  type PostFormat,
} from '@/components/marketing/PostFormatSelector'
import type { WorkflowView } from '@/types/workItem'

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
  fireTime?: string | null
}

/** What a selection sends back. `publishOptions` rides along only where the platform has any. */
interface PublishTargetSelectionPayload {
  platform: PublishPlatform
  /** Omitted (null) selects the platform's manual destination. */
  connectionId: string | null
  format?: PostFormat
  publishOptions?: PublishOptionsBag
  captionOverride?: string | null
  assetIds?: string[]
}

interface RetryPublishResponse {
  workItemId: string
  status: string
  retriedCount: number
  targets: SelectedPublishTarget[]
}

/** Render order, so the groups don't reshuffle as connections come and go. */
const PLATFORM_ORDER: PublishPlatform[] = ['facebook', 'instagram', 'youtube', 'tiktok']

const PLATFORM_LABELS: Record<PublishPlatform, string> = {
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
const STATE_HUES: Record<string, StatusHue> = {
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
const STATE_LABELS: Record<string, string> = {
  PENDING: 'Waiting',
  HANDED_OFF: 'Handed off',
  PUBLISHING: 'Publishing',
  AWAITING_MANUAL: 'Post it now',
  PUBLISHED: 'Published',
  FAILED: 'Failed',
  REVOKED: 'Taken back',
}

function stateHue(state: string): StatusHue {
  return STATE_HUES[state] ?? statusHue(state)
}

function stateLabel(state: string): string {
  return STATE_LABELS[state] ?? state
}

/**
 * A destination waiting on the person reading this: a manual one whose fire time has passed, or an
 * automated one the platform handed back (TikTok's pre-audit inbox upload). Either way the next step is a
 * human's, and the link they record is the outcome.
 */
function awaitsAHuman(target: SelectedPublishTarget): boolean {
  return target.state === 'AWAITING_MANUAL'
}

/** Strip the scheme so a permalink reads as a destination rather than a wall of URL. */
function permalinkText(permalink: string): string {
  return permalink.replace(/^https?:\/\//, '')
}

/**
 * Client-side mirror of PostScheduleValidator.declaresPublishTargets: a Workflow treats publishing as
 * a concept when one of its declared asset types is named for a publishable platform
 * (`instagram_post`, `youtube_video`, or a bare `tiktok`). It is the gate for offering this picker at
 * all — an ENGINEERING item declares `github_pr` and never sees it.
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
function targetKey(platform: string, connectionId: string | null): string {
  return `${platform} ${connectionId ?? 'manual'}`
}

function isManual(option: PublishTargetOption | SelectedPublishTarget): boolean {
  return option.lane === 'MANUAL'
}

/** Whether this platform group offers any connected account. */
function hasAccount(targets: PublishTargetOption[]): boolean {
  return targets.some((o) => !isManual(o))
}

function isUnhealthy(option: PublishTargetOption): boolean {
  return option.healthStatus === 'UNHEALTHY'
}

/**
 * A TikTok target's options, with a missing privacy level backfilled from the account's own first
 * allowed level — the same default TikTokPublishOptions shows pre-selected, so the picker never saves
 * (or gates approval on) "no privacy level chosen" for a row the audience select already shows as
 * decided. Applied at save time, in one place, rather than as a per-row effect on mount: two TikTok
 * rows defaulting in the same tick would otherwise race each other's save.
 */
function withTikTokDefault(
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
function isSettled(
  target: SelectedPublishTarget | undefined,
  approvedOrLater: boolean
): target is SelectedPublishTarget {
  if (!target) return false
  return approvedOrLater || target.state !== 'PENDING'
}

/**
 * The TikTok options carried by a set of persisted targets. `fallback` covers a backend that hasn't
 * started echoing publishOptions yet — without it, a round-trip would silently blank an edit.
 */
function seedTikTokOptions(
  targets: SelectedPublishTarget[],
  fallback: Record<string, TikTokPublishOptionValues> = {}
): Record<string, TikTokPublishOptionValues> {
  const seeded: Record<string, TikTokPublishOptionValues> = {}
  for (const target of targets) {
    if (target.platform !== 'tiktok') continue
    const key = targetKey(target.platform, target.connectionId)
    seeded[key] = normalizeTikTokOptions(target.publishOptions ?? fallback[key])
  }
  return seeded
}

/** Same trap, for Instagram's own option bag. */
function seedInstagramOptions(
  targets: SelectedPublishTarget[],
  fallback: Record<string, InstagramPublishOptionValues> = {}
): Record<string, InstagramPublishOptionValues> {
  const seeded: Record<string, InstagramPublishOptionValues> = {}
  for (const target of targets) {
    if (target.platform !== 'instagram') continue
    const key = targetKey(target.platform, target.connectionId)
    seeded[key] = normalizeInstagramOptions(target.publishOptions ?? fallback[key])
  }
  return seeded
}

/** Same trap, for YouTube's own option bag. */
function seedYouTubeOptions(
  targets: SelectedPublishTarget[],
  fallback: Record<string, YouTubePublishOptionValues> = {}
): Record<string, YouTubePublishOptionValues> {
  const seeded: Record<string, YouTubePublishOptionValues> = {}
  for (const target of targets) {
    if (target.platform !== 'youtube') continue
    const key = targetKey(target.platform, target.connectionId)
    seeded[key] = normalizeYouTubeOptions(target.publishOptions ?? fallback[key])
  }
  return seeded
}

/** The format each persisted target carries. A missing value reads as `feed`, same as the API. */
function seedFormats(
  targets: SelectedPublishTarget[],
  fallback: Record<string, PostFormat> = {}
): Record<string, PostFormat> {
  const seeded: Record<string, PostFormat> = {}
  for (const target of targets) {
    const key = targetKey(target.platform, target.connectionId)
    seeded[key] = target.format ?? fallback[key] ?? 'feed'
  }
  return seeded
}

/** This target's own effective media, in publish order — the chosen subset, or the whole Post's. */
function effectiveAssetsFor(content: TargetContent | undefined, assets: MediaAsset[]): MediaAsset[] {
  const ids = content?.assetIds ?? assets.map((a) => a.id)
  const byId = new Map(assets.map((a) => [a.id, a]))
  return ids.map((id) => byId.get(id)).filter((a): a is MediaAsset => Boolean(a))
}

/**
 * The per-target caption and media carried by a set of persisted targets. Same trap as the TikTok
 * options above: a save sends the complete selection, so anything not seeded back from the server would
 * be cleared by the next unrelated edit.
 */
function seedContent(
  targets: SelectedPublishTarget[],
  fallback: Record<string, TargetContent> = {}
): Record<string, TargetContent> {
  const seeded: Record<string, TargetContent> = {}
  for (const target of targets) {
    const key = targetKey(target.platform, target.connectionId)
    const stored: TargetContent = {
      captionOverride: target.captionOverride ?? null,
      assetIds: target.assetIds ?? null,
    }
    const known = target.captionOverride !== undefined || target.assetIds !== undefined
    seeded[key] = known ? stored : (fallback[key] ?? INHERITED_CONTENT)
  }
  return seeded
}

function isInherited(content: TargetContent | undefined): boolean {
  return !content || (content.captionOverride === null && content.assetIds === null)
}

interface PostTargetPickerProps {
  projectId: string
  workItemId: string
  token: string
  /** The Post's current status, for the "this will send it back for review" warning. */
  status?: string
  workflowView?: WorkflowView
  /** Fired after every successful save, so a parent can refresh anything bundle-derived. */
  onChanged?: (targets: SelectedPublishTarget[]) => void
  /**
   * Every selected TikTok destination with the options it currently carries, whenever that changes.
   * Reported upward so a status control elsewhere (StatusDropdown) can gate on it without owning this
   * whole picker.
   */
  onTikTokChange?: (targets: TikTokConsentTarget[]) => void
  /** The Post's uploaded media, so a destination can choose which of it to publish. */
  assets?: MediaAsset[]
  /** The Post's caption, shown as what a destination falls back to. */
  caption?: string | null
  /**
   * Told the TikTok disclosure's consent answer whenever it changes — consent is one of the gate's
   * inputs, so the caller (the readiness state) has to ask the server again when it moves.
   */
  onTikTokConsentChange?: (consented: boolean) => void
}

export function PostTargetPicker({
  projectId,
  workItemId,
  token,
  status,
  workflowView,
  onChanged,
  onTikTokChange,
  assets = [],
  caption = null,
  onTikTokConsentChange,
}: PostTargetPickerProps) {
  const [options, setOptions] = useState<PublishTargetOption[]>([])
  const [selected, setSelected] = useState<SelectedPublishTarget[]>([])
  // Per-target caption and media, held beside `selected` for the same reason the platform options are:
  // it is the thing being edited, and an edit that fails to save must not linger.
  const [contentByKey, setContentByKey] = useState<Record<string, TargetContent>>({})
  const [customizing, setCustomizing] = useState<Set<string>>(new Set())
  // Per-target format and platform options, keyed the same way a target is. Kept beside `selected`
  // rather than inside it because it is the thing being edited, and an edit that fails to save must
  // not linger.
  const [formatByKey, setFormatByKey] = useState<Record<string, PostFormat>>({})
  const [optionsByKey, setOptionsByKey] = useState<Record<string, TikTokPublishOptionValues>>({})
  const [instagramOptionsByKey, setInstagramOptionsByKey] = useState<
    Record<string, InstagramPublishOptionValues>
  >({})
  const [youtubeOptionsByKey, setYoutubeOptionsByKey] = useState<
    Record<string, YouTubePublishOptionValues>
  >({})
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [retrying, setRetrying] = useState(false)
  /** The one target whose "mark published" form is open, if any — one at a time. */
  const [completingKey, setCompletingKey] = useState<string | null>(null)

  // Both lists in one pass: the choices are project-scoped and the selection is item-scoped, but a
  // picker that rendered one before the other would flash rows as unchecked before checking them.
  // `loading` starts true and is only ever cleared here — the deps are route-stable, so there is no
  // re-entry to re-arm it for.
  useEffect(() => {
    let cancelled = false
    Promise.all([
      apiGet<PublishTargetOption[]>(`/api/v2/projects/${projectId}/publish-targets`, token),
      apiGet<SelectedPublishTarget[]>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets`,
        token
      ),
    ])
      .then(([available, current]) => {
        if (cancelled) return
        setOptions(available)
        setSelected(current)
        setOptionsByKey(seedTikTokOptions(current))
        setInstagramOptionsByKey(seedInstagramOptions(current))
        setYoutubeOptionsByKey(seedYouTubeOptions(current))
        setFormatByKey(seedFormats(current))
        const content = seedContent(current)
        setContentByKey(content)
        // Any destination that already differs from the Post opens with its editor showing, so a
        // customisation is never invisible until somebody thinks to look for it.
        setCustomizing(
          new Set(Object.entries(content).filter(([, c]) => !isInherited(c)).map(([key]) => key))
        )
        setLoadError(null)
      })
      .catch((err) => {
        if (cancelled) return
        setLoadError(apiErrorMessage(err, 'Could not load publishing accounts'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, workItemId, token])

  const availableKeys = useMemo(
    () => new Set(options.map((o) => targetKey(o.platform, o.connectionId))),
    [options]
  )
  const selectedKeys = useMemo(
    () => new Set(selected.map((t) => targetKey(t.platform, t.connectionId))),
    [selected]
  )
  const selectedByKey = useMemo(
    () => new Map(selected.map((t) => [targetKey(t.platform, t.connectionId), t])),
    [selected]
  )

  /**
   * Every row the picker shows: the project's options, plus any still-selected target whose
   * connection has since disappeared, so it can be seen and unchecked instead of vanishing.
   */
  const rows = useMemo(() => {
    const orphans: PublishTargetOption[] = selected
      .filter((t) => !availableKeys.has(targetKey(t.platform, t.connectionId)))
      .map((t) => ({
        platform: t.platform,
        connectorId: t.connectorId,
        connectionId: t.connectionId,
        label: t.label ?? t.connectionId ?? 'Manual',
        lane: t.lane,
      }))
    return [...options, ...orphans]
  }, [options, selected, availableKeys])

  const groups = useMemo(
    () =>
      PLATFORM_ORDER.map((platform) => ({
        platform,
        targets: rows.filter((r) => r.platform === platform),
      })).filter((group) => group.targets.length > 0),
    [rows]
  )

  interface SavePatch {
    keys?: Set<string>
    tiktok?: Record<string, TikTokPublishOptionValues>
    instagram?: Record<string, InstagramPublishOptionValues>
    youtube?: Record<string, YouTubePublishOptionValues>
    content?: Record<string, TargetContent>
    formats?: Record<string, PostFormat>
  }

  const save = useCallback(
    async (patch: SavePatch) => {
      const nextKeys = patch.keys ?? selectedKeys
      const nextTiktok = patch.tiktok ?? optionsByKey
      const nextInstagram = patch.instagram ?? instagramOptionsByKey
      const nextYoutube = patch.youtube ?? youtubeOptionsByKey
      const nextContent = patch.content ?? contentByKey
      const nextFormats = patch.formats ?? formatByKey

      // Every selected target's caption and media go out on every save, because this endpoint is a
      // set-replace: a target sent without them would have its customisation cleared by an edit to a
      // different target entirely.
      // Inheriting is expressed by leaving the field out, not by sending null: the server reads both
      // the same way, and an omitted field keeps an uncustomised target's payload byte-identical to
      // what a client that predates per-target content would send.
      const contentFor = (o: PublishTargetOption): Partial<PublishTargetSelectionPayload> => {
        const content = nextContent[targetKey(o.platform, o.connectionId)]
        if (!content) return {}
        return {
          ...(content.captionOverride === null ? {} : { captionOverride: content.captionOverride }),
          ...(content.assetIds === null ? {} : { assetIds: content.assetIds }),
        }
      }
      // Options ride along only for an API (non-manual) target. A manual one never uses them — they
      // are the payload we would send the platform, and on that lane the creator sets every one of
      // them in the platform's own composer — and sending them anyway would store a bag of
      // meaningless falses on the row that then counts as part of the publish bundle.
      const optionsFor = (o: PublishTargetOption): Partial<PublishTargetSelectionPayload> => {
        if (isManual(o)) return {}
        const key = targetKey(o.platform, o.connectionId)
        if (o.platform === 'tiktok') {
          return { publishOptions: withTikTokDefault(o, nextTiktok[key] ?? EMPTY_TIKTOK_OPTIONS) }
        }
        if (o.platform === 'instagram') {
          const values = nextInstagram[key]
          return values && Object.keys(values).length > 0 ? { publishOptions: values } : {}
        }
        if (o.platform === 'youtube') {
          const values = nextYoutube[key]
          return values && Object.keys(values).length > 0 ? { publishOptions: values } : {}
        }
        return {}
      }
      // Only ever send targets the project can still publish to; an orphan would be refused.
      const payload: PublishTargetSelectionPayload[] = options
        .filter((o) => nextKeys.has(targetKey(o.platform, o.connectionId)))
        .map((o) => ({
          platform: o.platform,
          connectionId: o.connectionId,
          format: nextFormats[targetKey(o.platform, o.connectionId)] ?? 'feed',
          ...optionsFor(o),
          ...contentFor(o),
        }))
      setSaving(true)
      try {
        const updated = await apiPut<SelectedPublishTarget[]>(
          `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets`,
          { targets: payload },
          token
        )
        setSelected(updated)
        // What was just sent is only committed once the server took it — an edit that 400s reverts
        // to what is on the server. The server's echo wins where it has one, so a value it clamps
        // shows as clamped rather than as what was typed.
        setOptionsByKey({ ...nextTiktok, ...seedTikTokOptions(updated, nextTiktok) })
        setInstagramOptionsByKey({ ...nextInstagram, ...seedInstagramOptions(updated, nextInstagram) })
        setYoutubeOptionsByKey({ ...nextYoutube, ...seedYouTubeOptions(updated, nextYoutube) })
        setContentByKey({ ...nextContent, ...seedContent(updated, nextContent) })
        setFormatByKey({ ...nextFormats, ...seedFormats(updated, nextFormats) })
        onChanged?.(updated)
      } catch (err) {
        toastError(apiErrorMessage(err, 'Could not update publishing accounts'))
      } finally {
        setSaving(false)
      }
    },
    [
      options,
      projectId,
      workItemId,
      token,
      onChanged,
      selectedKeys,
      optionsByKey,
      instagramOptionsByKey,
      youtubeOptionsByKey,
      contentByKey,
      formatByKey,
    ]
  )

  const toggle = useCallback(
    (option: PublishTargetOption) => {
      const key = targetKey(option.platform, option.connectionId)
      const next = new Set(selectedKeys)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      void save({ keys: next })
    },
    [selectedKeys, save]
  )

  const changeTikTokOptions = useCallback(
    (option: PublishTargetOption, next: TikTokPublishOptionValues) => {
      void save({
        tiktok: { ...optionsByKey, [targetKey(option.platform, option.connectionId)]: next },
      })
    },
    [optionsByKey, save]
  )

  const changeInstagramOptions = useCallback(
    (option: PublishTargetOption, next: InstagramPublishOptionValues) => {
      void save({
        instagram: {
          ...instagramOptionsByKey,
          [targetKey(option.platform, option.connectionId)]: next,
        },
      })
    },
    [instagramOptionsByKey, save]
  )

  const changeYouTubeOptions = useCallback(
    (option: PublishTargetOption, next: YouTubePublishOptionValues) => {
      void save({
        youtube: { ...youtubeOptionsByKey, [targetKey(option.platform, option.connectionId)]: next },
      })
    },
    [youtubeOptionsByKey, save]
  )

  const changeFormat = useCallback(
    (option: PublishTargetOption, next: PostFormat) => {
      void save({
        formats: { ...formatByKey, [targetKey(option.platform, option.connectionId)]: next },
      })
    },
    [formatByKey, save]
  )

  /** Opens or closes one destination's editor. Purely local — nothing is saved by looking. */
  const toggleCustomizing = useCallback((option: PublishTargetOption) => {
    const key = targetKey(option.platform, option.connectionId)
    setCustomizing((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }, [])

  const changeContent = useCallback(
    (option: PublishTargetOption, next: TargetContent) => {
      const key = targetKey(option.platform, option.connectionId)
      const nextContent = { ...contentByKey, [key]: next }
      setContentByKey(nextContent)
      void save({ content: nextContent })
    },
    [contentByKey, save]
  )

  /**
   * Records that a human posted a manual destination by hand.
   *
   * The response is the target as the server now holds it, so it is also the refresh — the same
   * reasoning as retry: no second GET, and no window where the row shows a state already moved past.
   * The Post's own status can roll up on this call (the last outstanding target settling it), so the
   * parent is told to refresh exactly as it is after a retry.
   */
  const completeManual = useCallback(
    async (targetId: string, permalink: string, publishedAt: string | null) => {
      const updated = await apiPost<SelectedPublishTarget>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets/${targetId}/manual-publish`,
        { permalink, publishedAt },
        token
      )
      setSelected((current) => current.map((t) => (t.id === updated.id ? updated : t)))
      setCompletingKey(null)
      onChanged?.(selected.map((t) => (t.id === updated.id ? updated : t)))
    },
    [projectId, workItemId, token, onChanged, selected]
  )

  const retry = useCallback(async () => {
    setRetrying(true)
    try {
      // The response carries every target, so the retry is also the refresh — no second GET, and no
      // window where the picker shows a state the server has already moved past.
      const result = await apiPost<RetryPublishResponse>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets/retry`,
        {},
        token
      )
      setSelected(result.targets)
      onChanged?.(result.targets)
    } catch (err) {
      // Never swallow: the outcomes stay exactly as they were and the reason is said out loud.
      toastError(apiErrorMessage(err, 'Could not retry the failed accounts'))
    } finally {
      setRetrying(false)
    }
  }, [projectId, workItemId, token, onChanged])

  /** Every selected TikTok destination, with the options it carries and why it isn't postable yet. */
  const tiktokTargets = useMemo<TikTokConsentTarget[]>(
    () =>
      options
        .filter(
          (o) =>
            o.platform === 'tiktok' &&
            // A manual TikTok destination needs no consent: the creator posts it inside TikTok, seeing
            // TikTok's own preview. Mirrors PublishConsentService.requiresConsent exactly — if these two
            // disagreed, the UI would ask for a consent the approval gate does not want.
            !isManual(o) &&
            selectedKeys.has(targetKey(o.platform, o.connectionId))
        )
        .map((o) => {
          const key = targetKey(o.platform, o.connectionId)
          const values = optionsByKey[key] ?? EMPTY_TIKTOK_OPTIONS
          const content = contentByKey[key] ?? INHERITED_CONTENT
          return {
            connectionId: o.connectionId ?? '',
            label: o.label,
            creatorNickname: o.creatorNickname ?? null,
            options: values,
            problem: tiktokOptionsProblem(values),
            // What will actually go out to this account, so the creator consents to the post rather
            // than to the Post. Undefined assetIds means it inherits, which the preview reads as
            // "all of the Post's media".
            caption: content.captionOverride ?? caption,
            ...(content.assetIds === null ? {} : { assetIds: content.assetIds }),
          }
        }),
    [options, selectedKeys, optionsByKey, contentByKey, caption]
  )

  useEffect(() => {
    onTikTokChange?.(tiktokTargets)
  }, [tiktokTargets, onTikTokChange])

  const revertsOnEdit = isApprovedOrLater(workflowView, status)
  // Frozen while somebody is reading it: changing where a post goes is changing what is being approved.
  const frozen = isUnderReviewOrLater(workflowView, status) && !revertsOnEdit
  const noun = workflowView?.noun ?? 'Post'

  const failedCount = selected.filter((t) => t.state === 'FAILED').length
  const awaitingCount = selected.filter(awaitsAHuman).length

  if (loading) {
    return (
      <Card>
        <CardHeader>
          <h2 className="text-sm font-medium text-foreground">Publishing to</h2>
        </CardHeader>
        <div className="space-y-2.5 px-4 py-3">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-4 w-32" />
        </div>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <div>
          <h2 className="text-sm font-medium text-foreground">Publishing to</h2>
          <span className="text-xs text-muted-foreground">
            {selected.length === 0
              ? 'No accounts selected'
              : `${selected.length} account${selected.length === 1 ? '' : 's'} selected`}
          </span>
        </div>
        {failedCount > 0 && (
          <Button variant="outline" size="sm" onClick={() => void retry()} disabled={retrying}>
            <RotateCw className={cn('mr-1.5 h-3.5 w-3.5', retrying && 'animate-spin')} />
            {retrying ? 'Retrying…' : 'Retry failed'}
          </Button>
        )}
      </CardHeader>

      {loadError && (
        <div className="px-4 py-3">
          <Alert variant="destructive">{loadError}</Alert>
        </div>
      )}

      {!loadError && awaitingCount > 0 && (
        <div className="px-4 pt-3">
          <Alert variant="warning">
            {awaitingCount === 1
              ? 'One destination is due and publishes by hand. Post it, then paste the link back below.'
              : `${awaitingCount} destinations are due and publish by hand. Post each one, then paste its link back below.`}
          </Alert>
        </div>
      )}

      {!loadError && failedCount > 0 && (
        <div className="px-4 pt-3">
          <Alert variant="warning">
            {failedCount === 1
              ? '1 account could not publish. Retrying re-sends only that one — what is already live stays live.'
              : `${failedCount} accounts could not publish. Retrying re-sends only the failed ones — what is already live stays live.`}
          </Alert>
        </div>
      )}

      {!loadError && groups.length === 0 && (
        // Unreachable against a current backend, which always offers a manual destination per platform.
        // Kept as the honest rendering of an empty list rather than removed, so an older or partial
        // response degrades into an explanation instead of a blank card.
        <EmptyState
          icon={Share2}
          title="Nowhere to publish"
          description={`Connect a Facebook Page, Instagram, YouTube or TikTok account in Integrations to choose where this ${noun} publishes.`}
        />
      )}

      {!loadError && groups.length > 0 && (
        <>
          {frozen && (
            <div className="px-4 pt-3">
              <Alert variant="info">
                Publishing accounts are locked while this {noun} is{' '}
                {statusMeta(workflowView, status ?? '').label}. It has to be sent back for changes before
                they can change.
              </Alert>
            </div>
          )}
          {revertsOnEdit && (
            <div className="px-4 pt-3">
              <Alert variant="warning">
                Changing accounts sends this {noun} back for review and takes back anything already
                scheduled on a platform.
              </Alert>
            </div>
          )}
          <fieldset disabled={saving || frozen} className="divide-y divide-border">
            <legend className="sr-only">Publishing accounts</legend>
            {groups.map((group) => (
              <div key={group.platform} className="py-1.5">
                <div className="px-4 py-1 text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
                  {PLATFORM_LABELS[group.platform]}
                </div>
                {group.targets.map((option) => {
                  const key = targetKey(option.platform, option.connectionId)
                  // A platform with a connected account does not offer its by-hand row: with an account
                  // to publish through, the row reads as a duplicate. A by-hand row already on the Post
                  // stays, so it can be unticked; a platform with no account keeps it as the only way.
                  if (isManual(option) && !selectedKeys.has(key) && hasAccount(group.targets)) {
                    return null
                  }
                  return (
                    <TargetRow
                      key={key}
                      option={option}
                      checked={selectedKeys.has(key)}
                      unavailable={!availableKeys.has(key)}
                      saving={saving}
                      format={formatByKey[key] ?? 'feed'}
                      tiktokOptions={optionsByKey[key] ?? EMPTY_TIKTOK_OPTIONS}
                      instagramOptions={instagramOptionsByKey[key] ?? {}}
                      youtubeOptions={youtubeOptionsByKey[key] ?? {}}
                      assets={assets}
                      postCaption={caption}
                      content={contentByKey[key] ?? INHERITED_CONTENT}
                      customizing={customizing.has(key)}
                      frozen={frozen}
                      approvedOrLater={revertsOnEdit}
                      selectedTarget={selectedByKey.get(key)}
                      completing={completingKey === key}
                      onOpenComplete={() => setCompletingKey(key)}
                      onCancelComplete={() => setCompletingKey(null)}
                      onCompleteManual={(permalink, publishedAt) => {
                        const target = selectedByKey.get(key)
                        if (target) return completeManual(target.id, permalink, publishedAt)
                        return Promise.resolve()
                      }}
                      onToggle={() => toggle(option)}
                      onFormatChange={(next) => changeFormat(option, next)}
                      onTikTokOptionsChange={(next) => changeTikTokOptions(option, next)}
                      onInstagramOptionsChange={(next) => changeInstagramOptions(option, next)}
                      onYouTubeOptionsChange={(next) => changeYouTubeOptions(option, next)}
                      onCustomizeToggle={() => toggleCustomizing(option)}
                      onContentChange={(next) => changeContent(option, next)}
                    />
                  )
                })}
                {group.platform === 'tiktok' && tiktokTargets.length > 0 && (
                  <TikTokConsentStep
                    bare
                    targets={tiktokTargets}
                    assets={assets}
                    projectId={projectId}
                    workItemId={workItemId}
                    token={token}
                    disabled={frozen}
                    onConsentChange={onTikTokConsentChange}
                  />
                )}
              </div>
            ))}
          </fieldset>
        </>
      )}
    </Card>
  )
}

interface TargetRowProps {
  option: PublishTargetOption
  checked: boolean
  /** The connection behind an already-selected target has gone away. */
  unavailable: boolean
  saving: boolean
  format: PostFormat
  tiktokOptions: TikTokPublishOptionValues
  instagramOptions: InstagramPublishOptionValues
  youtubeOptions: YouTubePublishOptionValues
  /** The Post's media and caption, which this destination may override. */
  assets: MediaAsset[]
  postCaption: string | null
  content: TargetContent
  /** Whether the per-destination editor is open. */
  customizing: boolean
  /** Editing is refused past the review gate, so the controls are disabled rather than 400ing. */
  frozen: boolean
  /** The Post has committed its bundle (approved or later) — see `isSettled`. */
  approvedOrLater: boolean
  /** The persisted row, once the Post has one — carries the outcome once state leaves PENDING. */
  selectedTarget?: SelectedPublishTarget
  /** Whether this row's "mark published" form is the open one. */
  completing: boolean
  onOpenComplete: () => void
  onCancelComplete: () => void
  onCompleteManual: (permalink: string, publishedAt: string | null) => Promise<void>
  onToggle: () => void
  onFormatChange: (next: PostFormat) => void
  onTikTokOptionsChange: (next: TikTokPublishOptionValues) => void
  onInstagramOptionsChange: (next: InstagramPublishOptionValues) => void
  onYouTubeOptionsChange: (next: YouTubePublishOptionValues) => void
  onCustomizeToggle: () => void
  onContentChange: (next: TargetContent) => void
}

function TargetRow({
  option,
  checked,
  unavailable,
  saving,
  format,
  tiktokOptions,
  instagramOptions,
  youtubeOptions,
  assets,
  postCaption,
  content,
  customizing,
  frozen,
  approvedOrLater,
  selectedTarget,
  completing,
  onOpenComplete,
  onCancelComplete,
  onCompleteManual,
  onToggle,
  onFormatChange,
  onTikTokOptionsChange,
  onInstagramOptionsChange,
  onYouTubeOptionsChange,
  onCustomizeToggle,
  onContentChange,
}: TargetRowProps) {
  const unhealthy = isUnhealthy(option)
  // An unhealthy account can't be added — its credentials no longer work — but one already on the
  // Post stays actionable, or a human could never take it back off.
  const disabled = unhealthy && !checked
  const noteId = `${option.platform}-${option.connectionId ?? 'manual'}-note`
  const settled = checked && isSettled(selectedTarget, approvedOrLater)
  const note = unavailable
    ? 'This account is no longer connected — it will be removed when you change the selection.'
    : unhealthy
      ? (option.healthMessage ?? 'This account needs to be reconnected before it can publish.')
      : isManual(option) && !settled
        ? "Conductor won't post this one. It still goes through review and onto the calendar; when it's due you'll be asked to post it yourself and paste the link back."
        : null

  // Platform options are only meaningful for an API target: they are the payload we send the
  // platform, and on the manual lane the creator sets all of it in the platform's own composer.
  const showOptions = checked && !unavailable && !isManual(option) && !settled
  const customized = content.captionOverride !== null || content.assetIds !== null
  const idPrefix = `${option.platform}-${option.connectionId ?? 'manual'}`

  // This destination's own effective media, for the pickers that key off it — TikTok's video/photo
  // split, and Instagram's single-image alt text.
  const effectiveAssets = effectiveAssetsFor(content, assets)
  const postImages = assets.filter((a) => !isVideoContentType(a.contentType))

  return (
    <div>
      <label
        className={cn(
          'flex items-start gap-2.5 px-4 py-2',
          disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:bg-muted/50'
        )}
      >
        <input
          type="checkbox"
          className="mt-0.5 rounded border-border"
          checked={checked}
          disabled={disabled}
          aria-describedby={note ? noteId : undefined}
          onChange={onToggle}
        />
        <span className="min-w-0 flex-1">
          <span className="flex items-center gap-2">
            <span className="text-sm text-foreground">
              {option.label}
              <FormatBadge format={format} />
            </span>
            {settled && (
              <span
                className={cn(
                  'rounded-full px-2 py-0.5 text-xs font-medium',
                  statusHueClasses(stateHue(selectedTarget!.state)).bg,
                  statusHueClasses(stateHue(selectedTarget!.state)).text
                )}
              >
                {stateLabel(selectedTarget!.state)}
              </span>
            )}
          </span>
          {note && (
            <span
              id={noteId}
              className={cn(
                'block text-xs',
                // A manual destination is a normal choice, not a problem to warn about — amber is
                // reserved for the two rows a human has to do something about.
                isManual(option) && !unavailable && !unhealthy
                  ? 'text-muted-foreground'
                  : statusHueClasses('amber').text
              )}
            >
              {note}
            </span>
          )}
          {settled && selectedTarget!.permalink && (
            <a
              href={selectedTarget!.permalink}
              target="_blank"
              rel="noopener noreferrer"
              onClick={(e) => e.stopPropagation()}
              className="mt-0.5 inline-flex items-center gap-1 text-xs text-primary hover:underline"
            >
              <span className="truncate">{permalinkText(selectedTarget!.permalink)}</span>
              <ExternalLink className="h-3 w-3 shrink-0" aria-hidden="true" />
            </a>
          )}
        </span>
      </label>
      {settled ? (
        <SettledOutcomeRow
          target={selectedTarget!}
          open={completing}
          onOpen={onOpenComplete}
          onCancel={onCancelComplete}
          onComplete={onCompleteManual}
        />
      ) : (
        checked &&
        !unavailable && (
          <div className="px-4 pb-3">
            <button
              type="button"
              // Outside the <label>, like the option editors below: a click here must not also un-pick
              // the destination it belongs to.
              className="text-xs font-medium text-muted-foreground underline-offset-2 hover:underline"
              onClick={onCustomizeToggle}
            >
              {customized ? 'Customized for this destination' : 'Customize for this destination'}
            </button>
            {/* TikTok's options stay visible whenever the account is picked, not behind "Customize": the
                privacy level is mandatory for approval, so hiding it would only move the blocker. */}
            {showOptions && option.platform === 'tiktok' && (
              <TikTokPublishOptions
                idPrefix={`tiktok-${option.connectionId}`}
                accountLabel={option.label}
                privacyLevelOptions={option.privacyLevelOptions ?? []}
                isVideo={effectiveAssets.some((a) => isVideoContentType(a.contentType))}
                images={effectiveAssets.filter((a) => !isVideoContentType(a.contentType))}
                value={tiktokOptions}
                disabled={saving}
                onChange={onTikTokOptionsChange}
              />
            )}
            {customizing && (
              <div className="mt-2 space-y-3">
                <PostFormatSelector
                  idPrefix={idPrefix}
                  platform={option.platform}
                  formats={option.formats}
                  value={format}
                  disabled={saving || frozen}
                  onChange={onFormatChange}
                />
                {showOptions && option.platform === 'instagram' && (
                  <InstagramPublishOptions
                    idPrefix={idPrefix}
                    format={format}
                    images={postImages}
                    isSingleImage={isSingleImageTarget(effectiveAssets)}
                    value={instagramOptions}
                    disabled={saving}
                    onChange={onInstagramOptionsChange}
                  />
                )}
                {showOptions && option.platform === 'youtube' && (
                  <YouTubePublishOptions
                    idPrefix={idPrefix}
                    images={postImages}
                    value={youtubeOptions}
                    disabled={saving}
                    onChange={onYouTubeOptionsChange}
                  />
                )}
                <TargetContentEditor
                  assets={assets}
                  postCaption={postCaption}
                  value={content}
                  disabled={saving || frozen}
                  onChange={onContentChange}
                />
              </div>
            )}
          </div>
        )
      )}
    </div>
  )
}

/**
 * What happened to one destination, plus the actions that belong to it: nothing for a plain success or
 * an in-flight state (the chip and permalink in the row header already say it), a "Mark published" form
 * for one waiting on a human, and the platform's own error verbatim for one that failed.
 */
function SettledOutcomeRow({
  target,
  open,
  onOpen,
  onCancel,
  onComplete,
}: {
  target: SelectedPublishTarget
  open: boolean
  onOpen: () => void
  onCancel: () => void
  onComplete: (permalink: string, publishedAt: string | null) => Promise<void>
}) {
  const awaiting = awaitsAHuman(target)

  return (
    <div className={cn('px-4 pb-2', awaiting && 'bg-muted/40')}>
      {awaiting && !open && (
        <>
          <p className="ml-6 text-xs text-muted-foreground">
            {isManual(target)
              ? 'Nothing is publishing this one — post it yourself, then record the link.'
              : (target.errorMessage ??
                'The platform handed this one to a person — finish it there, then record the link.')}
          </p>
          {/* What to post, not just that something must be posted: this destination may carry copy
              and media of its own, and a person told only "post it" would go looking for them. */}
          {target.effectiveCaption && (
            <p className="ml-6 mt-1 whitespace-pre-wrap rounded-md border border-border bg-surface-2 px-2 py-1.5 text-xs text-foreground">
              {target.effectiveCaption}
            </p>
          )}
          {target.effectiveAssetIds && target.effectiveAssetIds.length > 0 && (
            <p className="ml-6 mt-1 text-xs text-muted-foreground">
              {target.effectiveAssetIds.length === 1
                ? 'Post the file attached to this Post.'
                : `Post ${target.effectiveAssetIds.length} files, in the order shown on the Post.`}
            </p>
          )}
          <div className="ml-6 mt-1.5">
            <Button variant="outline" size="sm" onClick={onOpen}>
              Mark published
            </Button>
          </div>
        </>
      )}
      {target.errorMessage && !awaiting && (
        <p className={cn('ml-6 text-xs', statusHueClasses('red').text)}>{target.errorMessage}</p>
      )}
      {open && <ManualPublishForm target={target} onCancel={onCancel} onComplete={onComplete} />}
    </div>
  )
}

/**
 * Records what a human already did: the link to the post they published by hand, and when.
 *
 * The link is required and the reason is not pedantry — there is no platform to ask, so it is the only
 * record this destination ever went out, and the thing the calendar, the Asset library and any later
 * reader all read. The time defaults to now but is editable, because the common case for filling this
 * in is a few hours after the fact and a wrong timestamp on a published post is quietly misleading.
 */
function ManualPublishForm({
  target,
  onCancel,
  onComplete,
}: {
  target: SelectedPublishTarget
  onCancel: () => void
  onComplete: (permalink: string, publishedAt: string | null) => Promise<void>
}) {
  const [permalink, setPermalink] = useState('')
  const [publishedAt, setPublishedAt] = useState(() => localDateTimeValue(new Date()))
  const [saving, setSaving] = useState(false)
  const linkId = `manual-link-${target.id}`
  const timeId = `manual-time-${target.id}`

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    if (!permalink.trim() || saving) return
    setSaving(true)
    try {
      await onComplete(permalink.trim(), publishedAt ? new Date(publishedAt).toISOString() : null)
    } catch (err) {
      // Never swallow: the row stays exactly as it was and the reason is said out loud.
      toastError(apiErrorMessage(err, 'Could not record this as published'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={submit} className="ml-6 space-y-2.5 border-t border-border pt-3">
      <div className="space-y-1">
        <label htmlFor={linkId} className="block text-xs font-medium text-foreground">
          Link to the published post
        </label>
        <input
          id={linkId}
          type="url"
          required
          autoFocus
          value={permalink}
          onChange={(e) => setPermalink(e.target.value)}
          placeholder="https://…"
          className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        />
      </div>
      <div className="space-y-1">
        <span className="block text-xs font-medium text-foreground">When it went out</span>
        <DateTimePicker id={timeId} label="When it went out" value={publishedAt} onChange={setPublishedAt} />
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="ghost" size="sm" onClick={onCancel} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" size="sm" disabled={saving || !permalink.trim()}>
          {saving ? 'Recording…' : 'Record as published'}
        </Button>
      </div>
    </form>
  )
}

/**
 * `new Date()` as the value a `datetime-local` input accepts: local wall-clock, no zone, no seconds.
 * `toISOString` would be wrong here — it is UTC, and the input would show a time the user did not mean.
 */
function localDateTimeValue(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}
