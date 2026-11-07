import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { runCapture, type PlacementFit } from '../../lib/image-common.js'
import { resolveFfmpegPath } from '../../lib/media-probe.js'
import { probeVideo } from '../../lib/mp4-probe.js'
import { writeProvenanceSidecar } from '../../lib/provenance-sidecar.js'
import { generateVideos } from '../../lib/video-generation.js'
import { videoPlacementFit, type VideoParams, type VideoRoute } from '../../lib/video-common.js'
import { prepareGeneratedOutDir } from './creative-images.js'

/**
 * generate_creative_video: makes a short clip from a still (Veo on Vertex AI, or Gemini Omni), on this
 * machine's credentials. Files land in <project>/.conductor/generated/ with provenance sidecars; nothing is
 * sent to Conductor. Always estimates first and needs confirmCost.
 */

export interface GenerateCreativeVideoOptions {
  log?: (message: string) => void
  /** Overrides the output folder (tests). */
  outDir?: string
  cwd?: string
  /** Overrides the generator (tests). */
  generate?: typeof generateVideos
  /** Overrides the contact sheet maker (tests). */
  contactSheet?: (clip: string, durationSeconds: number | null, outDir: string) => Promise<{ data: Buffer; mimeType: string } | undefined>
}

export interface GeneratedClip {
  path: string
  width: number | null
  height: number | null
  durationSeconds: number | null
  fps: number | null
  hasAudio: boolean
}

export interface GenerateCreativeVideoResult {
  files: GeneratedClip[]
  placementFit?: Record<string, PlacementFit>
  estimatedCostUsd: number
  spentTodayUsd: number
  dailyCapUsd: number
  generator: { route: VideoRoute; model: string }
  warnings: string[]
  outDir: string
  provenance: { source: string; licence: string; aiGenerated: true }
  nextStep: string
  image?: { data: Buffer; mimeType: string }
}

const NEXT_STEP =
  'Run the video realism check before using it: look at the contact sheet, then crop faces and hands at 100% at 2 or 3 timestamps (a still frame from each) and look for AI tells (exaggerated or bug eyes, rubber or morphing faces, hands or fingers that merge, appear or vanish, identity or clothing drift between frames, background warping or flicker, garbled text or logos appearing, unnatural mouth motion, a camera move no handheld phone would make); if the clip has sound, listen for lip-sync drift, a robotic voice, sound that does not match the action and music nobody asked for. If a tell shows, regenerate at most twice with a prompt that names each failed tell; if it still fails, stop and say what looks generated. Then trim to the beat (hooks 2-4 s) and pass the path as the MOTION clipPath in preview_creative_draft with motion.background.motion \"none\" (the clip already moves; the default zoom would upscale it and add a fake camera move), or upload it as a CLIP. Its .provenance.json sidecar carries the AI label (TikTok isAigc, YouTube containsSyntheticMedia).'

/** 4 fps contact sheet of the first clip as a JPEG; undefined when ffmpeg is missing or fails. */
export async function makeContactSheet(clip: string, durationSeconds: number | null, outDir: string): Promise<{ data: Buffer; mimeType: string } | undefined> {
  try {
    const ffmpeg = await resolveFfmpegPath()
    const frames = Math.max(1, Math.min(32, Math.ceil((durationSeconds ?? 4) * 4)))
    const cols = Math.min(8, frames)
    const rows = Math.ceil(frames / cols)
    const out = join(outDir, '.contact-sheet.jpg')
    const res = await runCapture(ffmpeg, ['-y', '-v', 'error', '-i', clip, '-vf', `fps=4,scale=270:-2,tile=${cols}x${rows}`, '-frames:v', '1', '-q:v', '5', out])
    if (res.code !== 0) return undefined
    return { data: await readFile(out), mimeType: 'image/jpeg' }
  } catch {
    return undefined
  }
}

export async function generateCreativeVideo(params: VideoParams, options: GenerateCreativeVideoOptions = {}): Promise<GenerateCreativeVideoResult> {
  const run = options.generate ?? generateVideos
  // The gate (route, validation, caps, confirmation) runs inside and throws before anything is created on disk.
  const gen = await run(params, { onProgress: options.log, ...(options.cwd ? { cwd: options.cwd } : {}) })

  const outDir = resolve(options.outDir ?? (await prepareGeneratedOutDir(gen.request.prompt, options.cwd)))
  await mkdir(outDir, { recursive: true })

  const date = new Date().toISOString().slice(0, 10)
  const routeName = gen.route === 'vertex' ? 'Vertex AI' : 'the Gemini API'
  const provenance = { source: `Generated with ${gen.model} via ${routeName} on ${date}`, licence: 'Generated, house use', aiGenerated: true as const }
  const warnings = [...gen.warnings]

  const files: GeneratedClip[] = []
  for (const [i, bytes] of gen.videos.entries()) {
    const path = join(outDir, `clip-${i + 1}.mp4`)
    await writeFile(path, bytes)
    const probe = await probeVideo(path).catch(() => ({ width: null, height: null, durationSeconds: null, fps: null, hasAudio: false }))
    files.push({ path, width: probe.width, height: probe.height, durationSeconds: probe.durationSeconds, fps: probe.fps, hasAudio: probe.hasAudio })
    await writeProvenanceSidecar(path, {
      ...provenance,
      prompt: gen.request.prompt,
      model: gen.model,
      route: gen.route,
      ...(gen.route === 'vertex' ? { tier: gen.request.tier } : {}),
      resolution: gen.request.resolution,
      generateAudio: gen.request.generateAudio,
      estimatedCostUsd: gen.perClipUsd,
      ...(gen.sourceImagePath ? { sourceImagePath: gen.sourceImagePath } : {}),
      ...(gen.lastFramePath ? { lastFramePath: gen.lastFramePath } : {}),
      generatedAt: new Date().toISOString(),
      synthId: true,
      ...(probe.durationSeconds !== null ? { durationSeconds: probe.durationSeconds } : {}),
    })
  }

  const first = files[0]
  let fit: Record<string, PlacementFit> | undefined
  if (first?.width && first.height) {
    const v = videoPlacementFit(first.width, first.height)
    fit = v.placementFit
    warnings.push(...v.warnings)
  }
  const image = first ? await (options.contactSheet ?? makeContactSheet)(first.path, first.durationSeconds, outDir) : undefined

  return {
    files,
    ...(fit ? { placementFit: fit } : {}),
    // What this call cost: less than the estimate when an Omni run stopped early.
    estimatedCostUsd: gen.billedUsd,
    spentTodayUsd: gen.spentTodayUsd,
    dailyCapUsd: gen.dailyCapUsd,
    generator: { route: gen.route, model: gen.model },
    warnings,
    outDir,
    provenance,
    nextStep: NEXT_STEP,
    ...(image ? { image } : {}),
  }
}
