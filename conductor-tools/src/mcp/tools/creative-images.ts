import { existsSync, statSync } from 'node:fs'
import { lstat, mkdir, readdir, rm, writeFile } from 'node:fs/promises'
import { isAbsolute, join, resolve, sep } from 'node:path'
import { generateImages, type GeneratedImage, type ImageAspect, type ImageQuality } from '../../lib/codex-image.js'
import { findProjectRoot, slugify, stamp } from './creative-drafts.js'

/**
 * generate_creative_image: makes a photo on this machine with the person's Codex CLI and returns it
 * (inline preview + local paths with provenance sidecars) for use as `photoPath` in
 * preview_creative_draft. Nothing is sent to Conductor.
 */

export const GENERATED_SUBDIR = join('.conductor', 'generated')
const MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000
const QUALITIES: readonly ImageQuality[] = ['low', 'medium', 'high']
const ASPECTS: readonly ImageAspect[] = ['portrait', 'square', 'landscape']

export interface GenerateCreativeImageParams {
  prompt: string
  aspect?: string
  count?: number
  quality?: string
  referenceImagePaths?: string[]
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
  quality: ImageQuality
  model: string
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

export async function generateCreativeImage(
  params: GenerateCreativeImageParams,
  options: GenerateCreativeImageOptions = {}
): Promise<GenerateCreativeImageResult> {
  const prompt = typeof params.prompt === 'string' ? params.prompt.trim() : ''
  if (!prompt) throw new Error('prompt is required — describe the scene to photograph.')
  const aspect = (params.aspect ?? 'portrait') as ImageAspect
  if (!ASPECTS.includes(aspect)) throw new Error(`aspect must be one of ${ASPECTS.join(', ')}.`)
  const count = params.count ?? 1
  if (!Number.isInteger(count) || count < 1 || count > 3) throw new Error('count must be a whole number from 1 to 3.')
  if (params.quality !== undefined && !QUALITIES.includes(params.quality as ImageQuality)) {
    throw new Error(`quality must be one of ${QUALITIES.join(', ')}.`)
  }
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
    count,
    ...(params.quality ? { quality: params.quality as ImageQuality } : {}),
    referenceImagePaths,
    outDir,
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
    nextStep:
      'Look at the image for pseudo-text, warped hands or a fake product; if it is good, pass a path as photoPath (or sequence[].photoPath) to preview_creative_draft. Its .provenance.json sidecar carries the AI-generated label.',
    ...(result.preview ? { image: result.preview } : {}),
  }
}
