// Pure helpers behind the Post's media section: how files group by destination and by origin, and the
// human words for a destination's shape. No React, no fetches.

import { isVideoContentType, type MediaAsset } from '@/components/workitems/MediaUploadPanel'
import type { DestinationRowModel } from '@/components/marketing/destinations/model'
import type { PublishPlatform } from '@/components/marketing/destinations/types'

export const PLATFORM_NAME: Record<PublishPlatform, string> = {
  facebook: 'Facebook',
  instagram: 'Instagram',
  youtube: 'YouTube',
  tiktok: 'TikTok',
}

const KNOWN_RATIOS: { w: number; h: number }[] = [
  { w: 9, h: 16 },
  { w: 4, h: 5 },
  { w: 1, h: 1 },
  { w: 16, h: 9 },
  { w: 3, h: 4 },
  { w: 2, h: 3 },
  { w: 3, h: 2 },
  { w: 4, h: 3 },
]

/** "9:16" for a familiar shape, a reduced ratio otherwise, null when the size is not known. */
export function aspectLabel(width?: number | null, height?: number | null): string | null {
  if (!width || !height) return null
  const r = width / height
  const known = KNOWN_RATIOS.find((k) => Math.abs(k.w / k.h - r) < 0.02)
  if (known) return `${known.w}:${known.h}`
  return `${r.toFixed(2)}:1`
}

/** Width / height for CSS, with a square fallback while the size is unknown. */
export function aspectOf(asset: MediaAsset): number {
  return asset.width && asset.height ? asset.width / asset.height : 1
}

/** "4:5", "1:1", "9:16", "16:9" or "Story" for a placement key however it was written ("4×5", "4x5", "4:5"). */
export function placementLabel(key: string | null | undefined): string {
  if (!key) return ''
  const m = key.match(/(\d+)\s*[x×:]\s*(\d+)/i)
  if (m) return `${m[1]}:${m[2]}`
  if (/story/i.test(key)) return 'Story'
  return key
}

/**
 * A file's name for people. A Creative frame is "Slide n" (prefixed with its ratio when `withRatio`, for places
 * where the size is not already the context); anything else keeps its own label. The data is never changed.
 */
export function assetDisplayLabel(
  asset: Pick<MediaAsset, 'label' | 'type' | 'creativeFrameId' | 'sequenceIndex' | 'placementKey'>,
  opts: { withRatio?: boolean } = {},
): string {
  if (asset.creativeFrameId) {
    const ratio = placementLabel(asset.placementKey)
    if (asset.sequenceIndex != null) {
      const slide = `slide ${asset.sequenceIndex + 1}`
      if (opts.withRatio && ratio) return `${ratio} · ${slide}`
      return `Slide ${asset.sequenceIndex + 1}`
    }
    if (ratio) return ratio
  }
  return asset.label || asset.type
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`
}

/** What a destination publishes, in words: Reel, Story, Photo carousel, Photo, Video. */
export function formatLabel(row: DestinationRowModel): string {
  const media = row.effectiveAssets
  const videos = media.filter((a) => isVideoContentType(a.contentType)).length
  switch (row.format) {
    case 'reel':
      return 'Reel'
    case 'story':
      return media.length > 1 ? 'Story sequence' : 'Story'
    default:
      if (row.option.platform === 'youtube') return 'Video'
      if (media.length > 1 && videos === 0) return 'Photo carousel'
      if (videos > 0 && media.length === videos) return media.length > 1 ? 'Videos' : 'Video'
      return media.length === 0 ? 'Post' : 'Photo'
  }
}

export function destinationTitle(row: DestinationRowModel): string {
  return row.manual ? row.option.label : `${PLATFORM_NAME[row.option.platform]} · ${row.option.label}`
}

/** The destinations a Post actually goes to: ticked, and not a hidden by-hand twin. */
export function activeDestinations(rows: DestinationRowModel[]): DestinationRowModel[] {
  return rows.filter((r) => r.checked && !r.hidden)
}

/** Asset id -> the destinations (with the file's 1-based slide position) that publish it. */
export function usageByAsset(rows: DestinationRowModel[]): Map<string, { row: DestinationRowModel; position: number }[]> {
  const out = new Map<string, { row: DestinationRowModel; position: number }[]>()
  for (const row of rows) {
    row.effectiveAssets.forEach((a, i) => {
      const list = out.get(a.id) ?? []
      list.push({ row, position: i + 1 })
      out.set(a.id, list)
    })
  }
  return out
}

export interface PlacementRow {
  key: string
  placementKey: string
  assets: MediaAsset[]
}

export interface OriginGroup {
  key: string
  kind: 'creative' | 'library' | 'upload'
  title: string
  /** "Carousel" when the Creative's frames are slides. */
  detail: string | null
  creativeId: string | null
  creativeDisplayId: string | null
  /** Creatives split by placement; the other kinds have one unnamed row. */
  rows: PlacementRow[]
  /** Every file in walking order. */
  assets: MediaAsset[]
}

function bySequence(a: MediaAsset, b: MediaAsset): number {
  return (a.sequenceIndex ?? -1) - (b.sequenceIndex ?? -1)
}

/** Files grouped by where they came from: one group per Creative, then the library, then uploads. */
export function groupByOrigin(assets: MediaAsset[]): OriginGroup[] {
  const creatives = new Map<string, MediaAsset[]>()
  const library: MediaAsset[] = []
  const uploaded: MediaAsset[] = []
  for (const a of assets) {
    if (a.creativeFrameId) {
      const key = a.creativeId ?? a.creativeFrameId
      creatives.set(key, [...(creatives.get(key) ?? []), a])
    } else if (a.mediaId) library.push(a)
    else uploaded.push(a)
  }

  const groups: OriginGroup[] = []
  for (const [key, files] of creatives) {
    const byPlacement = new Map<string, MediaAsset[]>()
    for (const f of files) {
      const p = f.placementKey ?? 'frame'
      byPlacement.set(p, [...(byPlacement.get(p) ?? []), f])
    }
    const rows: PlacementRow[] = [...byPlacement.entries()]
      .map(([placementKey, list]) => ({
        key: `${key}:${placementKey}`,
        placementKey,
        assets: [...list].sort(bySequence),
      }))
      .sort((a, b) => a.placementKey.localeCompare(b.placementKey))
    const displayId = files.find((f) => f.creativeDisplayId)?.creativeDisplayId ?? null
    groups.push({
      key: `creative:${key}`,
      kind: 'creative',
      title: displayId ? `Creative ${displayId}` : 'Creative',
      detail: files.some((f) => f.sequenceIndex != null) ? 'Carousel' : null,
      creativeId: files.find((f) => f.creativeId)?.creativeId ?? null,
      creativeDisplayId: displayId,
      rows,
      assets: rows.flatMap((r) => r.assets),
    })
  }
  if (library.length > 0) {
    groups.push({
      key: 'library',
      kind: 'library',
      title: 'From library',
      detail: null,
      creativeId: null,
      creativeDisplayId: null,
      rows: [{ key: 'library', placementKey: '', assets: library }],
      assets: library,
    })
  }
  if (uploaded.length > 0) {
    groups.push({
      key: 'upload',
      kind: 'upload',
      title: 'Uploaded',
      detail: null,
      creativeId: null,
      creativeDisplayId: null,
      rows: [{ key: 'upload', placementKey: '', assets: uploaded }],
      assets: uploaded,
    })
  }
  return groups
}
