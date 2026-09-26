'use client'

// COND-24 T2: the Creative library — /marketing/creatives. Pattern: AssetLibraryGrid.tsx (filters +
// a responsive tile grid), but each tile's thumbnail is a live browser render of the 4:5 placement
// (@cliangdev/creative-render's mountBoard) rather than a stored image — there is no rendered PNG
// until T3's render job exists. Mounting a live board is comparatively expensive, so each tile only
// mounts once it scrolls into view (IntersectionObserver) and unmounts on unmount.

import Link from 'next/link'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import { ImagesIcon, LayersIcon } from 'lucide-react'
import { mountBoard } from '@cliangdev/creative-render/mount'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage } from '@/lib/api'
import { brandKitToBrand, listBrandKits, type BrandKit } from '@/components/marketing/brand/types'
import {
  creativeToRenderCreative,
  createCreative,
  listCreatives,
  type Creative,
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

function CreativeThumb({ creative, brand }: { creative: Creative; brand: RenderBrand }) {
  const containerRef = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false)

  useEffect(() => {
    const el = containerRef.current
    if (!el) return
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
  }, [])

  useEffect(() => {
    if (!visible || !containerRef.current) return
    const handle = mountBoard(containerRef.current, {
      creative: creativeToRenderCreative(creative),
      brand,
      placementKey: '4x5',
    })
    return () => handle.destroy()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-mount only when the rendered fields change
  }, [visible, creative.headline, creative.body, creative.layout, creative.theme, creative.photoUrl, brand])

  return (
    <div
      ref={containerRef}
      data-testid={`creative-thumb-${creative.id}`}
      className="relative aspect-[4/5] w-full overflow-hidden bg-surface-3"
    />
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

  useEffect(() => {
    if (!projectId || !accessToken) return
    listBrandKits(projectId, accessToken)
      .then(setKits)
      .catch(() => setKits([]))
  }, [projectId, accessToken])

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

        <div className="ml-auto">
          <Button onClick={handleNewCreative} disabled={creating}>
            {creating ? 'Creating…' : 'New creative'}
          </Button>
        </div>
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
                <Button onClick={handleNewCreative} disabled={creating}>
                  New creative
                </Button>
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
                <CreativeThumb creative={root} brand={brand} />
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
                    {root.name || root.layout}
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
