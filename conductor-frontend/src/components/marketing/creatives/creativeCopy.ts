// Small read-only helpers over a Creative that the chooser and the compose page both need.

import { placementLabel } from './placementMap'
import type { Creative, CreativeKind } from './types'

/** The Creative's caption, falling back to its headline and body when the caption is empty. */
export function creativeCaption(c: Pick<Creative, 'caption' | 'headline' | 'body'>): string {
  const caption = c.caption?.trim()
  if (caption) return caption
  return [c.headline, c.body]
    .map((s) => s?.trim())
    .filter((s): s is string => !!s)
    .join('\n\n')
}

/** Still | Carousel | Video — MOTION and CLIP are both just "Video" to a person choosing. */
export function kindLabel(kind: CreativeKind | undefined): string {
  switch (kind) {
    case 'CAROUSEL':
      return 'Carousel'
    case 'MOTION':
    case 'CLIP':
      return 'Video'
    default:
      return 'Still'
  }
}

export type KindFilter = 'STILL' | 'CAROUSEL' | 'VIDEO'

export function kindFilterOf(kind: CreativeKind | undefined): KindFilter {
  return kind === 'CAROUSEL' ? 'CAROUSEL' : kind === 'MOTION' || kind === 'CLIP' ? 'VIDEO' : 'STILL'
}

/** Slides in a carousel: the longest per-placement list. */
export function slideCount(c: Pick<Creative, 'slides'>): number {
  return Math.max(0, ...Object.values(c.slides ?? {}).map((s) => s.length))
}

/** "9:16, 4:5, 1:1" — the sizes the Creative is made in. */
export function sizesLabel(c: Pick<Creative, 'placements'>): string {
  return c.placements.map(placementLabel).join(', ')
}

/** "Carousel · 5 slides · 9:16, 4:5, 1:1" */
export function creativeSummary(c: Pick<Creative, 'kind' | 'slides' | 'placements'>): string {
  const parts = [kindLabel(c.kind)]
  if (c.kind === 'CAROUSEL') {
    const n = slideCount(c)
    if (n > 0) parts.push(`${n} slide${n === 1 ? '' : 's'}`)
  }
  const sizes = sizesLabel(c)
  if (sizes) parts.push(sizes)
  return parts.join(' · ')
}

/** Whether a list row has a render a Post could use. (A preview-only render is only known on detail.) */
export function hasRender(c: Pick<Creative, 'latestRenderId'>): boolean {
  return !!c.latestRenderId
}

/**
 * Whether this Creative's render happens on this machine (Claude Code / CLI) rather than instantly on
 * the server. MOTION and STILL always do; a CAROUSEL does when it has a music track (the slideshow
 * video); a CLIP does when its sound is replaced, mixed or muted. Must match the backend's rule.
 */
export function needsLocalRender(c: Pick<Creative, 'kind' | 'audio'>): boolean {
  const source = c.audio?.source ?? null
  if (c.kind === 'CAROUSEL') return source === 'track'
  if (c.kind === 'CLIP') return source === 'track' || source === 'mix' || source === 'none'
  return true
}

/**
 * Whether a Creative can be used in a Post from the chooser. A plain CLIP or CAROUSEL render is
 * assembled by the backend in one synchronous call, so a READY one is usable even before its first
 * render; STILL, MOTION and any Creative with music or a changed clip sound need a local render
 * (Claude Code / CLI) first.
 */
export function canUseInPost(c: Pick<Creative, 'latestRenderId' | 'kind' | 'state' | 'audio'>): boolean {
  return (
    hasRender(c) ||
    (c.state === 'READY' && (c.kind === 'CLIP' || c.kind === 'CAROUSEL') && !needsLocalRender(c))
  )
}

/** Whether using this Creative means the backend must assemble its render first (instant server render). */
export function needsServerRender(c: Pick<Creative, 'kind' | 'audio'>): boolean {
  return (c.kind === 'CLIP' || c.kind === 'CAROUSEL') && !needsLocalRender(c)
}
