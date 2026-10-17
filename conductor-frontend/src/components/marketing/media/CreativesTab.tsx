'use client'

// The chooser's Creatives tab: pick one finished Creative and see, before committing, which of its sizes
// each chosen destination will get (the same rules attach uses, via placementMap). A Creative is a set of
// renders, not a file, so this tab brings its own footer ("Use Creative 18a") rather than the Library's
// "Add N".

import { useEffect, useMemo, useState } from 'react'
import { AlertTriangleIcon, FilmIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { apiErrorMessage } from '@/lib/api'
import { cn } from '@/lib/utils'
import { PlatformIcon } from '@/components/marketing/destinations/PlatformIcon'
import type { PublishPlatform } from '@/components/marketing/destinations/types'
import {
  canUseInPost,
  creativeSummary,
  needsServerRender,
  kindFilterOf,
  kindLabel,
  slideCount,
  sizesLabel,
  type KindFilter,
} from '@/components/marketing/creatives/creativeCopy'
import {
  groupFrames,
  placementChip,
  placementFor,
  placementLabel,
  type PlacementGroup,
} from '@/components/marketing/creatives/placementMap'
import {
  getCreative,
  listCreativePhotos,
  listCreatives,
  requestCreativeRender,
  type Creative,
  type CreativePhoto,
  type CreativeRenderFrame,
} from '@/components/marketing/creatives/types'
import { CarouselThumb, ClipThumb } from '@/components/marketing/creatives/CreativeThumbs'
import { FilterChip } from './MediaFilterBar'

/** A destination the caller has picked, as the preview needs it. */
export interface ChooserDestination {
  platform: PublishPlatform
  format?: string | null
  /** e.g. "@acme" — shown beside the platform icon. */
  label: string
}

/** What choosing a Creative hands back to the caller. */
export interface CreativeChoice {
  creative: Creative
  renderId: string
  /** The render's frames, so the caller can show each destination's size without another fetch. */
  frames: CreativeRenderFrame[]
  /** Whether the person asked for the Creative's caption. */
  useCaption: boolean
}

/** Loads the project's Creatives once. `null` while loading; an error leaves the list empty. */
export function useCreatives(projectId: string, token: string) {
  const [creatives, setCreatives] = useState<Creative[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    if (!token) return
    let cancelled = false
    listCreatives(projectId, token)
      .then((rows) => {
        if (!cancelled) setCreatives(rows.filter((c) => c.state !== 'ARCHIVED'))
      })
      .catch((err) => {
        if (cancelled) return
        setCreatives([])
        setError(apiErrorMessage(err, 'Could not load Creatives'))
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token])
  return { creatives, error }
}

type Detail =
  | { status: 'loading' }
  | { status: 'preparing' }
  | { status: 'error'; message: string }
  | { status: 'ready'; renderId: string; frames: CreativeRenderFrame[] }
  | { status: 'unusable'; message: string }

const KIND_FILTERS: Array<{ value: KindFilter; label: string }> = [
  { value: 'STILL', label: 'Still' },
  { value: 'CAROUSEL', label: 'Carousel' },
  { value: 'VIDEO', label: 'Video' },
]

function matchesQuery(c: Creative, q: string): boolean {
  const needle = q.trim().toLowerCase()
  if (!needle) return true
  return [c.name, c.displayId, c.headline].some((s) => (s ?? '').toLowerCase().includes(needle))
}

export interface CreativesTabProps {
  creatives: Creative[] | null
  loadError?: string | null
  projectId: string
  token: string
  destinations: ChooserDestination[]
  /** The Post's caption is empty, so the Creative's caption is offered (and pre-ticked). */
  captionEmpty: boolean
  onUse: (choice: CreativeChoice) => void
  onCancel: () => void
}

export function CreativesTab({
  creatives,
  loadError,
  projectId,
  token,
  destinations,
  captionEmpty,
  onUse,
  onCancel,
}: CreativesTabProps) {
  const [query, setQuery] = useState('')
  const anyReady = (creatives ?? []).some((c) => c.state === 'READY' && canUseInPost(c))
  // Until the person chooses, Ready is the default whenever any Creative is ready to use.
  const [readyChoice, setReadyOnly] = useState<boolean | null>(null)
  const readyOnly = readyChoice ?? anyReady
  const [kinds, setKinds] = useState<Set<KindFilter>>(new Set())
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [details, setDetails] = useState<Record<string, Detail>>({})
  const [useCaption, setUseCaption] = useState(captionEmpty)

  const visible = useMemo(
    () =>
      (creatives ?? []).filter(
        (c) =>
          (!readyOnly || c.state === 'READY') &&
          (kinds.size === 0 || kinds.has(kindFilterOf(c.kind))) &&
          matchesQuery(c, query),
      ),
    [creatives, readyOnly, kinds, query],
  )

  // Clip and Carousel tiles without a render thumbnail fall back to media posters; load each list once.
  const needsVideoMedia = (creatives ?? []).some((c) => c.kind === 'CLIP' && !c.latestRenderThumbnailUrl)
  const needsImageMedia = (creatives ?? []).some((c) => c.kind === 'CAROUSEL' && !c.latestRenderThumbnailUrl)
  const [videoMedia, setVideoMedia] = useState<CreativePhoto[]>([])
  const [imageMedia, setImageMedia] = useState<CreativePhoto[]>([])
  useEffect(() => {
    if (!token || !needsVideoMedia) return
    listCreativePhotos(projectId, token, true, 'VIDEO').then(setVideoMedia).catch(() => setVideoMedia([]))
  }, [projectId, token, needsVideoMedia])
  useEffect(() => {
    if (!token || !needsImageMedia) return
    listCreativePhotos(projectId, token, true, 'IMAGE').then(setImageMedia).catch(() => setImageMedia([]))
  }, [projectId, token, needsImageMedia])
  const mediaById = useMemo(() => {
    const map = new Map<string, CreativePhoto>()
    for (const m of videoMedia) map.set(m.id, m)
    for (const m of imageMedia) map.set(m.id, m)
    return map
  }, [videoMedia, imageMedia])

  const selected = (creatives ?? []).find((c) => c.id === selectedId) ?? null
  const detail = selected ? details[selected.id] : undefined

  // One detail call per selected Creative (never per card), cached for the life of the tab.
  useEffect(() => {
    if (!selected || details[selected.id]) return
    const id = selected.id
    setDetails((d) => ({ ...d, [id]: { status: 'loading' } }))
    getCreative(projectId, id, token)
      .then((full) => {
        const render = full.latestRender
        const ready = render && render.state === 'SUCCEEDED' && !render.previewOnly
        if (!ready && needsServerRender(full)) {
          // Clip and Carousel renders are assembled by the backend in this one call.
          setDetails((d) => ({ ...d, [id]: { status: 'preparing' } }))
          return requestCreativeRender(projectId, id, { renderer: 'web' }, token).then((made) => {
            const next: Detail =
              made.state === 'SUCCEEDED' && made.frames.length > 0
                ? { status: 'ready', renderId: made.id, frames: made.frames }
                : { status: 'error', message: made.error || 'Could not prepare this Creative for posting' }
            setDetails((d) => ({ ...d, [id]: next }))
          })
        }
        const next: Detail =
          !render || render.state !== 'SUCCEEDED'
            ? { status: 'unusable', message: 'This Creative has no finished render yet.' }
            : render.previewOnly
              ? { status: 'unusable', message: 'Only a preview was rendered. Render it for posting first.' }
              : { status: 'ready', renderId: render.id, frames: render.frames }
        setDetails((d) => ({ ...d, [id]: next }))
      })
      .catch((err) => setDetails((d) => ({ ...d, [id]: { status: 'error', message: apiErrorMessage(err, 'Could not prepare this Creative') } })))
  }, [selected, details, projectId, token])

  const groups: PlacementGroup[] = useMemo(
    () => (detail?.status === 'ready' ? groupFrames(detail.frames) : []),
    [detail],
  )

  function toggleKind(k: KindFilter) {
    setKinds((prev) => {
      const next = new Set(prev)
      if (next.has(k)) next.delete(k)
      else next.add(k)
      return next
    })
  }

  const canUse = selected != null && detail?.status === 'ready'

  return (
    <div className="flex min-h-0 flex-1 flex-col" data-testid="creatives-tab">
      <div className="flex min-h-0 flex-1 gap-5">
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <Input
              type="search"
              aria-label="Search Creatives"
              placeholder="Search name, ID or headline…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              className="h-9 max-w-xs"
            />
            <div role="group" aria-label="State" className="flex items-center gap-1">
              <FilterChip pressed={readyOnly} onClick={() => setReadyOnly(true)}>
                Ready
              </FilterChip>
              <FilterChip pressed={!readyOnly} onClick={() => setReadyOnly(false)}>
                All
              </FilterChip>
            </div>
            <div role="group" aria-label="Kind" className="flex items-center gap-1">
              {KIND_FILTERS.map((k) => (
                <FilterChip key={k.value} pressed={kinds.has(k.value)} onClick={() => toggleKind(k.value)}>
                  {k.label}
                </FilterChip>
              ))}
            </div>
          </div>

          {loadError && <Alert variant="destructive">{loadError}</Alert>}

          <div className="min-h-0 flex-1 overflow-y-auto">
            {creatives === null ? (
              <Skeleton className="h-40 w-full" />
            ) : visible.length === 0 ? (
              <EmptyState
                icon={FilmIcon}
                title={creatives.length === 0 ? 'No Creatives yet' : 'No Creatives match'}
                description={
                  creatives.length === 0
                    ? 'Make one from the Creatives page, render it, then come back here.'
                    : 'Try a different search, or switch Ready to All.'
                }
              />
            ) : (
              <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3" data-testid="creatives-grid">
                {visible.map((c) => {
                  const rendered = canUseInPost(c)
                  const isSelected = c.id === selectedId
                  return (
                    <li key={c.id}>
                      <button
                        type="button"
                        disabled={!rendered}
                        title={rendered ? undefined : 'Render it from Claude Code first'}
                        aria-pressed={isSelected}
                        aria-label={`Choose Creative ${c.displayId}${c.name ? ` ${c.name}` : ''}`}
                        onClick={() => setSelectedId(c.id)}
                        className={cn(
                          'block w-full overflow-hidden rounded-[10px] border text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                          isSelected ? 'border-primary ring-2 ring-primary' : 'border-border hover:border-border-strong',
                          !rendered && 'cursor-not-allowed opacity-60',
                        )}
                      >
                        {c.kind === 'CLIP' ? (
                          <ClipThumb creative={c} mediaById={mediaById} />
                        ) : c.kind === 'CAROUSEL' ? (
                          <CarouselThumb creative={c} mediaById={mediaById} />
                        ) : (
                          <span className="relative block aspect-[4/5] w-full bg-surface-3">
                            {c.latestRenderThumbnailUrl && (
                              // eslint-disable-next-line @next/next/no-img-element
                              <img src={c.latestRenderThumbnailUrl} alt="" className="h-full w-full object-cover" />
                            )}
                            {!rendered && (
                              <span className="absolute inset-0 flex items-center justify-center text-xs text-muted-foreground">
                                Not rendered yet
                              </span>
                            )}
                          </span>
                        )}
                        <span className="block space-y-0.5 border-t border-border px-2.5 py-2">
                          <span className="flex items-center gap-1.5">
                            <span className="rounded border border-border px-1 font-mono text-[11px] text-muted-foreground">
                              {c.displayId}
                            </span>
                            <span
                              aria-hidden
                              className={cn('h-1.5 w-1.5 rounded-full', c.state === 'READY' ? 'bg-status-done' : 'bg-status-draft')}
                            />
                            <span className="text-[11px] text-muted-foreground">{c.state === 'READY' ? 'Ready' : 'Draft'}</span>
                          </span>
                          <span className="block truncate text-[13px] text-foreground">{c.name || c.headline || 'Untitled'}</span>
                          <span className="block truncate text-[11px] text-muted-foreground">
                            {kindLabel(c.kind)}
                            {c.kind === 'CAROUSEL' && slideCount(c) > 0 ? ` · ${slideCount(c)} slides` : ''}
                            {sizesLabel(c) ? ` · ${sizesLabel(c)}` : ''}
                          </span>
                        </span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            )}
          </div>
        </div>

        <aside
          aria-label="What each destination gets"
          className="hidden w-[300px] shrink-0 flex-col gap-3 overflow-y-auto rounded-[10px] border border-border bg-surface-2 p-4 md:flex"
          data-testid="creatives-preview"
        >
          <h3 className="text-sm font-semibold text-foreground">What each destination gets</h3>
          {!selected ? (
            <p className="text-sm text-muted-foreground">Pick a Creative to see which size each destination will use.</p>
          ) : detail?.status === 'ready' ? (
            <>
              <p className="text-xs text-muted-foreground">
                <span className="font-mono">{selected.displayId}</span> · {creativeSummary(selected)}
              </p>
              {destinations.length === 0 ? (
                <div className="space-y-2 text-sm text-muted-foreground">
                  <p>No destinations picked yet. This Creative comes in:</p>
                  <ul className="space-y-1">
                    {groups.map((g) => (
                      <li key={g.placementKey} className="flex items-center justify-between">
                        <span className="font-mono text-foreground">{placementLabel(g.placementKey)}</span>
                        <span>
                          {g.frames.length} {g.frames.length === 1 ? 'file' : 'files'}
                        </span>
                      </li>
                    ))}
                  </ul>
                  <p>Each destination you add then gets its matching size.</p>
                </div>
              ) : (
                <ul className="space-y-3">
                  {destinations.map((d, i) => {
                    const result = placementFor(selected.kind ?? 'STILL', groups, d)
                    return (
                      <li key={`${d.platform}-${d.format}-${d.label}-${i}`} className="space-y-1.5">
                        <div className="flex items-center gap-2 text-sm">
                          <PlatformIcon platform={d.platform} />
                          <span className="truncate font-medium text-foreground">{d.label}</span>
                          {'reason' in result ? null : (
                            <span className="ml-auto rounded border border-border px-1 font-mono text-[11px] text-foreground">
                              {placementChip(result)}
                            </span>
                          )}
                        </div>
                        {'reason' in result ? (
                          <p className="flex items-start gap-1.5 text-[13px] text-status-progress" data-testid="creative-warning">
                            <AlertTriangleIcon className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                            <span>{result.reason}</span>
                          </p>
                        ) : (
                          <FrameStrip frames={result.frames} />
                        )}
                      </li>
                    )
                  })}
                </ul>
              )}
            </>
          ) : detail?.status === 'preparing' ? (
            <div className="space-y-2" role="status">
              <p className="text-sm text-muted-foreground">Preparing for posting…</p>
              <Skeleton className="h-24 w-full" />
            </div>
          ) : detail?.status === 'loading' || detail == null ? (
            <Skeleton className="h-24 w-full" />
          ) : (
            <Alert variant={detail.status === 'error' ? 'destructive' : 'info'}>{detail.message}</Alert>
          )}
        </aside>
      </div>

      <div className="mt-4 flex shrink-0 flex-wrap items-center gap-3 border-t border-border pt-3">
        <Checkbox
          checked={useCaption}
          onCheckedChange={setUseCaption}
          label="Use the Creative’s caption"
          description={captionEmpty ? undefined : 'This would replace the caption you have already written.'}
        />
        <div className="ml-auto flex items-center gap-2">
          <Button type="button" variant="outline" size="sm" onClick={onCancel}>
            Cancel
          </Button>
          <Button
            type="button"
            size="sm"
            disabled={!canUse}
            onClick={() => {
              if (!selected || detail?.status !== 'ready') return
              onUse({ creative: selected, renderId: detail.renderId, frames: detail.frames, useCaption })
            }}
          >
            {selected ? `Use Creative ${selected.displayId}` : 'Use Creative'}
          </Button>
        </div>
      </div>
    </div>
  )
}

const STRIP_MAX = 6

/** A small row of the frames a destination gets, in publish order. */
/** width / height of a frame: its real pixels, else the ratio in its placement key (e.g. "ig_feed_4x5"). */
function frameRatio(f: CreativeRenderFrame): number {
  if (f.width > 0 && f.height > 0) return f.width / f.height
  const m = /(\d+)[x:_-](\d+)\s*$/.exec(f.placementKey ?? '')
  if (m && Number(m[1]) > 0 && Number(m[2]) > 0) return Number(m[1]) / Number(m[2])
  return 4 / 5
}

export function FrameStrip({ frames }: { frames: CreativeRenderFrame[] }) {
  const shown = frames.slice(0, STRIP_MAX)
  const extra = frames.length - shown.length
  return (
    <div className="flex flex-wrap items-center gap-1" data-testid="frame-strip">
      {shown.map((f) => {
        const src = (f.contentType ?? '').startsWith('video/') ? f.posterUrl : f.url
        return (
          <span
            key={f.id}
            data-testid="frame-thumb"
            style={{ height: 40, width: Math.round(40 * frameRatio(f)) }}
            className="shrink-0 overflow-hidden rounded bg-surface-3"
          >
            {src && (
              // eslint-disable-next-line @next/next/no-img-element
              <img src={src} alt="" className="h-full w-full object-cover" />
            )}
          </span>
        )
      })}
      {extra > 0 && <span className="text-[11px] text-muted-foreground">+{extra}</span>}
    </div>
  )
}
