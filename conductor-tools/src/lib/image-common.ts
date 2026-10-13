import { spawn } from 'node:child_process'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { readImageDimensions } from './image-dimensions.js'
import { resolveFfmpegPath } from './media-probe.js'

export type ImageAspect = 'portrait' | 'square' | 'landscape'

export const ASPECT_SIZES: Record<ImageAspect, string> = {
  portrait: '1024x1536',
  square: '1024x1024',
  landscape: '1536x1024',
}

export interface GenerateImagesParams {
  prompt: string
  aspect?: ImageAspect
  count?: number
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
export function sceneRules(scene: string, _aspect: ImageAspect, hasReferences: boolean): string {
  return [
    scene.trim(),
    '',
    'Rules:',
    '- Photographic unless the scene above asks for another style.',
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
