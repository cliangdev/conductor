'use client'

// COND-24 T2: the Creative library — /marketing/creatives. Pattern: AssetLibraryGrid.tsx (filters +
// a responsive tile grid), but each tile's thumbnail is a live browser render of the 4:5 placement
// (@cliangdev/creative-render's mountBoard) rather than a stored image — there is no rendered PNG
// until T3's render job exists. Mounting a live board is comparatively expensive, so each tile only
// mounts once it scrolls into view (IntersectionObserver) and unmounts on unmount.

import Link from 'next/link'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { FilmIcon, ImagesIcon, LayersIcon, PlayIcon } from 'lucide-react'
import { mountBoard } from '@cliangdev/creative-render/mount'
import { motionKeyTimes } from '@cliangdev/creative-render/motion'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { Can } from '@/components/auth/Can'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage } from '@/lib/api'
import { formatDuration } from '@/lib/format'
import { brandKitToBrand, listBrandKits, type BrandKit } from '@/components/marketing/brand/types'
import {
  creativeToRenderCreative,
  createCreative,
  listCreativePhotos,
  listCreatives,
  type Creative,
  type CreativePhoto,
  type CreativeState,
} from '@/components/marketing/creatives/types'
import type { RenderBrand } from '@/components/marketing/creatives/renderTypes'

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

  useEffect(() => {
    if (thumbnailUrl || !visible || !containerRef.current) return
    const clipMedia =
      isMotion && creative.motion?.background?.source === 'clip' && creative.motion.background.clipMediaId
        ? mediaById.get(creative.motion.background.clipMediaId)
        : undefined
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
  }, [thumbnailUrl, visible, creative.headline, creative.body, creative.layout, creative.theme, creative.photoUrl, creative.kind, creative.motion, brand, isMotion, mediaById, creative])

  return (
    <div data-testid={`creative-thumb-${creative.id}`} className="relative aspect-[4/5] w-full overflow-hidden bg-surface-3">
      {thumbnailUrl ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img src={thumbnailUrl} alt="" className="h-full w-full object-cover" />
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

/** CLIP creatives never mount a live board — there's no layout/theme/headline to render — so their
 *  tile shows a poster instead: the latest render's frame poster if there is one, else the default
 *  clip media's own poster (set at upload time — see MediaPicker's captureVideoPoster). */
function ClipThumb({ creative, mediaById }: { creative: Creative; mediaById: Map<string, CreativePhoto> }) {
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
  const [media, setMedia] = useState<CreativePhoto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const [stateFilter, setStateFilter] = useState<CreativeState | ''>('')
  const [kitFilter, setKitFilter] = useState('')

  useEffect(() => {
    if (!projectId || !accessToken) return
    listBrandKits(projectId, accessToken)
      .then(setKits)
      .catch(() => setKits([]))
  }, [projectId, accessToken])

  // Only CLIP tiles need this — see ClipThumb's fallback to the default clip media's own poster,
  // for a Clip that hasn't been rendered yet.
  useEffect(() => {
    if (!projectId || !accessToken) return
    listCreativePhotos(projectId, accessToken, true, 'VIDEO')
      .then(setMedia)
      .catch(() => setMedia([]))
  }, [projectId, accessToken])

  const mediaById = useMemo(() => {
    const map = new Map<string, CreativePhoto>()
    for (const m of media) map.set(m.id, m)
    return map
  }, [media])

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
  }, [projectId, accessToken, stateFilter, kitFilter])

  const kitById = useMemo(() => {
    const map = new Map<string, BrandKit>()
    for (const kit of kits ?? []) map.set(kit.id, kit)
    return map
  }, [kits])

  const showKitName = (kits?.length ?? 0) > 1

  // Group by family (display number): the "a" root plus any lettered variants, so the grid reads
  // like the wireframe ("12a" with "b c" pills) instead of one indistinguishable tile per variant.
  const families = useMemo(() => {
    const byNumber = new Map<number, Creative[]>()
    for (const creative of creatives ?? []) {
      const list = byNumber.get(creative.number) ?? []
      list.push(creative)
      byNumber.set(creative.number, list)
    }
    return [...byNumber.entries()]
      .map(([number, members]) => ({
        number,
        members: [...members].sort((a, b) => a.variantLetter.localeCompare(b.variantLetter)),
      }))
      .sort((a, b) => b.number - a.number)
  }, [creatives])

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
  const filtered = Boolean(stateFilter || kitFilter)

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
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
                : 'Start a Creative and its layout, theme and copy will preview live as you edit.'
            }
            action={
              !filtered && (
                <Can do="creative.manage">
                  <Button onClick={handleNewCreative} disabled={creating}>
                    New creative
                  </Button>
                </Can>
              )
            }
          />
        )
      ) : (
        <div data-testid="creative-grid" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
          {families.map(({ members }) => {
            const root = members[0]
            const variants = members.slice(1)
            const kit = kitById.get(root.brandKitId)
            const brand = brandKitToBrand(kit)
            const isSequence = Boolean(root.sequenceKind && root.sequence.length > 0)
            return (
              <Link
                key={root.id}
                href={`/app/projects/${projectId}/marketing/creatives/${root.id}`}
                className="group block overflow-hidden rounded-lg border border-border bg-surface transition-colors hover:border-border-strong"
              >
                {root.kind === 'CLIP' ? (
                  <ClipThumb creative={root} mediaById={mediaById} />
                ) : (
                  <CreativeThumb creative={root} brand={brand} mediaById={mediaById} />
                )}
                <div className="flex flex-col gap-1.5 border-t border-border p-3">
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span className="font-mono">{root.displayId}</span>
                    {isSequence && (
                      <span title={`${root.sequenceKind === 'carousel' ? 'Carousel' : 'Story'} · ${root.sequence.length}`}>
                        <LayersIcon className="h-3 w-3" aria-hidden />
                      </span>
                    )}
                    {variants.length > 0 && (
                      <span className="flex gap-1">
                        {variants.map((v) => (
                          <span
                            key={v.id}
                            role="link"
                            tabIndex={0}
                            onClick={(e) => {
                              e.preventDefault()
                              e.stopPropagation()
                              router.push(`/app/projects/${projectId}/marketing/creatives/${v.id}`)
                            }}
                            className="cursor-pointer rounded px-1 font-mono hover:bg-surface-3 hover:text-foreground"
                          >
                            {v.variantLetter}
                          </span>
                        ))}
                      </span>
                    )}
                    {showKitName && kit && <span className="ml-auto shrink-0 truncate">{kit.name}</span>}
                  </div>
                  <p className="truncate text-sm text-foreground" title={root.name || root.displayId}>
                    {root.name || (root.kind === 'CLIP' ? 'Clip' : root.layout)}
                  </p>
                  <StatusBadge status={root.state} hue={STATE_HUE[root.state]} label={STATE_LABEL[root.state]} className="self-start" />
                </div>
              </Link>
            )
          })}
        </div>
      )}
    </div>
  )
}
