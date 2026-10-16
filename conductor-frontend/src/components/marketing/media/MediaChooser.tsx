'use client'

// The one place a Post (or anything else) picks media: a wide dialog with a Library tab (search,
// filters, sort, numbered multi-select, full-size preview) and an Upload tab (drop zone). Built on the
// base-ui Dialog directly, like FullSizeViewer, because the fixed-width Modal is too narrow for a grid.
//
// It is a chooser, not a manager: no Checked / Block / Delete here (those live on the Media page).
// Blocked items are never listed; unchecked ones carry a small "Not checked" tag.
//
// `extraTabs` is the seam for further tabs (e.g. Creatives); they render before Library.

import { useEffect, useMemo, useRef, useState } from 'react'
import type { DragEvent, ReactNode } from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { ChevronDownIcon, FilmIcon, MaximizeIcon, PlayIcon, UploadCloudIcon, XIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs } from '@/components/ui/tabs'
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { formatDuration } from '@/lib/format'
import { cn } from '@/lib/utils'
import { FullSizeViewer } from '@/components/marketing/creatives/FullSizeViewer'
import {
  ALLOWED_CONTENT_TYPES,
  mediaKindForFile,
  stripExtension,
  uploadMediaFile,
} from '@/components/marketing/creatives/mediaUpload'
import { listCreativePhotos, type CreativePhoto } from '@/components/marketing/creatives/types'
import { MediaFilterBar } from './MediaFilterBar'
import { setPrefix } from './mediaSets'
import { useMediaFilters, type KindTab } from './useMediaFilters'

type ChooserKind = 'IMAGE' | 'VIDEO'

export interface ChooserExtraTab {
  value: string
  label: string
  content: ReactNode
}

export interface MediaChooserProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  token: string
  /** Called with the chosen items in pick order. The chooser closes itself afterwards. */
  onPicked: (items: CreativePhoto[]) => void
  /** Restrict to one kind (photos only / videos only). Default: images and video. */
  kinds?: ChooserKind[]
  /** false = pick exactly one (a new pick replaces the last). Default true. */
  multiple?: boolean
  /** Extra tabs shown before Library (T2 adds Creatives here). */
  extraTabs?: ChooserExtraTab[]
  /** Tab shown first; defaults to Library. */
  defaultTab?: string
  /** Upload tab hands the raw files back instead of uploading them to the library — for a caller
   *  (the compose page) that uploads them itself once its Post exists. Not permission-gated. */
  onFilesChosen?: (files: File[]) => void
}

const DEFAULT_KINDS: ChooserKind[] = ['IMAGE', 'VIDEO']

function itemMeta(item: CreativePhoto): string {
  const parts: string[] = []
  if (item.width && item.height) parts.push(`${item.width}×${item.height}`)
  const posts = item.postCount ?? 0
  if (posts > 0) parts.push(`on ${posts} ${posts === 1 ? 'post' : 'posts'}`)
  else if ((item.creativeCount ?? 0) === 0) parts.push('unused')
  return parts.join(' · ')
}

function Thumb({ item }: { item: CreativePhoto }) {
  const [failed, setFailed] = useState(false)
  const isVideo = item.mediaKind === 'VIDEO'
  const src = isVideo ? item.posterUrl : item.url
  return (
    <>
      {src && !failed ? (
        // eslint-disable-next-line @next/next/no-img-element
        <img
          src={src}
          alt={item.label || 'Media'}
          onError={() => setFailed(true)}
          className="h-full w-full object-contain"
        />
      ) : (
        <span className="flex h-full flex-col items-center justify-center gap-1 text-muted-foreground">
          <FilmIcon className="h-5 w-5" aria-hidden />
          {isVideo && item.durationSeconds != null && (
            <span className="text-[11px]">{formatDuration(item.durationSeconds)}</span>
          )}
        </span>
      )}
      {isVideo && (
        <span className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <PlayIcon className="h-6 w-6 fill-background text-background drop-shadow" aria-hidden />
        </span>
      )}
    </>
  )
}

interface Section {
  key: string
  heading: string | null
  items: CreativePhoto[]
}

/** When sorted by name, items sharing a set prefix ("01-import" for "01-import 4x5 slide 03") sit under
 *  that set's name. A prefix held by a single item is not a set, so it gets no heading. */
function sectionsFor(items: CreativePhoto[], bySet: boolean): Section[] {
  if (!bySet) return [{ key: 'all', heading: null, items }]
  const counts = new Map<string, number>()
  for (const i of items) {
    const p = setPrefix(i.label).toLowerCase()
    if (p) counts.set(p, (counts.get(p) ?? 0) + 1)
  }
  const out: Section[] = []
  for (const i of items) {
    const p = setPrefix(i.label)
    const heading = p && (counts.get(p.toLowerCase()) ?? 0) > 1 ? p : null
    const last = out[out.length - 1]
    if (last && last.heading?.toLowerCase() === heading?.toLowerCase()) last.items.push(i)
    else out.push({ key: `${out.length}-${heading ?? 'loose'}`, heading, items: [i] })
  }
  return out
}

function LibraryTab({
  items,
  loadError,
  kinds,
  selected,
  multiple,
  onToggle,
}: {
  items: CreativePhoto[] | null
  loadError: string | null
  kinds: ChooserKind[]
  selected: CreativePhoto[]
  multiple: boolean
  onToggle: (item: CreativePhoto) => void
}) {
  const filters = useMediaFilters(items, { sort: 'newest', kind: kinds.length === 1 ? kinds[0] : 'ALL' })
  const { visible } = filters
  const [viewerIndex, setViewerIndex] = useState<number | null>(null)
  const viewing = viewerIndex != null ? visible[viewerIndex] : null
  const kindTabs: KindTab[] = kinds.length === 1 ? kinds : ['ALL', ...kinds]
  const sections = useMemo(() => sectionsFor(visible, filters.sort === 'name'), [visible, filters.sort])

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-4">
      <MediaFilterBar filters={filters} chips={['unused', 'onPosts', 'ai']} kinds={kindTabs} sortable />
      {loadError && <Alert variant="destructive">{loadError}</Alert>}
      <div className="min-h-0 flex-1 overflow-y-auto">
        {items == null ? (
          <div data-testid="chooser-skeleton" className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5" aria-hidden>
            {Array.from({ length: 12 }, (_, i) => (
              <Skeleton key={i} className="aspect-square" />
            ))}
          </div>
        ) : visible.length === 0 ? (
          <EmptyState
            icon={FilmIcon}
            title={filters.filtering ? 'Nothing matches' : 'No media yet'}
            description={
              filters.filtering ? 'Try a different search or clear the filters.' : 'Use the Upload tab to add images or video.'
            }
          />
        ) : (
          <div className="space-y-5">
            {sections.map((section) => (
              <section key={section.key} aria-label={section.heading ?? undefined}>
                {section.heading && (
                  <h3 className="mb-2 text-xs font-semibold uppercase tracking-wide text-muted-foreground">{section.heading}</h3>
                )}
                <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5">
                  {section.items.map((item) => {
                    const order = selected.findIndex((s) => s.id === item.id)
                    const isSelected = order >= 0
                    const name = item.label || 'Untitled'
                    return (
                      <li
                        key={item.id}
                        data-testid={`chooser-tile-${item.id}`}
                        className={cn(
                          'group relative overflow-hidden rounded-lg border bg-surface',
                          isSelected ? 'border-primary ring-2 ring-primary' : 'border-border',
                        )}
                      >
                        <button
                          type="button"
                          aria-pressed={isSelected}
                          aria-label={`${multiple ? 'Select' : 'Choose'} ${name}`}
                          onClick={() => onToggle(item)}
                          className="block w-full text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        >
                          <span className="relative m-1.5 block aspect-square overflow-hidden rounded-md border border-border bg-surface-2">
                            <Thumb item={item} />
                          </span>
                          <span className="block space-y-0.5 border-t border-border px-2 py-1.5">
                            <span className="block truncate text-[12px] text-foreground" title={item.label ?? undefined}>
                              {name}
                            </span>
                            <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
                              <span className="truncate">{itemMeta(item)}</span>
                              {!item.checked && (
                                <span className="shrink-0 rounded-full border border-border px-1.5 text-[10px]">Not checked</span>
                              )}
                            </span>
                          </span>
                        </button>
                        {isSelected && (
                          <span
                            data-testid={`order-badge-${item.id}`}
                            className="pointer-events-none absolute left-1.5 top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-primary px-1 text-[11px] font-semibold text-primary-foreground shadow ring-1 ring-background"
                          >
                            {order + 1}
                          </span>
                        )}
                        <button
                          type="button"
                          aria-label={`Expand ${name}`}
                          onClick={() => setViewerIndex(visible.findIndex((v) => v.id === item.id))}
                          className="absolute right-1.5 top-1.5 inline-flex h-6 w-6 items-center justify-center rounded-md bg-foreground/70 text-background opacity-0 transition-opacity focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring group-hover:opacity-100"
                        >
                          <MaximizeIcon className="h-3.5 w-3.5" aria-hidden />
                        </button>
                      </li>
                    )
                  })}
                </ul>
              </section>
            ))}
          </div>
        )}
      </div>

      <FullSizeViewer
        open={viewing != null}
        onOpenChange={(o) => {
          if (!o) setViewerIndex(null)
        }}
        title={viewing?.label || 'Untitled'}
        subtitle={viewing ? itemMeta(viewing) : undefined}
        onPrev={viewerIndex != null && viewerIndex > 0 ? () => setViewerIndex(viewerIndex - 1) : undefined}
        onNext={viewerIndex != null && viewerIndex < visible.length - 1 ? () => setViewerIndex(viewerIndex + 1) : undefined}
      >
        {viewing &&
          (viewing.mediaKind === 'VIDEO' ? (
            <video src={viewing.url ?? undefined} poster={viewing.posterUrl ?? undefined} controls className="max-h-[70vh] max-w-full" />
          ) : (
            // eslint-disable-next-line @next/next/no-img-element
            <img src={viewing.url ?? undefined} alt={viewing.label || 'Media'} className="max-h-[70vh] max-w-full object-contain" />
          ))}
      </FullSizeViewer>
    </div>
  )
}

function UploadTab({
  projectId,
  token,
  kinds,
  deferred,
  onFiles,
  onUploaded,
}: {
  projectId: string
  token: string
  kinds: ChooserKind[]
  /** Hand files back instead of uploading (no metadata to collect). */
  deferred: boolean
  onFiles: (files: File[]) => void
  onUploaded: (items: CreativePhoto[]) => void
}) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [dragging, setDragging] = useState(false)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const [label, setLabel] = useState('')
  const [source, setSource] = useState('')
  const [licence, setLicence] = useState('')
  const [aiGenerated, setAiGenerated] = useState(false)
  const [detailsOpen, setDetailsOpen] = useState(false)
  const accept = kinds.flatMap((k) => ALLOWED_CONTENT_TYPES[k]).join(',')

  async function handle(files: File[]) {
    if (files.length === 0) return
    setError(null)
    setWarnings([])
    const allowed = files.filter((f) => {
      const k = mediaKindForFile(f)
      return k != null && (kinds as string[]).includes(k)
    })
    const rejected = files.filter((f) => !allowed.includes(f))
    if (deferred) {
      if (allowed.length) onFiles(allowed)
      if (rejected.length) setError(`${rejected.map((f) => f.name).join(', ')}: unsupported file type`)
      return
    }
    const failures = rejected.map((f) => `${f.name}: unsupported file type`)
    const done: CreativePhoto[] = []
    const warn: string[] = []
    for (let n = 0; n < allowed.length; n++) {
      const file = allowed[n]
      setBusy(`Uploading ${n + 1} of ${allowed.length}…`)
      try {
        const item = await uploadMediaFile(projectId, token, mediaKindForFile(file)!, file, {
          label: (allowed.length === 1 && label.trim()) || stripExtension(file.name),
          source,
          licence,
          aiGenerated,
        })
        warn.push(...(item.warnings ?? []))
        done.push(item)
      } catch (err) {
        failures.push(`${file.name}: ${apiErrorMessage(err, err instanceof Error ? err.message : 'Upload failed')}`)
      }
    }
    setBusy(null)
    setWarnings(warn)
    if (failures.length) setError(failures.join(' · '))
    if (done.length) {
      setLabel('')
      onUploaded(done)
    }
  }

  function onDrop(e: DragEvent) {
    e.preventDefault()
    setDragging(false)
    if (!busy) void handle(Array.from(e.dataTransfer.files))
  }

  return (
    <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
      <div
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        data-testid="chooser-dropzone"
        className={cn(
          'flex flex-col items-center justify-center gap-3 rounded-lg border border-dashed px-6 py-14 text-center transition-colors',
          dragging ? 'border-primary bg-accent-soft' : 'border-border-strong bg-surface-2',
        )}
      >
        <UploadCloudIcon className="h-8 w-8 text-muted-foreground" aria-hidden />
        <p className="text-sm text-foreground">Drop {kinds.length === 1 ? (kinds[0] === 'IMAGE' ? 'images' : 'videos') : 'images or video'} here</p>
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={accept}
          aria-label="Files to upload"
          className="sr-only"
          onChange={(e) => {
            const files = Array.from(e.target.files ?? [])
            e.target.value = ''
            void handle(files)
          }}
        />
        <Button type="button" variant="outline" size="sm" disabled={busy != null} onClick={() => inputRef.current?.click()}>
          {busy ?? 'Browse files'}
        </Button>
        {!deferred && <p className="text-xs text-muted-foreground">Uploads are saved to your Media library and selected for you.</p>}
      </div>

      {!deferred && (
        <div className="rounded-lg border border-border">
          <button
            type="button"
            aria-expanded={detailsOpen}
            onClick={() => setDetailsOpen((v) => !v)}
            className="flex w-full items-center justify-between px-3 py-2 text-sm font-medium text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            Details (optional)
            <ChevronDownIcon className={cn('h-4 w-4 transition-transform', detailsOpen && 'rotate-180')} aria-hidden />
          </button>
          {detailsOpen && (
            <div className="space-y-3 border-t border-border p-3">
              <div>
                <Label htmlFor="chooser-label" className="text-xs">
                  Label
                </Label>
                <Input id="chooser-label" value={label} onChange={(e) => setLabel(e.target.value)} placeholder="Defaults to the file name" />
              </div>
              <div className="grid grid-cols-2 gap-2">
                <div>
                  <Label htmlFor="chooser-source" className="text-xs">
                    Source
                  </Label>
                  <Input id="chooser-source" value={source} onChange={(e) => setSource(e.target.value)} placeholder="Unsplash" />
                </div>
                <div>
                  <Label htmlFor="chooser-licence" className="text-xs">
                    Licence
                  </Label>
                  <Input id="chooser-licence" value={licence} onChange={(e) => setLicence(e.target.value)} placeholder="CC0" />
                </div>
              </div>
              <Checkbox id="chooser-ai" checked={aiGenerated} onCheckedChange={setAiGenerated} label="AI-generated" />
            </div>
          )}
        </div>
      )}

      {error && <Alert variant="destructive">{error}</Alert>}
      {warnings.map((w) => (
        <Alert key={w} variant="warning">
          {w}
        </Alert>
      ))}
    </div>
  )
}

function ChooserBody({
  onOpenChange,
  projectId,
  token,
  onPicked,
  kinds = DEFAULT_KINDS,
  multiple = true,
  extraTabs,
  defaultTab,
  onFilesChosen,
}: MediaChooserProps) {
  const canManage = useCan('creative.manage')
  const deferred = onFilesChosen != null
  const showUpload = canManage || deferred
  const [items, setItems] = useState<CreativePhoto[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [selected, setSelected] = useState<CreativePhoto[]>([])
  const firstTab = defaultTab ?? 'library'
  const [tab, setTab] = useState(firstTab)

  // The body only exists while the dialog is open, so mounting is "opened": load fresh, start clean.
  useEffect(() => {
    let cancelled = false
    ;(async () => {
      try {
        const rows = await listCreativePhotos(projectId, token, false)
        if (cancelled) return
        setItems(rows.filter((m) => m.uploadStatus === 'UPLOADED' && !m.blocked))
        setLoadError(null)
      } catch (err) {
        if (cancelled) return
        setItems((prev) => prev ?? [])
        setLoadError(apiErrorMessage(err, 'Could not load media — please try again.'))
      }
    })()
    return () => {
      cancelled = true
    }
  }, [projectId, token])

  const scoped = useMemo(
    () => (items == null ? null : items.filter((m) => (kinds as string[]).includes(m.mediaKind ?? 'IMAGE'))),
    [items, kinds],
  )

  const toggle = (item: CreativePhoto) =>
    setSelected((prev) => {
      if (prev.some((p) => p.id === item.id)) return prev.filter((p) => p.id !== item.id)
      return multiple ? [...prev, item] : [item]
    })

  const tabs = [
    ...(extraTabs ?? []).map((t) => ({ value: t.value, label: t.label })),
    { value: 'library', label: 'Library' },
    ...(showUpload ? [{ value: 'upload', label: 'Upload' }] : []),
  ]
  const activeTab = tabs.some((t) => t.value === tab) ? tab : 'library'
  const extra = (extraTabs ?? []).find((t) => t.value === activeTab)

  function uploaded(done: CreativePhoto[]) {
    setItems((prev) => [...done.filter((d) => !(prev ?? []).some((p) => p.id === d.id)), ...(prev ?? [])])
    setSelected((prev) => {
      const next = [...prev, ...done.filter((d) => !prev.some((p) => p.id === d.id))]
      return multiple ? next : next.slice(-1)
    })
    setTab('library')
  }

  return (
    <>
    <div className="flex shrink-0 items-start justify-between gap-3 px-4 pt-4 sm:px-5">
      <div className="min-w-0">
        <Dialog.Title className="text-base font-semibold text-foreground">Add media</Dialog.Title>
        <Dialog.Description className="text-sm text-muted-foreground">
          Pick from your library or upload new files. Click in the order you want them to appear.
        </Dialog.Description>
      </div>
      <Button variant="ghost" size="icon" aria-label="Close" onClick={() => onOpenChange(false)}>
        <XIcon className="h-4 w-4" aria-hidden />
      </Button>
    </div>

    <div className="min-w-0 shrink-0 overflow-x-auto px-4 pt-3 sm:px-5">
      <Tabs ariaLabel="Media source" value={activeTab} onValueChange={setTab} items={tabs} />
    </div>

    <div className="flex min-h-0 flex-1 flex-col px-4 py-4 sm:px-5">
      {extra ? (
        extra.content
      ) : activeTab === 'upload' ? (
        <UploadTab
          projectId={projectId}
          token={token}
          kinds={kinds}
          deferred={deferred}
          onFiles={(files) => {
            onFilesChosen?.(files)
            onOpenChange(false)
          }}
          onUploaded={uploaded}
        />
      ) : (
        <LibraryTab
          items={scoped}
          loadError={loadError}
          kinds={kinds}
          selected={selected}
          multiple={multiple}
          onToggle={toggle}
        />
      )}
    </div>

    <div className="flex shrink-0 flex-wrap items-center gap-x-3 gap-y-2 border-t border-border px-4 py-3 sm:px-5">
      <span className="text-sm text-muted-foreground" aria-live="polite">
        {selected.length === 0 ? 'Nothing selected' : `${selected.length} selected`}
      </span>
      {selected.length > 0 && (
        <Button type="button" variant="ghost" size="sm" onClick={() => setSelected([])}>
          Clear
        </Button>
      )}
      <div className="ml-auto flex items-center gap-2">
        <Button type="button" variant="outline" size="sm" onClick={() => onOpenChange(false)}>
          Cancel
        </Button>
        <Button
          type="button"
          size="sm"
          disabled={selected.length === 0}
          onClick={() => {
            onPicked(selected)
            onOpenChange(false)
          }}
        >
          {selected.length > 0 ? `Add ${selected.length}` : 'Add'}
        </Button>
      </div>
    </div>
    </>
  )
}

export function MediaChooser(props: MediaChooserProps) {
  return (
    <Dialog.Root open={props.open} onOpenChange={props.onOpenChange}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-40 bg-black/60 dark:bg-black/80" />
        <Dialog.Popup
          className={cn(
            'fixed left-1/2 top-1/2 z-50 -translate-x-1/2 -translate-y-1/2',
            'flex h-[85vh] w-[calc(100vw-2rem)] max-w-[1100px] flex-col overflow-hidden rounded-lg border border-border bg-popover shadow-lg outline-none',
          )}
        >
          <ChooserBody {...props} />
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
