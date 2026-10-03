/**
 * Runs a Creative render locally: loads the render core copied into `dist/creative/` at build time
 * (see `scripts/build-creative.mjs`), drives it against the external v2 creatives API with this
 * project's own API key, and reads back the stored result. Shared by the `conductor creative render`
 * CLI command and the `render_creative` MCP tool so the two never drift.
 *
 * The render core itself (`conductor-creative/job/render.mjs`'s `run()`) knows nothing about how it's
 * launched or what browser it gets — that's `job/transport.mjs`'s and this file's job respectively.
 */
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Config } from '../mcp/config.js'
import { apiGet, apiPost, ApiError } from '../mcp/api.js'
import { findBrowserFactory } from './creative-browser.js'
import { resolveFfmpegPath } from './media-probe.js'

/** Frames per second for a MOTION render's ffmpeg encode — fixed, not user-configurable (see
 * conductor-creative/README.md's job/render.mjs section). */
const MOTION_FPS = 30

export interface RenderFrame {
  id?: string
  placementKey: string
  platform?: string
  sequenceIndex?: number | null
  url?: string
  width?: number
  height?: number
  sizeBytes?: number
  warnings?: string[]
  /** Video-frame fields — only present on a CLIP creative's frames, which reference a video, not a JPEG. */
  durationSeconds?: number
  hasAudio?: boolean
  posterUrl?: string
  contentType?: string
}

export interface RenderCreativeParams {
  creativeId: string
  previewOnly?: boolean
  renderer?: string
  workflowRunId?: string
}

export interface RenderCreativeResult {
  ok: boolean
  renderId?: string
  state?: string
  error?: string | null
  frames: RenderFrame[]
}

interface RenderCore {
  run(opts: {
    transport: unknown
    browserFactory?: () => Promise<unknown>
    log?: (...args: unknown[]) => void
    /** Required for a MOTION render (ignored otherwise) — a local ffmpeg binary's path. */
    ffmpegPath?: string
    /** Frames per second for a MOTION render's ffmpeg encode (default 30). */
    fps?: number
  }): Promise<boolean>
}

interface TransportModule {
  createApiTransport(opts: {
    apiUrl: string
    apiKey: string
    projectId: string
    creativeId: string
    previewOnly?: boolean
    renderer?: string
    workflowRunId?: string
  }): {
    getSpec(): Promise<unknown>
    putFrame(
      placementKey: string,
      opts: { index?: number; width: number; height: number; bytes: Uint8Array; contentType?: string }
    ): Promise<void>
    complete(warnings?: unknown[]): Promise<unknown>
    fail(message: string, log?: string): Promise<unknown>
    getRenderId(): string | undefined
  }
}

/** Where `npm run build` copies `conductor-creative/`'s runtime files, relative to this file's own
 * compiled location (`dist/lib/creative-render.js` -> `dist/creative/job/`). */
function jobDir(): string {
  const here = dirname(fileURLToPath(import.meta.url))
  return join(here, '..', 'creative', 'job')
}

async function loadRenderCore(): Promise<{ run: RenderCore['run']; createApiTransport: TransportModule['createApiTransport'] }> {
  const dir = jobDir()
  const renderPath = join(dir, 'render.mjs')
  const transportPath = join(dir, 'transport.mjs')
  if (!existsSync(renderPath) || !existsSync(transportPath)) {
    throw new Error(
      `Creative render core not found under ${dir}. This is a packaging bug in @cliangdev/conductor ` +
        '(the build step that copies conductor-creative/ into dist/creative/ did not run) — ' +
        'reinstall the CLI, or in this repo run `npm run build`.'
    )
  }
  const [renderMod, transportMod] = await Promise.all([
    import(renderPath) as Promise<RenderCore>,
    import(transportPath) as Promise<TransportModule>,
  ])
  return { run: renderMod.run, createApiTransport: transportMod.createApiTransport }
}

/**
 * A CLIP creative has no local browser render path at all — it's a finished video used as-is, and
 * the backend assembles its frames itself synchronously. This just POSTs the render and relays what
 * came back — `ApiError`'s `message` is already the server's own plain-English sentence (an RFC 7807
 * `detail`), so a failure reaches the caller unchanged rather than wrapped in a generic message.
 */
async function renderViaApi(params: RenderCreativeParams, config: Config): Promise<RenderCreativeResult> {
  try {
    const detail = await apiPost<{ id: string; state: string; error?: string | null; frames?: RenderFrame[] }>(
      `/api/v2/projects/${config.projectId}/marketing/creatives/${params.creativeId}/renders`,
      { previewOnly: !!params.previewOnly, renderer: params.renderer, workflowRunId: params.workflowRunId },
      config
    )
    return { ok: detail.state === 'SUCCEEDED', renderId: detail.id, state: detail.state, error: detail.error ?? null, frames: detail.frames ?? [] }
  } catch (err) {
    if (err instanceof ApiError) {
      return { ok: false, error: err.message, frames: [] }
    }
    throw err
  }
}

/**
 * Renders a Creative end to end. STILL and MOTION creatives both find a local browser and run the
 * render core against the external API, reading back the stored render (frames with signed URLs,
 * final state) whether or not the render itself succeeded — a FAILED render still has an id and an
 * error message worth returning. A MOTION render (not previewOnly) additionally needs a local ffmpeg
 * binary — resolved the same way `upload_creative_media`/`media-probe.ts` finds one (a system binary
 * on PATH first, else the bundled `ffmpeg-static` fallback) — and runs at a fixed 30fps; a previewOnly
 * MOTION render (the key-moments contact sheet) needs neither ffmpeg nor a video encode, so it skips
 * this resolution entirely. CLIP creatives never launch a browser — see {@link renderViaApi}.
 */
export async function renderCreative(
  params: RenderCreativeParams,
  config: Config,
  log: (...args: unknown[]) => void = () => {}
): Promise<RenderCreativeResult> {
  const creative = await apiGet<{ kind?: string }>(
    `/api/v2/projects/${config.projectId}/marketing/creatives/${params.creativeId}`,
    config
  )
  if (creative.kind === 'CLIP') {
    return renderViaApi(params, config)
  }

  let ffmpegPath: string | undefined
  if (creative.kind === 'MOTION' && !params.previewOnly) {
    try {
      ffmpegPath = await resolveFfmpegPath()
    } catch (err) {
      return { ok: false, error: err instanceof Error ? err.message : String(err), frames: [] }
    }
  }

  const { run, createApiTransport } = await loadRenderCore()
  const transport = createApiTransport({
    apiUrl: config.apiUrl,
    apiKey: config.apiKey,
    projectId: config.projectId,
    creativeId: params.creativeId,
    previewOnly: !!params.previewOnly,
    renderer: params.renderer,
    workflowRunId: params.workflowRunId,
  })
  const browserFactory = await findBrowserFactory(log)

  // fps only matters on the MOTION encode path (run() ignores it otherwise); passing it
  // unconditionally keeps this call site simple and matches the engine's own default.
  const ok = await run({ transport, browserFactory, log, ffmpegPath, fps: MOTION_FPS })
  const renderId = transport.getRenderId()
  if (!renderId) {
    return { ok: false, error: 'The render was never started — the API rejected the request before a render id was assigned.', frames: [] }
  }

  try {
    const detail = await apiGet<{ state?: string; error?: string | null; frames?: RenderFrame[] }>(
      `/api/v2/projects/${config.projectId}/marketing/creatives/${params.creativeId}/renders/${renderId}`,
      config
    )
    return { ok, renderId, state: detail.state, error: detail.error ?? null, frames: detail.frames ?? [] }
  } catch (err) {
    return {
      ok,
      renderId,
      error: `Render finished (${ok ? 'succeeded' : 'failed'}) but reading the result back failed: ${err instanceof Error ? err.message : String(err)}`,
      frames: [],
    }
  }
}

// --- Draft rendering (preview before anything is saved) -------------------------

export interface DraftManifestFrame {
  placementKey: string
  index?: number
  file: string
  contentType: string
  width?: number
  height?: number
  sizeBytes?: number
  durationSeconds?: number
  hasAudio?: boolean
  poster?: string
}

export interface DraftManifest {
  ok: boolean
  error?: string
  renderId?: string
  previewOnly?: boolean
  frames: DraftManifestFrame[]
  warnings: Array<{ placementKey?: string; index?: number; message: string }>
}

export interface RenderDraftResult {
  ok: boolean
  error?: string
  manifest?: DraftManifest
}

interface FileTransportModule {
  createFileTransport(opts: { spec: unknown; localFiles?: Record<string, string>; outDir: string }): {
    getError(): string | undefined
    close(): Promise<void>
  }
}

/**
 * Renders a DRAFT spec (the backend's `draft-spec` response — nothing about it is saved) with the same
 * render core a saved Creative uses, but through the file transport: the local files behind
 * `local:<key>` are served to the headless browser from this machine, and the frames plus a
 * `manifest.json` land in `outDir`. Never talks to the Conductor API. A MOTION spec that is not
 * `previewOnly` needs ffmpeg, resolved the same way {@link renderCreative} resolves it.
 */
export async function renderDraft(
  params: { spec: { creative?: { kind?: string }; previewOnly?: boolean }; localFiles: Record<string, string>; outDir: string },
  log: (...args: unknown[]) => void = () => {}
): Promise<RenderDraftResult> {
  const { spec, localFiles, outDir } = params

  let ffmpegPath: string | undefined
  if (spec.creative?.kind === 'MOTION' && !spec.previewOnly) {
    try {
      ffmpegPath = await resolveFfmpegPath()
    } catch (err) {
      return { ok: false, error: err instanceof Error ? err.message : String(err) }
    }
  }

  const { run } = await loadRenderCore()
  const transportPath = join(jobDir(), 'file-transport.mjs')
  if (!existsSync(transportPath)) {
    throw new Error(
      `Creative draft transport not found at ${transportPath}. This is a packaging bug in @cliangdev/conductor — reinstall the CLI, or in this repo run \`npm run build\`.`
    )
  }
  const { createFileTransport } = (await import(transportPath)) as FileTransportModule
  const transport = createFileTransport({ spec, localFiles, outDir })
  const browserFactory = await findBrowserFactory(log)

  let ok: boolean
  try {
    ok = await run({ transport, browserFactory, log, ffmpegPath, fps: MOTION_FPS })
  } finally {
    await transport.close()
  }

  let manifest: DraftManifest | undefined
  try {
    manifest = JSON.parse(await readFile(join(outDir, 'manifest.json'), 'utf8')) as DraftManifest
  } catch {
    manifest = undefined
  }
  if (!ok) return { ok: false, error: transport.getError() ?? manifest?.error ?? 'The draft render failed.', manifest }
  if (!manifest) return { ok: false, error: 'The draft render finished but wrote no manifest.json.' }
  return { ok: true, manifest }
}
