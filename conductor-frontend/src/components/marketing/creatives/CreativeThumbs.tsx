'use client'

// Poster tiles for CLIP and CAROUSEL Creatives, shared by the Creatives library and the compose
// chooser: the latest render's thumbnail if there is one, else the clip's own poster / slide 1.

import { FilmIcon, ImagesIcon, PlayIcon } from 'lucide-react'
import { formatDuration } from '@/lib/format'
import { carouselSlideCount, primaryCarouselPlacement } from '@/components/marketing/creatives/carouselSlides'
import type { Creative, CreativePhoto } from '@/components/marketing/creatives/types'

/** CLIP creatives never mount a live board — there's no layout/theme/headline to render — so their
 *  tile shows a poster instead: the latest render's frame poster if there is one, else the default
 *  clip media's own poster (set at upload time — see MediaPicker's captureVideoPoster). */
export function ClipThumb({ creative, mediaById }: { creative: Creative; mediaById: Map<string, CreativePhoto> }) {
  const defaultMediaId = creative.clipMedia?.default
  const defaultMedia = defaultMediaId ? mediaById.get(defaultMediaId) : undefined
  const posterUrl = creative.latestRenderThumbnailUrl ?? defaultMedia?.posterUrl ?? undefined
  const durationSeconds = defaultMedia?.durationSeconds ?? undefined

  return (
    <div data-testid={`creative-thumb-${creative.id}`} className="relative aspect-[4/5] w-full overflow-hidden bg-surface-3">
      {posterUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={posterUrl} alt="" className="h-full w-full object-cover" />
      ) : (
        <span className="flex h-full items-center justify-center text-muted-foreground">
          <FilmIcon className="h-6 w-6" aria-hidden />
        </span>
      )}
      <span className="pointer-events-none absolute inset-0 flex items-center justify-center">
        <PlayIcon className="h-8 w-8 fill-background text-background drop-shadow" aria-hidden />
      </span>
      {durationSeconds != null && (
        <span className="pointer-events-none absolute bottom-1.5 right-1.5 rounded bg-foreground/70 px-1.5 py-0.5 text-[11px] text-background">
          {formatDuration(durationSeconds)}
        </span>
      )}
    </div>
  )
}

/** CAROUSEL tiles never mount a board: the latest render's thumbnail, else slide 1 of the primary
 *  placement (resolved from IMAGE media), plus an "N slides" badge in the duration-badge style. */
export function CarouselThumb({ creative, mediaById }: { creative: Creative; mediaById: Map<string, CreativePhoto> }) {
  const key = primaryCarouselPlacement(creative.slides)
  const firstId = key ? creative.slides?.[key]?.[0] : undefined
  const url = creative.latestRenderThumbnailUrl ?? (firstId ? mediaById.get(firstId)?.url : undefined) ?? undefined
  const count = carouselSlideCount(creative.slides)

  return (
    <div data-testid={`creative-thumb-${creative.id}`} className="relative aspect-[4/5] w-full overflow-hidden bg-surface-3">
      {url ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={url} alt="" className="h-full w-full object-cover" />
      ) : (
        <span className="flex h-full items-center justify-center text-muted-foreground">
          <ImagesIcon className="h-6 w-6" aria-hidden />
        </span>
      )}
      {count > 0 && (
        <span className="pointer-events-none absolute bottom-1.5 right-1.5 rounded bg-foreground/70 px-1.5 py-0.5 text-[11px] text-background">
          {count === 1 ? '1 slide' : `${count} slides`}
        </span>
      )}
    </div>
  )
}
