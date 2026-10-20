'use client'

// Keyset-paged Media list for a picker: the first page loads when the query changes (a response for an
// older query is dropped), later pages load on demand or as the sentinel nears the viewport. Same
// contract as the Media page's own paging (`limit` + `cursor`, `X-Next-Cursor`).

import { useCallback, useEffect, useRef, useState } from 'react'
import { apiErrorMessage } from '@/lib/api'
import {
  listCreativePhotosPage,
  type CreativePhoto,
  type MediaPageQuery,
} from '@/components/marketing/creatives/types'

export const PICKER_PAGE_SIZE = 60

export interface PagedMedia {
  /** null until the first page lands; while a changed query reloads, the previous results stay up. */
  items: CreativePhoto[] | null
  error: string | null
  hasMore: boolean
  loadingMore: boolean
  loadMore: () => void
}

/** `paged: false` loads the whole list in one request (for client-side-only filters). */
export function usePagedMedia(
  projectId: string,
  token: string,
  query: MediaPageQuery,
  paged = true,
  /** An element after the grid: the next page loads as it nears the viewport. */
  sentinelRef?: React.RefObject<HTMLDivElement | null>,
): PagedMedia {
  const [items, setItems] = useState<CreativePhoto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const generation = useRef(0)
  const loadingMoreRef = useRef(false)
  // The query arrives as a fresh object each render; compare by content.
  const queryKey = JSON.stringify(query)

  useEffect(() => {
    const q = JSON.parse(queryKey) as MediaPageQuery
    const mine = ++generation.current
    loadingMoreRef.current = false
    setLoadingMore(false)
    setNextCursor(null)
    ;(async () => {
      try {
        const page = await listCreativePhotosPage(projectId, token, {
          ...q,
          limit: paged ? PICKER_PAGE_SIZE : undefined,
        })
        if (generation.current !== mine) return
        setItems(page.items)
        setNextCursor(page.nextCursor)
        setError(null)
      } catch (err) {
        if (generation.current !== mine) return
        setItems((prev) => prev ?? [])
        setError(apiErrorMessage(err, 'Could not load media — please try again.'))
      }
    })()
    return () => {
      generation.current++
    }
  }, [projectId, token, queryKey, paged])

  const loadMore = useCallback(async () => {
    if (!nextCursor || loadingMoreRef.current) return
    const mine = generation.current
    loadingMoreRef.current = true
    setLoadingMore(true)
    try {
      const page = await listCreativePhotosPage(projectId, token, {
        ...(JSON.parse(queryKey) as MediaPageQuery),
        limit: PICKER_PAGE_SIZE,
        cursor: nextCursor,
      })
      if (generation.current !== mine) return
      setItems((prev) => {
        const seen = new Set((prev ?? []).map((i) => i.id))
        return [...(prev ?? []), ...page.items.filter((i) => !seen.has(i.id))]
      })
      setNextCursor(page.nextCursor)
    } catch (err) {
      if (generation.current === mine) setError(apiErrorMessage(err, 'Could not load more media — please try again.'))
    } finally {
      if (generation.current === mine) {
        loadingMoreRef.current = false
        setLoadingMore(false)
      }
    }
  }, [projectId, token, queryKey, nextCursor])

  useEffect(() => {
    const el = sentinelRef?.current
    if (!nextCursor || !el || typeof IntersectionObserver === 'undefined') return
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) void loadMore()
      },
      { rootMargin: '600px 0px' },
    )
    io.observe(el)
    return () => io.disconnect()
  }, [nextCursor, loadMore, items, sentinelRef])

  return { items, error, hasMore: nextCursor != null, loadingMore, loadMore: () => void loadMore() }
}
