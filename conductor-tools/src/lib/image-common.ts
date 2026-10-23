import { spawn } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { readImageDimensions } from './image-dimensions.js'
import { resolveFfmpegPath } from './media-probe.js'

export type ImageAspect = '9:16' | '4:5' | '1:1' | '2:3' | '3:2' | '16:9'
export const IMAGE_ASPECTS: readonly ImageAspect[] = ['9:16', '4:5', '1:1', '2:3', '3:2', '16:9']
export const DEFAULT_ASPECT: ImageAspect = '9:16'

/** Older names for the 2:3 / 1:1 / 3:2 ratios. */
export const ASPECT_ALIASES = { portrait: '2:3', square: '1:1', landscape: '3:2' } as const
export type ImageAspectInput = ImageAspect | keyof typeof ASPECT_ALIASES

export type ImageQuality = 'low' | 'medium' | 'high'
export const IMAGE_QUALITIES: readonly ImageQuality[] = ['low', 'medium', 'high']

/** Largest non-experimental size per ratio: exact ratio, both edges multiples of 16, at most 2560x1440 px in total. */
export const ASPECT_SIZES: Record<ImageAspect, string> = {
  '9:16': '1440x2560',
  '4:5': '1664x2080',
  '1:1': '1920x1920',
  '2:3': '1536x2304',
  '3:2': '2304x1536',
  '16:9': '2560x1440',
}

/** Sizes any image model takes, used when custom sizes are not available. */
const STANDARD_SIZES = { portrait: '1024x1536', square: '1024x1024', landscape: '1536x1024' } as const

/** Only these models take a custom WIDTHxHEIGHT. */
const CUSTOM_SIZE_MODEL = /^gpt-image-2\.5/
const MAX_EDGE = 3840
const MIN_PIXELS = 655_360
const MAX_PIXELS = 8_294_400
/** Above 2560x1440 OpenAI marks a size experimental. */
const EXPERIMENTAL_PIXELS = 3_686_400

/** Accepts a ratio or an alias; throws naming the valid values. */
export function normalizeAspect(input: string | undefined): ImageAspect {
  if (input === undefined) return DEFAULT_ASPECT
  if ((IMAGE_ASPECTS as readonly string[]).includes(input)) return input as ImageAspect
  const alias = (ASPECT_ALIASES as Record<string, ImageAspect>)[input]
  if (alias) return alias
  throw new Error(`aspect must be one of ${IMAGE_ASPECTS.join(', ')} (or portrait, square, landscape).`)
}

/** Parses 'WIDTHxHEIGHT' against OpenAI's custom-size rules; throws on a violation. */
export function parseSize(size: string): { width: number; height: number; experimental: boolean } {
  const m = /^(\d+)x(\d+)$/.exec(size.trim().toLowerCase())
  if (!m) throw new Error('size must look like WIDTHxHEIGHT, e.g. 1440x2560.')
  const width = Number(m[1])
  const height = Number(m[2])
  if (width % 16 !== 0 || height % 16 !== 0) throw new Error('size: both edges must be multiples of 16.')
  if (width > MAX_EDGE || height > MAX_EDGE) throw new Error(`size: no edge may be over ${MAX_EDGE}.`)
  if (width > height * 3 || height > width * 3) throw new Error('size: the aspect ratio must be between 1:3 and 3:1.')
  const pixels = width * height
  if (pixels < MIN_PIXELS || pixels > MAX_PIXELS) throw new Error(`size: total pixels must be ${MIN_PIXELS} to ${MAX_PIXELS}.`)
  return { width, height, experimental: pixels > EXPERIMENTAL_PIXELS }
}

export interface ResolvedSize {
  size: string
  /** Set on a fallback or an experimental size. */
  note?: string
}

/**
 * The size to request. `size` (custom) wins over `aspect`, but only on a model that takes custom sizes
 * (`model` undefined means yes: the Codex route asks in its prompt); otherwise the closest standard size by orientation.
 */
export function resolveImageSize(aspect: ImageAspectInput | undefined, size?: string, model?: string): ResolvedSize {
  const ratio = normalizeAspect(aspect)
  const custom = size === undefined ? undefined : parseSize(size)
  const customOk = model === undefined || CUSTOM_SIZE_MODEL.test(model)
  if (customOk) {
    if (!custom) return { size: ASPECT_SIZES[ratio] }
    const out = `${custom.width}x${custom.height}`
    return custom.experimental ? { size: out, note: `${out} is above 2560x1440 and experimental in OpenAI's size rules; results may vary.` } : { size: out }
  }
  const [w, h] = custom ? [custom.width, custom.height] : ASPECT_SIZES[ratio].split('x').map(Number) as [number, number]
  const standard = STANDARD_SIZES[w === h ? 'square' : w < h ? 'portrait' : 'landscape']
  return {
    size: standard,
    note: `Model ${model} does not take custom sizes, so ${custom ? `${custom.width}x${custom.height}` : ratio} fell back to ${standard}; the photo is smaller than the default; check placementFit before using it full-bleed.`,
  }
}

/** Placement sizes mirror conductor-creative/placements.json; the video zoom mirrors ZOOM_IN in conductor-creative/motion.js. */
const PLACEMENT_SIZES = { '9x16': [1080, 1920], '4x5': [1080, 1350], '1x1': [1080, 1080] } as const
export const VIDEO_ZOOM = 1.12

export interface PlacementFit {
  /** Full-bleed cover scale: above 1.0 means the photo is upscaled. */
  scale: number
  /** The same with the video zoom-in applied. */
  videoScale: number
  ok: boolean
  videoOk: boolean
}

const round2 = (n: number): number => Math.round(n * 100) / 100

export function placementFit(width: number, height: number): Record<keyof typeof PLACEMENT_SIZES, PlacementFit> {
  const fit = ([pw, ph]: readonly [number, number]): PlacementFit => {
    const scale = Math.max(pw / width, ph / height)
    const videoScale = scale * VIDEO_ZOOM
    return { scale: round2(scale), videoScale: round2(videoScale), ok: scale <= 1, videoOk: videoScale <= 1 }
  }
  return { '9x16': fit(PLACEMENT_SIZES['9x16']), '4x5': fit(PLACEMENT_SIZES['4x5']), '1x1': fit(PLACEMENT_SIZES['1x1']) }
}

export interface GenerateImagesParams {
  prompt: string
  aspect?: ImageAspectInput
  /** Custom WIDTHxHEIGHT; wins over aspect on models that take it. */
  size?: string
  count?: number
  quality?: ImageQuality
  referenceImagePaths?: string[]
  outDir: string
  onProgress?: (message: string) => void
  timeoutMs?: number
}

export interface GeneratedImage {
  path: string
  width: number
  height: number
}

export interface GenerateImagesResult {
  files: GeneratedImage[]
  provenance: { source: string; licence: string; aiGenerated: true }
  revisedPrompt: string
  outDir: string
  /** Size requested from the model; the files' own width/height are what you actually got. */
  size: string
  /** Why the size differs from the request, or that it is experimental. */
  sizeNote?: string
  /** Image quality used. */
  quality: ImageQuality
  /** Model that did the work: the image model (OpenAI) or the Codex agent model ('default' after the no-model retry). */
  model: string
  /** JPEG (or small PNG) of the first image for inline display; absent when none fits ~1 MB. */
  preview?: { data: Buffer; mimeType: string }
}

export interface Captured {
  code: number | null
  output: string
}

export function runCapture(command: string, args: string[]): Promise<Captured> {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let output = ''
    child.stdout?.on('data', (d) => (output += d))
    child.stderr?.on('data', (d) => (output += d))
    child.on('error', reject)
    child.on('exit', (code) => resolvePromise({ code, output }))
  })
}

const MAX_INLINE_IMAGE_BYTES = 1_000_000

/** The scene plus the rules every image route shares. */
export function sceneRules(scene: string, _aspect: ImageAspectInput, hasReferences: boolean): string {
  return [
    scene.trim(),
    '',
    'Rules:',
    '- Photographic unless the scene above asks for another style.',
    '- If photographic: make it look like an unretouched photo a person took: natural available light, true-to-life colour and contrast, realistic depth of field, slight sensor grain.',
    '- If photographic: avoid the AI look: no waxy, glossy or plastic surfaces; no over-sharpening, HDR glow or halos; no perfect symmetry; no duplicated, merged or floating objects; no impossible reflections, shadows or steam.',
    '- If photographic: include small real-world imperfections (wear, crumbs, creases, uneven edges) and an ordinary, believable setting.',
    '- NO text, letters, numbers, logos, watermarks, UI, or phone/computer screens anywhere in the image.',
    '- Leave clean negative space for a headline.',
    ...(hasReferences ? ['- Use the attached images as references.'] : []),
  ].join('\n')
}

export async function validImage(path: string): Promise<GeneratedImage | null> {
  try {
    const info = readImageDimensions(await readFile(path))
    return info ? { path, width: info.width, height: info.height } : null
  } catch {
    return null
  }
}

export async function jpegPreview(png: string, outDir: string): Promise<{ data: Buffer; mimeType: string } | undefined> {
  try {
    const ffmpeg = await resolveFfmpegPath()
    const out = join(outDir, '.preview.jpg')
    for (const q of ['4', '10']) {
      const res = await runCapture(ffmpeg, [
        '-y', '-v', 'error', '-i', png,
        '-vf', "scale='if(gt(iw,ih),min(1024,iw),-2)':'if(gt(iw,ih),-2,min(1024,ih))'",
        '-q:v', q, out,
      ])
      if (res.code !== 0) break
      const data = await readFile(out)
      if (data.length <= MAX_INLINE_IMAGE_BYTES) return { data, mimeType: 'image/jpeg' }
    }
  } catch {
    /* fall through to the PNG */
  }
  try {
    const data = await readFile(png)
    const info = readImageDimensions(data)
    if (info && data.length < MAX_INLINE_IMAGE_BYTES) return { data, mimeType: info.contentType }
  } catch {
    /* no preview */
  }
  return undefined
}
