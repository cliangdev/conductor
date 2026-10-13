'use client'

// The Media library page body: a browsable grid of the project's creative media (images, video,
// audio) with kind tabs, label search, filters, upload, a detail panel, multi-select and
// "Make carousel" from the selected images.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { FilmIcon, UploadCloudIcon, XIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs } from '@/components/ui/tabs'
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
  listCreativePhotos,
  patchCreativePhoto,
  type CreativePhoto,
} from '@/components/marketing/creatives/types'
import { MakeCarouselDialog } from './MakeCarouselDialog'
import { MediaDetailSheet } from './MediaDetailSheet'
import { inSet, setPrefix, sortNatural } from './mediaSets'

type KindTab = 'ALL' | 'IMAGE' | 'VIDEO' | 'AUDIO'
const TABS: { value: KindTab; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'IMAGE', label: 'Images' },
  { value: 'VIDEO', label: 'Video' },
  { value: 'AUDIO', label: 'Audio' },
]

const ACCEPT = Object.values(ALLOWED_CONTENT_TYPES).flat().join(',')

function FilterChip({ pressed, onClick, children }: { pressed: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={cn(
        'rounded-full border px-3 py-1 text-[13px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        pressed ? 'border-accent bg-accent-soft text-foreground' : 'border-border text-muted-foreground hover:bg-surface-3',
      )}
    >
      {children}
    </button>
  )
}

export function MediaLibrary({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const canManage = useCan('creative.manage')
  const token = accessToken ?? ''

  const [items, setItems] = useState<CreativePhoto[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [kind, setKind] = useState<KindTab>('ALL')
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')
  const [unchecked, setUnchecked] = useState(false)
  const [showBlocked, setShowBlocked] = useState(false)
  const [aiOnly, setAiOnly] = useState(false)
  const [selected, setSelected] = useState<string[]>([])
  const anchor = useRef<number | null>(null)
  const [detailId, setDetailId] = useState<string | null>(null)
  const [carouselOpen, setCarouselOpen] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [uploadStatus, setUploadStatus] = useState<string | null>(null)
  const fileInput = useRef<HTMLInputElement>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => setDebounced(query.trim()), 300)
    return () => clearTimeout(t)
  }, [query])

  useEffect(() => {
    if (!projectId || !accessToken) return
    let cancelled = false
    ;(async () => {
      try {
        // The server filters by label *prefix*; a mid-label search ("slide 03") finds nothing there, so
        // fall back to the whole library and let the substring filter below narrow it.
        let rows = await listCreativePhotos(projectId, accessToken, showBlocked, undefined, debounced || undefined)
        if (debounced && rows.length === 0) rows = await listCreativePhotos(projectId, accessToken, showBlocked)
        if (cancelled) return
        setItems(rows)
        setError(null)
      } catch (err) {
        if (cancelled) return
        setItems((prev) => prev ?? [])
        setError(apiErrorMessage(err, 'Could not load media — please try again.'))
      }
    })()
    return () => {
      cancelled = true
    }
  }, [projectId, accessToken, debounced, showBlocked, reloadKey])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    return sortNatural(items ?? []).filter(
      (i) =>
        (kind === 'ALL' || (i.mediaKind ?? 'IMAGE') === kind) &&
        (!q || (i.label ?? '').toLowerCase().includes(q)) &&
        (!unchecked || !i.checked) &&
        (!aiOnly || i.aiGenerated) &&
        (showBlocked || !i.blocked),
    )
  }, [items, kind, query, unchecked, aiOnly, showBlocked])

  const selectedItems = useMemo(() => (items ?? []).filter((i) => selected.includes(i.id)), [items, selected])
  const detail = (items ?? []).find((i) => i.id === detailId) ?? null
  const nonImageSelected = selectedItems.some((i) => (i.mediaKind ?? 'IMAGE') !== 'IMAGE')

  const replaceItem = useCallback((next: CreativePhoto) => {
    setItems((prev) => (prev ?? []).map((i) => (i.id === next.id ? next : i)))
  }, [])

  function select(item: CreativePhoto, index: number, range: boolean) {
    if (range && anchor.current != null) {
      const [a, b] = [Math.min(anchor.current, index), Math.max(anchor.current, index)]
      const ids = visible.slice(a, b + 1).map((i) => i.id)
      setSelected((prev) => [...prev, ...ids.filter((id) => !prev.includes(id))])
    } else {
      setSelected((prev) => (prev.includes(item.id) ? prev.filter((id) => id !== item.id) : [...prev, item.id]))
      anchor.current = index
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

  function selectSet() {
    const prefixes = new Set(selectedItems.map((i) => setPrefix(i.label)).filter(Boolean))
    const ids = (items ?? []).filter((i) => [...prefixes].some((p) => inSet(i.label, p))).map((i) => i.id)
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

  const filtering = query.trim() !== '' || unchecked || aiOnly || kind !== 'ALL'

  return (
    <div className="space-y-4 pb-20">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          type="search"
          aria-label="Search by label"
          placeholder="Search labels…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="h-9 max-w-xs"
        />
        <FilterChip pressed={unchecked} onClick={() => setUnchecked((v) => !v)}>
          Unchecked
        </FilterChip>
        <FilterChip pressed={showBlocked} onClick={() => setShowBlocked((v) => !v)}>
          Blocked
        </FilterChip>
        <FilterChip pressed={aiOnly} onClick={() => setAiOnly((v) => !v)}>
          AI-generated
        </FilterChip>
        {canManage && (
          <div className="ml-auto">
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
          </div>
        )}
      </div>

      <Tabs
        ariaLabel="Media type"
        value={kind}
        onValueChange={(v) => setKind(v as KindTab)}
        items={TABS.map((t) => ({ value: t.value, label: t.label }))}
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
                  isSelected ? 'border-accent ring-2 ring-accent' : 'border-border',
                )}
              >
                <button
                  type="button"
                  aria-label={`Open ${item.label || 'Untitled'}`}
                  onClick={(e) => {
                    if (e.shiftKey || e.metaKey || e.ctrlKey) select(item, index, e.shiftKey)
                    else setDetailId(item.id)
                  }}
                  className={cn('relative block aspect-square w-full bg-surface-3', item.blocked && 'opacity-50')}
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
                      'checked:border-accent checked:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
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
                  {!item.checked && <span className="text-muted-foreground">Unchecked</span>}
                </div>
              </li>
            )
          })}
        </ul>
      )}

      {canManage && selected.length > 0 && (
        <div
          role="toolbar"
          aria-label="Selection actions"
          className="fixed inset-x-4 bottom-4 z-30 mx-auto flex max-w-3xl flex-wrap items-center gap-2 rounded-lg border border-border bg-popover px-4 py-2.5 shadow-lg"
        >
          <span className="text-sm font-medium text-foreground">{selected.length} selected</span>
          <Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => void markChecked()}>
            Mark checked
          </Button>
          <Button type="button" variant="outline" size="sm" onClick={selectSet}>
            Select set
          </Button>
          <Button type="button" size="sm" disabled={nonImageSelected} onClick={() => setCarouselOpen(true)}>
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
