import { existsSync, statSync } from 'node:fs'
import { lstat, mkdir, readdir, rm, writeFile } from 'node:fs/promises'
import { isAbsolute, join, resolve, sep } from 'node:path'
import { IMAGE_QUALITIES, normalizeAspect, parseSize, type GeneratedImage, type ImageQuality, type PlacementFit } from '../../lib/image-common.js'
import { generateImages } from '../../lib/image-generation.js'
import { findProjectRoot, slugify, stamp } from './creative-drafts.js'

/**
 * generate_creative_image: makes a photo on this machine (OpenAI Images API, or the person's Codex CLI when they agreed) and returns it
 * (inline preview + local paths with provenance sidecars) for use as `photoPath` in
 * preview_creative_draft. Nothing is sent to Conductor.
 */

export const GENERATED_SUBDIR = join('.conductor', 'generated')
const MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000

export interface GenerateCreativeImageParams {
  prompt: string
  /** A ratio (default 9:16) or the aliases portrait, square, landscape. */
  aspect?: string
  /** Custom WIDTHxHEIGHT; overrides aspect. */
  size?: string
  count?: number
  quality?: string
  referenceImagePaths?: string[]
  /** Only after the person agreed to spend their Codex quota. */
  useCodex?: boolean
}

export interface GenerateCreativeImageOptions {
  log?: (message: string) => void
  /** Overrides the output folder (tests). */
  outDir?: string
  cwd?: string
  timeoutMs?: number
}

export interface GenerateCreativeImageResult {
  files: GeneratedImage[]
  provenance: { source: string; licence: string; aiGenerated: true }
  revisedPrompt: string
  outDir: string
  size: string
  sizeNote?: string
  placementFit?: Record<string, PlacementFit>
  quality: ImageQuality
  model: string
  generator: { route: 'openai-api' | 'codex'; model: string }
  nextStep: string
  image?: { data: Buffer; mimeType: string }
}

/** Removes sub-folders of `<projectRoot>/.conductor/generated` older than 14 days. Never throws. */
export async function pruneGenerated(projectRoot: string, now: Date = new Date()): Promise<string[]> {
  const root = join(resolve(projectRoot), GENERATED_SUBDIR)
  const removed: string[] = []
  let entries: string[]
  try {
    entries = await readdir(root)
  } catch {
    return removed
  }
  for (const name of entries) {
    const dir = resolve(root, name)
    if (!dir.startsWith(root + sep)) continue
    try {
      const info = await lstat(dir)
      if (!info.isDirectory() || info.isSymbolicLink()) continue
      if (now.getTime() - info.mtimeMs > MAX_AGE_MS) {
        await rm(dir, { recursive: true, force: true })
        removed.push(dir)
      }
    } catch {
      /* leave it */
    }
  }
  return removed
}

const REALISM_STEP =
  'Run the realism check before using it: crop the on-screen region at 100% for each placement\'s crop (not a contact sheet) and look for AI tells (waxy or plastic surfaces, over-sharpening or HDR glow, warped hands, faces or teeth, duplicated, merged or floating objects, inconsistent light or shadows, mushy background detail, pseudo-text, a fake product or screen). If any shows, regenerate at most twice with a prompt that names each failed tell; if it still fails, stop, say what looks generated and offer a library photo instead. Never use a photo that failed.'

function nextStep(fit: Record<string, PlacementFit> | undefined): string {
  const entries = Object.entries(fit ?? {})
  const over = entries.filter(([, f]) => !f.ok).map(([p, f]) => `${p} (${f.scale}x)`)
  const overVideo = entries.filter(([, f]) => f.ok && !f.videoOk).map(([p, f]) => `${p} (${f.videoScale}x with the video zoom)`)
  const fix = "regenerate at that placement's aspect, use another photo, or use a framed layout at or below native size."
  const upscale =
    (over.length ? ` Placements above 1.0x: ${over.join(', ')}. Do not use this photo full-bleed there (it would be upscaled): ${fix}` : '') +
    (overVideo.length ? ` As a MOTION background it would be upscaled at ${overVideo.join(', ')}: use background motion "none" or ${fix}` : '')
  return `${REALISM_STEP}${upscale} If it passes, pass a path as photoPath (or sequence[].photoPath) to preview_creative_draft. Its .provenance.json sidecar carries the AI-generated label either way.`
}

export async function generateCreativeImage(
  params: GenerateCreativeImageParams,
  options: GenerateCreativeImageOptions = {}
): Promise<GenerateCreativeImageResult> {
  const prompt = typeof params.prompt === 'string' ? params.prompt.trim() : ''
  if (!prompt) throw new Error('prompt is required — describe the scene to photograph.')
  const aspect = normalizeAspect(params.aspect)
  if (params.size !== undefined) {
    if (typeof params.size !== 'string') throw new Error('size must look like WIDTHxHEIGHT, e.g. 1440x2560.')
    parseSize(params.size)
  }
  const count = params.count ?? 1
  if (!Number.isInteger(count) || count < 1 || count > 3) throw new Error('count must be a whole number from 1 to 3.')
  if (params.quality !== undefined && !(IMAGE_QUALITIES as readonly string[]).includes(params.quality)) {
    throw new Error(`quality must be one of ${IMAGE_QUALITIES.join(', ')}.`)
  }
  if (params.useCodex !== undefined && typeof params.useCodex !== 'boolean') throw new Error('useCodex must be true or false.')
  const refs = params.referenceImagePaths ?? []
  const referenceImagePaths: string[] = []
  for (const r of refs) {
    const abs = isAbsolute(r) ? r : resolve(options.cwd ?? process.cwd(), r)
    if (!existsSync(abs) || !statSync(abs).isFile()) throw new Error(`Reference image not found: ${r}`)
    referenceImagePaths.push(abs)
  }

  let outDir = options.outDir
  if (!outDir) {
    const projectRoot = findProjectRoot(options.cwd)
    const root = join(resolve(projectRoot), GENERATED_SUBDIR)
    await pruneGenerated(projectRoot)
    await mkdir(root, { recursive: true })
    await writeFile(join(root, '.gitignore'), '*\n').catch(() => {})
    outDir = join(root, `${stamp(new Date())}-${slugify(prompt)}`)
  }

  const result = await generateImages({
    prompt,
    aspect,
    ...(params.size ? { size: params.size } : {}),
    count,
    ...(params.quality ? { quality: params.quality as ImageQuality } : {}),
    referenceImagePaths,
    outDir,
    ...(params.useCodex ? { useCodex: true } : {}),
    onProgress: options.log,
    ...(options.timeoutMs ? { timeoutMs: options.timeoutMs } : {}),
  })

  return {
    files: result.files,
    provenance: result.provenance,
    revisedPrompt: result.revisedPrompt,
    outDir: result.outDir,
    quality: result.quality,
    model: result.model,
    generator: result.generator,
    size: result.size,
    ...(result.sizeNote ? { sizeNote: result.sizeNote } : {}),
    ...(result.placementFit ? { placementFit: result.placementFit } : {}),
    nextStep: nextStep(result.placementFit),
    ...(result.preview ? { image: result.preview } : {}),
  }
}
