'use client'

// COND-24 T2: the Creative library — /marketing/creatives. Pattern: the Media library's filters +
// a responsive tile grid, but each tile's thumbnail is a live browser render of the 4:5 placement
// (@cliangdev/creative-render's mountBoard) rather than a stored image — there is no rendered PNG
// until T3's render job exists. Mounting a live board is comparatively expensive, so each tile only
// mounts once it scrolls into view (IntersectionObserver) and unmounts on unmount.

import Link from 'next/link'
import { useEffect, useMemo, useRef, useState } from 'react'
import { markPageReady } from '@/lib/perf'
import { useRouter } from 'next/navigation'
import { ImagesIcon, LayersIcon, PlayIcon, SearchIcon } from 'lucide-react'
import { mountBoard } from '@cliangdev/creative-render/mount'
import { motionKeyTimes } from '@cliangdev/creative-render/motion'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { Input } from '@/components/ui/input'
import { CopyableCode } from '@/components/ui/copyable-code'
import { EmptyState } from '@/components/ui/empty-state'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { Can } from '@/components/auth/Can'
import { useCan } from '@/contexts/PermissionsContext'
import { FloatingBulkBar } from '@/components/marketing/shared/FloatingBulkBar'
import { useRangeSelection } from '@/components/marketing/shared/useRangeSelection'
import { usePostsHref } from '@/components/marketing/shared/ContentSubNav'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage } from '@/lib/api'
import { formatDuration } from '@/lib/format'
import { carouselSlideCount } from '@/components/marketing/creatives/carouselSlides'
import { CarouselThumb, ClipThumb } from '@/components/marketing/creatives/CreativeThumbs'
import { BrandSetupNotice } from '@/components/marketing/brand/BrandSetupNotice'
import { brandKitToBrand, listBrandKits, type BrandKit } from '@/components/marketing/brand/types'
import {
  creativeToRenderCreative,
  createCreative,
  deleteCreative,
  listCreatives,
  type Creative,
  type CreativePhoto,
  type CreativeState,
} from '@/components/marketing/creatives/types'
import type { RenderBrand } from '@/components/marketing/creatives/renderTypes'
import { tileMediaIds, useMediaByIds } from '@/components/marketing/media/useMediaByIds'

type CreativeSort = 'newest' | 'oldest' | 'name' | 'used'

/** Pure: a family's text for search — id, names and copy of every member. */
function familyText(members: Creative[]): string {
  return members
    .map((m) => [m.displayId, m.name, m.headline, m.body].filter(Boolean).join(' '))
    .join(' ')
    .toLowerCase()
}

const STATE_HUE: Record<CreativeState, 'gray' | 'teal' | 'slate'> = {
  DRAFT: 'gray',
  READY: 'teal',
  ARCHIVED: 'slate',
}

const STATE_LABEL: Record<CreativeState, string> = {
  DRAFT: 'Draft',
  READY: 'Ready',
  ARCHIVED: 'Archived',
}

/** The empty state's copyable examples. `/conductor:marketing create` is the CLI's skill-driven slash
 *  command (conductor-tools 0.25.0+) — slash commands only exist in Claude Code, so Claude Desktop
 *  gets the same request phrased as a plain ask; either way Claude drives the same MCP tools the
 *  "Make a creative" docs walk through. */
const CREATIVE_EXAMPLE_PROMPT =
  "Make a 9:16 TikTok video from our newest photo with the headline 'Dinner, sorted in minutes'"
const CREATIVE_SLASH_PROMPT = `/conductor:marketing create ${CREATIVE_EXAMPLE_PROMPT}`

/** STILL and MOTION both mount a live board when there's no rendered thumbnail yet. A MOTION tile
 *  additionally overlays a play badge + duration (a still frame of a video, not a photo) and, once
 *  mounted, seeks the board to a representative moment — motion.js's presets start most elements
 *  hidden/mid-fade at t=0, so a bare mount would show a near-blank frame; `motionKeyTimes`'s last
 *  entry (inside the end-card hold, per contract) is the fully-composed frame instead. */
function CreativeThumb({
  creative,
  brand,
  mediaById,
}: {
  creative: Creative
  brand: RenderBrand
  mediaById: Map<string, CreativePhoto>
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false)
  const isMotion = creative.kind === 'MOTION'

  // A rendered PNG/poster (from a local `render_creative`/CLI run) is the real thing the platform
  // will show — prefer it over the live browser mount, which falls back to whenever no render exists yet.
  const thumbnailUrl = creative.latestRenderThumbnailUrl
  const motionDurationSec = creative.motion?.durationSec ?? 8

  useEffect(() => {
    const el = containerRef.current
    if (!el || thumbnailUrl) return
    if (typeof IntersectionObserver === 'undefined') {
      setVisible(true)
      return
    }
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0]?.isIntersecting) {
          setVisible(true)
          observer.disconnect()
        }
      },
      { rootMargin: '200px' },
    )
    observer.observe(el)
    return () => observer.disconnect()
  }, [thumbnailUrl])

  // Only the clip this tile's background uses matters; depend on its identity, not the whole media map,
  // so an unrelated media list arriving later doesn't tear this tile down and redraw it.
  const clipMediaId =
    isMotion && creative.motion?.background?.source === 'clip' ? creative.motion.background.clipMediaId : undefined
  const clipMedia = clipMediaId ? mediaById.get(clipMediaId) : undefined
  const clipMediaKey = clipMedia ? `${clipMedia.id}|${clipMedia.url ?? ''}|${clipMedia.posterUrl ?? ''}` : ''
  const creativeKey = JSON.stringify(creative)
  // The parent rebuilds `brand` on every render; compare by content so identical branding keeps the tile.
  const brandKey = JSON.stringify(brand)

  useEffect(() => {
    if (thumbnailUrl || !visible || !containerRef.current) return
    const handle = mountBoard(containerRef.current, {
      creative: creativeToRenderCreative(creative, undefined, { clipMedia }),
      brand,
      placementKey: '4x5',
    })
    if (isMotion) {
      const times = motionKeyTimes(creative.motion ?? {})
      void handle.ready.then(() => void handle.seek(times[times.length - 1]))
    }
    return () => handle.destroy()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-mount only when the rendered fields change
  }, [thumbnailUrl, visible, creative.headline, creative.body, creative.layout, creative.theme, creative.photoUrl, creativeKey, clipMediaKey, brandKey, isMotion])

  return (
    <div data-testid={`creative-thumb-${creative.id}`} className="relative aspect-[4/5] w-full overflow-hidden bg-surface-3">
      {thumbnailUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img loading="lazy" decoding="async" src={thumbnailUrl} alt="" className="h-full w-full object-cover" />
      ) : (
        <div ref={containerRef} className="h-full w-full" />
      )}
      {isMotion && (
        <>
          <span className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <PlayIcon className="h-8 w-8 fill-background text-background drop-shadow" aria-hidden />
          </span>
          <span className="pointer-events-none absolute bottom-1.5 right-1.5 rounded bg-foreground/70 px-1.5 py-0.5 text-[11px] text-background">
            {formatDuration(motionDurationSec)}
          </span>
        </>
      )}
    </div>
  )
}

function GridSkeleton() {
  return (
    <div data-testid="creative-grid-skeleton" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4" aria-hidden>
      {Array.from({ length: 8 }, (_, i) => (
        <div key={i} className="overflow-hidden rounded-lg border border-border">
          <Skeleton className="aspect-[4/5] rounded-none" />
          <div className="space-y-2 p-3">
            <Skeleton className="h-3 w-16" />
            <Skeleton className="h-3.5 w-3/4" />
          </div>
        </div>
      ))}
    </div>
  )
}

export interface CreativeLibraryGridProps {
  projectId: string
}

export function CreativeLibraryGrid({ projectId }: CreativeLibraryGridProps) {
  const router = useRouter()
  const { accessToken } = useAuth()

  const [kits, setKits] = useState<BrandKit[] | null>(null)
  const [creatives, setCreatives] = useState<Creative[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const [stateFilter, setStateFilter] = useState<CreativeState | ''>('')
  const [kitFilter, setKitFilter] = useState('')
  const [query, setQuery] = useState('')
  const [sort, setSort] = useState<CreativeSort>('newest')
  const [reloadKey, setReloadKey] = useState(0)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const canManage = useCan('creative.manage')
  const postsHref = usePostsHref(projectId)

  useEffect(() => {
    if (!projectId || !accessToken) return
    listBrandKits(projectId, accessToken)
      .then(setKits)
      .catch(() => setKits([]))
  }, [projectId, accessToken])

  // Tiles fall back to their media's poster / slide 1 / background clip; resolve just those items by id.
  const tileIds = useMemo(() => tileMediaIds(creatives ?? [], { motion: true }), [creatives])
  const mediaById = useMediaByIds(projectId, accessToken, tileIds)

  useEffect(() => {
    if (!projectId || !accessToken) return
    let cancelled = false
    listCreatives(projectId, accessToken, {
      state: stateFilter || undefined,
      brandKitId: kitFilter || undefined,
    })
      .then((rows) => {
        if (cancelled) return
        setCreatives(rows)
        setError(null)
      })
      .catch((err) => {
        if (cancelled) return
        setCreatives([])
        setError(apiErrorMessage(err, 'Could not load Creatives — please try again.'))
      })
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken, stateFilter, kitFilter, reloadKey])

  const kitById = useMemo(() => {
    const map = new Map<string, BrandKit>()
    for (const kit of kits ?? []) map.set(kit.id, kit)
    return map
  }, [kits])

  const showKitName = (kits?.length ?? 0) > 1
  const defaultKit = useMemo(() => kits?.find((k) => k.isDefault) ?? kits?.[0] ?? null, [kits])

  // Group by family (display number): the "a" root plus any lettered variants, so the grid reads
  // like the wireframe ("12a" with "b c" pills) instead of one indistinguishable tile per variant.
  const families = useMemo(() => {
    const byNumber = new Map<number, Creative[]>()
    for (const creative of creatives ?? []) {
      const list = byNumber.get(creative.number) ?? []
      list.push(creative)
      byNumber.set(creative.number, list)
    }
    const all = [...byNumber.entries()].map(([number, members]) => {
      const sorted = [...members].sort((a, b) => a.variantLetter.localeCompare(b.variantLetter))
      return {
        number,
        members: sorted,
        used: sorted.reduce((n, m) => n + (m.postCount ?? 0), 0),
        text: familyText(sorted),
      }
    })
    const needle = query.trim().toLowerCase()
    const matched = needle ? all.filter((f) => f.text.includes(needle)) : all
    const nameOf = (f: (typeof all)[number]) => (f.members[0].name || f.members[0].displayId).toLowerCase()
    return matched.sort((a, b) => {
      switch (sort) {
        case 'oldest':
          return a.number - b.number
        case 'name':
          return nameOf(a).localeCompare(nameOf(b))
        case 'used':
          return b.used - a.used || b.number - a.number
        default:
          return b.number - a.number
      }
    })
  }, [creatives, query, sort])

  // Selection works on whole families (the "a" root with its variants): that is what a tile is.
  const rootIds = useMemo(() => families.map((f) => f.members[0].id), [families])
  const selection = useRangeSelection(rootIds)
  const selectedFamilies = families.filter((f) => selection.selectedVisible.includes(f.members[0].id))

  /** Deletes each selected family, variants first (the server refuses a root that still has variants). */
  async function deleteSelected() {
    if (!accessToken) return
    setDeleting(true)
    setError(null)
    setNotice(null)
    const failures: string[] = []
    let done = 0
    for (const family of selectedFamilies) {
      const ordered = [...family.members].reverse()
      try {
        for (const member of ordered) await deleteCreative(projectId, member.id, accessToken)
        done++
        selection.remove(family.members[0].id)
      } catch (err) {
        failures.push(`${family.members[0].displayId}: ${apiErrorMessage(err, 'could not be deleted')}`)
      }
    }
    setDeleting(false)
    setDeleteOpen(false)
    setNotice(`${done} deleted${failures.length ? `, ${failures.length} failed` : ''}.`)
    if (failures.length) setError(failures.join(' · '))
    setReloadKey((k) => k + 1)
  }

  async function handleNewCreative() {
    if (!accessToken || creating) return
    setCreating(true)
    setError(null)
    try {
      const created = await createCreative(projectId, {}, accessToken)
      router.push(`/app/projects/${projectId}/marketing/creatives/${created.id}`)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not create the Creative — please try again.'))
      setCreating(false)
    }
  }

  const loading = creatives === null
  useEffect(() => { if (!loading) markPageReady('creatives-library') }, [loading])
  const filtered = Boolean(stateFilter || kitFilter || query.trim())

  return (
    <div className="space-y-4 pb-16">
      <BrandSetupNotice projectId={projectId} kit={defaultKit} />

      <div className="flex flex-wrap items-end gap-3">
        <div className="relative w-56">
          <SearchIcon className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-subtle" aria-hidden />
          <Input
            type="search"
            aria-label="Search creatives"
            placeholder="Search name, copy or ID"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="pl-8"
          />
        </div>

        <div className="min-w-[9rem]">
          <Label htmlFor="creative-sort" className="text-xs font-medium text-muted-foreground">
            Sort
          </Label>
          <Select id="creative-sort" value={sort} onChange={(e) => setSort(e.target.value as CreativeSort)}>
            <option value="newest">Newest</option>
            <option value="oldest">Oldest</option>
            <option value="name">Name</option>
            <option value="used">Most used</option>
          </Select>
        </div>

        <div className="min-w-[9rem]">
          <Label htmlFor="creative-state-filter" className="text-xs font-medium text-muted-foreground">
            State
          </Label>
          <Select
            id="creative-state-filter"
            value={stateFilter}
            onChange={(e) => setStateFilter(e.target.value as CreativeState | '')}
          >
            <option value="">All states</option>
            <option value="DRAFT">Draft</option>
            <option value="READY">Ready</option>
            <option value="ARCHIVED">Archived</option>
          </Select>
        </div>

        <div className="min-w-[9rem]">
          <Label htmlFor="creative-kit-filter" className="text-xs font-medium text-muted-foreground">
            Kit
          </Label>
          <Select id="creative-kit-filter" value={kitFilter} onChange={(e) => setKitFilter(e.target.value)}>
            <option value="">All kits</option>
            {(kits ?? []).map((kit) => (
              <option key={kit.id} value={kit.id}>
                {kit.name}
              </option>
            ))}
          </Select>
        </div>

        {filtered && (
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setStateFilter('')
              setKitFilter('')
              setQuery('')
            }}
          >
            Clear filters
          </Button>
        )}

        <Can do="creative.manage">
          <div className="ml-auto">
            <Button onClick={handleNewCreative} disabled={creating}>
              {creating ? 'Creating…' : 'New creative'}
            </Button>
          </div>
        </Can>
      </div>

      {error && <Alert variant="destructive">{error}</Alert>}
      {notice && <Alert variant="info">{notice}</Alert>}

      {loading ? (
        <GridSkeleton />
      ) : families.length === 0 ? (
        !error && (
          <EmptyState
            icon={ImagesIcon}
            title="No Creatives yet"
            description={
              filtered
                ? 'No Creatives match these filters.'
                : 'Make one three ways: ask Claude, start one here, or upload a finished video as a Clip.'
            }
            action={
              !filtered && (
                <div className="w-full max-w-md space-y-3 text-left">
                  <div className="space-y-1">
                    <p className="text-xs font-medium text-muted-foreground">In Claude Code:</p>
                    <CopyableCode text={CREATIVE_SLASH_PROMPT} label="Copy Claude Code prompt" />
                  </div>
                  <div className="space-y-1">
                    <p className="text-xs font-medium text-muted-foreground">
                      In Claude Desktop (or Claude Code): ask, e.g.
                    </p>
                    <CopyableCode text={CREATIVE_EXAMPLE_PROMPT} label="Copy ask-Claude prompt" />
                  </div>
                  <Can do="creative.manage">
                    <Button onClick={handleNewCreative} disabled={creating}>
                      {creating ? 'Creating…' : 'New creative'}
                    </Button>
                  </Can>
                  <p className="text-xs text-muted-foreground">
                    Already have a finished video? Start a creative here and set its kind to Clip.
                  </p>
                </div>
              )
            }
          />
        )
      ) : (
        <div data-testid="creative-grid" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {families.map(({ members, used }) => {
            const root = members[0]
            const variants = members.slice(1)
            const kit = kitById.get(root.brandKitId)
            const brand = brandKitToBrand(kit)
            const isSequence = Boolean(root.sequenceKind && root.sequence.length > 0)
            const isSelected = selection.isSelected(root.id)
            return (
              <div
                key={root.id}
                data-testid={`creative-tile-${root.id}`}
                data-selected={isSelected || undefined}
                className={`group relative overflow-hidden rounded-lg border bg-surface transition-colors hover:border-border-strong ${
                  isSelected ? 'border-primary ring-2 ring-primary' : 'border-border'
                }`}
              >
                <Link
                  href={`/app/projects/${projectId}/marketing/creatives/${root.id}`}
                  className="block focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {root.kind === 'CAROUSEL' ? (
                    <CarouselThumb creative={root} mediaById={mediaById} />
                  ) : root.kind === 'CLIP' ? (
                    <ClipThumb creative={root} mediaById={mediaById} />
                  ) : (
                    <CreativeThumb creative={root} brand={brand} mediaById={mediaById} />
                  )}
                  <div className="flex flex-col gap-1.5 border-t border-border px-3 pt-3">
                    <div className="flex items-center gap-2 text-xs text-muted-foreground">
                      <span className="font-mono">{root.displayId}</span>
                      {isSequence && (
                        <span title={`${root.sequenceKind === 'carousel' ? 'Carousel' : 'Story'} · ${root.sequence.length}`}>
                          <LayersIcon className="h-3 w-3" aria-hidden />
                        </span>
                      )}
                      {showKitName && kit && <span className="ml-auto shrink-0 truncate">{kit.name}</span>}
                    </div>
                    <p className="truncate text-sm text-foreground" title={root.name || root.displayId}>
                      {root.name || (root.kind === 'CAROUSEL' ? (carouselSlideCount(root.slides) === 1 ? 'Image' : 'Carousel') : root.kind === 'CLIP' ? 'Clip' : root.layout)}
                    </p>
                    <StatusBadge status={root.state} hue={STATE_HUE[root.state]} label={STATE_LABEL[root.state]} className="self-start" />
                  </div>
                </Link>
                {/* Variant letters and usage are real links beside the tile link, never nested inside it. */}
                <div className="flex flex-wrap items-center gap-x-2 gap-y-1 px-3 pb-3 pt-1.5 text-xs text-muted-foreground">
                  {variants.map((v) => (
                    <Link
                      key={v.id}
                      href={`/app/projects/${projectId}/marketing/creatives/${v.id}`}
                      aria-label={`Open variant ${v.displayId}`}
                      className="rounded px-1 font-mono hover:bg-surface-3 hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring"
                    >
                      {v.variantLetter}
                    </Link>
                  ))}
                  {used > 0 && postsHref ? (
                    <Link
                      href={`${postsHref}?creativeId=${root.id}`}
                      data-testid={`creative-used-${root.id}`}
                      className="ml-auto hover:text-foreground hover:underline"
                    >
                      Used in {used} {used === 1 ? 'post' : 'posts'}
                    </Link>
                  ) : used > 0 ? (
                    <span className="ml-auto" data-testid={`creative-used-${root.id}`}>
                      Used in {used} {used === 1 ? 'post' : 'posts'}
                    </span>
                  ) : null}
                </div>
                {canManage && (
                  <input
                    type="checkbox"
                    aria-label={`Select ${root.displayId}`}
                    checked={isSelected}
                    onChange={() => {}}
                    onClick={(e) => selection.select(root.id, e.shiftKey)}
                    className={`absolute left-1.5 top-1.5 h-4 w-4 cursor-pointer appearance-none rounded border border-border-strong bg-background checked:border-primary checked:bg-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring transition-opacity group-hover:opacity-100 focus-visible:opacity-100 checked:opacity-100 ${
                      selection.selected.length === 0 ? 'opacity-0' : ''
                    }`}
                  />
                )}
              </div>
            )
          })}
        </div>
      )}

      {canManage && selectedFamilies.length > 0 && (
        <FloatingBulkBar count={selectedFamilies.length} hiddenCount={selection.hiddenCount} onClear={selection.clear}>
          <Button variant="outline" size="sm" className="text-destructive" disabled={deleting} onClick={() => setDeleteOpen(true)}>
            Delete
          </Button>
        </FloatingBulkBar>
      )}

      {deleteOpen && (
        <ConfirmModal
          open
          title={`Delete ${selectedFamilies.length} ${selectedFamilies.length === 1 ? 'creative' : 'creatives'}?`}
          description="Variants go with their creative. One still on a post is refused, and the post is named."
          confirmLabel={`Delete ${selectedFamilies.length}`}
          busyLabel="Deleting…"
          busy={deleting}
          onCancel={() => !deleting && setDeleteOpen(false)}
          onConfirm={() => void deleteSelected()}
        >
          <ul className="max-h-48 space-y-1 overflow-y-auto text-sm text-foreground">
            {selectedFamilies.map((f) => (
              <li key={f.members[0].id} className="truncate">
                <span className="mr-2 font-mono text-xs text-muted-foreground">{f.members[0].displayId}</span>
                {f.members[0].name}
                {f.used > 0 && <span className="ml-2 text-xs text-muted-foreground">used in {f.used} {f.used === 1 ? 'post' : 'posts'}</span>}
              </li>
            ))}
          </ul>
        </ConfirmModal>
      )}
    </div>
  )
}
