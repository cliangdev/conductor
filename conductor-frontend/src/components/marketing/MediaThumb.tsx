'use client'

import { isVideoContentType, type MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { cn } from '@/lib/utils'

/** Tile sizes, spelled out so Tailwind sees every class it has to generate. */
const SIZES = {
  sm: 'h-8 w-8 rounded',
  md: 'h-16 w-16 rounded-md',
  lg: 'h-24 w-24 rounded-md',
} as const

export type MediaThumbSize = keyof typeof SIZES

/** A small preview, or a neutral placeholder while an upload has not finished. */
export function MediaThumb({
  asset,
  size = 'sm',
  className,
}: {
  asset: MediaAsset
  size?: MediaThumbSize
  className?: string
}) {
  const base = cn('shrink-0 bg-surface-3 object-cover', SIZES[size], className)
  if (!asset.previewUrl) {
    return <span className={base} aria-hidden />
  }
  if (isVideoContentType(asset.contentType)) {
    return <video src={asset.previewUrl} className={base} aria-label={asset.label || asset.type} />
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={asset.previewUrl} alt={asset.label || asset.type} className={base} />
  )
}
