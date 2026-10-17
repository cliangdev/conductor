// Which of a Creative's rendered sizes a destination gets — a line-for-line mirror of the backend's
// CreativePlacementTargetMapper (matches / matchesVideo / carouselPlacementFor) and of
// CreativePlacementMedia.Placements#mediaFor, so the chooser and compose page promise exactly what
// "attach" will do. When the backend rules change, change this file and placementMap.test.ts with them.

import type { CreativeKind, CreativeRenderFrame } from './types'

export interface DestinationLike {
  platform: string
  /** feed | reel | story; absent or unknown reads as feed, like the server. */
  format?: string | null
}

/** The frames of one placement, in render order. */
export interface PlacementGroup {
  placementKey: string
  frames: CreativeRenderFrame[]
  video: boolean
}

export type PlacementResult = { placementKey: string; frames: CreativeRenderFrame[] } | { reason: string }

const SHEET = 'sheet'

function isVideoFrame(f: CreativeRenderFrame): boolean {
  return (f.contentType ?? '').startsWith('video/')
}

/**
 * A render's frames grouped by placement, in the server's order (sequence index first, nulls first, then
 * placement key); the contact `sheet` is never attached so it is dropped here too.
 */
export function groupFrames(frames: CreativeRenderFrame[]): PlacementGroup[] {
  const ordered = frames
    .filter((f) => f.placementKey !== SHEET)
    .map((f, i) => ({ f, i }))
    .sort((a, b) => {
      const sa = a.f.sequenceIndex ?? -1
      const sb = b.f.sequenceIndex ?? -1
      if (sa !== sb) return sa - sb
      if (a.f.placementKey !== b.f.placementKey) return a.f.placementKey < b.f.placementKey ? -1 : 1
      return a.i - b.i
    })
    .map(({ f }) => f)
  const groups = new Map<string, PlacementGroup>()
  for (const f of ordered) {
    let g = groups.get(f.placementKey)
    if (!g) {
      g = { placementKey: f.placementKey, frames: [], video: false }
      groups.set(f.placementKey, g)
    }
    g.frames.push(f)
    if (isVideoFrame(f)) g.video = true
  }
  return [...groups.values()]
}

const platformOf = (d: DestinationLike) => d.platform.trim().toLowerCase()
const formatOf = (d: DestinationLike): 'feed' | 'reel' | 'story' => {
  const f = (d.format ?? '').trim().toLowerCase()
  return f === 'reel' || f === 'story' ? f : 'feed'
}

/** CreativePlacementTargetMapper.matches — a still frame at `key`. */
export function matches(key: string, d: DestinationLike): boolean {
  const p = platformOf(d)
  const f = formatOf(d)
  switch (key) {
    case '9x16':
      return p === 'tiktok' || (p === 'instagram' && f === 'reel')
    case '4x5':
      return p === 'instagram' && f === 'feed'
    case '1x1':
      return p === 'facebook' && f === 'feed'
    case 'story':
      return f === 'story'
    default:
      return false
  }
}

/** CreativePlacementTargetMapper.matchesVideo — a video frame at `key`, given every placement present. */
export function matchesVideo(key: string, d: DestinationLike, present: Set<string>): boolean {
  const p = platformOf(d)
  const f = formatOf(d)
  switch (key) {
    case '9x16':
      return (
        p === 'tiktok' ||
        (p === 'instagram' && f === 'reel') ||
        (p === 'facebook' && f === 'reel') ||
        (p === 'facebook' && f === 'feed' && !present.has('1x1')) ||
        // Instagram publishes a single video as a Reel, so its feed takes the vertical cut without a 4:5.
        (p === 'instagram' && f === 'feed' && !present.has('4x5')) ||
        (p === 'youtube' && !present.has('16x9')) ||
        (f === 'story' && !present.has('story'))
      )
    case '4x5':
      return p === 'instagram' && f === 'feed'
    case '1x1':
      return p === 'facebook' && f === 'feed'
    case '16x9':
      return p === 'youtube' || (p === 'facebook' && f === 'feed' && !present.has('1x1') && !present.has('9x16'))
    case 'story':
      return f === 'story'
    default:
      return false
  }
}

/** The ordered placements a carousel destination prefers; null when it takes no multi-image post. */
function carouselPreference(d: DestinationLike): string[] | null {
  const p = platformOf(d)
  const f = formatOf(d)
  if (p === 'instagram' && f === 'feed') return ['4x5', '1x1']
  if (p === 'facebook' && f === 'feed') return ['1x1', '4x5']
  if (p === 'tiktok') return ['9x16', '4x5']
  return null
}

/** CreativePlacementTargetMapper.carouselPlacementFor. */
export function carouselPlacementFor(d: DestinationLike, withSlides: Set<string>): string | null {
  return carouselPreference(d)?.find((k) => withSlides.has(k)) ?? null
}

/** "9x16" → "9:16"; "story" → "Story". */
export function placementLabel(key: string): string {
  if (key === 'story') return 'Story'
  return key.replace('x', ':')
}

const PLATFORM_NAMES: Record<string, string> = {
  tiktok: 'TikTok',
  instagram: 'Instagram',
  facebook: 'Facebook',
  youtube: 'YouTube',
}

function kindWord(kind: CreativeKind): string {
  if (kind === 'CAROUSEL') return 'a carousel'
  if (kind === 'STILL') return 'a still image'
  return 'a video'
}

function reasonFor(kind: CreativeKind, d: DestinationLike, groups: PlacementGroup[]): string {
  const p = platformOf(d)
  const f = formatOf(d)
  const name = PLATFORM_NAMES[p] ?? d.platform
  const isVideo = kind === 'MOTION' || kind === 'CLIP'
  if (p === 'youtube') {
    return isVideo
      ? 'YouTube needs a 16:9 or 9:16 video size, and this Creative has neither.'
      : `YouTube takes video only — this Creative is ${kindWord(kind)}.`
  }
  if (kind === 'CAROUSEL' && (f === 'reel' || f === 'story')) {
    return `${name} ${f === 'reel' ? 'Reels take one video' : 'Stories take one item'} — this Creative is a carousel.`
  }
  const wanted =
    kind === 'CAROUSEL'
      ? carouselPreference(d)
      : f === 'story'
        ? ['story']
        : p === 'tiktok' || (p === 'instagram' && f === 'reel')
          ? ['9x16']
          : p === 'instagram'
            ? ['4x5']
            : p === 'facebook' && f === 'feed'
              ? ['1x1']
              : null
  if (!wanted) {
    return `${name} ${f === 'reel' ? 'Reels' : 'posts'} take a video, and this Creative is ${kindWord(kind)}.`
  }
  const has = groups.map((g) => placementLabel(g.placementKey)).join(', ')
  return `No ${wanted.map(placementLabel).join(' or ')} size${has ? ` (has ${has})` : ''}; add one in the Creative.`
}

/**
 * The size and frames `destination` gets from a Creative, or why it gets none. Carousels go to the one
 * placement the destination prefers; everything else to the first placement, in rendered order, that
 * matches — so a destination that could take two video sizes gets exactly one.
 */
export function placementFor(kind: CreativeKind, groups: PlacementGroup[], destination: DestinationLike): PlacementResult {
  if (groups.length === 0) return { reason: 'This Creative has no render yet.' }
  const present = new Set(groups.map((g) => g.placementKey))
  if (kind === 'CAROUSEL') {
    const key = carouselPlacementFor(destination, present)
    const group = groups.find((g) => g.placementKey === key)
    return group ? { placementKey: group.placementKey, frames: group.frames } : { reason: reasonFor(kind, destination, groups) }
  }
  for (const g of groups) {
    const hit = g.video ? matchesVideo(g.placementKey, destination, present) : matches(g.placementKey, destination)
    if (hit) return { placementKey: g.placementKey, frames: g.frames }
  }
  return { reason: reasonFor(kind, destination, groups) }
}

/** Short chip text for a result: "4:5 ×5" for several frames, "9:16" for one. */
export function placementChip(result: PlacementResult): string {
  if ('reason' in result) return ''
  const n = result.frames.length
  return n > 1 ? `${placementLabel(result.placementKey)} ×${n}` : placementLabel(result.placementKey)
}

/** The chip shown on a destination that gets nothing: a few words; the full reason goes in its title. */
export function shortReason(kind: CreativeKind, destination: DestinationLike): string {
  if (platformOf(destination) === 'youtube') return 'Video only'
  if (kind === 'CAROUSEL' && formatOf(destination) !== 'feed') return 'No carousel'
  return 'No matching size'
}
