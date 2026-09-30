// COND-24 creative-ease-of-use: whether a Brand Kit still looks unconfigured — i.e. nobody has set
// it up yet. The server computes this (`BrandKitResponse.configured`) the same way conductor-tools'
// get_brand_kit does, so the Creatives library and the Creative editor both just read that one
// field rather than re-deriving "unconfigured" from raw kit fields on the frontend too.

import type { BrandKit } from './types'

/**
 * A kit counts as unconfigured when the server says `configured === false`. A missing field (an
 * older backend that hasn't shipped this yet) is treated as configured, so we never show a false
 * nudge just because the field wasn't in the response.
 */
export function isBrandKitUnconfigured(kit: BrandKit | null | undefined): boolean {
  return kit?.configured === false
}
