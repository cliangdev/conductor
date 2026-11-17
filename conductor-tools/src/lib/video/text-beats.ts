/**
 * Renders compose_video's timed text beats to transparent PNGs with the compose_slideshow text renderer (the
 * same HTML, fonts, styles and in-page checks as a slideshow photo slide, native look: slideshows and clips
 * bypass the Brand Kit), and measures where each beat lands so overlaps can be told apart from stacking.
 */
import { mkdir, rm, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import type { DraftCheck } from '../creative-render.js'
import { findBrowserFactory, type BrowserFactory } from '../creative-browser.js'
import { fontsDir } from '../slideshow/compose.js'
import { buildSlideHtml, FRAME_W, FRAME_H } from '../slideshow/html.js'
import { resolveStyleLayers } from '../slideshow/style.js'
import type { SlideshowSpec } from '../slideshow/spec.js'
import type { TextBeatSpec } from './timeline.js'

export interface RenderedBeat {
  index: number
  png: string
  /** Vertical extent (frame px) of the text on this beat; null when it could not be measured. */
  top: number | null
  bottom: number | null
}

export interface RenderedBeats {
  beats: RenderedBeat[]
  checks: DraftCheck[]
}

/** The page paints #111 behind the slide; a text beat must keep only the text. */
const TRANSPARENT = '<style>html,body,#s{background:transparent!important}.grain,.dim{display:none!important}</style>'

interface InPageCheck {
  rule: string
  severity: 'error' | 'warning'
  message: string
}

export async function renderTextBeats(
  texts: readonly TextBeatSpec[],
  outDir: string,
  options: { browserFactory?: BrowserFactory; log?: (message: string) => void } = {},
): Promise<RenderedBeats> {
  if (texts.length === 0) return { beats: [], checks: [] }
  const out = resolve(outDir)
  await mkdir(out, { recursive: true })
  const style = resolveStyleLayers({})
  const assetBase = pathToFileURL(fontsDir()).href + '/'
  const browser = await (options.browserFactory ?? (await findBrowserFactory()))()
  const beats: RenderedBeat[] = []
  const checks: DraftCheck[] = []
  try {
    const page = await browser.newPage({ deviceScaleFactor: 1 })
    page.on('pageerror', (e) => options.log?.(`page error: ${e.message}`))
    await page.setViewportSize({ width: FRAME_W, height: FRAME_H['9x16'] })
    for (const [i, beat] of texts.entries()) {
      const spec: SlideshowSpec = {
        id: 'beat',
        slides: [{ type: 'photo', texts: [{ t: beat.t, y: beat.y, ...(beat.fs ? { fs: beat.fs } : {}), ...(beat.style ? { style: beat.style } : {}) }] }],
      }
      const html = buildSlideHtml(spec, 0, '9x16', assetBase, style).replace('</head>', `${TRANSPARENT}</head>`)
      const htmlFile = join(out, `.beat-${i}.html`)
      const png = join(out, `beat-${String(i + 1).padStart(2, '0')}.png`)
      await writeFile(htmlFile, html)
      try {
        await page.goto(pathToFileURL(htmlFile).href)
        await page.waitForFunction('window.__READY === true', null, { timeout: 30000 })
        for (const c of (await page.evaluate('window.__CHECKS')) as InPageCheck[]) {
          // contrast is judged against the box or outline only; what is behind the text is moving video
          checks.push({ placementKey: '9x16', index: i, rule: c.rule, severity: c.severity, message: `texts[${i}]: ${c.message}`, atSeconds: beat.fromSec })
        }
        const extent = (await page.evaluate(
          "(function(){var t=1e9,b=-1;document.querySelectorAll('[data-fg]').forEach(function(e){var r=e.getBoundingClientRect();if(r.width<1||r.height<1)return;t=Math.min(t,r.top);b=Math.max(b,r.bottom)});return b<0?null:[t,b]})()",
        )) as [number, number] | null
        await page.screenshot({ path: png, omitBackground: true })
        beats.push({ index: i, png, top: extent ? extent[0] : null, bottom: extent ? extent[1] : null })
      } finally {
        await rm(htmlFile, { force: true })
      }
      options.log?.(`rendered text beat ${i + 1}/${texts.length}`)
    }
  } finally {
    await browser.close()
  }
  return { beats, checks }
}
