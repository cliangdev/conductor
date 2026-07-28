interface FitScaleOptions {
  minScale?: number
  maxScale?: number
  padding?: number
}

/** Contain-fit scale that fills as much of the container as possible without cropping. */
export function computeFitScale(container: HTMLElement, svg: SVGSVGElement, opts?: FitScaleOptions): number {
  const minScale = opts?.minScale ?? 0.2
  const maxScale = opts?.maxScale ?? 8
  const padding = opts?.padding ?? 0

  const containerWidth = container.clientWidth - padding * 2
  const containerHeight = container.clientHeight - padding * 2
  const svgRect = svg.getBoundingClientRect()
  if (!containerWidth || !containerHeight || !svgRect.width || !svgRect.height) return 1

  const scaleX = containerWidth / svgRect.width
  const scaleY = containerHeight / svgRect.height
  return Math.min(maxScale, Math.max(minScale, Math.min(scaleX, scaleY)))
}
