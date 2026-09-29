// COND-24 T6 (video creatives): mirrors the backend's CLIP render-frame placement rule for the
// `clipMedia.default` entry (see video-contract.md's "Renders for CLIP") so the editor can show a
// marketer which placement a default video will actually serve, without waiting on a render.
//
// The backend rule: "the placement whose aspect ratio is closest to the video's (from registry w/h;
// among ALL registry placements, story counts as 9x16 -> use 9x16)". Kept as a small pure function so
// it's independently testable and has nothing to do with the DOM or the API client.

import type { CreativeRegistryPlacement } from './types'

/** The four placement keys a Clip creative's per-placement overrides map onto — see
 *  video-contract.md's "Web" section. Exported so the editor and its tests share one list. */
export const CLIP_PLACEMENT_KEYS = ['9x16', '4x5', '1x1', '16x9'] as const

/**
 * Picks the registry placement whose aspect ratio (width/height) is closest to the given video's own.
 * `story` shares its 9:16 frame with `9x16` and never wins outright — a video whose nearest match is
 * `story` resolves to `9x16` instead, matching the backend. Ties break toward whichever placement
 * comes first in `placements`. Returns null when there's nothing to compare (no placements, or the
 * video's dimensions haven't been measured yet).
 */
export function nearestAspectPlacement(
  placements: CreativeRegistryPlacement[],
  videoWidth: number | null | undefined,
  videoHeight: number | null | undefined,
): string | null {
  if (!videoWidth || !videoHeight || placements.length === 0) return null
  const videoRatio = videoWidth / videoHeight

  let best: CreativeRegistryPlacement | null = null
  let bestDelta = Infinity
  for (const placement of placements) {
    if (!placement.width || !placement.height) continue
    const delta = Math.abs(placement.width / placement.height - videoRatio)
    if (delta < bestDelta) {
      bestDelta = delta
      best = placement
    }
  }
  if (!best) return null
  return best.key === 'story' ? '9x16' : best.key
}
