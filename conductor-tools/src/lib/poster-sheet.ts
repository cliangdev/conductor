/**
 * Composes several poster JPEGs (one per CLIP placement) into a single small "key moments" sheet
 * image with ffmpeg's hstack filter, so `preview_creative` has something to show for a CLIP
 * creative — which has no server-rendered contact sheet the way a STILL creative's previewOnly
 * render does (rendering one locally isn't possible either: CLIP has no browser-driven render path,
 * see render_creative in tools/creatives.ts). Shares ffmpeg discovery with media-probe.ts rather
 * than duplicating it.
 */
import { spawn } from 'node:child_process'
import { readFile, unlink } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomUUID } from 'node:crypto'
import { resolveFfmpegPath } from './media-probe.js'

const SHEET_HEIGHT = 360

/** One `scale=-2:360` per input, then an hstack chaining every scaled output left to right. A single
 * input skips the stack (nothing to stack) and just scales. */
export function buildSheetFilterComplex(count: number): string {
  if (count === 1) return `[0:v]scale=-2:${SHEET_HEIGHT}[v]`
  const scales = Array.from({ length: count }, (_, i) => `[${i}:v]scale=-2:${SHEET_HEIGHT}[v${i}]`).join(';')
  const stackInputs = Array.from({ length: count }, (_, i) => `[v${i}]`).join('')
  return `${scales};${stackInputs}hstack=inputs=${count}[v]`
}

/**
 * Composes the given local poster JPEGs left to right into one JPEG sheet, each scaled to ~360px
 * high. Throws on any ffmpeg failure (no binary, a bad/corrupt poster, etc.) — callers should catch
 * this and fall back to showing a single poster on its own rather than nothing.
 */
export async function composePosterSheet(posterPaths: string[]): Promise<Buffer> {
  if (posterPaths.length === 0) {
    throw new Error('composePosterSheet needs at least one poster image')
  }
  const ffmpegPath = await resolveFfmpegPath()
  const outPath = join(tmpdir(), `conductor-sheet-${randomUUID()}.jpg`)
  const inputs = posterPaths.flatMap((p) => ['-i', p])
  const args = ['-y', ...inputs, '-filter_complex', buildSheetFilterComplex(posterPaths.length), '-map', '[v]', '-q:v', '3', outPath]

  await new Promise<void>((resolve, reject) => {
    const child = spawn(ffmpegPath, args, { stdio: ['ignore', 'ignore', 'pipe'] })
    let stderr = ''
    child.stderr.on('data', (d) => (stderr += d))
    child.on('error', reject)
    child.on('exit', (code) => {
      if (code === 0) resolve()
      else reject(new Error(`ffmpeg sheet composition failed (exit ${code}): ${stderr.trim().slice(-2000) || 'no error output'}`))
    })
  })

  try {
    return await readFile(outPath)
  } finally {
    await unlink(outPath).catch(() => {})
  }
}
