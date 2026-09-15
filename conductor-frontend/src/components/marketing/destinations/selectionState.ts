// The editable half of a Post's destinations — per-target format, platform options, caption and media
// — as plain data, plus the two pure conversions around it: seeding it from what the server holds, and
// turning it back into the set-replace payload the server takes. The picker and the compose flow both
// hold a `DestinationDraft`; only the picker persists it.

import { INHERITED_CONTENT, type TargetContent } from '@/components/marketing/TargetContentEditor'
import {
  EMPTY_TIKTOK_OPTIONS,
  normalizeTikTokOptions,
  type TikTokPublishOptionValues,
} from '@/components/marketing/TikTokPublishOptions'
import {
  normalizeInstagramOptions,
  type InstagramPublishOptionValues,
} from '@/components/marketing/InstagramPublishOptions'
import {
  normalizeYouTubeOptions,
  type YouTubePublishOptionValues,
} from '@/components/marketing/YouTubePublishOptions'
import type { PostFormat } from '@/components/marketing/PostFormatSelector'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { isManual, targetKey, withTikTokDefault } from './publishState'
import type { PublishTargetOption, PublishTargetSelectionPayload, SelectedPublishTarget } from './types'

/** Everything a person can set per destination, keyed by `targetKey`. `keys` is which rows are picked. */
export interface DestinationDraft {
  keys: Set<string>
  formats: Record<string, PostFormat>
  tiktok: Record<string, TikTokPublishOptionValues>
  instagram: Record<string, InstagramPublishOptionValues>
  youtube: Record<string, YouTubePublishOptionValues>
  content: Record<string, TargetContent>
}

export const EMPTY_DRAFT: DestinationDraft = {
  keys: new Set(),
  formats: {},
  tiktok: {},
  instagram: {},
  youtube: {},
  content: {},
}

/**
 * The TikTok options carried by a set of persisted targets. `fallback` covers a backend that hasn't
 * started echoing publishOptions yet — without it, a round-trip would silently blank an edit.
 */
export function seedTikTokOptions(
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
export function seedInstagramOptions(
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
export function seedYouTubeOptions(
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
export function seedFormats(
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
export function effectiveAssetsFor(content: TargetContent | undefined, assets: MediaAsset[]): MediaAsset[] {
  const ids = content?.assetIds ?? assets.map((a) => a.id)
  const byId = new Map(assets.map((a) => [a.id, a]))
  return ids.map((id) => byId.get(id)).filter((a): a is MediaAsset => Boolean(a))
}

/**
 * The per-target caption and media carried by a set of persisted targets. Same trap as the TikTok
 * options above: a save sends the complete selection, so anything not seeded back from the server would
 * be cleared by the next unrelated edit.
 */
export function seedContent(
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

export function isInherited(content: TargetContent | undefined): boolean {
  return !content || (content.captionOverride === null && content.assetIds === null)
}

/** A whole draft read back from what the server holds, with `fallback` for fields it does not echo yet. */
export function draftFromTargets(
  targets: SelectedPublishTarget[],
  fallback: DestinationDraft = EMPTY_DRAFT
): DestinationDraft {
  return {
    keys: new Set(targets.map((t) => targetKey(t.platform, t.connectionId))),
    formats: { ...fallback.formats, ...seedFormats(targets, fallback.formats) },
    tiktok: { ...fallback.tiktok, ...seedTikTokOptions(targets, fallback.tiktok) },
    instagram: { ...fallback.instagram, ...seedInstagramOptions(targets, fallback.instagram) },
    youtube: { ...fallback.youtube, ...seedYouTubeOptions(targets, fallback.youtube) },
    content: { ...fallback.content, ...seedContent(targets, fallback.content) },
  }
}

/**
 * The set-replace payload for a draft: every picked destination the project can still publish to
 * (an orphan whose connection is gone would be refused), each with its format, the options its platform
 * takes, and any caption or media of its own.
 *
 * Inheriting is expressed by leaving a field out, not by sending null: the server reads both the same
 * way, and an omitted field keeps an uncustomised target's payload byte-identical to what a client that
 * predates per-target content would send. Options ride along only for an API (non-manual) target — on
 * the manual lane the creator sets every one of them in the platform's own composer, and sending them
 * anyway would store a bag of meaningless falses on the row that then counts as part of the bundle.
 */
export function buildSelectionPayload(
  options: PublishTargetOption[],
  draft: DestinationDraft
): PublishTargetSelectionPayload[] {
  const contentFor = (o: PublishTargetOption): Partial<PublishTargetSelectionPayload> => {
    const content = draft.content[targetKey(o.platform, o.connectionId)]
    if (!content) return {}
    return {
      ...(content.captionOverride === null ? {} : { captionOverride: content.captionOverride }),
      ...(content.assetIds === null ? {} : { assetIds: content.assetIds }),
    }
  }
  const optionsFor = (o: PublishTargetOption): Partial<PublishTargetSelectionPayload> => {
    if (isManual(o)) return {}
    const key = targetKey(o.platform, o.connectionId)
    if (o.platform === 'tiktok') {
      return { publishOptions: withTikTokDefault(o, draft.tiktok[key] ?? EMPTY_TIKTOK_OPTIONS) }
    }
    if (o.platform === 'instagram') {
      const values = draft.instagram[key]
      return values && Object.keys(values).length > 0 ? { publishOptions: values } : {}
    }
    if (o.platform === 'youtube') {
      const values = draft.youtube[key]
      return values && Object.keys(values).length > 0 ? { publishOptions: values } : {}
    }
    return {}
  }
  return options
    .filter((o) => draft.keys.has(targetKey(o.platform, o.connectionId)))
    .map((o) => ({
      platform: o.platform,
      connectionId: o.connectionId,
      format: draft.formats[targetKey(o.platform, o.connectionId)] ?? 'feed',
      ...optionsFor(o),
      ...contentFor(o),
    }))
}
