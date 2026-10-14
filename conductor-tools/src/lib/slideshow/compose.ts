import { mkdir, rm, writeFile } from 'node:fs/promises'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { findBrowserFactory, type BrowserFactory } from '../creative-browser.js'
import { buildSlideHtml, FRAME_H, FRAME_W } from './html.js'
import { assertValidSlideshowSpec, SLIDE_RATIOS, type SlideRatio, type SlideshowSpec } from './spec.js'

export interface SlideshowCheck {
  slide: number
  ratio: SlideRatio
  rule: 'safeZone' | 'spill' | 'textSize' | 'wordsPerLine' | 'image' | 'font' | 'render'
  severity: 'error' | 'warning'
  message: string
}

export interface ComposeOptions {
  ratios?: SlideRatio[]
  /** Folder relative image paths resolve against (default: the current directory). */
  specDir?: string
  /** Tests: supply a browser instead of discovering one. */
  browserFactory?: BrowserFactory
  log?: (message: string) => void
}

export interface ComposeResult {
  slidePaths: Partial<Record<SlideRatio, string[]>>
  checks: SlideshowCheck[]
  /** True when no check has severity "error" (warnings do not fail a slideshow). */
  passed: boolean
  specPath: string
  outDir: string
}

/** Bundled TikTok Sans lives in <package>/assets/fonts; this file is at src/lib/slideshow or dist/lib/slideshow. */
export function fontsDir(): string {
  return resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'assets', 'fonts')
}

function resolveImage(value: string | undefined, specDir: string, where: string): string | undefined {
  if (!value) return value
  if (/^https?:\/\//i.test(value)) {
    throw new Error(`${where}: "${value}" is a web URL. Slideshows use local files only; download the image first and pass its path.`)
  }
  if (value.startsWith('file:') || value.startsWith('data:')) return value
  return isAbsolute(value) ? value : resolve(specDir, value)
}

/** Maps every image reference in the spec through `fn`, returning a copy. */
function mapImages(spec: SlideshowSpec, fn: (value: string | undefined, where: string) => string | undefined): SlideshowSpec {
  const copy = structuredClone(spec)
  copy.slides.forEach((sl, i) => {
    const at = `slides[${i}]`
    if (sl.bg) sl.bg = fn(sl.bg, `${at}.bg`)
    if (sl.shot) sl.shot = fn(sl.shot, `${at}.shot`)
    sl.photos?.forEach((p, j) => { p.src = fn(p.src, `${at}.photos[${j}].src`)! })
    sl.days?.forEach((d, j) => { if (d.img) d.img = fn(d.img, `${at}.days[${j}].img`) })
  })
  return copy
}

const toUrl = (value: string | undefined): string | undefined =>
  !value || value.startsWith('file:') || value.startsWith('data:') ? value : pathToFileURL(value).href

/** Resolves relative image paths against `specDir`; refuses http(s). Exported for the MCP tool and tests. */
export function resolveSpecImages(spec: SlideshowSpec, specDir: string): SlideshowSpec {
  return mapImages(spec, (v, where) => resolveImage(v, specDir, where))
}

export async function composeSlideshow(specInput: unknown, outDir: string, options: ComposeOptions = {}): Promise<ComposeResult> {
  assertValidSlideshowSpec(specInput)
  const ratios = options.ratios ?? ['9x16', '4x5']
  for (const r of ratios) if (!SLIDE_RATIOS.includes(r)) throw new Error(`ratios must be 9x16 and/or 4x5, got "${String(r)}"`)
  if (ratios.length === 0) throw new Error('ratios must name at least one of 9x16, 4x5')

  const specDir = resolve(options.specDir ?? process.cwd())
  const resolved = resolveSpecImages(specInput, specDir)
  const htmlSpec = mapImages(resolved, (v) => toUrl(v))
  const out = resolve(outDir)
  await mkdir(out, { recursive: true })
  const specPath = join(out, 'spec.json')
  await writeFile(specPath, JSON.stringify(resolved, null, 2))
  const assetBase = pathToFileURL(fontsDir()).href + '/'

  const factory = options.browserFactory ?? (await findBrowserFactory())
  const browser = await factory()
  const slidePaths: Partial<Record<SlideRatio, string[]>> = {}
  const checks: SlideshowCheck[] = []
  try {
    const page = await browser.newPage({ deviceScaleFactor: 1 })
    page.on('pageerror', (e) => options.log?.(`page error: ${e.message}`))
    for (const ratio of ratios) {
      await page.setViewportSize({ width: FRAME_W, height: FRAME_H[ratio] })
      const files: string[] = []
      for (let i = 0; i < htmlSpec.slides.length; i++) {
        const nn = String(i + 1).padStart(2, '0')
        const htmlFile = join(out, `.slide-${ratio}-${nn}.html`)
        await writeFile(htmlFile, buildSlideHtml(htmlSpec, i, ratio, assetBase))
        try {
          await page.goto(pathToFileURL(htmlFile).href)
          await page.waitForFunction('window.__READY === true', null, { timeout: 30000 })
          checks.push(...((await page.evaluate('window.__CHECKS')) as SlideshowCheck[]))
          const png = join(out, `${htmlSpec.id}-${ratio}-${nn}.png`)
          await page.screenshot({ path: png })
          files.push(png)
        } finally {
          await rm(htmlFile, { force: true })
        }
        options.log?.(`rendered ${ratio} slide ${i + 1}/${htmlSpec.slides.length}`)
      }
      slidePaths[ratio] = files
    }
  } finally {
    await browser.close()
  }
  return { slidePaths, checks, passed: !checks.some((c) => c.severity === 'error'), specPath, outDir: out }
}
