// Pure helpers for the Media library page: natural label ordering, the "set" prefix of a numbered
// slide label, and the carousel plan (selected images -> placement groups) mirroring the server's
// slideCount / slideAspect rules so a refusal is explained before any request is made.

import { nearestAspectPlacement } from '@/components/marketing/creatives/clipPlacement'
import { aspectMismatch, CAROUSEL_PLACEMENT_LABEL } from '@/components/marketing/creatives/carouselSlides'
import type { CreativePhoto, CreativeRegistryPlacement } from '@/components/marketing/creatives/types'

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })

/** Natural order: "slide 2" < "slide 10". Falls back to id so the order is total and stable. */
export function compareLabels(a: CreativePhoto, b: CreativePhoto): number {
  return collator.compare(a.label ?? '', b.label ?? '') || a.id.localeCompare(b.id)
}

export function sortNatural(items: CreativePhoto[]): CreativePhoto[] {
  return [...items].sort(compareLabels)
}

const ASPECT_SUFFIX = /[\s_-]*(4x5|9x16|1x1|4:5|9:16|1:1)$/i

/** "01-import 4x5 slide 01" -> "01-import": the label before " slide", minus a trailing aspect token. */
export function setPrefix(label: string | null | undefined): string {
  const base = (label ?? '').replace(/[\s_-]*slide\b.*$/i, '').trim()
  return base.replace(ASPECT_SUFFIX, '').trim()
}

/** True when `label` belongs to the set named `prefix` (same prefix, then a non-alphanumeric boundary). */
export function inSet(label: string | null | undefined, prefix: string): boolean {
  if (!prefix) return false
  const l = (label ?? '').toLowerCase()
  const p = prefix.toLowerCase()
  return l.startsWith(p) && !/[a-z0-9]/.test(l.charAt(p.length) || ' ')
}

export const CAROUSEL_PLACEMENTS = ['4x5', '9x16', '1x1'] as const
const REGISTRY: CreativeRegistryPlacement[] = [
  { key: '4x5', label: '4:5', width: 1080, height: 1350, default: true },
  { key: '9x16', label: '9:16', width: 1080, height: 1920, default: false },
  { key: '1x1', label: '1:1', width: 1080, height: 1080, default: false },
]

export function maxSlides(placement: string): number {
  return placement === '9x16' ? 35 : 10
}

export interface CarouselGroup {
  placement: string
  items: CreativePhoto[]
}

export interface CarouselPlan {
  groups: CarouselGroup[]
  /** Reasons the plan can't be created; empty when it can. */
  problems: string[]
  /** Suggested Creative name from the common label prefix. */
  name: string
}

function commonPrefix(strings: string[]): string {
  if (strings.length === 0) return ''
  let prefix = strings[0]
  for (const s of strings) {
    while (!s.toLowerCase().startsWith(prefix.toLowerCase())) prefix = prefix.slice(0, -1)
  }
  return prefix
}

export function suggestCarouselName(items: CreativePhoto[]): string {
  const bases = sortNatural(items).map((i) => setPrefix(i.label))
  const common = commonPrefix(bases).replace(/[\s_-]+$/, '').replace(ASPECT_SUFFIX, '').trim()
  return common || 'Carousel'
}

export function buildCarouselPlan(items: CreativePhoto[]): CarouselPlan {
  const problems: string[] = []
  const nonImages = items.filter((i) => (i.mediaKind ?? 'IMAGE') !== 'IMAGE')
  if (nonImages.length > 0) problems.push('Only images can go in a carousel — deselect the video and audio items.')

  const label = (list: CreativePhoto[]) => sortNatural(list).map((i) => i.label || 'Untitled').join(', ')
  const images = items.filter((i) => (i.mediaKind ?? 'IMAGE') === 'IMAGE')
  const blocked = images.filter((i) => i.blocked)
  if (blocked.length > 0) problems.push(`Blocked: ${label(blocked)}. Unblock or deselect them.`)
  const pending = images.filter((i) => i.uploadStatus === 'PENDING')
  if (pending.length > 0) problems.push(`Still uploading: ${label(pending)}.`)

  const buckets = new Map<string, CreativePhoto[]>()
  const offAspect: CreativePhoto[] = []
  for (const item of images) {
    const key = nearestAspectPlacement(REGISTRY, item.width, item.height)
    if (!key || aspectMismatch(key, item.width, item.height)) {
      offAspect.push(item)
      continue
    }
    buckets.set(key, [...(buckets.get(key) ?? []), item])
  }
  if (offAspect.length > 0) {
    const names = sortNatural(offAspect).map((i) => `${i.label || 'Untitled'} (${i.width ?? '?'}×${i.height ?? '?'})`)
    problems.push(`Not within 2% of 4:5, 9:16 or 1:1: ${names.join(', ')}.`)
  }

  const groups: CarouselGroup[] = CAROUSEL_PLACEMENTS.filter((p) => buckets.has(p)).map((p) => ({
    placement: p,
    items: sortNatural(buckets.get(p)!),
  }))
  for (const g of groups) {
    if (g.items.length > maxSlides(g.placement)) {
      problems.push(
        `${CAROUSEL_PLACEMENT_LABEL[g.placement]} has ${g.items.length} slides — the limit is ${maxSlides(g.placement)}. Deselect ${g.items.length - maxSlides(g.placement)}.`,
      )
    }
  }
  if (items.length === 0) problems.push('Select at least one image.')
  return { groups, problems, name: suggestCarouselName(items) }
}
