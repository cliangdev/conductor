'use client'

// The Media library page body: a browsable grid of the project's creative media (images, video,
// audio) with kind tabs, label search, filters, upload, a detail panel, multi-select and
// "Make carousel" from the selected images.
//
// The list is keyset-paged: the first MEDIA_PAGE_SIZE items (newest first) load and render, the rest load
// as the sentinel below the grid nears the viewport (or via "Load more"). Kind, search, Unchecked, AI and
// Blocked are server-side filters, so a page is always a full page of matches. Sorting by Name and the
// "On posts" / "Unused" chips need the whole library (name order and usage counts are not keyset
// columns), so those load everything unpaged, exactly like the page used to.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { markPageReady } from '@/lib/perf'
import { FilmIcon, UploadCloudIcon, XIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/contexts/AuthContext'
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { cn } from '@/lib/utils'
import { MediaTile } from '@/components/marketing/creatives/MediaTile'
import {
  ALLOWED_CONTENT_TYPES,
  mediaKindForFile,
  stripExtension,
  uploadMediaFile,
} from '@/components/marketing/creatives/mediaUpload'
import {
  listCreativePhotosPage,
  patchCreativePhoto,
  type CreativePhoto,
  type MediaPageQuery,
} from '@/components/marketing/creatives/types'
import { MakeCarouselDialog } from './MakeCarouselDialog'
import { MediaDetailSheet } from './MediaDetailSheet'
import { inSet, setPrefix } from './mediaSets'
import { MediaFilterBar } from './MediaFilterBar'
import { useMediaFilters } from './useMediaFilters'

/** Items per page; the backend caps `limit` at 200. */
export const MEDIA_PAGE_SIZE = 60

const ACCEPT = Object.values(ALLOWED_CONTENT_TYPES).flat().join(',')

export function MediaLibrary({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const canManage = useCan('creative.manage')
  const token = accessToken ?? ''

  const [items, setItems] = useState<CreativePhoto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<string[]>([])
  // The id (not index) of the last plain-clicked tile: indexes shift when a filter or search changes.
  const anchor = useRef<string | null>(null)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [carouselOpen, setCarouselOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [uploadStatus, setUploadStatus] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [reloadKey, setReloadKey] = useState(0)
  const [nextCursor, setNextCursor] = useState<string | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const filters = useMediaFilters(items, { sort: 'newest', serverOrder: true })
  const showBlocked = filters.chips.blocked
  const sentinel = useRef<HTMLDivElement>(null)
  // Bumped whenever the first page is (re)requested: a slower response for an older filter is dropped.
  const generation = useRef(0)
  const loadingMoreRef = useRef(false)

  // Debounced so typing in the search box doesn't fire a request per keystroke.
  const [debouncedQuery, setDebouncedQuery] = useState('')
  useEffect(() => {
    const t = setTimeout(() => setDebouncedQuery(filters.query.trim()), 250)
    return () => clearTimeout(t)
  }, [filters.query])

  const { kind, chips, sort } = filters
  const paged = sort === 'newest' && !chips.onPosts && !chips.unused
  const baseQuery = useMemo<MediaPageQuery>(
    () => ({
      includeBlocked: showBlocked,
      mediaKind: kind === 'ALL' ? undefined : kind,
      q: debouncedQuery || undefined,
      unchecked: chips.unchecked,
      aiGenerated: chips.ai,
    }),
    [showBlocked, kind, debouncedQuery, chips.unchecked, chips.ai],
  )

  useEffect(() => { if (items !== null) markPageReady('media-library') }, [items])

  useEffect(() => {
    if (!projectId || !accessToken) return
    const mine = ++generation.current
    loadingMoreRef.current = false
    setLoadingMore(false)
    setNextCursor(null)
    ;(async () => {
      try {
        const page = await listCreativePhotosPage(projectId, accessToken, {
          ...baseQuery,
          limit: paged ? MEDIA_PAGE_SIZE : undefined,
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
      // A superseded request must not land.
      generation.current++
    }
  }, [projectId, accessToken, baseQuery, paged, reloadKey])

  /** Fetches the page after `cursor` and returns it, or null when the filters changed meanwhile. */
  const fetchAfter = useCallback(
    async (cursor: string) => {
      const mine = generation.current
      const page = await listCreativePhotosPage(projectId, token, { ...baseQuery, limit: MEDIA_PAGE_SIZE, cursor })
      return generation.current === mine ? page : null
    },
    [projectId, token, baseQuery],
  )

  const append = (prev: CreativePhoto[] | null, more: CreativePhoto[]) => {
    const seen = new Set((prev ?? []).map((i) => i.id))
    return [...(prev ?? []), ...more.filter((i) => !seen.has(i.id))]
  }

  const loadMore = useCallback(async () => {
    if (!nextCursor || loadingMoreRef.current) return
    loadingMoreRef.current = true
    setLoadingMore(true)
    try {
      const page = await fetchAfter(nextCursor)
      if (!page) return
      setItems((prev) => append(prev, page.items))
      setNextCursor(page.nextCursor)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not load more media — please try again.'))
    } finally {
      loadingMoreRef.current = false
      setLoadingMore(false)
    }
  }, [nextCursor, fetchAfter])

  // Load the next page as the sentinel nears the viewport; each page re-arms the observer, so a tall
  // viewport keeps filling until it is full or the list ends.
  useEffect(() => {
    const el = sentinel.current
    if (!nextCursor || !el || typeof IntersectionObserver === 'undefined') return
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) void loadMore()
      },
      { rootMargin: '800px 0px' },
    )
    io.observe(el)
    return () => io.disconnect()
  }, [nextCursor, loadMore, items])

  /** Every remaining page (for "Select set", which needs the whole library). Returns the full list. */
  async function loadRemaining(): Promise<CreativePhoto[]> {
    let all = items ?? []
    let cursor = nextCursor
    while (cursor) {
      const page = await fetchAfter(cursor)
      if (!page) break
      all = append(all, page.items)
      cursor = page.nextCursor
    }
    setItems(all)
    setNextCursor(null)
    return all
  }

  const { visible, filtering } = filters

  // Bulk actions and Make carousel act on what's selected *and shown*: a selection hidden by a search,
  // filter or tab is kept (clearing the filter brings it back) but never acted on unseen.
  const selectedItems = useMemo(() => visible.filter((i) => selected.includes(i.id)), [visible, selected])
  const hiddenSelected = selected.length - selectedItems.length
  const detail = (items ?? []).find((i) => i.id === detailId) ?? null
  const nonImageSelected = selectedItems.some((i) => (i.mediaKind ?? 'IMAGE') !== 'IMAGE')

  const replaceItem = useCallback((next: CreativePhoto) => {
    setItems((prev) => (prev ?? []).map((i) => (i.id === next.id ? next : i)))
  }, [])

  function select(item: CreativePhoto, index: number, range: boolean) {
    const from = anchor.current == null ? -1 : visible.findIndex((i) => i.id === anchor.current)
    if (range && from >= 0) {
      const [a, b] = [Math.min(from, index), Math.max(from, index)]
      const ids = visible.slice(a, b + 1).map((i) => i.id)
      setSelected((prev) => [...prev, ...ids.filter((id) => !prev.includes(id))])
    } else {
      setSelected((prev) => (prev.includes(item.id) ? prev.filter((id) => id !== item.id) : [...prev, item.id]))
      anchor.current = item.id
    }
  }

  async function markChecked() {
    const todo = selectedItems.filter((i) => !i.checked)
    setBusy(true)
    setNotice(null)
    let failed = 0
    let lastError: string | null = null
    for (const item of todo) {
      try {
        replaceItem(await patchCreativePhoto(projectId, item.id, { checked: true }, token))
      } catch (err) {
        failed++
        lastError = apiErrorMessage(err, 'Could not update')
      }
    }
    setBusy(false)
    if (failed > 0) setError(`${failed} of ${todo.length} could not be marked checked: ${lastError}`)
    else setNotice(`Marked ${todo.length} checked.`)
  }

  async function selectSet() {
    let pool = items ?? []
    if (nextCursor) {
      setBusy(true)
      try {
        pool = await loadRemaining()
      } catch (err) {
        setError(apiErrorMessage(err, 'Could not load the rest of the library — please try again.'))
        return
      } finally {
        setBusy(false)
      }
    }
    const prefixes = new Set(selectedItems.map((i) => setPrefix(i.label)).filter(Boolean))
    const ids = pool.filter((i) => [...prefixes].some((p) => inSet(i.label, p))).map((i) => i.id)
    setSelected((prev) => [...new Set([...prev, ...ids])])
  }

  async function upload(files: File[]) {
    setError(null)
    setNotice(null)
    const failures: string[] = []
    for (let n = 0; n < files.length; n++) {
      const file = files[n]
      const k = mediaKindForFile(file)
      if (!k) {
        failures.push(`${file.name}: unsupported file type`)
        continue
      }
      setUploadStatus(`Uploading ${n + 1} of ${files.length}…`)
      try {
        await uploadMediaFile(projectId, token, k, file, { label: stripExtension(file.name) })
      } catch (err) {
        failures.push(`${file.name}: ${apiErrorMessage(err, err instanceof Error ? err.message : 'Upload failed')}`)
      }
    }
    setUploadStatus(null)
    if (failures.length) setError(failures.join(' · '))
    else setNotice(`Uploaded ${files.length} ${files.length === 1 ? 'file' : 'files'}.`)
    setReloadKey((k) => k + 1)
  }

  return (
    <div className="space-y-4 pb-20">
      <MediaFilterBar
        filters={filters}
        chips={['unchecked', 'blocked', 'ai', 'onPosts', 'unused']}
        kinds={['ALL', 'IMAGE', 'VIDEO', 'AUDIO']}
        sortable
        trailing={
          canManage && (
            <>
              <input
                ref={fileInput}
                type="file"
                multiple
                accept={ACCEPT}
                aria-label="Media files"
                className="sr-only"
                onChange={(e) => {
                  const files = Array.from(e.target.files ?? [])
                  e.target.value = ''
                  if (files.length) void upload(files)
                }}
              />
              <Button type="button" size="sm" disabled={uploadStatus != null} onClick={() => fileInput.current?.click()}>
                <UploadCloudIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
                {uploadStatus ?? 'Upload'}
              </Button>
            </>
          )
        }
      />

      {error && <Alert variant="destructive">{error}</Alert>}
      {notice && <Alert variant="info">{notice}</Alert>}

      {items == null ? (
        <div data-testid="media-grid-skeleton" className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6" aria-hidden>
          {Array.from({ length: 12 }, (_, i) => (
            <Skeleton key={i} className="aspect-square" />
          ))}
        </div>
      ) : visible.length === 0 ? (
        <EmptyState
          icon={FilmIcon}
          title={filtering ? 'Nothing matches' : 'No media yet'}
          description={
            filtering
              ? 'Try a different search or clear the filters.'
              : 'Upload images, video or audio to use in your Creatives.'
          }
        />
      ) : (
        <ul data-testid="media-grid" className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-6">
          {visible.map((item, index) => {
            const isSelected = selected.includes(item.id)
            return (
              <li
                key={item.id}
                data-testid={`media-tile-${item.id}`}
                className={cn(
                  'group relative overflow-hidden rounded-lg border bg-surface',
                  isSelected ? 'border-primary ring-2 ring-primary' : 'border-border',
                )}
              >
                <button
                  type="button"
                  aria-label={`Open ${item.label || 'Untitled'}`}
                  onClick={(e) => {
                    if (e.shiftKey || e.metaKey || e.ctrlKey) select(item, index, e.shiftKey)
                    else setDetailId(item.id)
                  }}
                  className={cn('relative block aspect-square w-full border-b border-border bg-surface-3', item.blocked && 'opacity-50')}
                >
                  <MediaTile item={item} />
                </button>
                {canManage && (
                  <input
                    type="checkbox"
                    aria-label={`Select ${item.label || 'Untitled'}`}
                    checked={isSelected}
                    onChange={() => {}}
                    onClick={(e) => select(item, index, e.shiftKey)}
                    className={cn(
                      'absolute left-1.5 top-1.5 h-4 w-4 cursor-pointer appearance-none rounded border border-border-strong bg-background',
                      'checked:border-primary checked:bg-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                      'transition-opacity group-hover:opacity-100 focus-visible:opacity-100 checked:opacity-100',
                      selected.length === 0 && 'opacity-0',
                    )}
                  />
                )}
                <div className="flex items-center gap-1.5 border-t border-border px-2 py-1 text-[11px]">
                  <span className="min-w-0 flex-1 truncate text-foreground" title={item.label ?? undefined}>
                    {item.label || 'Untitled'}
                  </span>
                  {item.blocked && <span className="text-status-failed">Blocked</span>}
                  {item.aiGenerated && <span className="text-muted-foreground">AI</span>}
                  {item.mediaKind === 'AUDIO' ? (
                    <span className="text-muted-foreground" data-testid="audio-usage">
                      {audioUsageLabel(item.creativeCount ?? 0, item.postCount ?? 0)}
                    </span>
                  ) : (
                    (item.postCount ?? 0) > 0 && (
                      <span className="text-muted-foreground">
                        {item.postCount} {item.postCount === 1 ? 'post' : 'posts'}
                      </span>
                    )
                  )}
                  {!item.checked && <span className="text-muted-foreground">Unchecked</span>}
                </div>
              </li>
            )
          })}
        </ul>
      )}

      {nextCursor && (
        <div ref={sentinel} className="flex justify-center py-2">
          <Button type="button" variant="outline" size="sm" disabled={loadingMore} onClick={() => void loadMore()}>
            {loadingMore ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      )}

      {canManage && selected.length > 0 && (
        <div
          role="toolbar"
          aria-label="Selection actions"
          className="fixed inset-x-4 bottom-4 z-30 mx-auto flex max-w-3xl flex-wrap items-center gap-2 rounded-lg border border-border bg-popover px-4 py-2.5 shadow-lg"
        >
          <span className="text-sm font-medium text-foreground">{selectedItems.length} selected</span>
          {hiddenSelected > 0 && <span className="text-xs text-muted-foreground">{hiddenSelected} hidden by filters</span>}
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={busy || selectedItems.length === 0}
            onClick={() => void markChecked()}
          >
            Mark checked
          </Button>
          <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void selectSet()}>
            Select set
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={nonImageSelected || selectedItems.length === 0}
            onClick={() => setCarouselOpen(true)}
          >
            Make carousel
          </Button>
          {nonImageSelected && <span className="text-xs text-muted-foreground">Carousels take images only — deselect video and audio.</span>}
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="ml-auto"
            onClick={() => {
              setSelected([])
              anchor.current = null
            }}
          >
            <XIcon className="mr-1 h-3.5 w-3.5" aria-hidden />
            Clear
          </Button>
        </div>
      )}

      <MediaDetailSheet
        item={detail}
        projectId={projectId}
        token={token}
        canManage={canManage}
        onClose={() => setDetailId(null)}
        onChanged={replaceItem}
        onDeleted={(id) => {
          setItems((prev) => (prev ?? []).filter((i) => i.id !== id))
          setSelected((prev) => prev.filter((s) => s !== id))
          setDetailId(null)
        }}
      />
      {carouselOpen && (
        <MakeCarouselDialog
          open
          onOpenChange={setCarouselOpen}
          projectId={projectId}
          token={token}
          selected={selectedItems}
        />
      )}
    </div>
  )
}

/** A track's use, for its tile footer: "2 creatives · 1 post", or "Unused". */
function audioUsageLabel(creatives: number, posts: number): string {
  if (creatives === 0 && posts === 0) return 'Unused'
  return `${creatives} ${creatives === 1 ? 'creative' : 'creatives'} · ${posts} ${posts === 1 ? 'post' : 'posts'}`
}
