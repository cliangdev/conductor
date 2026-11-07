/**
 * The platform UI zones of a 9:16 video or slide, in one table.
 *
 * Instagram Reels, TikTok and YouTube Shorts draw their own interface over the picture: a header at
 * the top, the caption/username/music row at the bottom and the like/comment/share column on the
 * right. Anything the creative puts there is hidden or crowded. The numbers are for 1080x1920 and are
 * scaled to the real frame size.
 */

export interface PixelBox {
  x: number
  y: number
  w: number
  h: number
}

export type ZoneName = 'top' | 'bottom' | 'right'

export interface SafeZone {
  name: ZoneName
  /** The zone as a rectangle in the real frame's pixels. */
  rect: PixelBox
  /** The platform UI that covers the zone. */
  platforms: string[]
  /** The y (top, bottom) or x (right) edge in 1080x1920 terms, for messages. */
  edge1080: number
}

export const REF_WIDTH = 1080
export const REF_HEIGHT = 1920
export const TOP_EDGE = 290
export const BOTTOM_EDGE = 1490
export const RIGHT_EDGE = 918
export const RIGHT_Y_MIN = 576
export const RIGHT_Y_MAX = 1632

/** A bottom hit needs this share of the box's height inside the zone, so text that just grazes the
 * line (an ascender or descender) is not flagged. Top and right count any overlap. */
export const BOTTOM_MIN_OVERLAP = 0.3

const ASPECT_TOLERANCE = 0.02

/** True when the frame is about 9:16 (portrait), within 2%. */
export function isNineBySixteen(width: number, height: number): boolean {
  if (!(width > 0) || !(height > 0)) return false
  return Math.abs(width / height / (9 / 16) - 1) <= ASPECT_TOLERANCE
}

/** The three zones scaled to the frame, or [] when the frame is not 9:16. */
export function zonesFor(width: number, height: number): SafeZone[] {
  if (!isNineBySixteen(width, height)) return []
  const sx = width / REF_WIDTH
  const sy = height / REF_HEIGHT
  const r = (v: number) => Math.round(v)
  return [
    {
      name: 'top',
      rect: { x: 0, y: 0, w: width, h: r(TOP_EDGE * sy) },
      platforms: ['Instagram Reels header', 'TikTok top tabs and search'],
      edge1080: TOP_EDGE,
    },
    {
      name: 'bottom',
      rect: { x: 0, y: r(BOTTOM_EDGE * sy), w: width, h: height - r(BOTTOM_EDGE * sy) },
      platforms: ['Instagram caption, username and music row', 'TikTok caption, username and music row', 'YouTube Shorts caption and channel row'],
      edge1080: BOTTOM_EDGE,
    },
    {
      name: 'right',
      rect: { x: r(RIGHT_EDGE * sx), y: r(RIGHT_Y_MIN * sy), w: width - r(RIGHT_EDGE * sx), h: r((RIGHT_Y_MAX - RIGHT_Y_MIN) * sy) },
      platforms: ['Instagram, TikTok and Shorts like/comment/share column'],
      edge1080: RIGHT_EDGE,
    },
  ]
}

function overlap(a: PixelBox, b: PixelBox): { w: number; h: number } {
  const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)
  const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)
  return { w: Math.max(0, w), h: Math.max(0, h) }
}

/** True when the box falls in the zone (any overlap, or at least 30% of its height for the bottom). */
export function boxInZone(box: PixelBox, zone: SafeZone): boolean {
  const o = overlap(box, zone.rect)
  if (o.w <= 0 || o.h <= 0) return false
  if (zone.name === 'bottom') return box.h > 0 && o.h / box.h >= BOTTOM_MIN_OVERLAP
  return true
}

/** The zones the box falls in. */
export function hitZones(box: PixelBox, zones: SafeZone[]): SafeZone[] {
  return zones.filter((z) => boxInZone(box, z))
}

/** Pushes a box outward from the frame centre by `scale` (a zoom-in crops edges and moves content
 * away from the middle, so text near an edge ends up nearer to, or past, the UI zone). */
export function projectBox(box: PixelBox, width: number, height: number, scale: number): PixelBox {
  const cx = width / 2
  const cy = height / 2
  return {
    x: cx + (box.x - cx) * scale,
    y: cy + (box.y - cy) * scale,
    w: box.w * scale,
    h: box.h * scale,
  }
}
