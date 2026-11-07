/**
 * Draws approximate Instagram Reels / TikTok chrome (header, action column, caption block) over 9:16
 * frames so the person approving a draft sees what the viewer sees, and composes the result into one
 * contact sheet. Everything is inline SVG/CSS rendered by Playwright; the glyphs are generic, not the
 * apps' real artwork, and every sheet says "(approximate)".
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { BOTTOM_EDGE, REF_HEIGHT, REF_WIDTH, RIGHT_EDGE, RIGHT_Y_MAX, RIGHT_Y_MIN, TOP_EDGE } from './zones.js'
import { findBrowserFactory, type BrowserFactory } from '../creative-browser.js'

export type OverlayPlatform = 'instagram' | 'tiktok'
export interface OverlayFrame {
  /** Image bytes, or a path to an image file (PNG or JPEG). */
  png: Buffer | string
  /** Caption drawn under the frame, e.g. "Slide 1", "0.5 s", "Cut at 4.2 s". */
  label: string
}
export interface OverlayOptions {
  browser?: BrowserFactory
}

// The zone outlines come from zones.ts, so they are the checker's zones by construction.
export const OVERLAY_REF = { width: REF_WIDTH, height: REF_HEIGHT } as const
export const OVERLAY_ZONES = { top: TOP_EDGE, bottom: BOTTOM_EDGE, rightX: RIGHT_EDGE, rightY0: RIGHT_Y_MIN, rightY1: RIGHT_Y_MAX } as const

const FRAME_W = 540
const FRAME_H = 960
const SHEET_FRAME_W = 320
const SHEET_FRAME_H = 569
const PER_ROW = 6

const PLATFORM_TITLE: Record<OverlayPlatform, string> = {
  instagram: 'Instagram Reels UI (approximate)',
  tiktok: 'TikTok UI (approximate)',
}

const esc = (s: string): string => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
const pct = (n: number, of: number): string => `${+((n / of) * 100).toFixed(3)}%`

// ---- image helpers ---------------------------------------------------------------------------

function imageSize(buf: Buffer): { width: number; height: number; mime: string } {
  if (buf.length > 24 && buf.readUInt32BE(0) === 0x89504e47) {
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20), mime: 'image/png' }
  }
  if (buf.length > 4 && buf[0] === 0xff && buf[1] === 0xd8) {
    let i = 2
    while (i + 9 < buf.length) {
      if (buf[i] !== 0xff) {
        i++
        continue
      }
      const marker = buf[i + 1]!
      if (marker === 0xff) {
        i++
        continue
      }
      if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
        return { width: buf.readUInt16BE(i + 7), height: buf.readUInt16BE(i + 5), mime: 'image/jpeg' }
      }
      i += 2 + buf.readUInt16BE(i + 2)
    }
  }
  throw new Error('overlay: frame is not a readable PNG or JPEG image')
}

interface LoadedFrame {
  dataUrl: string
  label: string
}

async function loadFrames(frames: OverlayFrame[]): Promise<LoadedFrame[]> {
  const out: LoadedFrame[] = []
  for (const [i, f] of frames.entries()) {
    const buf = typeof f.png === 'string' ? await readFile(f.png) : f.png
    const { width, height, mime } = imageSize(buf)
    if (Math.abs(width / height - 9 / 16) > 0.01) {
      throw new Error(`overlay: frame ${i + 1} ("${f.label}") is ${width}x${height}; the UI overlay only supports 9:16 frames`)
    }
    out.push({ dataUrl: `data:${mime};base64,${buf.toString('base64')}`, label: f.label })
  }
  return out
}

// ---- chrome ---------------------------------------------------------------------------------

const W = 'rgba(255,255,255,0.62)'
const SW = 'stroke="rgba(255,255,255,0.62)" stroke-width="7" stroke-linecap="round" stroke-linejoin="round" fill="none"'
const FONT = `font-family="-apple-system,Helvetica,Arial,sans-serif"`

const text = (x: number, y: number, size: number, s: string, extra = ''): string =>
  `<text x="${x}" y="${y}" font-size="${size}" fill="${W}" ${FONT} ${extra}>${esc(s)}</text>`
const heart = (cx: number, cy: number, s: number): string =>
  `<path transform="translate(${cx - s / 2} ${cy - s / 2}) scale(${s / 24})" d="M12 21 C4 14 2 10 2 7.5 A5 5 0 0 1 12 6 A5 5 0 0 1 22 7.5 C22 10 20 14 12 21 Z" fill="${W}"/>`
const bubble = (cx: number, cy: number, s: number): string =>
  `<path transform="translate(${cx - s / 2} ${cy - s / 2}) scale(${s / 24})" d="M3 11 A9 8 0 0 1 21 11 A9 8 0 0 1 11 19 L5 21 L7 16.5 A9 8 0 0 1 3 11 Z" fill="${W}"/>`
const plane = (cx: number, cy: number, s: number): string =>
  `<path transform="translate(${cx - s / 2} ${cy - s / 2}) scale(${s / 24})" d="M2 11 L22 3 L15 21 L11 13 Z" fill="${W}"/>`
const dots = (cx: number, cy: number): string => [-22, 0, 22].map((d) => `<circle cx="${cx}" cy="${cy + d}" r="6" fill="${W}"/>`).join('')
const bookmark = (cx: number, cy: number, s: number): string =>
  `<path transform="translate(${cx - s / 2} ${cy - s / 2}) scale(${s / 24})" d="M5 2 H19 V22 L12 16 L5 22 Z" fill="${W}"/>`
const search = (cx: number, cy: number): string =>
  `<g ${SW}><circle cx="${cx - 6}" cy="${cy - 6}" r="17"/><path d="M${cx + 7} ${cy + 7} L${cx + 22} ${cy + 22}"/></g>`
const avatar = (cx: number, cy: number, r: number): string =>
  `<circle cx="${cx}" cy="${cy}" r="${r}" fill="rgba(255,255,255,0.35)" stroke="${W}" stroke-width="4"/><circle cx="${cx}" cy="${cy - r * 0.2}" r="${r * 0.34}" fill="${W}"/><path d="M${cx - r * 0.6} ${cy + r * 0.7} A${r * 0.6} ${r * 0.5} 0 0 1 ${cx + r * 0.6} ${cy + r * 0.7} Z" fill="${W}"/>`
const bars = (x: number, y: number, widths: number[]): string =>
  widths.map((w, i) => `<rect x="${x}" y="${y + i * 54}" width="${w}" height="20" rx="10" fill="${W}"/>`).join('')
const scrimTop = (h: number): string =>
  `<rect x="0" y="0" width="1080" height="${h}" fill="url(#st)"/>`

function instagramChrome(): string {
  const cx = 990
  return [
    scrimTop(300),
    `<rect x="0" y="1380" width="1080" height="540" fill="url(#sb)"/>`,
    // header
    `<g ${SW}><path d="M92 150 L58 184 L92 218 M58 184 H118"/></g>`,
    text(190, 200, 50, 'Reels', 'font-weight="700"'),
    text(360, 200, 50, 'Friends', 'opacity="0.7"'),
    `<g ${SW}><rect x="940" y="156" width="70" height="56" rx="12"/><circle cx="975" cy="184" r="15"/><path d="M955 156 L962 142 H988 L995 156"/></g>`,
    // right column
    heart(cx, 800, 74),
    text(cx, 880, 34, '24.1K', 'text-anchor="middle"'),
    bubble(cx, 990, 70),
    text(cx, 1070, 34, '312', 'text-anchor="middle"'),
    plane(cx, 1180, 70),
    text(cx, 1260, 34, '1,204', 'text-anchor="middle"'),
    dots(cx, 1360),
    `<rect x="${cx - 36}" y="1440" width="72" height="72" rx="16" fill="rgba(255,255,255,0.3)" stroke="${W}" stroke-width="5"/>`,
    // bottom
    avatar(90, 1560, 38),
    text(150, 1556, 40, 'username  ·  Follow', 'font-weight="700"'),
    bars(60, 1620, [760, 520]),
    text(60, 1760, 36, '♪ Original audio'),
  ].join('')
}

function tiktokChrome(): string {
  const cx = 990
  return [
    scrimTop(300),
    `<rect x="0" y="1380" width="1080" height="540" fill="url(#sb)"/>`,
    // header
    text(70, 190, 42, 'LIVE', 'font-weight="700"'),
    `<rect x="52" y="152" width="108" height="52" rx="10" fill="none" stroke="${W}" stroke-width="4"/>`,
    text(400, 190, 46, 'Following', 'opacity="0.7" text-anchor="middle"'),
    text(540, 190, 46, '|', 'opacity="0.5" text-anchor="middle"'),
    text(680, 190, 46, 'For You', 'font-weight="700" text-anchor="middle"'),
    `<rect x="620" y="206" width="120" height="6" rx="3" fill="${W}"/>`,
    search(985, 176),
    // right column
    avatar(cx, 620, 52),
    `<circle cx="${cx}" cy="684" r="22" fill="rgba(240,60,90,0.8)"/><path d="M${cx - 10} 684 H${cx + 10} M${cx} 674 V694" stroke="white" stroke-width="5" stroke-linecap="round"/>`,
    heart(cx, 800, 76),
    text(cx, 880, 34, '98.2K', 'text-anchor="middle"'),
    bubble(cx, 985, 72),
    text(cx, 1065, 34, '1,046', 'text-anchor="middle"'),
    bookmark(cx, 1170, 66),
    text(cx, 1250, 34, '8,311', 'text-anchor="middle"'),
    plane(cx, 1350, 70),
    text(cx, 1430, 34, '2,917', 'text-anchor="middle"'),
    `<circle cx="${cx}" cy="1560" r="52" fill="rgba(30,30,30,0.7)" stroke="${W}" stroke-width="5"/><circle cx="${cx}" cy="1560" r="20" fill="${W}"/>`,
    // bottom
    text(60, 1556, 42, '@username', 'font-weight="700"'),
    bars(60, 1596, [760, 520]),
    text(60, 1760, 36, '♪ original sound - username'),
  ].join('')
}

function chromeSvg(platform: OverlayPlatform): string {
  const body = platform === 'instagram' ? instagramChrome() : tiktokChrome()
  return (
    `<svg class="chrome" viewBox="0 0 ${OVERLAY_REF.width} ${OVERLAY_REF.height}" xmlns="http://www.w3.org/2000/svg">` +
    `<defs><linearGradient id="st" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000" stop-opacity="0.45"/><stop offset="1" stop-color="#000" stop-opacity="0"/></linearGradient>` +
    `<linearGradient id="sb" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity="0.55"/></linearGradient></defs>` +
    body +
    `</svg>`
  )
}

function zoneDivs(): string {
  const { top, bottom, rightX, rightY0, rightY1 } = OVERLAY_ZONES
  const { width: w, height: h } = OVERLAY_REF
  return (
    `<div class="zone zone-top" style="left:0;top:0;width:100%;height:${pct(top, h)}"></div>` +
    `<div class="zone zone-bottom" style="left:0;top:${pct(bottom, h)};width:100%;height:${pct(h - bottom, h)}"></div>` +
    `<div class="zone zone-right" style="left:${pct(rightX, w)};top:${pct(rightY0, h)};width:${pct(w - rightX, w)};height:${pct(rightY1 - rightY0, h)}"></div>`
  )
}

function frameHtml(f: LoadedFrame, platform: OverlayPlatform, w: number, h: number): string {
  return (
    `<figure class="fig" style="width:${w}px"><div class="frame" style="width:${w}px;height:${h}px">` +
    `<img src="${f.dataUrl}" alt="">${chromeSvg(platform)}${zoneDivs()}</div>` +
    `<figcaption>${esc(f.label)}</figcaption></figure>`
  )
}

const BASE_CSS = `
*{box-sizing:border-box;margin:0;padding:0}
body{background:#14151a;color:#e8e8ec;font-family:-apple-system,Helvetica,Arial,sans-serif}
.fig{display:flex;flex-direction:column;gap:6px}
.frame{position:relative;overflow:hidden;background:#000;border-radius:6px}
.frame img{position:absolute;inset:0;width:100%;height:100%;object-fit:fill}
.chrome{position:absolute;inset:0;width:100%;height:100%}
.zone{position:absolute;border:1.5px dashed rgba(255,214,0,0.55);pointer-events:none}
figcaption{font-size:12px;text-align:center;color:#b8b9c2}
`

/**
 * The page for a contact sheet (title bar, frames in rows of up to 6) or, with `single`, one bare frame
 * at 540x960. Pure: images arrive as data URLs.
 */
export function overlayHtml(
  frames: Array<{ dataUrl: string; label: string }>,
  platform: OverlayPlatform,
  opts: { single?: boolean } = {},
): string {
  if (opts.single) {
    const f = frames[0]!
    return `<!doctype html><html><head><meta charset="utf-8"><style>${BASE_CSS}
body{background:#000}.fig{gap:0}figcaption{display:none}</style></head><body>${frameHtml(f, platform, FRAME_W, FRAME_H)}</body></html>`
  }
  const cols = Math.min(PER_ROW, Math.max(1, frames.length))
  const gap = 16
  const width = cols * SHEET_FRAME_W + (cols + 1) * gap
  return (
    `<!doctype html><html><head><meta charset="utf-8"><style>${BASE_CSS}
body{width:${width}px;padding:${gap}px}
h1{font-size:18px;font-weight:600;margin-bottom:12px}
h1 small{font-weight:400;color:#9a9ba5;margin-left:10px;font-size:13px}
.grid{display:grid;grid-template-columns:repeat(${cols},${SHEET_FRAME_W}px);gap:${gap}px}
</style></head><body><h1>${esc(PLATFORM_TITLE[platform])}<small>dashed outline = safe-zone check areas</small></h1>` +
    `<div class="grid">${frames.map((f) => frameHtml(f, platform, SHEET_FRAME_W, SHEET_FRAME_H)).join('')}</div></body></html>`
  )
}

// ---- rendering --------------------------------------------------------------------------------

async function withPage<T>(
  opts: OverlayOptions | undefined,
  viewport: { width: number; height: number },
  fn: (page: import('playwright-core').Page) => Promise<T>,
): Promise<T> {
  const factory = opts?.browser ?? (await findBrowserFactory())
  const browser = await factory()
  let page: import('playwright-core').Page | undefined
  try {
    page = await browser.newPage({ deviceScaleFactor: 1, viewport })
    return await fn(page)
  } finally {
    // The page is closed explicitly so a shared browser (whose close() is a no-op) does not accumulate pages.
    await page?.close().catch(() => {})
    await browser.close()
  }
}

/**
 * One browser for many renders. `factory` launches lazily on first use (so a run that never draws
 * launches nothing), every later call reuses that browser, and the `close()` of the browsers it hands
 * out does nothing; call `closeAll()` once, in a finally, to really close it.
 */
export function sharedBrowser(base?: BrowserFactory): { factory: BrowserFactory; closeAll: () => Promise<void> } {
  let launching: Promise<import('playwright-core').Browser> | undefined
  const launch = (): Promise<import('playwright-core').Browser> => {
    launching ??= (async () => (base ?? (await findBrowserFactory()))())()
    // A failed launch is not cached: the next call may try again.
    launching.catch(() => {
      launching = undefined
    })
    return launching
  }
  const factory: BrowserFactory = async () => {
    const real = await launch()
    return new Proxy(real, {
      get(target, prop) {
        if (prop === 'close') return async () => {}
        const v = Reflect.get(target, prop, target)
        return typeof v === 'function' ? v.bind(target) : v
      },
    })
  }
  return {
    factory,
    closeAll: async () => {
      const l = launching
      launching = undefined
      if (l) await (await l.catch(() => undefined))?.close().catch(() => {})
    },
  }
}

/** One 540x960 JPEG per frame, with the platform chrome drawn on top. All frames share one page. */
export async function renderOverlayFrames(
  frames: OverlayFrame[],
  platform: OverlayPlatform,
  opts?: OverlayOptions,
): Promise<Buffer[]> {
  const loaded = await loadFrames(frames)
  if (loaded.length === 0) return []
  return withPage(opts, { width: FRAME_W, height: FRAME_H }, async (page) => {
    const out: Buffer[] = []
    for (const f of loaded) {
      await page.setContent(overlayHtml([f], platform, { single: true }), { waitUntil: 'load' })
      out.push(await page.screenshot({ type: 'jpeg', quality: 88, clip: { x: 0, y: 0, width: FRAME_W, height: FRAME_H } }))
    }
    return out
  })
}

/** All frames in one JPEG sheet: a title bar naming the platform, a single row (wrapping after 6). */
export async function composeOverlaySheet(
  frames: OverlayFrame[],
  platform: OverlayPlatform,
  opts?: OverlayOptions & { maxPerSheet?: number },
): Promise<{ data: Buffer; mimeType: 'image/jpeg' }> {
  const max = opts?.maxPerSheet ?? frames.length
  const loaded = await loadFrames(frames.slice(0, Math.max(1, max)))
  if (loaded.length === 0) throw new Error('overlay: no frames to compose')
  const html = overlayHtml(loaded, platform)
  const cols = Math.min(PER_ROW, loaded.length)
  const width = cols * SHEET_FRAME_W + (cols + 1) * 16
  return withPage(opts, { width, height: 700 }, async (page) => {
    await page.setContent(html, { waitUntil: 'load' })
    const data = await page.screenshot({ type: 'jpeg', quality: 80, fullPage: true })
    return { data, mimeType: 'image/jpeg' as const }
  })
}

/** Writes `<baseName>-ui-<platform>.jpg` per platform into outDir. */
export async function overlaySheetsFor(
  frames: OverlayFrame[],
  platforms: OverlayPlatform[],
  outDir: string,
  baseName: string,
  opts?: OverlayOptions & { maxPerSheet?: number },
): Promise<Array<{ platform: OverlayPlatform; file: string; bytes: number }>> {
  await mkdir(outDir, { recursive: true })
  const results: Array<{ platform: OverlayPlatform; file: string; bytes: number }> = []
  // One browser for every platform's sheet (unless the caller already shares one).
  const shared = sharedBrowser(opts?.browser)
  try {
    for (const platform of platforms) {
      const { data } = await composeOverlaySheet(frames, platform, { ...opts, browser: shared.factory })
      const file = join(outDir, `${baseName}-ui-${platform}.jpg`)
      await writeFile(file, data)
      results.push({ platform, file, bytes: data.length })
    }
  } finally {
    await shared.closeAll()
  }
  return results
}
