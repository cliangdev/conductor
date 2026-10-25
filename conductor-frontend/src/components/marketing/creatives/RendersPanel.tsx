'use client'

// COND-24 T3: the Renders panel on the Creative editor page. Rendering itself runs locally — Claude
// Code/Desktop's `render_creative` MCP tool or `conductor creative render <id>` on the CLI — so this
// panel is read-only: it lists what a local render produced, shows the latest SUCCEEDED render's
// frames as a grid, and polls while a render is in flight. For a Creative that needs a local render
// (`webRender`) there is also an explicit "Render" button that dispatches the `conductor-creative-render`
// system Workflow, which runs on the workspace's BYO Cloud Run runtime target when that target is set
// up for rendering and otherwise on its self-hosted daemon; it is never fired automatically.

import { useCallback, useEffect, useRef, useState } from 'react'
import Link from 'next/link'
import { ImagesIcon, Maximize2Icon, PlayIcon, SparklesIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { CopyableCode } from '@/components/ui/copyable-code'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { toastError, toastSuccess } from '@/components/ui/toast'
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage, type ApiError } from '@/lib/api'
import { REPO_URL } from '@/components/site/SiteChrome'
import { formatDuration, timeAgo } from '@/lib/format'
import { statusHue } from '@/lib/workflows'
import { FullSizeViewer } from '@/components/marketing/creatives/FullSizeViewer'
import { isRenderRunActive, renderRunPhase } from '@/components/marketing/creatives/creativeCopy'
import {
  dispatchCreativeRenderRun,
  getLatestCreativeRenderRun,
  listCreativeRenders,
  requestCreativeRender,
  type CreativeKind,
  type CreativeRegistry,
  type CreativeRender,
  type CreativeRenderRun,
} from '@/components/marketing/creatives/types'

const POLL_MS = 5000

const SELF_HOSTED_DOCS_URL = `${REPO_URL}/blob/main/docs/workflows.md#self-hosted-setup`

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
  /** CLIP/CAROUSEL whose render happens locally (a music track, or a replaced/mixed/muted clip sound):
   *  no "Prepare for posting" button, the same "renders locally" guidance MOTION shows. */
  localRender?: boolean
  /** The Creative needs a browser/ffmpeg render (`needsLocalRender`): show the Render button, which
   *  runs it through the `conductor-creative-render` Workflow: on the designated Cloud Run runtime target
   *  when its render job is ready, otherwise on the workspace's self-hosted daemon. */
  webRender?: boolean
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
  localRender = false,
  webRender = false,
  token,
  registry,
  onLatestSucceededChange,
}: RendersPanelProps) {
  const canManage = useCan('creative.manage')
  const [renders, setRenders] = useState<CreativeRender[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  // The render-run status poll has its own error so it neither hides nor clears the list's.
  const [runError, setRunError] = useState<string | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [preparing, setPreparing] = useState(false)
  // The latest render-run (system Workflow) for this Creative, from GET .../render-runs/latest.
  const [run, setRun] = useState<CreativeRenderRun | null>(null)
  const [dispatching, setDispatching] = useState(false)
  const dispatchingRef = useRef(false)
  const [dispatchError, setDispatchError] = useState<string | null>(null)
  const [violations, setViolations] = useState<string[]>([])
  const [disabledWorkflowId, setDisabledWorkflowId] = useState<string | null>(null)
  const prevRunActiveRef = useRef(false)
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

  const loadRun = useCallback(async () => {
    try {
      setRun(await getLatestCreativeRenderRun(projectId, creativeId, token))
      setRunError(null)
    } catch (err) {
      setRunError(apiErrorMessage(err, 'Could not load the render status'))
    }
  }, [projectId, creativeId, token])

  useEffect(() => {
    if (webRender) void loadRun()
  }, [webRender, loadRun])

  const anyRunning = (renders ?? []).some((r) => r.state === 'RUNNING')
  const runActive = webRender && isRenderRunActive(run)

  useEffect(() => {
    if (!anyRunning && !runActive) return
    const id = setInterval(() => {
      void load(false)
      if (webRender) void loadRun()
    }, POLL_MS)
    return () => clearInterval(id)
  }, [anyRunning, runActive, webRender, load, loadRun])

  // When the run settles, pull in the render it produced.
  useEffect(() => {
    if (prevRunActiveRef.current && !runActive) void load(false)
    prevRunActiveRef.current = runActive
  }, [runActive, load])

  async function handleRender() {
    if (dispatchingRef.current) return
    dispatchingRef.current = true
    setDispatching(true)
    setDispatchError(null)
    setViolations([])
    setDisabledWorkflowId(null)
    try {
      const res = await dispatchCreativeRenderRun(projectId, creativeId, token)
      setRun({ workflowId: res.workflowId, runId: res.runId, status: res.status })
      await loadRun()
    } catch (err) {
      const apiErr = err as Partial<ApiError>
      const ext = apiErr.extensions ?? {}
      if (apiErr.status === 409 && apiErr.code === 'RENDER_RUN_ACTIVE') {
        // Someone (or another tab) already started it: show that run, not an error.
        if (typeof ext.workflowId === 'string' && typeof ext.runId === 'string') {
          setRun({ workflowId: ext.workflowId, runId: ext.runId, status: 'RUNNING' })
        }
        await loadRun()
      } else if (apiErr.status === 409 && apiErr.code === 'RENDER_WORKFLOW_DISABLED') {
        setDispatchError(apiErrorMessage(err, 'The render workflow is disabled.'))
        setDisabledWorkflowId(typeof ext.workflowId === 'string' ? ext.workflowId : null)
      } else if (apiErr.status === 422 && (apiErr.violations?.length ?? 0) > 0) {
        setViolations(apiErr.violations!.map((v) => v.message))
        setDispatchError(apiErrorMessage(err, 'This Creative cannot be rendered yet.'))
      } else {
        setDispatchError(apiErrorMessage(err, 'Could not start the render'))
      }
    } finally {
      dispatchingRef.current = false
      setDispatching(false)
    }
  }

  const prompt = `Render creative ${creativeDisplayId} with render_creative`
  const cliCommand = `conductor creative render ${creativeDisplayId}`

  /** CLIP and CAROUSEL — see the `creativeKind` prop doc comment. The server assembles the frames itself
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
  // A CAROUSEL render has one frame per slide per placement — say which slide it is.
  const frameLabel = (f: { placementKey: string; sequenceIndex?: number | null }) =>
    placementLabel(registry, f.placementKey) +
    (creativeKind === 'CAROUSEL' && f.sequenceIndex != null ? ` · slide ${f.sequenceIndex + 1}` : '')

  return (
    <Card className="space-y-3 p-4" data-testid="renders-panel">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Renders</h3>
        <div className="flex items-center gap-2">
          {webRender && canManage && (
            <Button size="sm" onClick={() => void handleRender()} disabled={dispatching || runActive}>
              <PlayIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
              {dispatching ? 'Starting…' : 'Render'}
            </Button>
          )}
          {(creativeKind === 'CLIP' || creativeKind === 'CAROUSEL') && !localRender && canManage && (
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
      {runError && (
        <Alert variant="destructive" data-testid="render-run-error">
          {runError}
        </Alert>
      )}

      {dispatchError && (
        <Alert variant="destructive" data-testid="render-dispatch-error">
          <div className="space-y-1">
            <p>{dispatchError}</p>
            {violations.length > 0 && (
              <ul className="list-disc pl-4">
                {violations.map((m) => (
                  <li key={m}>{m}</li>
                ))}
              </ul>
            )}
            {disabledWorkflowId && (
              <Link
                href={`/app/projects/${projectId}/workflows/${disabledWorkflowId}`}
                className="underline underline-offset-2"
              >
                Open the workflow
              </Link>
            )}
          </div>
        </Alert>
      )}

      {webRender && run && <RenderRunStatus projectId={projectId} run={run} />}

      {(renders?.length ?? 0) === 0 ? (
        <EmptyState
          icon={ImagesIcon}
          title="No renders yet"
          description={
            webRender
              ? 'Click Render to build this Creative on your workspace\'s own runner. You can also render it from Claude Code/Desktop or the CLI.'
              : localRender && (creativeKind === 'CAROUSEL' || creativeKind === 'CLIP')
              ? creativeKind === 'CAROUSEL'
                ? 'Rendering the slideshow video for a carousel with music runs locally, on your own machine, through Claude Code/Desktop or the CLI. There is nothing to click here.'
                : 'Rendering a clip with changed sound runs locally, on your own machine, through Claude Code/Desktop or the CLI. There is nothing to click here.'
              : creativeKind === 'CAROUSEL'
              ? 'Preparing finished images is instant — Prepare for posting above, or from Claude Code/Desktop or the CLI.'
              : creativeKind === 'CLIP'
              ? 'Rendering a clip is instant — Prepare for posting above, or from Claude Code/Desktop or the CLI.'
              : creativeKind === 'MOTION'
                ? 'Rendering a Motion video runs locally, on your own machine, through Claude Code/Desktop or the CLI — about 20 seconds per placement. There is nothing to click here.'
                : 'Rendering runs locally, on your own machine, through Claude Code/Desktop or the CLI — there is nothing to click here.'
          }
          action={
            webRender ? undefined : (
            <div className="w-full max-w-sm space-y-2 text-left">
              <p className="text-xs text-muted-foreground">
                In Claude Code: <code className="text-foreground">/conductor:creative</code>.
              </p>
              <p className="text-xs text-muted-foreground">
                In Claude Desktop (or Claude Code): ask, e.g. copy this prompt, or the CLI command:
              </p>
              <CopyableCode text={prompt} label="Copy render prompt" />
              <CopyableCode text={cliCommand} label="Copy render CLI command" />
              <p className="text-xs text-muted-foreground">
                If <code className="text-foreground">render_creative</code> isn&apos;t available, update the
                CLI: <code className="text-foreground">npm i -g @cliangdev/conductor@latest</code>
              </p>
            </div>
            )
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
                        aria-label={`View ${frameLabel(frame)} full size`}
                        className="block w-full"
                      >
                        <video preload="metadata"
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
                        aria-label={`View ${frameLabel(frame)} full size`}
                        className="block w-full"
                      >
                        {/* eslint-disable-next-line @next/next/no-img-element */}
                        <img loading="lazy" decoding="async"
                          src={frame.url}
                          alt={frameLabel(frame)}
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
                      <p className="font-medium text-foreground">{frameLabel(frame)}</p>
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
              title={frameLabel(frames[viewerFrameIndex])}
              subtitle={`${frames[viewerFrameIndex].width}×${frames[viewerFrameIndex].height}px`}
              onPrev={viewerFrameIndex > 0 ? () => setViewerFrameIndex(viewerFrameIndex - 1) : undefined}
              onNext={viewerFrameIndex < frames.length - 1 ? () => setViewerFrameIndex(viewerFrameIndex + 1) : undefined}
            >
              {isVideoFrame(frames[viewerFrameIndex]) ? (
                <video preload="metadata"
                  controls
                  poster={frames[viewerFrameIndex].posterUrl ?? undefined}
                  src={frames[viewerFrameIndex].url}
                  className="max-h-[90vh] max-w-[85vw] object-contain"
                />
              ) : (
                // eslint-disable-next-line @next/next/no-img-element
                <img loading="lazy" decoding="async"
                  src={frames[viewerFrameIndex].url}
                  alt={frameLabel(frames[viewerFrameIndex])}
                  className="max-h-[90vh] max-w-[85vw] object-contain"
                />
              )}
            </FullSizeViewer>
          )}
        </div>
      )}
      {webRender && (
        <details className="text-xs text-muted-foreground" data-testid="render-local-help">
          <summary className="cursor-pointer select-none">Render with Claude Code or the CLI instead</summary>
          <div className="mt-2 w-full max-w-sm space-y-2">
            <p>
              In Claude Code: <code className="text-foreground">/conductor:creative</code>, or copy this prompt
              or the CLI command:
            </p>
            <CopyableCode text={prompt} label="Copy render prompt" />
            <CopyableCode text={cliCommand} label="Copy render CLI command" />
          </div>
        </details>
      )}
    </Card>
  )
}

function RenderRunStatus({ projectId, run }: { projectId: string; run: CreativeRenderRun }) {
  const phase = renderRunPhase(run)
  if (phase === 'succeeded') return null
  const onTarget = run.runtime === 'target'
  const runHref = `/app/projects/${projectId}/workflows/${run.workflowId}/runs/${run.runId}`
  const viewRun = (
    <Link href={runHref} className="text-xs text-primary underline-offset-2 hover:underline">
      View run
    </Link>
  )
  if (phase === 'failed') {
    return (
      <Alert variant="destructive" data-testid="render-run-failed">
        <div className="space-y-1">
          <p className="font-medium">Render failed</p>
          {run.error && <p>{run.error}</p>}
          {viewRun}
        </div>
      </Alert>
    )
  }
  return (
    <div className="space-y-1.5" data-testid="render-run-status">
      <div className="flex items-center gap-2">
        {onTarget ? (
          <StatusBadge
            status="running"
            hue="amber"
            label={run.targetName ? `Rendering on ${run.targetName}` : 'Rendering on your Cloud Run target'}
          />
        ) : phase === 'waiting' ? (
          <StatusBadge status="awaitingpickup" hue="amber" label="Waiting for a runner" />
        ) : (
          <StatusBadge status="running" hue="amber" label="Rendering…" />
        )}
        {viewRun}
      </div>
      {phase === 'waiting' && !onTarget && (
        <p className="text-xs text-muted-foreground">
          Start the conductor daemon on a machine with Docker (<code className="text-foreground">conductor start</code>
          ).{' '}
          <a
            href={SELF_HOSTED_DOCS_URL}
            target="_blank"
            rel="noreferrer"
            className="text-primary underline-offset-2 hover:underline"
          >
            Self-hosted setup
          </a>
        </p>
      )}
    </div>
  )
}
