'use client'

// COND-24 T2: the Creative editor — form on the left, every enabled placement rendering live on the
// right via @cliangdev/creative-render. No network call happens on a keystroke (AC-P0-2.2): the
// preview boards are re-rendered locally from the in-memory form, and the copy-rule feedback runs
// the same brand kit rules the backend's CreativeValidator enforces at save time.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import {
  ChevronDownIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  Maximize2Icon,
  MoreHorizontalIcon,
  PauseIcon,
  PlayIcon,
  PlusIcon,
  XIcon,
} from 'lucide-react'
import { attachFocalDrag, enabledPlacements, mountBoard } from '@cliangdev/creative-render/mount'
import { checkCreativeCopy } from '@cliangdev/creative-render/copy-rules'
import { placements as renderPlacements } from '@cliangdev/creative-render/placements'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { Slider } from '@/components/ui/slider'
import { StatusBadge } from '@/components/ui/status-badge'
import { Textarea } from '@/components/ui/textarea'
import { useToast } from '@/components/ui/toast'
import { Can } from '@/components/auth/Can'
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage, type ApiError } from '@/lib/api'
import { formatDuration } from '@/lib/format'
import { cn } from '@/lib/utils'
import { brandKitToBrand, listBrandKits, type BrandKit } from '@/components/marketing/brand/types'
import { CLIP_PLACEMENT_KEYS, nearestAspectPlacement } from '@/components/marketing/creatives/clipPlacement'
import { CreativePerformancePanel } from '@/components/marketing/creatives/CreativePerformancePanel'
import { FullSizeViewer } from '@/components/marketing/creatives/FullSizeViewer'
import { MediaPicker } from '@/components/marketing/creatives/MediaPicker'
import { PhotoPicker } from '@/components/marketing/creatives/PhotoPicker'
import { RendersPanel } from '@/components/marketing/creatives/RendersPanel'
import { UseInPostDialog } from '@/components/marketing/creatives/UseInPostDialog'
import {
  createCreativeVariant,
  deleteCreative,
  getCreative,
  getCreativePhoto,
  getCreativeReadiness,
  getCreativeRegistry,
  listCreativePhotos,
  patchCreative,
  type AudioSource,
  type Creative,
  type CreativeKind,
  type CreativeLockup,
  type CreativePhoto,
  type CreativeRegistry,
  type CreativeReadiness,
  type CreativeRender,
  type CreativeState,
  type CreativeTheme,
  type MotionBackgroundMotion,
  type MotionBackgroundSource,
  type MotionPreset,
  type SequenceBeat,
  type SequenceKind,
} from '@/components/marketing/creatives/types'
import type { RenderCreative } from '@/components/marketing/creatives/renderTypes'

// attachFocalDrag ships with no .d.ts (see renderTypes.ts's header on the same limitation for
// mountBoard) — TS's plain-JS inference of its destructured third parameter only picks up
// `threshold` (it has a default value); `onClick` has none, so TS drops it from the inferred type.
// This local mirror restores it rather than casting every call site to `any`.
type AttachFocalDrag = (
  handle: ReturnType<typeof mountBoard>,
  onChange: (value: string) => void,
  options?: { onClick?: (e: PointerEvent) => void; threshold?: number },
) => { detach: () => void }
const attachFocalDragTyped = attachFocalDrag as unknown as AttachFocalDrag

/** MOTION only. `clipStartSec` is a text input (like the layout-override maps below) so the field can
 *  sit blank while typing without forcing it to 0. */
interface MotionFormState {
  preset: MotionPreset
  durationSec: number
  backgroundSource: MotionBackgroundSource
  backgroundMotion: MotionBackgroundMotion
  clipMediaId: string | null
  clipStartSec: string
  endCard: boolean
}

/** MOTION only. */
interface AudioFormState {
  source: AudioSource
  trackId: string | null
  volume: number
  fadeOutSec: number
}

interface FormState {
  brandKitId: string
  name: string
  state: CreativeState
  kind: CreativeKind
  layout: string
  theme: CreativeTheme
  photoId: string | null
  focalOverride: Record<string, string>
  headline: string
  body: string
  caption: string
  altText: string
  /** CLIP only — see the `Creative.clipMedia` doc comment in types.ts. */
  clipMedia: Record<string, string>
  motion: MotionFormState
  audio: AudioFormState
  placements: string[]
  sequenceKind: SequenceKind | null
  sequence: SequenceBeat[]
  typeOverrides: Record<string, number[]>
  lockup: CreativeLockup
  // Per-placement text inputs (not numbers): an empty string means "inherit the layout's default",
  // distinct from "0", so the fields can be blank without forcing every placement to 0px.
  layoutOverrideBand: Record<string, string>
  layoutOverridePadBottom: Record<string, string>
}

function toStringMap(map: Record<string, number> | null | undefined): Record<string, string> {
  const out: Record<string, string> = {}
  for (const [k, v] of Object.entries(map ?? {})) out[k] = String(v)
  return out
}

/** The inverse of {@link toStringMap} — blank or non-numeric entries are dropped, not coerced to 0. */
function toIntMap(map: Record<string, string>): Record<string, number> {
  const out: Record<string, number> = {}
  for (const [k, v] of Object.entries(map)) {
    if (v.trim() === '') continue
    const n = Math.round(Number(v))
    if (Number.isFinite(n)) out[k] = n
  }
  return out
}

/** Defaults match the motion contract exactly (see CreativeMotion's doc comment in types.ts) — a
 *  freshly-switched-to-Motion Creative (no `creative.motion` yet) shows these, not blanks. */
function toMotionForm(motion: Creative['motion']): MotionFormState {
  return {
    preset: motion?.preset ?? 'fade-up',
    durationSec: motion?.durationSec ?? 8,
    backgroundSource: motion?.background?.source ?? 'photo',
    backgroundMotion: motion?.background?.motion ?? 'zoom-in',
    clipMediaId: motion?.background?.clipMediaId ?? null,
    clipStartSec: String(motion?.background?.clipStartSec ?? 0),
    endCard: motion?.endCard ?? true,
  }
}

/** See {@link toMotionForm} — `source` defaults to "none" here; the contract's data-dependent default
 *  ("clip" when the background clip has sound) is instead expressed by disabling the "Clip sound"
 *  option until it applies (see the Audio panel below), not by guessing a selection for the marketer. */
function toAudioForm(audio: Creative['audio']): AudioFormState {
  return {
    source: audio?.source ?? 'none',
    trackId: audio?.trackId ?? null,
    volume: audio?.volume ?? 0.8,
    fadeOutSec: audio?.fadeOutSec ?? 1,
  }
}

function toForm(creative: Creative): FormState {
  return {
    brandKitId: creative.brandKitId,
    name: creative.name ?? '',
    state: creative.state,
    kind: creative.kind ?? 'STILL',
    layout: creative.layout,
    theme: creative.theme,
    photoId: creative.photoId ?? null,
    focalOverride: { ...(creative.focalOverride ?? {}) },
    headline: creative.headline ?? '',
    body: creative.body ?? '',
    caption: creative.caption ?? '',
    altText: creative.altText ?? '',
    clipMedia: { ...(creative.clipMedia ?? {}) },
    motion: toMotionForm(creative.motion),
    audio: toAudioForm(creative.audio),
    placements: [...creative.placements],
    sequenceKind: creative.sequenceKind ?? null,
    sequence: creative.sequence.map((b) => ({ ...b })),
    typeOverrides: { ...creative.typeOverrides },
    lockup: creative.lockup,
    layoutOverrideBand: toStringMap(creative.layoutOverrides?.band),
    layoutOverridePadBottom: toStringMap(creative.layoutOverrides?.padBottom),
  }
}

function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

/** m:ss, for the transport's current-time/duration readouts. */
function formatClock(tSec: number): string {
  const t = Math.max(0, Math.round(tSec))
  const m = Math.floor(t / 60)
  const s = t % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

function violationsFor(err: ApiError | null, field: string): string[] {
  return (err?.violations ?? []).filter((v) => v.field === field).map((v) => v.message)
}

/** The Photo button's text — a raw UUID is never useful to a reviewer, so an unlabelled photo (one
 *  uploaded before PhotoPicker started defaulting the label to the file name) falls back to its
 *  content type instead. */
function photoDisplayName(photo: CreativePhoto | null): string {
  if (!photo) return 'Choose a photo…'
  if (photo.label) return photo.label
  const ext = photo.contentType?.split('/')[1]
  return ext ? `Untitled ${ext} photo` : 'Untitled photo'
}

const STATE_HUE: Record<CreativeState, 'gray' | 'teal' | 'slate'> = {
  DRAFT: 'gray',
  READY: 'teal',
  ARCHIVED: 'slate',
}

/** MOTION preview only — wires one PlacementBoard's mount handle into the shared transport below the
 *  boards (see MotionTransport). `onTime` is set on exactly one ("master") board per contract #2: the
 *  transport's current-time readout and scrub bar follow that one board's own play() clock, and every
 *  board (master included) is told to play/pause and to seek on a manual scrub. */
interface MotionControl {
  playing: boolean
  scrub: { t: number; nonce: number } | null
  onTime?: (tSec: number) => void
}

function PlacementBoard({
  placementKey,
  creative,
  brand,
  sequenceIndex,
  onFocalChange,
  draggable,
  onOpen,
  motionControl,
}: {
  placementKey: string
  creative: RenderCreative
  brand: ReturnType<typeof brandKitToBrand>
  sequenceIndex: number
  onFocalChange: (placementKey: string, value: string) => void
  /** Readers can't save changes anyway — skip wiring the focal-drag interaction for them (they can
   *  still click to open the full-size viewer; see the plain click listener below). */
  draggable: boolean
  /** Opens the full-size viewer for this placement — wired to both a plain click on the frame and
   *  the corner "expand" affordance. */
  onOpen: (placementKey: string) => void
  /** Present only in Motion mode (`creative.motion` set) — see {@link MotionControl}. */
  motionControl?: MotionControl
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const handleRef = useRef<ReturnType<typeof mountBoard> | null>(null)
  const onFocalChangeRef = useRef(onFocalChange)
  const onOpenRef = useRef(onOpen)
  const motionControlRef = useRef(motionControl)
  useEffect(() => {
    onFocalChangeRef.current = onFocalChange
  }, [onFocalChange])
  useEffect(() => {
    onOpenRef.current = onOpen
  }, [onOpen])
  useEffect(() => {
    motionControlRef.current = motionControl
  }, [motionControl])

  useEffect(() => {
    if (!containerRef.current) return
    const handle = mountBoard(containerRef.current, { creative, brand, placementKey, sequenceIndex })
    handleRef.current = handle
    // Draggable frames get the full focal-drag interaction (a plain click still opens the viewer,
    // via attachFocalDrag's own click/drag distinction — see conductor-creative/mount.js). Readers
    // can't drag (nothing to save), but a plain click must still open the viewer for them.
    const drag = draggable
      ? attachFocalDragTyped(handle, (value: string) => onFocalChangeRef.current(placementKey, value), {
          onClick: () => onOpenRef.current(placementKey),
        })
      : (() => {
          const onClick = () => onOpenRef.current(placementKey)
          handle.shell.addEventListener('click', onClick)
          return { detach: () => handle.shell.removeEventListener('click', onClick) }
        })()
    // The "master" board (the one carrying onTime) reports its own play() clock back to the shared
    // transport — see MotionControl's doc comment. A no-op subscribe for a STILL/CLIP board (no
    // motionControl at all) or a non-master Motion board (no onTime).
    const unsubTime = motionControlRef.current?.onTime ? handle.onTime(motionControlRef.current.onTime) : undefined
    // A remount (placement change, or `draggable` flipping once permissions load) builds a fresh handle
    // the play effect below won't revisit, so a board that should be playing starts itself here.
    if (motionControlRef.current?.playing) handle.play()
    return () => {
      unsubTime?.()
      drag.detach()
      handle.destroy()
      handleRef.current = null
    }
    // Mount once per placement; updates below patch the live board instead of re-mounting it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placementKey, draggable])

  useEffect(() => {
    handleRef.current?.update({ creative, brand, sequenceIndex })
  }, [creative, brand, sequenceIndex])

  // Play/pause follows the shared transport's `playing` flag — this effect also fires right after the
  // mount effect above on first render (not only on later changes), so a Motion board that starts
  // already `playing` (autoplay, unless prefers-reduced-motion) begins without a second, redundant
  // play() call from the mount effect itself.
  useEffect(() => {
    if (!handleRef.current || !motionControl) return
    if (motionControl.playing) handleRef.current.play()
    else handleRef.current.pause()
    // Also keyed on motionControl appearing: the board mounts before the creative loads (as a STILL
    // board with no motionControl), so on a Motion creative `playing` is already true by the time the
    // control arrives and would never change to trigger the first play().
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [motionControl?.playing, motionControl != null])

  // A manual scrub (from the shared transport's scrub bar) seeks every board, this one included —
  // keyed on `nonce` so scrubbing to the same second twice in a row still re-seeks.
  useEffect(() => {
    if (!handleRef.current || !motionControl?.scrub) return
    void handleRef.current.seek(motionControl.scrub.t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [motionControl?.scrub?.nonce])

  const dims = renderPlacements[placementKey as keyof typeof renderPlacements] as { w: number; h: number; label: string } | undefined
  const height = 260
  const width = dims ? Math.round((height * dims.w) / dims.h) : height

  return (
    <div className="group relative shrink-0 space-y-1">
      <div
        ref={containerRef}
        data-testid={`placement-board-${placementKey}`}
        className="relative overflow-hidden rounded-md bg-surface-3"
        style={{ width, height }}
      />
      <button
        type="button"
        onClick={() => onOpen(placementKey)}
        aria-label={`View ${dims?.label ?? placementKey} full size`}
        className="absolute right-1 top-1 z-10 rounded-md bg-surface/90 p-1 text-muted-foreground opacity-0 shadow-sm transition-opacity hover:text-foreground focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring group-hover:opacity-100 group-focus-within:opacity-100"
      >
        <Maximize2Icon className="h-3.5 w-3.5" aria-hidden />
      </button>
      <p className="text-center text-[11px] text-muted-foreground">{dims?.label ?? placementKey}</p>
    </div>
  )
}

/** The dims of a placement — pulled out of `renderPlacements` once so both the small board and the
 *  full-size viewer size themselves off the same registry entry. */
function placementDims(placementKey: string): { w: number; h: number; label: string } | undefined {
  return renderPlacements[placementKey as keyof typeof renderPlacements] as { w: number; h: number; label: string } | undefined
}

/** Fits `dims`' aspect ratio into ~90% of the viewport (minus a little room for the viewer's own
 *  chrome), scaling up past the placement's true pixel size when the viewport allows — the point is
 *  to see it big, not to cap it at 1:1. */
function fitToViewport(dims: { w: number; h: number } | undefined): { width: number; height: number } {
  if (!dims) return { width: 320, height: 320 }
  if (typeof window === 'undefined') return { width: dims.w, height: dims.h }
  const maxW = window.innerWidth * 0.8
  const maxH = window.innerHeight * 0.7
  const scale = Math.max(0.01, Math.min(maxW / dims.w, maxH / dims.h))
  return { width: Math.round(dims.w * scale), height: Math.round(dims.h * scale) }
}

/** The full-size viewer's board — a fresh `mountBoard`, sized to fill the viewer, with no drag
 *  interaction wired up (view only). */
function PlacementViewerBoard({
  placementKey,
  creative,
  brand,
  sequenceIndex,
}: {
  placementKey: string
  creative: RenderCreative
  brand: ReturnType<typeof brandKitToBrand>
  sequenceIndex: number
}) {
  const containerRef = useRef<HTMLDivElement>(null)
  const handleRef = useRef<ReturnType<typeof mountBoard> | null>(null)
  const dims = placementDims(placementKey)
  const [size, setSize] = useState(() => fitToViewport(dims))

  useEffect(() => {
    function onResize() {
      setSize(fitToViewport(dims))
    }
    onResize()
    window.addEventListener('resize', onResize)
    return () => window.removeEventListener('resize', onResize)
  }, [dims?.w, dims?.h])

  useEffect(() => {
    if (!containerRef.current) return
    const handle = mountBoard(containerRef.current, { creative, brand, placementKey, sequenceIndex })
    handleRef.current = handle
    // The full-size viewer plays the motion too (contract #2) — same reduced-motion default as the
    // small preview boards, and a no-op for a STILL/CLIP creative (no `creative.motion`).
    if (creative.motion && !prefersReducedMotion()) handle.play()
    return () => {
      handle.destroy()
      handleRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [placementKey])

  useEffect(() => {
    handleRef.current?.update({ creative, brand, sequenceIndex })
  }, [creative, brand, sequenceIndex])

  // Resizing the container doesn't re-fit itself — nudge mountBoard to redraw at the new size.
  useEffect(() => {
    handleRef.current?.update({})
  }, [size.width, size.height])

  return (
    <div
      ref={containerRef}
      data-testid={`placement-viewer-board-${placementKey}`}
      className="relative overflow-hidden rounded-md bg-surface-3"
      style={{ width: size.width, height: size.height }}
    />
  )
}

/** The shared transport under the Motion mode preview boards (contract #2): one play/pause button,
 *  one scrub bar driving every board via {@link MotionControl}, and a loop toggle. Audio is deliberately
 *  absent here — see the "Audio plays in the rendered video." label CreativeEditor renders alongside
 *  this (checkCreativeCopy-adjacent copy, not this component's job to own). */
function MotionTransport({
  durationSec,
  playing,
  onTogglePlay,
  currentT,
  onScrub,
  loop,
  onLoopChange,
}: {
  durationSec: number
  playing: boolean
  onTogglePlay: () => void
  currentT: number
  onScrub: (tSec: number) => void
  loop: boolean
  onLoopChange: (loop: boolean) => void
}) {
  return (
    <div
      data-testid="motion-transport"
      className="flex flex-wrap items-center gap-3 rounded-md border border-border bg-surface-2 px-3 py-2"
    >
      <Button type="button" variant="ghost" size="sm" onClick={onTogglePlay} aria-label={playing ? 'Pause' : 'Play'}>
        {playing ? <PauseIcon className="h-4 w-4" aria-hidden /> : <PlayIcon className="h-4 w-4" aria-hidden />}
      </Button>
      <span className="w-9 shrink-0 text-right text-xs tabular-nums text-muted-foreground">
        {formatClock(currentT)}
      </span>
      <Slider
        aria-label="Scrub"
        min={0}
        max={Math.max(durationSec, 0.1)}
        step={0.1}
        value={Math.min(currentT, durationSec)}
        onValueChange={onScrub}
        className="min-w-[8rem] flex-1"
      />
      <span className="w-9 shrink-0 text-xs tabular-nums text-muted-foreground">{formatClock(durationSec)}</span>
      <Checkbox id="motion-loop" checked={loop} onCheckedChange={onLoopChange} label="Loop" />
    </div>
  )
}

/** One row of the Clip media section — the default video, or one placement's override. */
function ClipMediaRow({
  rowKey,
  rowLabel,
  servesLabel,
  media,
  canManage,
  onPick,
  onClear,
}: {
  rowKey: string
  rowLabel: string
  servesLabel: string
  media: CreativePhoto | undefined
  canManage: boolean
  onPick: () => void
  onClear: () => void
}) {
  const aspect = media?.width && media?.height ? media.width / media.height : 9 / 16
  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <Label>{rowLabel}</Label>
        {media && canManage && (
          <button
            type="button"
            onClick={onClear}
            className="text-xs text-muted-foreground hover:text-destructive"
            aria-label={`Remove ${rowLabel.toLowerCase()} video`}
          >
            Remove
          </button>
        )}
      </div>
      {media ? (
        <div className="space-y-1">
          <video
            controls
            muted
            playsInline
            poster={media.posterUrl ?? undefined}
            src={media.url ?? undefined}
            data-testid={`clip-media-video-${rowKey}`}
            className="max-h-52 rounded-md border border-border bg-surface-3"
            style={{ aspectRatio: aspect }}
          />
          <p className="text-xs text-muted-foreground">
            {media.width && media.height ? `${media.width}×${media.height} · ` : ''}
            {media.durationSeconds != null ? formatDuration(media.durationSeconds) : '—'} · {servesLabel}
          </p>
        </div>
      ) : (
        <button
          type="button"
          onClick={onPick}
          disabled={!canManage}
          className="flex h-20 w-full items-center justify-center rounded-md border border-dashed border-border-strong text-sm text-muted-foreground hover:bg-surface-3 disabled:cursor-not-allowed disabled:opacity-50"
        >
          Choose a video…
        </button>
      )}
    </div>
  )
}

/** CLIP mode's right-column section — replaces the STILL/MOTION "Live preview" boards with the
 *  default video plus each optional per-placement override, matching video-contract.md's "Web"
 *  section: "a default video + optional per-placement videos ... each a `<video controls muted
 *  playsInline poster>` with its ratio, duration and which placement(s) it will serve". */
function ClipMediaSection({
  form,
  update,
  registry,
  mediaById,
  canManage,
  onPick,
}: {
  form: FormState
  update: <K extends keyof FormState>(key: K, value: FormState[K]) => void
  registry: CreativeRegistry
  mediaById: Map<string, CreativePhoto>
  canManage: boolean
  onPick: (rowKey: string) => void
}) {
  const defaultMedia = form.clipMedia.default ? mediaById.get(form.clipMedia.default) : undefined
  const defaultNearest = defaultMedia ? nearestAspectPlacement(registry.placements, defaultMedia.width, defaultMedia.height) : null
  const defaultServes = defaultNearest
    ? `Serves ${registry.placements.find((p) => p.key === defaultNearest)?.label ?? defaultNearest} by default (nearest match)`
    : 'Serves every placement without its own video'

  function clearRow(rowKey: string) {
    const next = { ...form.clipMedia }
    delete next[rowKey]
    update('clipMedia', next)
  }

  return (
    <Card className="space-y-4 p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Clip media</h3>
      <ClipMediaRow
        rowKey="default"
        rowLabel="Default (all placements)"
        servesLabel={defaultServes}
        media={defaultMedia}
        canManage={canManage}
        onPick={() => onPick('default')}
        onClear={() => clearRow('default')}
      />
      {CLIP_PLACEMENT_KEYS.map((key) => {
        const media = form.clipMedia[key] ? mediaById.get(form.clipMedia[key]) : undefined
        const label = registry.placements.find((p) => p.key === key)?.label ?? key
        return (
          <ClipMediaRow
            key={key}
            rowKey={key}
            rowLabel={label}
            servesLabel={`Serves ${label}`}
            media={media}
            canManage={canManage}
            onPick={() => onPick(key)}
            onClear={() => clearRow(key)}
          />
        )
      })}
    </Card>
  )
}

export interface CreativeEditorProps {
  projectId: string
  creativeId: string
  token: string
}

export function CreativeEditor({ projectId, creativeId, token }: CreativeEditorProps) {
  const router = useRouter()
  const { showToast } = useToast()
  const canManage = useCan('creative.manage')

  const [creative, setCreative] = useState<Creative | null>(null)
  const [form, setForm] = useState<FormState | null>(null)
  const [kits, setKits] = useState<BrandKit[] | null>(null)
  const [registry, setRegistry] = useState<CreativeRegistry | null>(null)
  const [photo, setPhoto] = useState<CreativePhoto | null>(null)
  const [photos, setPhotos] = useState<CreativePhoto[]>([])
  const [readiness, setReadiness] = useState<CreativeReadiness | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<ApiError | null>(null)
  const [conflict, setConflict] = useState(false)
  const [saving, setSaving] = useState(false)
  const [photoPickerOpen, setPhotoPickerOpen] = useState(false)
  // Which sequence beat's photo the shared PhotoPicker modal is targeting; null means the
  // Creative's own main photo. One modal instance is reused for both (see its onSelect below).
  const [beatPhotoPickerIndex, setBeatPhotoPickerIndex] = useState<number | null>(null)
  // CLIP mode: which clipMedia slot ('default' or a placement key) the shared video MediaPicker is
  // targeting; null means it's closed.
  const [clipMediaPickerKey, setClipMediaPickerKey] = useState<string | null>(null)
  // MOTION mode: the background clip picker (locked to VIDEO) and the audio track picker (locked to
  // AUDIO) — two separate MediaPicker instances, each open/closed on its own.
  const [motionClipPickerOpen, setMotionClipPickerOpen] = useState(false)
  const [audioTrackPickerOpen, setAudioTrackPickerOpen] = useState(false)
  // MOTION mode's shared transport (contract #2). `motionPlaying`'s initial value is the
  // prefers-reduced-motion default (start paused) — computed once, at this component's first mount,
  // which is also the earliest a Motion board could ever appear.
  const [motionPlaying, setMotionPlaying] = useState<boolean>(() => !prefersReducedMotion())
  const [motionLoop, setMotionLoop] = useState(true)
  const [motionCurrentT, setMotionCurrentT] = useState(0)
  const [motionScrub, setMotionScrub] = useState<{ t: number; nonce: number } | null>(null)
  const motionScrubNonceRef = useRef(0)
  // The master board's last-reported time — used only to detect a loop wrap-around (t drops back
  // near 0) so a "Loop" off can stop playback at the end instead of the engine's own unconditional
  // loop (see conductor-creative/mount.js's play()).
  const motionLastTRef = useRef(0)
  const motionLoopRef = useRef(motionLoop)
  useEffect(() => {
    motionLoopRef.current = motionLoop
  }, [motionLoop])
  const [variantOpen, setVariantOpen] = useState(false)
  const [variantHeadline, setVariantHeadline] = useState('')
  const [variantBusy, setVariantBusy] = useState(false)
  const [sequenceIndex, setSequenceIndex] = useState(0)
  const [latestSucceededRender, setLatestSucceededRender] = useState<CreativeRender | null>(null)
  const [useInPostOpen, setUseInPostOpen] = useState(false)
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)
  // Index into `enabledKeys` of the placement currently open in the full-size viewer, or null when
  // it's closed.
  const [viewerIndex, setViewerIndex] = useState<number | null>(null)

  const load = useCallback(async () => {
    const [loaded, kitRows, reg] = await Promise.all([
      getCreative(projectId, creativeId, token),
      listBrandKits(projectId, token),
      getCreativeRegistry(projectId, token),
    ])
    setCreative(loaded)
    setForm(toForm(loaded))
    setKits(kitRows)
    setRegistry(reg)
    setSequenceIndex(0)
    if (loaded.photoId) {
      getCreativePhoto(projectId, loaded.photoId, token).then(setPhoto).catch(() => setPhoto(null))
    } else {
      setPhoto(null)
    }
    getCreativeReadiness(projectId, creativeId, token).then(setReadiness).catch(() => setReadiness(null))
  }, [projectId, creativeId, token])

  useEffect(() => {
    load().catch((err) => setLoadError(apiErrorMessage(err, 'Could not load this Creative.')))
  }, [load])

  useEffect(() => {
    listCreativePhotos(projectId, token, true).then(setPhotos).catch(() => setPhotos([]))
  }, [projectId, token])

  useEffect(() => {
    if (!form?.photoId) {
      setPhoto(null)
      return
    }
    if (photo?.id === form.photoId) return
    let cancelled = false
    getCreativePhoto(projectId, form.photoId, token).then((p) => {
      if (!cancelled) setPhoto(p)
    })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [form?.photoId, projectId, token])

  const selectedKit = useMemo(() => kits?.find((k) => k.id === form?.brandKitId) ?? null, [kits, form?.brandKitId])
  const brand = useMemo(() => brandKitToBrand(selectedKit), [selectedKit])

  // Looked up by beat.photoId so the live preview and each beat's thumbnail can resolve a photo
  // without a per-beat network round trip — `photos` already holds the project's library
  // (including blocked ones, loaded once below) so this is a local map, not a fetch.
  const photoById = useMemo(() => {
    const map = new Map<string, CreativePhoto>()
    for (const p of photos) map.set(p.id, p)
    return map
  }, [photos])

  const renderCreative: RenderCreative | null = useMemo(() => {
    if (!form) return null
    const band = toIntMap(form.layoutOverrideBand)
    const padBottom = toIntMap(form.layoutOverridePadBottom)
    const isMotion = form.kind === 'MOTION'
    const motionClip =
      isMotion && form.motion.backgroundSource === 'clip' && form.motion.clipMediaId
        ? photoById.get(form.motion.clipMediaId)
        : undefined
    const audioTrack = isMotion && form.audio.trackId ? photoById.get(form.audio.trackId) : undefined
    return {
      layout: form.layout as RenderCreative['layout'],
      theme: form.theme,
      lockup: form.lockup,
      headline: form.headline,
      body: form.body || undefined,
      caption: form.caption || undefined,
      photoUrl: photo?.url ?? undefined,
      focal: photo?.focal,
      focalOverride: Object.keys(form.focalOverride).length ? form.focalOverride : undefined,
      placements: form.placements,
      layoutOverrides:
        Object.keys(band).length || Object.keys(padBottom).length
          ? { band: Object.keys(band).length ? band : undefined, padBottom: Object.keys(padBottom).length ? padBottom : undefined }
          : undefined,
      typeOverrides: form.typeOverrides,
      sequenceKind: form.sequenceKind ?? undefined,
      sequence: form.sequence.map((b) => {
        // A beat's own photo wins; an unset beat photo falls back to the Creative's main photo
        // (conductor-creative/README.md's "the creative shape" — the render package does not do
        // this fallback itself, so it's resolved here before the beat reaches the render package).
        const beatPhoto = b.photoId ? photoById.get(b.photoId) : undefined
        return {
          headline: b.headline ?? undefined,
          body: b.body ?? undefined,
          cta: b.cta ?? undefined,
          photoUrl: beatPhoto?.url ?? photo?.url ?? undefined,
          focal: beatPhoto?.focal ?? photo?.focal,
        }
      }),
      kind: form.kind,
      motion: isMotion
        ? {
            preset: form.motion.preset,
            durationSec: form.motion.durationSec,
            background: { source: form.motion.backgroundSource, motion: form.motion.backgroundMotion },
            endCard: form.motion.endCard,
          }
        : undefined,
      audio: isMotion
        ? {
            source: form.audio.source,
            trackUrl: audioTrack?.url ?? undefined,
            volume: form.audio.volume,
            fadeOutSec: form.audio.fadeOutSec,
          }
        : undefined,
      backgroundVideoUrl: isMotion && form.motion.backgroundSource === 'clip' ? motionClip?.url ?? undefined : undefined,
      clipStartSec: isMotion && form.motion.backgroundSource === 'clip' ? Number(form.motion.clipStartSec) || 0 : undefined,
    }
  }, [form, photo, photoById])

  const enabledKeys = useMemo(() => {
    if (!renderCreative) return []
    return enabledPlacements(renderCreative, brand.enabledPlacements ?? [], renderPlacements) as string[]
  }, [renderCreative, brand])

  const copyIssues = useMemo(() => {
    if (!form || !selectedKit) return []
    return checkCreativeCopy(
      { accentPhraseRequired: selectedKit.accentPhraseRequired, copyRules: selectedKit.copyRules },
      { headline: form.headline, body: form.body, caption: form.caption },
    ) as { id: string; field: string; message: string }[]
  }, [form, selectedKit])

  function issuesFor(field: string): string[] {
    return copyIssues.filter((i) => i.field === field).map((i) => i.message)
  }

  // The live copy-rule check and the server's 422 violations often flag the exact same rule with the
  // exact same message (the server enforces the same brand kit rules — see checkCreativeCopy above).
  // Show each distinct message once per field rather than twice.
  function messagesFor(field: string): string[] {
    const live = issuesFor(field)
    const serverOnly = violationsFor(saveError, field).filter((m) => !live.includes(m))
    return [...live, ...serverOnly]
  }

  function update<K extends keyof FormState>(key: K, value: FormState[K]) {
    setForm((prev) => (prev ? { ...prev, [key]: value } : prev))
  }

  function handleFocalChange(placementKey: string, value: string) {
    setForm((prev) =>
      prev ? { ...prev, focalOverride: { ...prev.focalOverride, [placementKey]: value } } : prev,
    )
  }

  function openPlacementViewer(placementKey: string) {
    const idx = enabledKeys.indexOf(placementKey)
    setViewerIndex(idx === -1 ? 0 : idx)
  }

  // MOTION mode's shared transport (contract #2) — see MotionTransport and PlacementBoard's
  // motionControl prop. handleMotionTime is stable (no deps) since it's handed to exactly one board
  // (the "master") at mount time and must not force a re-subscribe on every render.
  const handleMotionTime = useCallback((t: number) => {
    if (!motionLoopRef.current && t < motionLastTRef.current - 0.5 && motionLastTRef.current > 0) {
      // The engine always loops at durationSec (see mount.js's play()) — "Loop" off is a client-side
      // stop: the moment the master board wraps back near 0, pause every board right where it was.
      setMotionPlaying(false)
      motionLastTRef.current = 0
      return
    }
    motionLastTRef.current = t
    setMotionCurrentT(t)
  }, [])

  function handleMotionScrub(t: number) {
    motionScrubNonceRef.current += 1
    setMotionScrub({ t, nonce: motionScrubNonceRef.current })
    motionLastTRef.current = t
    setMotionCurrentT(t)
  }

  async function handleSave() {
    if (!form || !creative) return
    setSaving(true)
    setSaveError(null)
    setConflict(false)
    try {
      const band = toIntMap(form.layoutOverrideBand)
      const padBottom = toIntMap(form.layoutOverridePadBottom)
      const updated = await patchCreative(
        projectId,
        creativeId,
        {
          version: creative.version,
          brandKitId: form.brandKitId,
          name: form.name || undefined,
          state: form.state,
          kind: form.kind,
          layout: form.layout,
          theme: form.theme,
          photoId: form.photoId,
          focalOverride: Object.keys(form.focalOverride).length ? form.focalOverride : null,
          headline: form.headline,
          body: form.body,
          caption: form.caption,
          altText: form.altText,
          clipMedia: Object.keys(form.clipMedia).length ? form.clipMedia : null,
          motion:
            form.kind === 'MOTION'
              ? {
                  preset: form.motion.preset,
                  durationSec: form.motion.durationSec,
                  background: {
                    source: form.motion.backgroundSource,
                    motion: form.motion.backgroundMotion,
                    clipMediaId: form.motion.backgroundSource === 'clip' ? form.motion.clipMediaId : null,
                    clipStartSec: form.motion.backgroundSource === 'clip' ? Number(form.motion.clipStartSec) || 0 : null,
                  },
                  endCard: form.motion.endCard,
                }
              : null,
          audio:
            form.kind === 'MOTION'
              ? {
                  source: form.audio.source,
                  trackId: form.audio.source === 'track' ? form.audio.trackId : null,
                  volume: form.audio.volume,
                  fadeOutSec: form.audio.fadeOutSec,
                }
              : null,
          placements: form.placements,
          sequenceKind: form.sequenceKind,
          sequence: form.sequence,
          typeOverrides: form.typeOverrides,
          lockup: form.lockup,
          layoutOverrides:
            Object.keys(band).length || Object.keys(padBottom).length
              ? { band: Object.keys(band).length ? band : undefined, padBottom: Object.keys(padBottom).length ? padBottom : undefined }
              : null,
        },
        token,
      )
      setCreative(updated)
      setForm(toForm(updated))
      getCreativeReadiness(projectId, creativeId, token).then(setReadiness).catch(() => setReadiness(null))
      showToast('Creative saved')
    } catch (err) {
      const apiErr = err as ApiError
      if (apiErr.status === 409) {
        setConflict(true)
      } else {
        setSaveError(apiErr)
        showToast(apiErrorMessage(err, 'Could not save this Creative'), 'error')
      }
    } finally {
      setSaving(false)
    }
  }

  async function handleReload() {
    setConflict(false)
    setSaveError(null)
    await load()
  }

  async function handleDelete() {
    setDeleting(true)
    try {
      await deleteCreative(projectId, creativeId, token)
      router.push(`/app/projects/${projectId}/marketing/creatives`)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not delete this Creative'), 'error')
      setDeleting(false)
      setDeleteConfirmOpen(false)
    }
  }

  async function handleSaveAsVariant() {
    setVariantBusy(true)
    try {
      const variant = await createCreativeVariant(
        projectId,
        creativeId,
        { headline: variantHeadline || undefined },
        token,
      )
      router.push(`/app/projects/${projectId}/marketing/creatives/${variant.id}`)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Could not create the variant'), 'error')
      setVariantBusy(false)
    }
  }

  if (loadError) return <Alert variant="destructive">{loadError}</Alert>
  if (!form || !creative || !kits || !registry) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-80" />
        <div className="grid gap-6 lg:grid-cols-[360px_1fr]">
          <Skeleton className="h-96" />
          <Skeleton className="h-96" />
        </div>
      </div>
    )
  }

  const layoutOptions = Object.keys(registry.layouts)
  const themeOptions = registry.layouts[form.layout]?.themes ?? ['dark', 'light']
  // MOTION has no sequence/story/carousel — a single animated frame, not a set of beats.
  const hasSequence = form.kind === 'STILL' && form.sequenceKind != null && form.sequence.length > 0
  const motionClip =
    form.kind === 'MOTION' && form.motion.backgroundSource === 'clip' && form.motion.clipMediaId
      ? photoById.get(form.motion.clipMediaId)
      : undefined
  const clipHasAudio = motionClip?.hasAudio === true
  const audioTrack = form.kind === 'MOTION' && form.audio.trackId ? photoById.get(form.audio.trackId) : undefined

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold text-foreground">
          {creative.displayId} {form.name && <span className="text-muted-foreground">· {form.name}</span>}
        </h1>
        <StatusBadge status={form.state} hue={STATE_HUE[form.state]} label={form.state} />
        <span className="text-xs text-muted-foreground">
          kit: {selectedKit?.name ?? '—'} · v{creative.version}
        </span>
        <Can do="creative.manage">
          <div className="ml-auto flex gap-2">
            {latestSucceededRender && (
              <Button variant="outline" onClick={() => setUseInPostOpen(true)}>
                Use in Post
              </Button>
            )}
            <Button
              variant="outline"
              onClick={() => {
                setVariantHeadline(form.headline)
                setVariantOpen(true)
              }}
            >
              Save as variant
            </Button>
            <Button onClick={handleSave} disabled={saving}>
              {saving ? 'Saving…' : 'Save'}
            </Button>
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" className="px-2" aria-label="More actions">
                  <MoreHorizontalIcon className="h-4 w-4" aria-hidden />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                <DropdownMenuItem
                  onClick={() => setDeleteConfirmOpen(true)}
                  className="cursor-pointer text-destructive focus:text-destructive"
                >
                  Delete this Creative
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </Can>
      </div>

      {conflict && (
        <Alert variant="warning">
          This Creative changed elsewhere.{' '}
          <button type="button" className="underline" onClick={handleReload}>
            Reload
          </button>{' '}
          to see the latest version before saving again.
        </Alert>
      )}
      {saveError?.detail && !saveError.violations?.length && (
        <Alert variant="destructive">{saveError.detail}</Alert>
      )}

      <div className="grid gap-6 lg:grid-cols-[360px_1fr]">
        <fieldset disabled={!canManage} className="m-0 min-w-0 space-y-4 border-0 p-0">
          <legend className="sr-only">Creative details</legend>
          <Card className="space-y-3 p-4">
            <div>
              <Label htmlFor="creative-kind">Kind</Label>
              <Select
                id="creative-kind"
                value={form.kind}
                onChange={(e) => update('kind', e.target.value as CreativeKind)}
              >
                <option value="STILL">Still</option>
                <option value="MOTION">Motion</option>
                <option value="CLIP">Clip</option>
              </Select>
            </div>

            <div>
              <Label htmlFor="creative-name">Name</Label>
              <Input id="creative-name" value={form.name} onChange={(e) => update('name', e.target.value)} />
            </div>

            <div>
              <Label htmlFor="creative-kit">Brand kit</Label>
              <Select id="creative-kit" value={form.brandKitId} onChange={(e) => update('brandKitId', e.target.value)}>
                {kits.map((k) => (
                  <option key={k.id} value={k.id}>
                    {k.name}
                  </option>
                ))}
              </Select>
              {violationsFor(saveError, 'brandKitId').map((m) => (
                <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
            </div>

            {form.kind !== 'CLIP' && (
              <>
                <div>
                  <Label>Photo</Label>
                  <button
                    type="button"
                    onClick={() => setPhotoPickerOpen(true)}
                    className="flex w-full items-center gap-2 rounded-md border border-border-strong px-2 py-1.5 text-left text-sm hover:bg-surface-3"
                  >
                    {photo?.url ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={photo.url} alt="" className="h-8 w-8 rounded object-cover" />
                    ) : (
                      <span className="h-8 w-8 rounded bg-surface-3" />
                    )}
                    <span className="truncate text-muted-foreground">{photoDisplayName(photo)}</span>
                  </button>
                  {violationsFor(saveError, 'photoId').map((m) => (
                    <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                  ))}
                </div>

                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <Label htmlFor="creative-layout">Layout</Label>
                    <Select
                      id="creative-layout"
                      value={form.layout}
                      onChange={(e) => {
                        const layout = e.target.value
                        const themes = registry.layouts[layout]?.themes ?? []
                        setForm((prev) =>
                          prev ? { ...prev, layout, theme: (themes.includes(prev.theme) ? prev.theme : (themes[0] as CreativeTheme) ?? prev.theme) } : prev,
                        )
                      }}
                    >
                      {layoutOptions.map((l) => (
                        <option key={l} value={l}>
                          {l}
                        </option>
                      ))}
                    </Select>
                    {violationsFor(saveError, 'layout').map((m) => (
                      <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                    ))}
                  </div>
                  <div>
                    <Label htmlFor="creative-theme">Theme</Label>
                    <Select id="creative-theme" value={form.theme} onChange={(e) => update('theme', e.target.value as CreativeTheme)}>
                      {themeOptions.map((t) => (
                        <option key={t} value={t}>
                          {t}
                        </option>
                      ))}
                    </Select>
                    {violationsFor(saveError, 'theme').map((m) => (
                      <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                    ))}
                  </div>
                </div>

                <div>
                  <Label htmlFor="creative-headline">Headline</Label>
                  <Textarea id="creative-headline" value={form.headline} onChange={(e) => update('headline', e.target.value)} rows={2} />
                  {messagesFor('headline').map((m) => (
                    <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                  ))}
                </div>

                <div>
                  <Label htmlFor="creative-body">Body</Label>
                  <Textarea id="creative-body" value={form.body} onChange={(e) => update('body', e.target.value)} rows={3} />
                  {messagesFor('body').map((m) => (
                    <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                  ))}
                </div>
              </>
            )}

            <div>
              <Label htmlFor="creative-caption">Caption</Label>
              <Textarea id="creative-caption" value={form.caption} onChange={(e) => update('caption', e.target.value)} rows={2} />
              {issuesFor('caption').map((m) => (
                <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
              ))}
            </div>

            <div>
              <Label htmlFor="creative-alt-text">Alt text</Label>
              <Input id="creative-alt-text" value={form.altText} onChange={(e) => update('altText', e.target.value)} />
            </div>

            {form.kind !== 'CLIP' && (
              <div>
                <Label>Extra placements</Label>
                <div className="flex flex-wrap gap-x-3 gap-y-1.5">
                  {registry.placements
                    .filter((p) => !(brand.enabledPlacements ?? []).includes(p.key))
                    .map((p) => (
                      <Checkbox
                        key={p.key}
                        id={`extra-placement-${p.key}`}
                        checked={form.placements.includes(p.key)}
                        onCheckedChange={(checked) =>
                          update(
                            'placements',
                            checked ? [...form.placements, p.key] : form.placements.filter((k) => k !== p.key),
                          )
                        }
                        label={p.label}
                      />
                    ))}
                </div>
              </div>
            )}

            <div>
              <Label htmlFor="creative-state">State</Label>
              <Select id="creative-state" value={form.state} onChange={(e) => update('state', e.target.value as CreativeState)}>
                <option value="DRAFT">Draft</option>
                <option value="READY">Ready</option>
                <option value="ARCHIVED">Archived</option>
              </Select>
            </div>

            {form.kind !== 'CLIP' && (
              <div className="border-t border-border pt-3">
                <button
                  type="button"
                  onClick={() => setAdvancedOpen((o) => !o)}
                  className="flex w-full items-center gap-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground"
                  aria-expanded={advancedOpen}
                >
                  <ChevronDownIcon className={cn('h-3.5 w-3.5 transition-transform', !advancedOpen && '-rotate-90')} aria-hidden />
                  Advanced
                </button>
                {advancedOpen && (
                  <div className="mt-2 space-y-3">
                    <div>
                      <Label htmlFor="creative-lockup">Lockup</Label>
                      <Select
                        id="creative-lockup"
                        value={form.lockup}
                        onChange={(e) => update('lockup', e.target.value as CreativeLockup)}
                      >
                        <option value="plain">Plain</option>
                        <option value="chip">Chip (white pill, for busy photography)</option>
                      </Select>
                    </div>

                    <div>
                      <Label>Layout overrides (px)</Label>
                      <p className="mb-1.5 text-xs text-muted-foreground">
                        Blank inherits the layout&apos;s own default for that placement.
                      </p>
                      <div className="space-y-1.5">
                        {registry.placements.map((p) => (
                          <div key={p.key} className="grid grid-cols-[auto_1fr_1fr] items-center gap-2">
                            <span className="text-xs text-muted-foreground">{p.key}</span>
                            <Input
                              type="number"
                              aria-label={`${p.key} band height override`}
                              placeholder="Band"
                              value={form.layoutOverrideBand[p.key] ?? ''}
                              onChange={(e) =>
                                update('layoutOverrideBand', { ...form.layoutOverrideBand, [p.key]: e.target.value })
                              }
                            />
                            <Input
                              type="number"
                              aria-label={`${p.key} bottom padding override`}
                              placeholder="Pad bottom"
                              value={form.layoutOverridePadBottom[p.key] ?? ''}
                              onChange={(e) =>
                                update('layoutOverridePadBottom', { ...form.layoutOverridePadBottom, [p.key]: e.target.value })
                              }
                            />
                          </div>
                        ))}
                      </div>
                      {violationsFor(saveError, 'layoutOverrides.band').concat(violationsFor(saveError, 'layoutOverrides.padBottom')).map((m) => (
                        <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            )}
          </Card>

          {form.kind === 'MOTION' && (
            <Card className="space-y-3 p-4" data-testid="motion-panel">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Motion</h3>

              <div>
                <Label htmlFor="motion-preset">Preset</Label>
                <Select
                  id="motion-preset"
                  value={form.motion.preset}
                  onChange={(e) => update('motion', { ...form.motion, preset: e.target.value as MotionPreset })}
                >
                  <option value="fade-up">Fade up — headline and body rise into place</option>
                  <option value="word-by-word">Word by word — the headline builds in, one word at a time</option>
                  <option value="accent-pop">Accent pop — headline fades in, then the accent phrase pops</option>
                  <option value="none">None — everything visible immediately</option>
                </Select>
                {violationsFor(saveError, 'motion.preset').map((m) => (
                  <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                ))}
              </div>

              <div>
                <div className="flex items-center justify-between">
                  <Label htmlFor="motion-duration">Duration</Label>
                  <span className="text-xs text-muted-foreground">{form.motion.durationSec}s</span>
                </div>
                <Slider
                  id="motion-duration"
                  min={3}
                  max={60}
                  step={1}
                  value={form.motion.durationSec}
                  onValueChange={(v) => update('motion', { ...form.motion, durationSec: v })}
                />
                {violationsFor(saveError, 'motion.durationSec').map((m) => (
                  <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                ))}
              </div>

              <div>
                <Label htmlFor="motion-bg-source">Background</Label>
                <Select
                  id="motion-bg-source"
                  value={form.motion.backgroundSource}
                  onChange={(e) => update('motion', { ...form.motion, backgroundSource: e.target.value as MotionBackgroundSource })}
                >
                  <option value="photo">Photo</option>
                  <option value="clip">Clip</option>
                </Select>
                {violationsFor(saveError, 'motion.background.source').map((m) => (
                  <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                ))}
              </div>

              {form.motion.backgroundSource === 'photo' ? (
                <div>
                  <Label htmlFor="motion-bg-motion">Background motion</Label>
                  <Select
                    id="motion-bg-motion"
                    value={form.motion.backgroundMotion}
                    onChange={(e) => update('motion', { ...form.motion, backgroundMotion: e.target.value as MotionBackgroundMotion })}
                  >
                    <option value="zoom-in">Zoom in</option>
                    <option value="zoom-out">Zoom out</option>
                    <option value="pan-left">Pan left</option>
                    <option value="pan-right">Pan right</option>
                    <option value="none">None</option>
                  </Select>
                  {violationsFor(saveError, 'motion.background.motion').map((m) => (
                    <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                  ))}
                </div>
              ) : (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <Label>Clip</Label>
                    {motionClip && canManage && (
                      <button
                        type="button"
                        onClick={() => update('motion', { ...form.motion, clipMediaId: null })}
                        className="text-xs text-muted-foreground hover:text-destructive"
                      >
                        Remove
                      </button>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => setMotionClipPickerOpen(true)}
                    disabled={!canManage}
                    className="flex w-full items-center gap-2 rounded-md border border-border-strong px-2 py-1.5 text-left text-sm hover:bg-surface-3 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {motionClip?.posterUrl ? (
                      // eslint-disable-next-line @next/next/no-img-element
                      <img src={motionClip.posterUrl} alt="" className="h-8 w-8 rounded object-cover" />
                    ) : (
                      <span className="h-8 w-8 rounded bg-surface-3" />
                    )}
                    <span className="truncate text-muted-foreground">
                      {motionClip ? motionClip.label || 'Untitled clip' : 'Choose a clip…'}
                    </span>
                  </button>
                  {violationsFor(saveError, 'motion.background.clipMediaId').map((m) => (
                    <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                  ))}

                  <div>
                    <Label htmlFor="motion-clip-start">Clip start (seconds)</Label>
                    <Input
                      id="motion-clip-start"
                      type="number"
                      min={0}
                      max={motionClip?.durationSeconds ?? undefined}
                      value={form.motion.clipStartSec}
                      onChange={(e) => update('motion', { ...form.motion, clipStartSec: e.target.value })}
                    />
                    {violationsFor(saveError, 'motion.background.clipStartSec').map((m) => (
                      <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                    ))}
                  </div>
                </div>
              )}

              <Checkbox
                id="motion-end-card"
                checked={form.motion.endCard}
                onCheckedChange={(checked) => update('motion', { ...form.motion, endCard: checked })}
                label="End card — hold the full composition for the last 2 seconds"
              />
              {violationsFor(saveError, 'motion.endCard').map((m) => (
                <p key={m} className="text-xs text-destructive">{m}</p>
              ))}
            </Card>
          )}

          {form.kind === 'MOTION' && (
            <Card className="space-y-3 p-4" data-testid="audio-panel">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Audio</h3>

              <div>
                <Label htmlFor="audio-source">Source</Label>
                <Select
                  id="audio-source"
                  value={form.audio.source}
                  onChange={(e) => update('audio', { ...form.audio, source: e.target.value as AudioSource })}
                >
                  <option value="clip" disabled={!clipHasAudio}>
                    Clip sound{!clipHasAudio ? ' (clip has no audio)' : ''}
                  </option>
                  <option value="track">Music track</option>
                  <option value="none">None</option>
                </Select>
                {violationsFor(saveError, 'audio.source').map((m) => (
                  <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                ))}
              </div>

              {form.audio.source === 'track' && (
                <div className="space-y-2">
                  <div className="flex items-center justify-between">
                    <Label>Track</Label>
                    {audioTrack && canManage && (
                      <button
                        type="button"
                        onClick={() => update('audio', { ...form.audio, trackId: null })}
                        className="text-xs text-muted-foreground hover:text-destructive"
                      >
                        Remove
                      </button>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => setAudioTrackPickerOpen(true)}
                    disabled={!canManage}
                    className="flex w-full items-center gap-2 rounded-md border border-border-strong px-2 py-1.5 text-left text-sm hover:bg-surface-3 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <span className="truncate text-muted-foreground">
                      {audioTrack ? audioTrack.label || 'Untitled track' : 'Choose a track…'}
                    </span>
                  </button>
                  {audioTrack?.url && (
                    <audio controls src={audioTrack.url} data-testid="audio-track-audition" className="w-full">
                      Your browser does not support the audio element.
                    </audio>
                  )}
                  {violationsFor(saveError, 'audio.trackId').map((m) => (
                    <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                  ))}
                </div>
              )}

              {form.audio.source !== 'none' && (
                <>
                  <div>
                    <div className="flex items-center justify-between">
                      <Label htmlFor="audio-volume">Volume</Label>
                      <span className="text-xs text-muted-foreground">{Math.round(form.audio.volume * 100)}%</span>
                    </div>
                    <Slider
                      id="audio-volume"
                      min={0}
                      max={1}
                      step={0.05}
                      value={form.audio.volume}
                      onValueChange={(v) => update('audio', { ...form.audio, volume: v })}
                    />
                    {violationsFor(saveError, 'audio.volume').map((m) => (
                      <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                    ))}
                  </div>
                  <div>
                    <div className="flex items-center justify-between">
                      <Label htmlFor="audio-fadeout">Fade out</Label>
                      <span className="text-xs text-muted-foreground">{form.audio.fadeOutSec}s</span>
                    </div>
                    <Slider
                      id="audio-fadeout"
                      min={0}
                      max={5}
                      step={0.5}
                      value={form.audio.fadeOutSec}
                      onValueChange={(v) => update('audio', { ...form.audio, fadeOutSec: v })}
                    />
                    {violationsFor(saveError, 'audio.fadeOutSec').map((m) => (
                      <p key={m} className="mt-1 text-xs text-destructive">{m}</p>
                    ))}
                  </div>
                </>
              )}
            </Card>
          )}

          {form.kind === 'STILL' && (
            <Card className="space-y-3 p-4">
              <Label htmlFor="creative-sequence-kind">Sequence</Label>
              <Select
                id="creative-sequence-kind"
                value={form.sequenceKind ?? ''}
                onChange={(e) => {
                  const v = e.target.value
                  update('sequenceKind', v ? (v as SequenceKind) : null)
                }}
              >
                <option value="">None</option>
                <option value="story">Story</option>
                <option value="carousel">Carousel</option>
              </Select>

              {form.sequenceKind && (
                <div className="space-y-2">
                  {form.sequence.map((beat, i) => {
                    const beatPhoto = beat.photoId ? photoById.get(beat.photoId) : undefined
                    return (
                    <div key={i} className="flex items-start gap-2 rounded-md border border-border p-2">
                      <span className="mt-1.5 text-xs text-muted-foreground">{i + 1}</span>
                      <button
                        type="button"
                        onClick={() => setBeatPhotoPickerIndex(i)}
                        aria-label={`Beat ${i + 1} photo`}
                        className="mt-0.5 h-8 w-8 shrink-0 overflow-hidden rounded border border-border-strong bg-surface-3 disabled:cursor-not-allowed disabled:opacity-50"
                      >
                        {beatPhoto?.url ? (
                          // eslint-disable-next-line @next/next/no-img-element
                          <img src={beatPhoto.url} alt="" className="h-full w-full object-cover" />
                        ) : (
                          <span className="sr-only">Choose a photo for this beat</span>
                        )}
                      </button>
                      <div className="flex-1 space-y-1">
                        <Input
                          aria-label={`Beat ${i + 1} headline`}
                          value={beat.headline ?? ''}
                          onChange={(e) =>
                            update(
                              'sequence',
                              form.sequence.map((b, idx) => (idx === i ? { ...b, headline: e.target.value } : b)),
                            )
                          }
                          placeholder={i === 0 ? form.headline || 'Headline (inherits above)' : 'Headline'}
                        />
                        <Textarea
                          aria-label={`Beat ${i + 1} body`}
                          value={beat.body ?? ''}
                          onChange={(e) =>
                            update(
                              'sequence',
                              form.sequence.map((b, idx) => (idx === i ? { ...b, body: e.target.value } : b)),
                            )
                          }
                          rows={2}
                          placeholder="Body (optional, not inherited)"
                        />
                      </div>
                      <button
                        type="button"
                        aria-label={`Remove beat ${i + 1}`}
                        onClick={() => update('sequence', form.sequence.filter((_, idx) => idx !== i))}
                        className="text-muted-foreground hover:text-destructive"
                      >
                        <XIcon className="h-3.5 w-3.5" aria-hidden />
                      </button>
                    </div>
                    )
                  })}
                  <Button
                    variant="outline"
                    size="sm"
                    onClick={() => update('sequence', [...form.sequence, {}])}
                  >
                    <PlusIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
                    Beat
                  </Button>
                  {violationsFor(saveError, 'sequence').map((m) => (
                    <p key={m} className="text-xs text-destructive">{m}</p>
                  ))}
                </div>
              )}
            </Card>
          )}
        </fieldset>

        <div className="space-y-4">
          {form.kind === 'CLIP' ? (
            <ClipMediaSection
              form={form}
              update={update}
              registry={registry}
              mediaById={photoById}
              canManage={canManage}
              onPick={setClipMediaPickerKey}
            />
          ) : (
          <Card className="space-y-3 p-4">
            <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Live preview · click a frame to view it full size{canManage && ' · drag to set its focal point'}
            </h3>
            {renderCreative && (
              <div className="flex flex-wrap gap-4">
                {enabledKeys.map((key, i) => (
                  <PlacementBoard
                    key={key}
                    placementKey={key}
                    creative={renderCreative}
                    brand={brand}
                    sequenceIndex={sequenceIndex}
                    onFocalChange={handleFocalChange}
                    draggable={canManage}
                    onOpen={openPlacementViewer}
                    motionControl={
                      form.kind === 'MOTION'
                        ? { playing: motionPlaying, scrub: motionScrub, onTime: i === 0 ? handleMotionTime : undefined }
                        : undefined
                    }
                  />
                ))}
              </div>
            )}
            {form.kind === 'MOTION' && renderCreative && (
              <div className="space-y-1.5">
                <MotionTransport
                  durationSec={form.motion.durationSec}
                  playing={motionPlaying}
                  onTogglePlay={() => setMotionPlaying((p) => !p)}
                  currentT={motionCurrentT}
                  onScrub={handleMotionScrub}
                  loop={motionLoop}
                  onLoopChange={setMotionLoop}
                />
                <p className="text-xs text-muted-foreground">Audio plays in the rendered video.</p>
              </div>
            )}
            {hasSequence && (
              <div className="flex items-center gap-2">
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={sequenceIndex === 0}
                  onClick={() => setSequenceIndex((i) => Math.max(0, i - 1))}
                  aria-label="Previous beat"
                >
                  <ChevronLeftIcon className="h-4 w-4" aria-hidden />
                </Button>
                <span className="text-xs text-muted-foreground">
                  Beat {sequenceIndex + 1} of {form.sequence.length}
                </span>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={sequenceIndex >= form.sequence.length - 1}
                  onClick={() => setSequenceIndex((i) => Math.min(form.sequence.length - 1, i + 1))}
                  aria-label="Next beat"
                >
                  <ChevronRightIcon className="h-4 w-4" aria-hidden />
                </Button>
              </div>
            )}
          </Card>
          )}

          {readiness && (
            <Card className="space-y-1.5 p-4">
              <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Readiness</h3>
              <ul className="space-y-1">
                {readiness.items.map((item) => (
                  <li
                    key={item.key}
                    className={cn('text-sm', item.ok ? 'text-status-approved' : item.blocking ? 'text-destructive' : 'text-status-progress')}
                  >
                    {item.message}
                  </li>
                ))}
              </ul>
            </Card>
          )}

          <RendersPanel
            projectId={projectId}
            creativeId={creativeId}
            creativeDisplayId={creative.displayId}
            creativeVersion={creative.version}
            creativeKind={form.kind}
            token={token}
            registry={registry}
            onLatestSucceededChange={setLatestSucceededRender}
          />

          <CreativePerformancePanel projectId={projectId} creativeId={creativeId} token={token} />
        </div>
      </div>

      <PhotoPicker
        projectId={projectId}
        token={token}
        open={photoPickerOpen || beatPhotoPickerIndex !== null}
        onOpenChange={(open) => {
          if (!open) {
            setPhotoPickerOpen(false)
            setBeatPhotoPickerIndex(null)
          }
        }}
        photos={photos}
        onPhotosChanged={() => listCreativePhotos(projectId, token, true).then(setPhotos)}
        onSelect={(p) => {
          if (beatPhotoPickerIndex !== null) {
            const idx = beatPhotoPickerIndex
            update(
              'sequence',
              form.sequence.map((b, i) => (i === idx ? { ...b, photoId: p.id } : b)),
            )
          } else {
            setPhoto(p)
            update('photoId', p.id)
          }
          setPhotoPickerOpen(false)
          setBeatPhotoPickerIndex(null)
        }}
      />

      <MediaPicker
        projectId={projectId}
        token={token}
        open={clipMediaPickerKey !== null}
        onOpenChange={(open) => {
          if (!open) setClipMediaPickerKey(null)
        }}
        kind="VIDEO"
        media={photos}
        onMediaChanged={() => listCreativePhotos(projectId, token, true).then(setPhotos)}
        onSelect={(m) => {
          if (clipMediaPickerKey) {
            update('clipMedia', { ...form.clipMedia, [clipMediaPickerKey]: m.id })
          }
          setClipMediaPickerKey(null)
        }}
      />

      <MediaPicker
        projectId={projectId}
        token={token}
        open={motionClipPickerOpen}
        onOpenChange={setMotionClipPickerOpen}
        kind="VIDEO"
        media={photos}
        onMediaChanged={() => listCreativePhotos(projectId, token, true).then(setPhotos)}
        onSelect={(m) => {
          update('motion', { ...form.motion, clipMediaId: m.id, clipStartSec: '0' })
          setMotionClipPickerOpen(false)
        }}
      />

      <MediaPicker
        projectId={projectId}
        token={token}
        open={audioTrackPickerOpen}
        onOpenChange={setAudioTrackPickerOpen}
        kind="AUDIO"
        media={photos}
        onMediaChanged={() => listCreativePhotos(projectId, token, true).then(setPhotos)}
        onSelect={(m) => {
          update('audio', { ...form.audio, trackId: m.id })
          setAudioTrackPickerOpen(false)
        }}
      />

      <Modal
        open={variantOpen}
        onOpenChange={setVariantOpen}
        title="Save as variant"
        description="Copies the photo, layout and body; give this variant its own headline."
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setVariantOpen(false)} disabled={variantBusy}>
              Cancel
            </Button>
            <Button onClick={handleSaveAsVariant} disabled={variantBusy}>
              {variantBusy ? 'Creating…' : 'Create variant'}
            </Button>
          </div>
        }
      >
        <Label htmlFor="variant-headline">Headline</Label>
        <Textarea id="variant-headline" value={variantHeadline} onChange={(e) => setVariantHeadline(e.target.value)} rows={2} />
      </Modal>

      <ConfirmModal
        open={deleteConfirmOpen}
        title="Delete this Creative"
        confirmLabel="Delete"
        busyLabel="Deleting…"
        busy={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteConfirmOpen(false)}
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{creative.displayId}</strong>? This cannot be undone.
        </p>
      </ConfirmModal>

      {viewerIndex !== null && renderCreative && enabledKeys[viewerIndex] && (() => {
        const key = enabledKeys[viewerIndex]
        const dims = placementDims(key)
        return (
          <FullSizeViewer
            open
            onOpenChange={(open) => {
              if (!open) setViewerIndex(null)
            }}
            title={dims?.label ?? key}
            subtitle={dims ? `${dims.w}×${dims.h}px` : undefined}
            onPrev={viewerIndex > 0 ? () => setViewerIndex(viewerIndex - 1) : undefined}
            onNext={viewerIndex < enabledKeys.length - 1 ? () => setViewerIndex(viewerIndex + 1) : undefined}
          >
            <PlacementViewerBoard
              placementKey={key}
              creative={renderCreative}
              brand={brand}
              sequenceIndex={sequenceIndex}
            />
          </FullSizeViewer>
        )
      })()}

      {latestSucceededRender && (
        <UseInPostDialog
          open={useInPostOpen}
          onOpenChange={setUseInPostOpen}
          projectId={projectId}
          creativeId={creativeId}
          creativeDisplayId={creative.displayId}
          renderId={latestSucceededRender.id}
          renderFrames={latestSucceededRender.frames}
          token={token}
        />
      )}
    </div>
  )
}
