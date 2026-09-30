// COND-24 creative-ease-of-use: whether a Brand Kit still looks like the lazily-created default —
// i.e. nobody has set it up yet. Kept as one small pure function (mirrors the backend having no
// separate "configured" concept of its own — see the `configured` flag conductor-tools' get_brand_kit
// now derives the same way) so the Creatives library and the Creative editor read exactly one
// definition of "unconfigured", rather than two ad hoc checks drifting apart.

import type { BrandKit } from './types'

/**
 * A kit counts as unconfigured when it has never been edited since its lazy creation (`updatedAt`
 * still equals `createdAt`) and has no font family and no logo uploaded in any slot. A kit with a
 * font or a logo — even one that's never been "saved" again — has clearly had some attention, so it
 * no longer nudges the marketer toward Settings → Brand.
 */
export function isBrandKitUnconfigured(kit: BrandKit | null | undefined): boolean {
  if (!kit) return false
  const neverEdited = kit.updatedAt === kit.createdAt
  if (!neverEdited) return false
  if (kit.fontFamily) return false
  if (kit.markUrl || kit.wordmarkDarkUrl || kit.wordmarkLightUrl || kit.badgeUrl) return false
  return true
}
