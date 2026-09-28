'use client'

// COND-24 T3: the Renders panel on the Creative editor page. Rendering itself runs locally — Claude
// Code/Desktop's `render_creative` MCP tool or `conductor creative render <id>` on the CLI — so this
// panel is read-only: it lists what a local render produced, shows the latest SUCCEEDED render's
// frames as a grid, and polls while a render is in flight. There is deliberately no "Render" button
// here that launches anything server-side.

import { useCallback, useEffect, useRef, useState } from 'react'
import { CopyIcon, ImagesIcon, Maximize2Icon, SparklesIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { toastError, toastSuccess } from '@/components/ui/toast'
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { formatDuration, timeAgo } from '@/lib/format'
import { statusHue } from '@/lib/workflows'
import { FullSizeViewer } from '@/components/marketing/creatives/FullSizeViewer'
import {
  listCreativeRenders,
  requestCreativeRender,
  type CreativeKind,
  type CreativeRegistry,
  type CreativeRender,
} from '@/components/marketing/creatives/types'

const POLL_MS = 5000

function copy(text: string) {
  navigator.clipboard
    ?.writeText(text)
    .then(() => toastSuccess('Copied'))
    .catch(() => toastError('Could not copy — select and copy manually'))
}

function placementLabel(registry: CreativeRegistry | null | undefined, key: string): string {
  if (key === 'sheet') return 'Contact sheet'
  return registry?.placements.find((p) => p.key === key)?.label ?? key
}

/** A CLIP render's frames carry `contentType`; older cached rows without it are images. */
function isVideoFrame(frame: { contentType?: string | null }): boolean {
  return Boolean(frame.contentType?.startsWith('video/'))
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const kb = bytes / 1024
  if (kb < 1024) return `${kb.toFixed(kb < 10 ? 1 : 0)} KB`
  return `${(kb / 1024).toFixed((kb / 1024) < 10 ? 1 : 0)} MB`
}

export interface RendersPanelProps {
  projectId: string
  creativeId: string
  /** Used only in the empty-state's copyable prompt/command, e.g. "12a". */
  creativeDisplayId: string
  /** The Creative's current `version` — a render older than this is flagged stale. */
  creativeVersion: number
  /** CLIP creatives get a web "Prepare for posting" button — rendering a CLIP is a server-side
   *  frame copy, not a local Playwright job, so there's no reason to make the marketer leave the
   *  browser for it. STILL/MOTION keep the local-only flow (Claude Code/Desktop or the CLI). */
  creativeKind: CreativeKind
  token: string
  registry?: CreativeRegistry | null
  /** Told the latest SUCCEEDED render whenever the list (re)loads, so the page can gate "Use in Post". */
  onLatestSucceededChange?: (render: CreativeRender | null) => void
}

// A preview render holds only the contact sheet: it has no frames to show or attach to a Post.
function isAttachableRender(r: CreativeRender): boolean {
  return r.state === 'SUCCEEDED' && !r.previewOnly
}

export function RendersPanel({
  projectId,
  creativeId,
  creativeDisplayId,
  creativeVersion,
  creativeKind,
  token,
  registry,
  onLatestSucceededChange,
}: RendersPanelProps) {
  const canManage = useCan('creative.manage')
  const [renders, setRenders] = useState<CreativeRender[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [preparing, setPreparing] = useState(false)
  // Index into the latest succeeded render's non-sheet frames of the one open in the full-size
  // viewer, or null when it's closed.
  const [viewerFrameIndex, setViewerFrameIndex] = useState<number | null>(null)
  const onLatestSucceededChangeRef = useRef(onLatestSucceededChange)
  useEffect(() => {
    onLatestSucceededChangeRef.current = onLatestSucceededChange
  }, [onLatestSucceededChange])

  const load = useCallback(
    async (showSpinner: boolean) => {
      if (showSpinner) setRefreshing(true)
      try {
        const rows = await listCreativeRenders(projectId, creativeId, token)
        setRenders(rows)
        setError(null)
        onLatestSucceededChangeRef.current?.(rows.find(isAttachableRender) ?? null)
      } catch (err) {
        setError(apiErrorMessage(err, 'Could not load renders'))
      } finally {
        if (showSpinner) setRefreshing(false)
      }
    },
    [projectId, creativeId, token],
  )

  useEffect(() => {
    void load(false)
  }, [load])

  const anyRunning = (renders ?? []).some((r) => r.state === 'RUNNING')

  useEffect(() => {
    if (!anyRunning) return
    const id = setInterval(() => void load(false), POLL_MS)
    return () => clearInterval(id)
  }, [anyRunning, load])

  const prompt = `Render creative ${creativeDisplayId} with render_creative`
  const cliCommand = `conductor creative render ${creativeDisplayId}`

  /** CLIP only — see the `creativeKind` prop doc comment. The server assembles the frames itself
   *  (a copy per covered placement) and answers SUCCEEDED immediately; no local job to wait on. */
  async function handlePrepare() {
    setPreparing(true)
    try {
      await requestCreativeRender(projectId, creativeId, { renderer: 'web' }, token)
      await load(true)
      toastSuccess('Prepared for posting')
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not prepare this Creative'))
    } finally {
      setPreparing(false)
    }
  }

  if (renders === null && !error) {
    return (
      <Card className="space-y-3 p-4">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Renders</h3>
        <Skeleton className="h-24 w-full" />
      </Card>
    )
  }

  const latestSucceeded = (renders ?? []).find(isAttachableRender) ?? null
  const stale = latestSucceeded != null && latestSucceeded.creativeVersion < creativeVersion
  // The contact sheet frame has nothing of its own to view full size — only the real per-placement
  // outputs go in the grid and the viewer.
  const frames = latestSucceeded?.frames.filter((f) => f.placementKey !== 'sheet') ?? []

  return (
    <Card className="space-y-3 p-4" data-testid="renders-panel">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Renders</h3>
        <div className="flex items-center gap-2">
          {creativeKind === 'CLIP' && canManage && (
            <Button size="sm" onClick={() => void handlePrepare()} disabled={preparing}>
              <SparklesIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
              {preparing ? 'Preparing…' : 'Prepare for posting'}
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={() => void load(true)} disabled={refreshing}>
            {refreshing ? 'Refreshing…' : 'Refresh'}
          </Button>
        </div>
      </div>

      {error && <Alert variant="destructive">{error}</Alert>}

      {(renders?.length ?? 0) === 0 ? (
        <EmptyState
          icon={ImagesIcon}
          title="No renders yet"
          description={
            creativeKind === 'CLIP'
              ? 'Rendering a clip is instant — Prepare for posting above, or from Claude Code/Desktop or the CLI.'
              : 'Rendering runs locally, on your own machine, through Claude Code/Desktop or the CLI — there is nothing to click here.'
          }
          action={
            <div className="w-full max-w-sm space-y-2 text-left">
              <div className="flex items-center gap-2 rounded-md border border-border bg-surface-2 px-2 py-1.5">
                <code className="flex-1 truncate text-xs">{prompt}</code>
                <Button variant="ghost" size="sm" onClick={() => copy(prompt)} aria-label="Copy prompt">
                  <CopyIcon className="h-3.5 w-3.5" aria-hidden />
                </Button>
              </div>
              <div className="flex items-center gap-2 rounded-md border border-border bg-surface-2 px-2 py-1.5">
                <code className="flex-1 truncate text-xs">{cliCommand}</code>
                <Button variant="ghost" size="sm" onClick={() => copy(cliCommand)} aria-label="Copy CLI command">
                  <CopyIcon className="h-3.5 w-3.5" aria-hidden />
                </Button>
              </div>
            </div>
          }
        />
      ) : (
        <div className="space-y-3">
          <ul className="space-y-1.5">
            {renders!.map((r) => (
              <li key={r.id} className="flex items-center gap-2 text-sm">
                <StatusBadge status={r.state} hue={statusHue(r.state)} label={r.state} />
                <span className="text-muted-foreground">{timeAgo(r.requestedAt)}</span>
                {r.renderer && <span className="text-xs text-muted-foreground">· {r.renderer}</span>}
                {r.state === 'FAILED' && r.error && (
                  <span className="truncate text-xs text-destructive" title={r.error}>
                    {r.error}
                  </span>
                )}
                {r.state === 'SUCCEEDED' && r.frames.some((f) => f.warnings.length > 0) && (
                  <span className="text-xs text-status-progress">
                    {r.frames.reduce((n, f) => n + f.warnings.length, 0)} warning
                    {r.frames.reduce((n, f) => n + f.warnings.length, 0) === 1 ? '' : 's'}
                  </span>
                )}
              </li>
            ))}
          </ul>

          {latestSucceeded && (
            <div className="space-y-2">
              {stale && (
                <Alert variant="warning" data-testid="render-stale-badge">
                  This render is from v{latestSucceeded.creativeVersion}; the Creative is now v
                  {creativeVersion}. Re-render to pick up the latest changes.
                </Alert>
              )}
              <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                {frames.map((frame, i) => (
                  <div key={frame.id} className="group relative space-y-1 overflow-hidden rounded-md border border-border">
                    {isVideoFrame(frame) ? (
                      <button
                        type="button"
                        onClick={() => setViewerFrameIndex(i)}
                        aria-label={`View ${placementLabel(registry, frame.placementKey)} full size`}
                        className="block w-full"
                      >
                        <video
                          controls
                          poster={frame.posterUrl ?? undefined}
                          src={frame.url}
                          data-testid={`render-frame-video-${frame.id}`}
                          className="aspect-square w-full bg-surface-3 object-contain"
                        />
                      </button>
                    ) : (
                      <button
                        type="button"
                        onClick={() => setViewerFrameIndex(i)}
                        aria-label={`View ${placementLabel(registry, frame.placementKey)} full size`}
                        className="block w-full"
                      >
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img
                          src={frame.url}
                          alt={placementLabel(registry, frame.placementKey)}
                          className="aspect-square w-full bg-surface-3 object-contain"
                        />
                      </button>
                    )}
                    <span
                      aria-hidden
                      className="pointer-events-none absolute right-1 top-1 z-10 rounded-md bg-surface/90 p-1 text-muted-foreground opacity-0 shadow-sm transition-opacity group-hover:opacity-100 group-focus-within:opacity-100"
                    >
                      <Maximize2Icon className="h-3.5 w-3.5" />
                    </span>
                    <div className="space-y-0.5 px-2 pb-2 text-xs">
                      <p className="font-medium text-foreground">{placementLabel(registry, frame.placementKey)}</p>
                      <p className="text-muted-foreground">
                        {frame.platform ?? '—'} · {frame.width}×{frame.height} · {formatBytes(frame.sizeBytes)}
                        {frame.durationSeconds != null && ` · ${formatDuration(frame.durationSeconds)}`}
                      </p>
                      {frame.warnings.length > 0 && (
                        <p className="text-status-progress">{frame.warnings.join('; ')}</p>
                      )}
                      <a
                        href={frame.url}
                        target="_blank"
                        rel="noreferrer"
                        className="inline-block text-primary underline-offset-2 hover:underline"
                      >
                        Download
                      </a>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}

          {viewerFrameIndex !== null && frames[viewerFrameIndex] && (
            <FullSizeViewer
              open
              onOpenChange={(open) => {
                if (!open) setViewerFrameIndex(null)
              }}
              title={placementLabel(registry, frames[viewerFrameIndex].placementKey)}
              subtitle={`${frames[viewerFrameIndex].width}×${frames[viewerFrameIndex].height}px`}
              onPrev={viewerFrameIndex > 0 ? () => setViewerFrameIndex(viewerFrameIndex - 1) : undefined}
              onNext={viewerFrameIndex < frames.length - 1 ? () => setViewerFrameIndex(viewerFrameIndex + 1) : undefined}
            >
              {isVideoFrame(frames[viewerFrameIndex]) ? (
                <video
                  controls
                  poster={frames[viewerFrameIndex].posterUrl ?? undefined}
                  src={frames[viewerFrameIndex].url}
                  className="max-h-[90vh] max-w-[85vw] object-contain"
                />
              ) : (
                // eslint-disable-next-line @next/next/no-img-element
                <img
                  src={frames[viewerFrameIndex].url}
                  alt={placementLabel(registry, frames[viewerFrameIndex].placementKey)}
                  className="max-h-[90vh] max-w-[85vw] object-contain"
                />
              )}
            </FullSizeViewer>
          )}
        </div>
      )}
    </Card>
  )
}
