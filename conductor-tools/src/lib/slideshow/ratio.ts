/**
 * Classifying an image's shape as a slide ratio, with the same 2% tolerance the backend uses
 * (CreativeValidator.CAROUSEL_ASPECT_TOLERANCE). The slide-text render job does NOT use this to pick lists: the
 * backend names them (`renderLists`). It is for the local tools that must look at stored images themselves.
 */

export type ShapeRatio = '9x16' | '4x5' | '1x1'

const SHAPES: Array<[ShapeRatio, number]> = [
  ['9x16', 9 / 16],
  ['4x5', 4 / 5],
  ['1x1', 1],
]

const TOLERANCE = 0.02

/** 9x16, 4x5 or 1x1 when `width / height` is within 2% of it; undefined when it is none of them or unknown. */
export function ratioOfSize(width: number | null | undefined, height: number | null | undefined): ShapeRatio | undefined {
  if (!width || !height || width <= 0 || height <= 0) return undefined
  const shape = width / height
  return SHAPES.find(([, value]) => Math.abs(shape - value) / value <= TOLERANCE)?.[0]
}
