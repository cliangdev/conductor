import { mkdir, rm, writeFile } from 'node:fs/promises'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { findBrowserFactory, type BrowserFactory } from '../creative-browser.js'
import { readProvenanceSidecar, writeProvenanceSidecar, type SidecarProvenance } from '../provenance-sidecar.js'
import { buildSlideHtml, FRAME_H, FRAME_W } from './html.js'
import { resolveSlideshowStyle, type ResolvedSlideshowStyle } from './style.js'
import { assertValidSlideshowSpec, SLIDE_RATIOS, type SlideRatio, type SlideshowSpec } from './spec.js'

export interface SlideshowCheck {
  slide: number
  ratio: SlideRatio
  rule: 'safeZone' | 'spill' | 'textSize' | 'wordsPerLine' | 'image' | 'font' | 'provenance' | 'render'
  severity: 'error' | 'warning'
  message: string
}

export interface ComposeOptions {
  ratios?: SlideRatio[]
  /** Folder relative image paths resolve against (default: the current directory). */
  specDir?: string
  /** Tests: supply a browser instead of discovering one. */
  browserFactory?: BrowserFactory
  /** The style already layered by the caller (kit, inline); default: resolved from the spec's own `style`. */
  resolvedStyle?: ResolvedSlideshowStyle
  log?: (message: string) => void
}

export interface ComposeResult {
  slidePaths: Partial<Record<SlideRatio, string[]>>
  checks: SlideshowCheck[]
  /** True when no check has severity "error" (warnings do not fail a slideshow). */
  passed: boolean
  /** Sidecars written for the slide PNGs; `needed` lists the 1-based slides left without one. */
  provenance: { recorded: number; needed: number[]; aiGenerated: boolean }
  specPath: string
  outDir: string
  /** The full style the slides were composed with (spec.json carries it as `resolvedStyle`). */
  style: ResolvedSlideshowStyle
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


const MAX_FIELD = 200
const clip = (text: string): string => (text.length > MAX_FIELD ? `${text.slice(0, MAX_FIELD - 1)}…` : text)
const unique = (items: string[]): string[] => [...new Set(items)]

function slideImages(sl: SlideshowSpec['slides'][number]): string[] {
  return [sl.bg, sl.shot, ...(sl.photos ?? []).map((p) => p.src), ...(sl.days ?? []).map((d) => d.img)].filter((v): v is string => !!v)
}

/** Provenance for one composed slide, or null when an input image has none (the caller then supplies it at commit). */
async function slideProvenance(images: string[]): Promise<{ sidecar: SidecarProvenance | null; missing: string[]; anyAi: boolean }> {
  if (images.length === 0) {
    return {
      sidecar: { source: "Text slide composed with Conductor's slideshow composer", licence: 'Own work', aiGenerated: false },
      missing: [],
      anyAi: false,
    }
  }
  const known: SidecarProvenance[] = []
  const missing: string[] = []
  for (const img of unique(images)) {
    let found: SidecarProvenance | null = null
    if (!img.startsWith('data:')) {
      try {
        found = await readProvenanceSidecar(img.startsWith('file:') ? fileURLToPath(img) : img)
      } catch {
        found = null
      }
    }
    if (found) known.push(found)
    else missing.push(img.startsWith('data:') ? 'an inline data: image' : img)
  }
  const anyAi = known.some((k) => k.aiGenerated)
  if (missing.length > 0) return { sidecar: null, missing, anyAi }
  return {
    sidecar: {
      source: clip(`Composed with Conductor's slideshow composer from: ${unique(known.map((k) => k.source)).join('; ')}`),
      licence: clip(unique(known.map((k) => k.licence)).join('; ')),
      aiGenerated: anyAi,
    },
    missing,
    anyAi,
  }
}

export async function composeSlideshow(specInput: unknown, outDir: string, options: ComposeOptions = {}): Promise<ComposeResult> {
  assertValidSlideshowSpec(specInput)
  const ratios = options.ratios ?? ['9x16', '4x5']
  for (const r of ratios) if (!SLIDE_RATIOS.includes(r)) throw new Error(`ratios must be 9x16 and/or 4x5, got "${String(r)}"`)
  if (ratios.length === 0) throw new Error('ratios must name at least one of 9x16, 4x5')

  const specDir = resolve(options.specDir ?? process.cwd())
  // The caller (compose_slideshow) may have layered a Brand Kit / inline style already; else the spec's own.
  const style = options.resolvedStyle ?? resolveSlideshowStyle(specInput.style)
  // spec.json keeps the input `style` as given and records the outcome beside it as `resolvedStyle`.
  const { resolvedStyle: _ignored, ...input } = specInput as SlideshowSpec & { resolvedStyle?: unknown }
  void _ignored
  const resolved: SlideshowSpec & { resolvedStyle: ResolvedSlideshowStyle } = { ...resolveSpecImages(input, specDir), resolvedStyle: style }
  const htmlSpec = mapImages({ ...resolved, style }, (v) => toUrl(v))
  const out = resolve(outDir)
  await mkdir(out, { recursive: true })
  const specPath = join(out, 'spec.json')
  await writeFile(specPath, JSON.stringify(resolved, null, 2))
  const assetBase = pathToFileURL(fontsDir()).href + '/'

  const factory = options.browserFactory ?? (await findBrowserFactory())
  const browser = await factory()
  const slidePaths: Partial<Record<SlideRatio, string[]>> = {}
  const checks: SlideshowCheck[] = []
  const provenance = { recorded: 0, needed: [] as number[], aiGenerated: false }
  const perSlide = await Promise.all(resolved.slides.map((sl) => slideProvenance(slideImages(sl))))
  perSlide.forEach((p, i) => {
    if (p.anyAi || p.sidecar?.aiGenerated) provenance.aiGenerated = true
    if (p.sidecar) return
    provenance.needed.push(i + 1)
    checks.push({
      slide: i + 1,
      ratio: ratios[0]!,
      rule: 'provenance',
      severity: 'warning',
      message:
        `no .provenance.json for: ${p.missing.join(', ')}; no sidecar was written for this slide, so commit will ask for source and licence.` +
        (p.anyAi ? ' Another image on this slide is AI-generated: pass aiGenerated: true on commit_creative_draft.' : ''),
    })
  })
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
          const sidecar = perSlide[i]!.sidecar
          if (sidecar) {
            await writeProvenanceSidecar(png, sidecar)
            provenance.recorded += 1
          }
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
  return { slidePaths, checks, passed: !checks.some((c) => c.severity === 'error'), provenance, specPath, outDir: out, style }
}
