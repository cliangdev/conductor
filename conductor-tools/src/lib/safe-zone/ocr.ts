/**
 * A small OCR wrapper over tesseract.js. English data ships with the package (@tesseract.js-data/eng,
 * the small integer "best_int" model), so nothing is downloaded at run time. One worker is created on
 * first use and reused. Crops are read as they are and inverted (light-on-dark and dark-on-light).
 */
import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { readImageDimensions } from '../image-dimensions.js'
import { resolveFfmpegPath } from '../media-probe.js'

const require = createRequire(import.meta.url)

export interface OcrWord {
  text: string
  confidence: number
  box: { x: number; y: number; w: number; h: number }
}

export interface OcrRect {
  left: number
  top: number
  width: number
  height: number
}

/** Anything that can read words from a PNG (optionally one rectangle of it). Injectable for tests. */
export interface OcrEngine {
  recognizeWords(png: Buffer, rect?: OcrRect): Promise<OcrWord[]>
}

/** Thrown when the OCR engine cannot load or run. */
export class OcrUnavailable extends Error {
  constructor(reason: string) {
    super(reason)
    this.name = 'OcrUnavailable'
  }
}

export const MIN_CONFIDENCE = 70
export const MIN_WORD_CHARS = 2
export const MIN_BOX_HEIGHT_1080 = 16

interface TesseractWorker {
  recognize(image: Buffer, opts: object, output: object): Promise<{ data: { blocks?: TBlock[] | null } }>
  terminate(): Promise<unknown>
}
interface TWord { text: string; confidence: number; bbox: { x0: number; y0: number; x1: number; y1: number } }
interface TBlock { paragraphs: Array<{ lines: Array<{ words: TWord[] }> }> }

let workerPromise: Promise<TesseractWorker> | undefined

/** The folder with eng.traineddata.gz (the small integer model). */
function langPath(): string {
  const pkg = require.resolve('@tesseract.js-data/eng/package.json')
  return join(dirname(pkg), '4.0.0_best_int')
}

async function getWorker(): Promise<TesseractWorker> {
  workerPromise ??= (async () => {
    try {
      const { createWorker, PSM } = (await import('tesseract.js')) as unknown as {
        createWorker: (lang: string, oem: number, opts: object) => Promise<TesseractWorker & { setParameters(p: object): Promise<unknown> }>
        PSM: { SPARSE_TEXT: string }
      }
      const worker = await createWorker('eng', 1, {
        langPath: langPath(),
        gzip: true,
        cacheMethod: 'none', // never write language data into the user's cwd
        cachePath: join(tmpdir(), 'conductor-tesseract'),
      })
      await worker.setParameters({ tessedit_pageseg_mode: PSM?.SPARSE_TEXT ?? '11', user_defined_dpi: '300' })
      return worker
    } catch (e) {
      throw new OcrUnavailable(`OCR engine failed to load: ${(e as Error).message}`)
    }
  })()
  workerPromise.catch(() => { workerPromise = undefined })
  return workerPromise
}

/** Stops the shared worker (tests and shutdown). */
export async function closeOcr(): Promise<void> {
  const p = workerPromise
  workerPromise = undefined
  if (p) await (await p.catch(() => undefined))?.terminate()
}

/** Crops a PNG and inverts its colours with ffmpeg, returning a PNG. */
async function cropInverted(png: Buffer, rect?: OcrRect): Promise<Buffer> {
  const ffmpeg = await resolveFfmpegPath()
  const filters = [rect ? `crop=${rect.width}:${rect.height}:${rect.left}:${rect.top}` : undefined, 'negate'].filter(Boolean).join(',')
  return new Promise((resolve, reject) => {
    const child = spawn(ffmpeg, ['-v', 'error', '-i', 'pipe:0', '-vf', filters, '-frames:v', '1', '-f', 'image2pipe', '-c:v', 'png', 'pipe:1'])
    const chunks: Buffer[] = []
    child.stdout.on('data', (d: Buffer) => chunks.push(d))
    child.on('error', reject)
    child.on('close', (code) => (code === 0 ? resolve(Buffer.concat(chunks)) : reject(new Error(`ffmpeg exited ${code}`))))
    child.stdin.on('error', () => {})
    child.stdin.end(png)
  })
}

/** Runs one recognition pass. With `rectangle`, tesseract reports boxes in full-image coordinates. */
async function readOnce(worker: TesseractWorker, image: Buffer, rectangle?: OcrRect): Promise<OcrWord[]> {
  const { data } = await worker.recognize(image, rectangle ? { rectangle } : {}, { blocks: true })
  const words: OcrWord[] = []
  for (const b of data.blocks ?? [])
    for (const p of b.paragraphs) for (const l of p.lines) for (const w of l.words)
      words.push({
        text: w.text.trim(),
        confidence: w.confidence,
        box: { x: w.bbox.x0, y: w.bbox.y0, w: w.bbox.x1 - w.bbox.x0, h: w.bbox.y1 - w.bbox.y0 },
      })
  return words
}

/** Drops low-confidence, tiny and non-word results. `imageWidth` scales the 16 px minimum height. */
export function filterWords(words: OcrWord[], imageWidth: number): OcrWord[] {
  const minH = (MIN_BOX_HEIGHT_1080 * imageWidth) / 1080
  return words.filter(
    (w) => w.confidence >= MIN_CONFIDENCE && (w.text.match(/[\p{L}\p{N}]/gu)?.length ?? 0) >= MIN_WORD_CHARS && w.box.h >= minH,
  )
}

function iou(a: OcrWord['box'], b: OcrWord['box']): number {
  const w = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x)
  const h = Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y)
  if (w <= 0 || h <= 0) return 0
  const inter = w * h
  return inter / (a.w * a.h + b.w * b.h - inter)
}

/** Merges words found by both passes: overlapping boxes count once, keeping the more confident one. */
export function dedupeWords(words: OcrWord[]): OcrWord[] {
  const kept: OcrWord[] = []
  for (const w of [...words].sort((a, b) => b.confidence - a.confidence))
    if (!kept.some((k) => iou(k.box, w.box) > 0.4)) kept.push(w)
  return kept.sort((a, b) => a.box.y - b.box.y || a.box.x - b.box.x)
}

/** The tesseract engine: as-is and inverted passes, filtered and merged. */
export function createTesseractEngine(): OcrEngine {
  return {
    async recognizeWords(png, rect) {
      const width = readImageDimensions(png)?.width ?? 1080
      try {
        const worker = await getWorker()
        const plain = await readOnce(worker, png, rect)
        const inverted = (await readOnce(worker, await cropInverted(png, rect))).map((w) => ({
          ...w,
          box: { ...w.box, x: w.box.x + (rect?.left ?? 0), y: w.box.y + (rect?.top ?? 0) },
        }))
        return dedupeWords(filterWords([...plain, ...inverted], width))
      } catch (e) {
        if (e instanceof OcrUnavailable) throw e
        throw new OcrUnavailable(`OCR failed: ${(e as Error).message}`)
      }
    },
  }
}

let defaultEngine: OcrEngine | undefined

/** The shared tesseract engine. */
export function getDefaultEngine(): OcrEngine {
  return (defaultEngine ??= createTesseractEngine())
}

/** Reads words with the default engine. Boxes are in full-image coordinates. */
export function recognizeWords(png: Buffer, rect?: OcrRect): Promise<OcrWord[]> {
  return getDefaultEngine().recognizeWords(png, rect)
}
