'use client'

// CAROUSEL ("Finished images") mode's right-column section: a placement switcher, a stepped viewer
// for the active placement's slides, and the ordered slide list (reorder, remove, per-slide alt text).

import { useRef, useState, type KeyboardEvent } from 'react'
import { ArrowDownIcon, ArrowUpIcon, ChevronLeftIcon, ChevronRightIcon, GripVerticalIcon, PlusIcon, XIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Tabs } from '@/components/ui/tabs'
import {
  aspectMismatch,
  CAROUSEL_PLACEMENT_KEYS,
  CAROUSEL_PLACEMENT_LABEL,
  primaryCarouselPlacement,
} from './carouselSlides'
import type { CreativePhoto } from './types'

const SWIPE_THRESHOLD_PX = 40

export interface CarouselSlidesSectionProps {
  slides: Record<string, string[]>
  slideAltText: string[]
  mediaById: Map<string, CreativePhoto>
  canManage: boolean
  /** Opens the multi-select picker for this placement; the parent appends the picked ids. */
  onAddImages: (placementKey: string) => void
  onChange: (next: { slides: Record<string, string[]>; slideAltText: string[] }) => void
  /** Server 422 messages for slides / slideAltText, shown as-is. */
  violations?: string[]
}

export function CarouselSlidesSection({
  slides,
  slideAltText,
  mediaById,
  canManage,
  onAddImages,
  onChange,
  violations = [],
}: CarouselSlidesSectionProps) {
  const [active, setActive] = useState<string>(() => primaryCarouselPlacement(slides) ?? '4x5')
  const [indexByKey, setIndexByKey] = useState<Record<string, number>>({})
  const [dragFrom, setDragFrom] = useState<number | null>(null)
  const swipeStartX = useRef<number | null>(null)

  const ids = slides[active] ?? []
  const index = Math.min(indexByKey[active] ?? 0, Math.max(ids.length - 1, 0))
  const setIndex = (i: number) => setIndexByKey((prev) => ({ ...prev, [active]: Math.max(0, Math.min(ids.length - 1, i)) }))
  const current = ids[index] ? mediaById.get(ids[index]) : undefined

  function commit(nextIds: string[], nextAlt: string[]) {
    const next = { ...slides }
    if (nextIds.length) next[active] = nextIds
    else delete next[active]
    onChange({ slides: next, slideAltText: nextAlt })
  }

  function move(from: number, to: number) {
    if (to < 0 || to >= ids.length || from === to) return
    const nextIds = [...ids]
    const [id] = nextIds.splice(from, 1)
    nextIds.splice(to, 0, id)
    // The alt text travels with its slide.
    const alt = paddedAlt()
    const [a] = alt.splice(from, 1)
    alt.splice(to, 0, a)
    commit(nextIds, alt)
    setIndex(to)
  }

  // slideAltText is shared by every placement, so it can be longer than this placement's list: keep the
  // entries past the end (another placement's slides) instead of truncating them away.
  function paddedAlt(): string[] {
    return Array.from({ length: Math.max(ids.length, slideAltText.length) }, (_, k) => slideAltText[k] ?? '')
  }

  function remove(i: number) {
    const alt = paddedAlt()
    alt.splice(i, 1)
    commit(ids.filter((_, k) => k !== i), alt)
  }

  function setAlt(i: number, value: string) {
    const alt = paddedAlt()
    alt[i] = value
    onChange({ slides, slideAltText: alt })
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'ArrowLeft') {
      e.preventDefault()
      setIndex(index - 1)
    } else if (e.key === 'ArrowRight') {
      e.preventDefault()
      setIndex(index + 1)
    }
  }

  return (
    <Card className="space-y-4 p-4" data-testid="carousel-slides-section">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Finished images</h3>

      <Tabs
        ariaLabel="Placement"
        value={active}
        onValueChange={setActive}
        items={CAROUSEL_PLACEMENT_KEYS.map((k) => ({
          value: k,
          label: `${CAROUSEL_PLACEMENT_LABEL[k]}${slides[k]?.length ? ` (${slides[k].length})` : ''}`,
        }))}
      />

      {ids.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No images for {CAROUSEL_PLACEMENT_LABEL[active]} yet.
          {active === 'default' ? ' Default images serve every placement without its own list.' : ''}
        </p>
      ) : (
        <div
          tabIndex={0}
          role="group"
          aria-roledescription="carousel"
          aria-label={`${CAROUSEL_PLACEMENT_LABEL[active]} slides`}
          data-testid="carousel-viewer"
          onKeyDown={onKeyDown}
          onPointerDown={(e) => {
            swipeStartX.current = e.clientX
          }}
          onPointerUp={(e) => {
            const start = swipeStartX.current
            swipeStartX.current = null
            if (start == null) return
            const dx = e.clientX - start
            if (dx <= -SWIPE_THRESHOLD_PX) setIndex(index + 1)
            else if (dx >= SWIPE_THRESHOLD_PX) setIndex(index - 1)
          }}
          className="space-y-2 rounded-md focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <div className="flex justify-center rounded-md border border-border bg-surface-3 p-2">
            {current?.url ? (
              // eslint-disable-next-line @next/next/no-img-element
              <img
                src={current.url}
                alt={slideAltText[index] || current.label || `Slide ${index + 1}`}
                draggable={false}
                data-testid="carousel-viewer-image"
                className="max-h-96 max-w-full select-none object-contain"
                style={current.width && current.height ? { aspectRatio: current.width / current.height } : undefined}
              />
            ) : (
              <span className="flex h-40 items-center text-sm text-muted-foreground">Image unavailable</span>
            )}
          </div>
          <div className="flex items-center justify-center gap-2">
            <Button variant="ghost" size="sm" disabled={index === 0} onClick={() => setIndex(index - 1)} aria-label="Previous slide">
              <ChevronLeftIcon className="h-4 w-4" aria-hidden />
            </Button>
            <span className="text-xs text-muted-foreground" data-testid="carousel-position">
              {index + 1} / {ids.length}
            </span>
            <Button
              variant="ghost"
              size="sm"
              disabled={index >= ids.length - 1}
              onClick={() => setIndex(index + 1)}
              aria-label="Next slide"
            >
              <ChevronRightIcon className="h-4 w-4" aria-hidden />
            </Button>
          </div>
          {ids.length <= 12 && (
            <div className="flex justify-center gap-1.5" aria-hidden>
              {ids.map((id, i) => (
                <span
                  key={`${id}-${i}`}
                  className={`h-1.5 w-1.5 rounded-full ${i === index ? 'bg-primary' : 'bg-border-strong'}`}
                />
              ))}
            </div>
          )}
        </div>
      )}

      {ids.length > 0 && (
        <ol className="space-y-2" aria-label="Slide order">
          {ids.map((id, i) => {
            const media = mediaById.get(id)
            const mismatch = aspectMismatch(active, media?.width, media?.height)
            return (
              <li
                key={`${id}-${i}`}
                data-testid={`slide-row-${i}`}
                draggable={canManage}
                onDragStart={() => setDragFrom(i)}
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => {
                  e.preventDefault()
                  if (dragFrom !== null) move(dragFrom, i)
                  setDragFrom(null)
                }}
                onDragEnd={() => setDragFrom(null)}
                className="space-y-1 rounded-md border border-border p-2"
              >
                <div className="flex items-center gap-2">
                  <GripVerticalIcon className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="w-5 shrink-0 text-xs text-muted-foreground">{i + 1}</span>
                  <button
                    type="button"
                    onClick={() => setIndex(i)}
                    className="h-10 w-10 shrink-0 overflow-hidden rounded bg-surface-3"
                    aria-label={`Show slide ${i + 1}`}
                  >
                    {media?.url && (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={media.url} alt="" draggable={false} className="h-full w-full object-cover" />
                    )}
                  </button>
                  <span className="min-w-0 flex-1 truncate text-sm" title={media?.label ?? undefined}>
                    {media?.label || 'Untitled'}
                  </span>
                  {canManage && (
                    <div className="flex shrink-0 items-center">
                      <Button variant="ghost" size="sm" disabled={i === 0} onClick={() => move(i, i - 1)} aria-label={`Move slide ${i + 1} up`}>
                        <ArrowUpIcon className="h-3.5 w-3.5" aria-hidden />
                      </Button>
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={i === ids.length - 1}
                        onClick={() => move(i, i + 1)}
                        aria-label={`Move slide ${i + 1} down`}
                      >
                        <ArrowDownIcon className="h-3.5 w-3.5" aria-hidden />
                      </Button>
                      <Button variant="ghost" size="sm" onClick={() => remove(i)} aria-label={`Remove slide ${i + 1}`}>
                        <XIcon className="h-3.5 w-3.5" aria-hidden />
                      </Button>
                    </div>
                  )}
                </div>
                <Input
                  value={slideAltText[i] ?? ''}
                  onChange={(e) => setAlt(i, e.target.value)}
                  placeholder="Alt text for this slide"
                  aria-label={`Alt text for slide ${i + 1}`}
                />
                {mismatch && (
                  <p className="text-xs text-status-progress" data-testid={`slide-aspect-warning-${i}`}>
                    {media?.width}×{media?.height} doesn&apos;t match {CAROUSEL_PLACEMENT_LABEL[active]} (within 2%); the server will
                    reject it.
                  </p>
                )}
              </li>
            )
          })}
        </ol>
      )}

      {violations.map((m) => (
        <p key={m} className="text-xs text-destructive">
          {m}
        </p>
      ))}

      {canManage && (
        <Button variant="outline" size="sm" onClick={() => onAddImages(active)}>
          <PlusIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
          Add images
        </Button>
      )}
    </Card>
  )
}
