import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateSync } from 'node:zlib'
import { findBrowserFactory, type BrowserFactory } from '../lib/creative-browser.js'
import { pathToFileURL } from 'node:url'
import { buildSlideHtml, BUNDLED_FONTS, composeSlideshow, fontsDir, overImageContrast, type SlideshowSpec } from '../lib/slideshow/index.js'

function crc32(buf: Buffer): number {
  let c = ~0
  for (const b of buf) {
    c ^= b
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1))
  }
  return ~c >>> 0
}
function chunk(type: string, data: Buffer): Buffer {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
function solidPng(w: number, h: number, rgb: [number, number, number]): Buffer {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8
  ihdr[9] = 2
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: w }, () => rgb).flat())])
  const raw = Buffer.concat(Array.from({ length: h }, () => row))
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}

async function browserAvailable(): Promise<boolean> {
  try {
    const { chromium } = await import('playwright-core')
    for (const options of [{ channel: 'chrome' }, { channel: 'msedge' }, {}]) {
      try {
        const browser = await chromium.launch(options)
        await browser.close()
        return true
      } catch {
        continue
      }
    }
  } catch {
    /* no playwright */
  }
  return false
}
const hasBrowser = await browserAvailable()

/** Image names in the fixture folder -> file: URLs, the way composeSlideshow hands them to the page. */
const withFileUrls = (spec: SlideshowSpec, dir: string): SlideshowSpec => {
  const copy = structuredClone(spec)
  for (const sl of copy.slides) if (sl.bg) sl.bg = pathToFileURL(join(dir, sl.bg)).href
  return copy
}

describe('bundled font files', () => {
  it('ship with a licence beside them', () => {
    const present = readdirSync(fontsDir())
    for (const { faces } of Object.values(BUNDLED_FONTS)) for (const [, file] of faces) expect(present, file).toContain(file)
    for (const f of ['OFL.txt', 'OFL-inter.txt', 'OFL-source-serif-4.txt', 'OFL-courier-prime.txt']) expect(existsSync(join(fontsDir(), f)), f).toBe(true)
  })
})

describe.skipIf(!hasBrowser)('bundled fonts and contrast (real browser)', { timeout: 60000 }, () => {
  let dir: string
  beforeAll(() => {
    dir = mkdtempSync(join(tmpdir(), 'ss-fc-'))
    writeFileSync(join(dir, 'light.png'), solidPng(60, 60, [245, 245, 235]))
    writeFileSync(join(dir, 'dark.png'), solidPng(60, 60, [20, 20, 30]))
  })
  afterAll(() => rmSync(dir, { recursive: true, force: true }))

  const compose = (spec: SlideshowSpec, name: string, browserFactory?: BrowserFactory) =>
    composeSlideshow(spec, join(dir, name), { ratios: ['9x16'], specDir: dir, ...(browserFactory ? { browserFactory } : {}) })

  it('loads the bundled Inter, Source Serif 4 and Courier Prime on the system-UI slide types without a font error', async () => {
    const result = await compose(
      {
        id: 'sys',
        slides: [
          { type: 'notes', title: 'Groceries', date: 'Today', items: ['milk', { t: 'eggs', done: true }] },
          { type: 'messages', bubbles: [{ from: 'me', t: 'hi' }, { from: 'them', t: 'hello' }] },
          { type: 'week', title: 'Week', days: [{ d: 'Mon', t: 'Tacos' }] },
          { type: 'receipt', head: 'SHOP', sections: [{ h: 'A', items: ['bread'] }], count: '1' },
          { type: 'placard', title: 'Untitled', ex: 'Exhibit A', lines: [{ k: 'Year', v: '2024' }] },
          { type: 'grid', photos: [{ src: 'light.png', badge: '0:40' }] },
        ],
      },
      'sys'
    )
    expect(result.checks.filter((c) => c.rule === 'font')).toEqual([])
    expect(result.slidePaths['9x16']).toHaveLength(6)
  })

  it('makes a bundled font that fails to load an error naming the family', async () => {
    const real = await findBrowserFactory()
    const blocking: BrowserFactory = async () => {
      const browser = await real()
      const newPage = browser.newPage.bind(browser)
      browser.newPage = (async (o?: Parameters<typeof newPage>[0]) => {
        const page = await newPage(o)
        await page.route('**/courier-prime-*.ttf', (route) => route.abort())
        return page
      }) as typeof browser.newPage
      return browser
    }
    const spec: SlideshowSpec = { id: 'rc', slides: [{ type: 'receipt', head: 'SHOP', sections: [{ h: 'A', items: ['bread'] }], count: '1' }] }
    const bad = await compose(spec, 'rc-bad', blocking)
    expect(bad.passed).toBe(false)
    expect(bad.checks.find((c) => c.rule === 'font')?.message).toMatch(/bundled font Courier Prime .*did not load/)
    const ok = await compose(spec, 'rc-ok')
    expect(ok.checks.filter((c) => c.rule === 'font')).toEqual([])
  })

  it('judges outlined text against its outline colour: a warning, never an error', async () => {
    const native = await compose({ id: 'l', slides: [{ bg: 'light.png', texts: [{ t: 'white on a pale photo', y: 40, style: 'sub' }, { t: 'outlined', y: 60, style: 'stroke' }] }] }, 'light')
    // white ink with a black halo is readable on any photo
    expect(native.checks.filter((c) => c.rule === 'contrast')).toEqual([])
    const noHalo = await composeSlideshow(
      { id: 'n', style: { strokeInk: '#ffffff', outline: '#ffffff' }, slides: [{ bg: 'light.png', texts: [{ t: 'invisible text', y: 40, style: 'stroke' }] }] },
      join(dir, 'nohalo'),
      { ratios: ['9x16'], specDir: dir }
    )
    const c = noHalo.checks.filter((x) => x.rule === 'contrast')
    expect(c).toHaveLength(1)
    expect(c[0]).toMatchObject({ slide: 1, ratio: '9x16', severity: 'warning' })
    expect(c[0]!.message).toMatch(/has 1:1 contrast against its outline/)
    expect(noHalo.passed).toBe(true)
  })

  it('measures text with no box and no outline from the pixels behind it', async () => {
    // No native style leaves text bare, so strip the shadows from a stroke slide and measure what is left
    const page = async (slide: SlideshowSpec['slides'][number], name: string) => {
      const spec: SlideshowSpec = { id: name, style: { strokeInk: '#ffffff' } as never, slides: [slide] }
      const html = buildSlideHtml(withFileUrls(spec, dir), 0, '9x16', pathToFileURL(fontsDir()).href + '/').replaceAll('text-shadow:', '-x-shadow:')
      const file = join(dir, `${name}.html`)
      writeFileSync(file, html)
      const browser = await (await findBrowserFactory())()
      try {
        const p = await browser.newPage({ deviceScaleFactor: 1 })
        await p.setViewportSize({ width: 1080, height: 1920 })
        await p.goto(pathToFileURL(file).href)
        await p.waitForFunction('window.__READY === true')
        const own = (await p.evaluate('window.__CHECKS')) as Array<{ rule: string }>
        return { own: own.filter((x) => x.rule === 'contrast'), over: await overImageContrast(p) }
      } finally {
        await browser.close()
      }
    }
    const onLight = await page({ bg: 'light.png', texts: [{ t: 'white on pale', y: 40, style: 'stroke' }] }, 'o-light')
    expect(onLight.own).toEqual([])
    expect(onLight.over).toHaveLength(1)
    expect(onLight.over[0]).toMatchObject({ slide: 1, ratio: '9x16', rule: 'contrast', severity: 'warning' })
    expect(onLight.over[0]!.message).toMatch(/"white on pale" has 1\.\d:1 contrast against the image behind it/)
    const onDark = await page({ bg: 'dark.png', texts: [{ t: 'white on dark', y: 40, style: 'stroke' }] }, 'o-dark')
    expect(onDark.over).toEqual([])
    const onColour = await page({ bgColor: '#f4f4f0', dim: 0.1, texts: [{ t: 'white on cream', y: 40, style: 'stroke' }] }, 'o-colour')
    expect(onColour.over).toHaveLength(1)
    // a dim layer is part of what is measured
    expect((await page({ bg: 'light.png', dim: 0.9, texts: [{ t: 'white on dimmed pale', y: 40, style: 'stroke' }] }, 'o-dim')).over).toEqual([])
  })

  it('judges boxed text against its box, and checks the pill and the system-UI slides', async () => {
    const poor = await composeSlideshow(
      { id: 'p', style: { boxBg: '#eeeeee', boxInk: '#ffffff' }, slides: [{ bg: 'dark.png', texts: [{ t: 'light grey box, white ink', y: 40 }] }] },
      join(dir, 'p'),
      { ratios: ['9x16'], specDir: dir }
    )
    expect(poor.checks.filter((c) => c.rule === 'contrast')[0]?.message).toMatch(/against its background/)
    const fine = await compose({ id: 'f', slides: [{ bg: 'light.png', texts: [{ t: 'default box', y: 40 }, { t: 'dark box', y: 55, style: 'dark' }] }, { type: 'notes', title: 'Plain', items: ['a'] }, { type: 'messages', bubbles: [{ from: 'me', t: 'hi' }, { from: 'them', t: 'yo' }] }] }, 'fine')
    expect(fine.checks.filter((c) => c.rule === 'contrast')).toEqual([])
  })
})
