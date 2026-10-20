'use client'

// Creative tiles show a poster (a clip's frame, a carousel's slide 1) that lives on a Media item. This
// resolves exactly the items the visible tiles reference, by id, instead of loading — and signing a URL
// for — the whole library. Ids already resolved are not fetched again.

import { useEffect, useMemo, useRef, useState } from 'react'
import { primaryCarouselPlacement } from '@/components/marketing/creatives/carouselSlides'
import {
  listCreativePhotosByIds,
  type Creative,
  type CreativePhoto,
} from '@/components/marketing/creatives/types'

/** The media ids a tile for each Creative needs: a thumbnail-less Clip's default clip, a thumbnail-less
 *  Carousel's first slide and, with `motion`, a Motion creative's background clip (its tile draws it live). */
export function tileMediaIds(creatives: Creative[], opts: { motion?: boolean } = {}): string[] {
  const ids = new Set<string>()
  for (const c of creatives) {
    if (c.kind === 'CLIP' && !c.latestRenderThumbnailUrl && c.clipMedia?.default) ids.add(c.clipMedia.default)
    if (c.kind === 'CAROUSEL' && !c.latestRenderThumbnailUrl) {
      const key = primaryCarouselPlacement(c.slides)
      const first = key ? c.slides?.[key]?.[0] : undefined
      if (first) ids.add(first)
    }
    if (opts.motion && c.motion?.background?.source === 'clip' && c.motion.background.clipMediaId) {
      ids.add(c.motion.background.clipMediaId)
    }
  }
  return [...ids].sort()
}

export function useMediaByIds(projectId: string, token: string | null | undefined, ids: string[]): Map<string, CreativePhoto> {
  const [byId, setById] = useState<Map<string, CreativePhoto>>(() => new Map())
  const requested = useRef(new Set<string>())
  const key = ids.join(',')
  const wanted = useMemo(() => (key ? key.split(',') : []), [key])

  useEffect(() => {
    if (!projectId || !token) return
    const missing = wanted.filter((id) => !requested.current.has(id))
    if (missing.length === 0) return
    missing.forEach((id) => requested.current.add(id))
    let cancelled = false
    let settled = false
    listCreativePhotosByIds(projectId, token, missing)
      .then((rows) => {
        settled = true
        if (cancelled) return
        setById((prev) => {
          const next = new Map(prev)
          for (const r of rows) next.set(r.id, r)
          return next
        })
      })
      .catch(() => {
        // Let a later render retry; tiles fall back to their placeholder meanwhile.
        settled = true
        missing.forEach((id) => requested.current.delete(id))
      })
    return () => {
      cancelled = true
      // A superseded request that never landed must be retried; one that landed stays resolved.
      if (!settled) missing.forEach((id) => requested.current.delete(id))
    }
  }, [projectId, token, wanted])

  return byId
}
