// CAROUSEL ("Finished images") helpers shared by the library grid and the editor: placement keys,
// the "primary placement" rule, and the server's aspect rule (slideAspect, ±2%).

export const CAROUSEL_PLACEMENT_KEYS = ['4x5', '9x16', '1x1', 'default'] as const
export type CarouselPlacementKey = (typeof CAROUSEL_PLACEMENT_KEYS)[number]

export const CAROUSEL_PLACEMENT_LABEL: Record<string, string> = {
  '4x5': '4:5',
  '9x16': '9:16',
  '1x1': '1:1',
  default: 'Default',
}

/** Target aspect (width / height) per placement; `default` accepts any aspect. */
const ASPECT: Record<string, number> = { '4x5': 1080 / 1350, '9x16': 1080 / 1920, '1x1': 1 }

/** 4x5 if it has slides, else the first non-default key with slides, else default. */
export function primaryCarouselPlacement(slides?: Record<string, string[]> | null): string | undefined {
  if (!slides) return undefined
  const withSlides = Object.keys(slides).filter((k) => (slides[k]?.length ?? 0) > 0)
  if (withSlides.includes('4x5')) return '4x5'
  return withSlides.find((k) => k !== 'default') ?? withSlides[0]
}

export function carouselSlideCount(slides?: Record<string, string[]> | null): number {
  const key = primaryCarouselPlacement(slides)
  return key && slides ? slides[key].length : 0
}

/** True when the image's aspect is off the placement's target by more than 2% (mirrors the server). */
export function aspectMismatch(placement: string, width?: number | null, height?: number | null): boolean {
  const target = ASPECT[placement]
  if (!target || !width || !height) return false
  return Math.abs(width / height - target) / target > 0.02
}
