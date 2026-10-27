import { describe, it, expect } from 'vitest'
import { createHash } from 'node:crypto'
import { createServer } from 'node:http'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { slideCss } from '../lib/slideshow/css.js'
import { findBrowserFactory, type BrowserFactory } from '../lib/creative-browser.js'
import {
  buildSlideHtml,
  composeSlideshow,
  fontsDir,
  NATIVE_STYLE,
  pickKnownStyleFields,
  resolveSlideshowStyle,
  resolveStyleLayers,
  hasAnyStyleField,
  validateSlideshowSpec,
  validateSlideshowStyle,
  SlideshowStyleError,
} from '../lib/slideshow/index.js'

const base = 'file:///f/'

describe('slideCss', () => {
  it('renders the native look byte-for-byte unchanged when no style is given', () => {
    // sha256 of the stylesheet before slideshow styles existed (slideCss('file:///f/'))
    expect(createHash('sha256').update(slideCss(base)).digest('hex')).toBe('fff75b978d82f8583b814686a9beb8f04bb34236e13c07630eb3dc2b6610f2ea')
    expect(slideCss(base, NATIVE_STYLE)).toBe(slideCss(base))
    expect(slideCss(base, resolveSlideshowStyle())).toBe(slideCss(base))
  })

  it('applies a style: font family, colours and sizes', () => {
    const css = slideCss(
      base,
      resolveSlideshowStyle({ fontFamily: 'Poppins', fontUrl: 'https://fonts.example/p.css', boxBg: '#ffeedc', boxInk: '#223345', darkBg: '#112234', darkInk: '#ddeef0', outline: '#0000ff', accent: '#00ff88', textSize: 72, subSize: 50 })
    )
    expect(css).toContain('font-family:"Poppins",-apple-system')
    expect(css).toContain('color:#223345;background:#ffeedc')
    expect(css).toContain('.box.dark{background:#112234;color:#ddeef0}')
    expect(css).toContain('var(--fs,72px)')
    expect(css).toContain('var(--ss,50px)')
    expect(css).toContain('text-shadow:0 0 3px #00f')
    expect(css).toContain('rgba(0,0,255,.8)')
    expect(css).toContain('background:#0f8')
    expect(css).not.toContain('#ff2d55')
  })
})

describe('resolveSlideshowStyle', () => {
  it('is the native look with no layers', () => {
    expect(resolveSlideshowStyle()).toEqual(NATIVE_STYLE)
    expect(resolveSlideshowStyle(null, undefined, {})).toEqual(NATIVE_STYLE)
  })

  it('layers kit style, then inline overrides on top', () => {
    const kit = { fontFamily: 'Lora', fontUrl: 'https://fonts.example/lora.css', boxBg: '#FFF8E7', defaultText: 'dark' as const }
    const out = resolveSlideshowStyle(kit, { boxBg: '#000000', textSize: 80 })
    expect(out).toMatchObject({ fontFamily: 'Lora', fontUrl: 'https://fonts.example/lora.css', boxBg: '#000000', defaultText: 'dark', textSize: 80, subSize: 44, accent: '#ff2d55' })
  })

  it('a layer that changes the family does not inherit the previous stylesheet; null family keeps native font', () => {
    const out = resolveSlideshowStyle({ fontFamily: 'A', fontUrl: 'https://x.example/a.css' }, { fontFamily: 'B' })
    expect(out.fontFamily).toBe('B')
    expect(out.fontUrl).toBeNull()
    expect(resolveSlideshowStyle({ fontFamily: null, boxBg: '#123456' }).fontFamily).toBeNull()
  })

  it('keeps the inherited fontUrl when a layer names the same family (case-insensitive, trimmed)', () => {
    const kit = { fontFamily: 'Lora', fontUrl: 'https://x.example/lora.css' }
    expect(resolveSlideshowStyle(kit, { fontFamily: '  lora ' })).toMatchObject({ fontFamily: 'lora', fontUrl: 'https://x.example/lora.css' })
    expect(resolveSlideshowStyle(kit, { fontFamily: 'Lora', fontUrl: 'https://y.example/l.css' }).fontUrl).toBe('https://y.example/l.css')
    expect(resolveSlideshowStyle(kit, { fontFamily: 'Other' }).fontUrl).toBeNull()
  })

  it('compares the first family, so a list with generics keeps the inherited fontUrl', () => {
    const kit = { fontFamily: 'Poppins', fontUrl: 'https://x.example/p.css' }
    expect(resolveSlideshowStyle(kit, { fontFamily: 'Poppins, sans-serif' }).fontUrl).toBe('https://x.example/p.css')
    expect(resolveSlideshowStyle({ ...kit, fontFamily: 'poppins , serif' }, { fontFamily: ' Poppins' }).fontUrl).toBe('https://x.example/p.css')
    expect(resolveSlideshowStyle(kit, { fontFamily: 'Inter, Poppins' }).fontUrl).toBeNull()
  })

  it('an explicit null resets that field to native; an absent field inherits', () => {
    const kit = { fontFamily: 'Lora', fontUrl: 'https://x.example/l.css', boxBg: '#112233', textSize: 80 }
    expect(resolveSlideshowStyle(kit, { fontFamily: null })).toMatchObject({ fontFamily: null, fontUrl: null, boxBg: '#112233', textSize: 80 })
    expect(resolveSlideshowStyle(kit, { boxBg: null, textSize: null })).toMatchObject({ fontFamily: 'Lora', boxBg: NATIVE_STYLE.boxBg, textSize: 60 })
    expect(resolveSlideshowStyle(kit, { fontUrl: null })).toMatchObject({ fontFamily: 'Lora', fontUrl: null })
    expect(resolveSlideshowStyle(kit, {})).toMatchObject(kit)
  })

  it('validates font family lists and URLs like the backend', () => {
    for (const ok of ['Inter, sans-serif', 'Söhne', '  Lora  ', '', '   ']) expect(validateSlideshowStyle({ fontFamily: ok })).toEqual([])
    for (const bad of ['a"b', "a'b", 'a;b', 'a{b', 'a}b', 'a<b', 'a>b', 'a\\b', 'a\u0001b', 'x'.repeat(201), ',,']) expect(validateSlideshowStyle({ fontFamily: bad })).toHaveLength(1)
    expect(validateSlideshowStyle({ fontFamily: 'x'.repeat(200) })).toEqual([])
    expect(validateSlideshowStyle({ fontUrl: 'https://fonts.googleapis.com/css?family=Open+Sans|Lora' })).toEqual([])
    expect(validateSlideshowStyle({ fontUrl: '' })).toEqual([])
    expect(validateSlideshowStyle({ fontUrl: 'https://x' })).toEqual([]) // only the backend's pattern: no URL parse
    expect(validateSlideshowStyle({ fontUrl: 'http://fonts.example/a.css' })).toHaveLength(1) // https only
    expect(validateSlideshowStyle({ fontFamily: ', ,' })).toHaveLength(1)
    for (const bad of ['ftp://x.example/a.css', 'https://x.example/a b', 'https://x.example/a"b', 'https://x.example/' + 'a'.repeat(2048)]) expect(validateSlideshowStyle({ fontUrl: bad })).toHaveLength(1)
    expect(resolveSlideshowStyle({ fontFamily: '   ', fontUrl: '' }).fontFamily).toBeNull()
  })

  it('renders a family list as quoted names, generics bare', () => {
    expect(slideCss(base, resolveSlideshowStyle({ fontFamily: 'Open Sans, Söhne ,sans-serif' }))).toContain('font-family:"Open Sans","Söhne",sans-serif,-apple-system')
  })

  it('emits generic and system keywords unquoted, in their canonical casing', () => {
    expect(slideCss(base, resolveSlideshowStyle({ fontFamily: '-apple-system, blinkmacsystemfont, Inter, SANS-SERIF' }))).toContain('font-family:-apple-system,BlinkMacSystemFont,"Inter",sans-serif,-apple-system')
  })

  it('colours outlined text and sub text with strokeInk, not darkInk', () => {
    const css = slideCss(base, resolveSlideshowStyle({ darkInk: '#112233', strokeInk: '#ffee00' }))
    expect(css).toMatch(/\.stroke\{[^}]*color:#fe0;/)
    expect(css).toMatch(/\.sub\{[^}]*color:#fe0;/)
    expect(css).toContain('.box.dark{background:#111;color:#123}')
    expect(resolveSlideshowStyle({ darkInk: '#112233' }).strokeInk).toBe('#ffffff')
    expect(validateSlideshowStyle({ strokeInk: 'white' })).toHaveLength(1)
  })

  it('resolves kit < spec < inline once, naming a kit in its errors, and rejects unknown inline fields', () => {
    const r = resolveStyleLayers({ kit: { accent: '#111111', textSize: 50, futureField: 1, fontFamily: null }, spec: { textSize: 55 }, inline: { subSize: 40 } })
    expect(r).toMatchObject({ accent: '#111111', textSize: 55, subSize: 40, fontFamily: null })
    expect(() => resolveStyleLayers({ kit: { accent: 'zzz' }, kitLabel: 'Brand Kit "Warm" slideshowStyle' })).toThrow(/Brand Kit "Warm" slideshowStyle\.accent/)
    expect(() => resolveStyleLayers({ inline: { acent: '#fff' } as never })).toThrow(SlideshowStyleError)
  })

  it('hasAnyStyleField needs one known non-null field', () => {
    for (const none of [null, undefined, 'x', [], {}, { futureField: 1 }, { accent: null, fontFamily: null }]) expect(hasAnyStyleField(none)).toBe(false)
    expect(hasAnyStyleField({ accent: '#112233' })).toBe(true)
    expect(hasAnyStyleField({ textSize: 0, futureField: 1 })).toBe(true)
  })

  it('ignores unknown keys read from a Brand Kit, but not from an inline style', () => {
    expect(pickKnownStyleFields({ accent: '#00ff88', futureField: 1 })).toEqual({ accent: '#00ff88' })
    expect(validateSlideshowStyle({ futureField: 1 })).toHaveLength(1)
    expect(validateSlideshowStyle({ futureField: 1 }, 'style', true)).toEqual([])
  })

  it('rejects bad values with every problem named', () => {
    const errors = validateSlideshowStyle({ boxBg: 'red', accent: '#fff', defaultText: 'sub', textSize: 10, subSize: 200, fontUrl: 'ftp://x', fontFamily: 'a"b', bogus: 1 })
    expect(errors).toHaveLength(8)
    expect(errors.join('\n')).toMatch(/boxBg.*#RRGGBB/)
    expect(errors.join('\n')).toMatch(/textSize.*24 to 120/)
    expect(errors.join('\n')).toMatch(/subSize.*16 to 96/)
    expect(() => resolveSlideshowStyle({ boxBg: 'red' })).toThrow(SlideshowStyleError)
  })

  it('treats style: null in a spec like no layer', () => {
    expect(validateSlideshowSpec({ id: 'a', style: null, slides: [{}] })).toEqual([])
    expect(buildSlideHtml({ id: 't', style: null, slides: [{}] }, 0, '9x16', base)).toContain('"fontFamily":null')
  })

  it('is validated inside a spec', () => {
    expect(validateSlideshowSpec({ id: 'a', style: { accent: 'nope' }, slides: [{}] })).toEqual([expect.stringMatching(/style\.accent/)])
    expect(validateSlideshowSpec({ id: 'a', style: { accent: '#00ff88' }, slides: [{}] })).toEqual([])
  })
})

describe('buildSlideHtml with a style', () => {
  const slide = { texts: [{ t: 'one', y: 20 }, { t: 'two', y: 40, style: 'stroke' as const }, { t: 'three', y: 60, style: 'sub' as const }] }

  it('links the font stylesheet and tells the font check which family to wait for', () => {
    const out = buildSlideHtml({ id: 't', slides: [slide], style: resolveSlideshowStyle({ fontFamily: 'Poppins', fontUrl: 'https://fonts.example/p.css?family=Poppins&display=swap' }) }, 0, '9x16', base)
    expect(out).toContain('<link rel="stylesheet" href="https://fonts.example/p.css?family=Poppins&amp;display=swap">')
    expect(out).toContain('"fontFamily":"Poppins"')
  })

  it('has no link and checks TikTok Sans when native', () => {
    const out = buildSlideHtml({ id: 't', slides: [slide] }, 0, '9x16', base)
    expect(out).not.toContain('<link')
    expect(out).toContain('"fontFamily":null')
    expect(out).toContain("document.fonts.check('700 60px TT')")
  })

  it('defaultText applies only to texts with no explicit style', () => {
    const out = buildSlideHtml({ id: 't', slides: [slide], style: resolveSlideshowStyle({ defaultText: 'stroke' }) }, 0, '9x16', base)
    expect(out).toMatch(/class="stroke" data-fg[^>]*>one/)
    expect(out).toMatch(/class="sub" data-fg[^>]*>three/)
    const dark = buildSlideHtml({ id: 't', slides: [slide], style: resolveSlideshowStyle({ defaultText: 'dark' }) }, 0, '9x16', base)
    expect(dark).toMatch(/class="box dark" data-fg[^>]*>one/)
  })

  it('keeps the system-font slide types on their own fonts', () => {
    const css = slideCss(base, resolveSlideshowStyle({ fontFamily: 'Poppins' }))
    expect(css).toMatch(/\.notes\{[^}]*font-family:-apple-system/)
    expect(css).toMatch(/\.msgs\{[^}]*font-family:-apple-system/)
    expect(css).toMatch(/\.receipt\{[^}]*"Courier New"/)
  })
})

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

describe.skipIf(!hasBrowser)('composeSlideshow with a custom font (real browser)', () => {
  it('checks the custom family, writes the resolved style to spec.json, and blocks on a font that fails to load', async () => {
    const ttf = readFileSync(join(fontsDir(), 'tiktok-sans-700.ttf'))
    const server = createServer((req, res) => {
      if (req.url === '/f.ttf') return void res.writeHead(200, { 'content-type': 'font/ttf', 'access-control-allow-origin': '*' }).end(ttf)
      if (req.url === '/good.css') {
        return void res
          .writeHead(200, { 'content-type': 'text/css', 'access-control-allow-origin': '*' })
          .end('@font-face{font-family:"Test Sans";font-weight:700;src:url(/f.ttf)}')
      }
      res.writeHead(404).end()
    })
    await new Promise<void>((ok) => server.listen(0, '127.0.0.1', ok))
    const port = (server.address() as { port: number }).port
    const out = mkdtempSync(join(tmpdir(), 'ss-font-'))
    // fontUrl must be https, so the browser's https://fonts.test/* requests are answered from the local server
    const real = await findBrowserFactory()
    const factoryFor = (blockTT = false): BrowserFactory => async () => {
      const browser = await real()
      const newPage = browser.newPage.bind(browser)
      browser.newPage = (async (o?: Parameters<typeof newPage>[0]) => {
        const page = await newPage(o)
        if (blockTT) await page.route('**/tiktok-sans-*.ttf', (route) => route.abort())
        await page.route('https://fonts.test/**', async (route) => {
          const r = await fetch(`http://127.0.0.1:${port}${new URL(route.request().url()).pathname}`)
          await route.fulfill({ status: r.status, headers: { 'content-type': r.headers.get('content-type') ?? 'text/plain', 'access-control-allow-origin': '*' }, body: Buffer.from(await r.arrayBuffer()) })
        })
        return page
      }) as typeof browser.newPage
      return browser
    }
    const browserFactory = factoryFor()
    try {
      const spec = { id: 'f', slides: [{ bgColor: '#336699', texts: [{ t: 'hello there', y: 40 }] }] }
      const good = await composeSlideshow({ ...spec, style: { fontFamily: 'Test Sans', fontUrl: `https://fonts.test/good.css`, accent: '#00FF88' } }, join(out, 'good'), { ratios: ['9x16'], browserFactory })
      expect(good.checks.filter((c) => c.rule === 'font')).toEqual([])
      expect(good.style).toMatchObject({ fontFamily: 'Test Sans', accent: '#00ff88', boxBg: '#ffffff' })
      const saved = JSON.parse(readFileSync(good.specPath, 'utf8'))
      expect(saved.resolvedStyle).toEqual(good.style)
      expect(saved.style).toEqual({ fontFamily: 'Test Sans', fontUrl: `https://fonts.test/good.css`, accent: '#00FF88' })

      const bad = await composeSlideshow({ ...spec, style: { fontFamily: 'Missing Sans', fontUrl: `https://fonts.test/missing.css` } }, join(out, 'bad'), { ratios: ['9x16'], browserFactory })
      expect(bad.passed).toBe(false)
      expect(bad.checks.find((c) => c.rule === 'font')?.message).toMatch(/"Missing Sans" did not load/)

      const native = await composeSlideshow(spec, join(out, 'native'), { ratios: ['9x16'], browserFactory })
      expect(native.style).toEqual(NATIVE_STYLE)
      expect(native.checks.filter((c) => c.rule === 'font')).toEqual([])
      const nativeSaved = JSON.parse(readFileSync(native.specPath, 'utf8'))
      expect(nativeSaved.resolvedStyle).toEqual(NATIVE_STYLE)
      expect(nativeSaved.style).toBeUndefined()

      // no fontUrl: an uninstalled first family is a blocking check naming it; a generic passes
      const noUrl = await composeSlideshow({ ...spec, style: { fontFamily: 'Missing Sans, sans-serif' } }, join(out, 'nourl'), { ratios: ['9x16'], browserFactory })
      expect(noUrl.passed).toBe(false)
      expect(noUrl.checks.find((c) => c.rule === 'font')?.message).toMatch(/"Missing Sans" is not installed.*fontUrl/)
      // a font list on a slide with no styled text still loads and passes the check
      const list = { fontFamily: 'Test Sans, sans-serif', fontUrl: `https://fonts.test/good.css` }
      const notes = await composeSlideshow({ id: 'n', style: list, slides: [{ type: 'notes', title: 'Plain notes', items: ['one'] }] }, join(out, 'notes'), { ratios: ['9x16'], browserFactory })
      expect(notes.checks.filter((c) => c.rule === 'font')).toEqual([])
      const photo = await composeSlideshow({ id: 'p', style: list, slides: [{ bgColor: '#336699' }] }, join(out, 'photo'), { ratios: ['9x16'], browserFactory })
      expect(photo.checks.filter((c) => c.rule === 'font')).toEqual([])
      const generic = await composeSlideshow({ ...spec, style: { fontFamily: 'sans-serif' } }, join(out, 'generic'), { ratios: ['9x16'], browserFactory })
      expect(generic.checks.filter((c) => c.rule === 'font')).toEqual([])

      // system keywords are emitted bare and never checked as a custom font (a list of only keywords has no check)
      const system = await composeSlideshow({ ...spec, style: { fontFamily: '-apple-system, BlinkMacSystemFont, sans-serif' } }, join(out, 'system'), { ratios: ['9x16'], browserFactory })
      expect(system.checks.filter((c) => c.rule === 'font')).toEqual([])
      expect(slideCss(base, system.style)).toContain('font-family:-apple-system,BlinkMacSystemFont,sans-serif,-apple-system')
      // ... and only the first non-keyword family is checked
      const mixed = await composeSlideshow({ ...spec, style: { fontFamily: '-apple-system, Missing Sans' } }, join(out, 'mixed'), { ratios: ['9x16'], browserFactory })
      expect(mixed.checks.find((c) => c.rule === 'font')?.message).toMatch(/"Missing Sans" is not installed/)

      // the bundled TikTok Sans is checked whenever any text uses it: native text, or a placard's label
      const placard = { id: 'pl', slides: [{ bgColor: '#336699' }, { type: 'placard' as const, title: 'Untitled', ex: 'Exhibit A' }], style: { fontFamily: 'Test Sans', fontUrl: 'https://fonts.test/good.css' } }
      const blocked = factoryFor(true)
      const withPlacard = await composeSlideshow(placard, join(out, 'placard'), { ratios: ['9x16'], browserFactory: blocked })
      expect(withPlacard.passed).toBe(false)
      expect(withPlacard.checks.filter((c) => c.rule === 'font').map((c) => c.message)).toEqual([expect.stringMatching(/TikTok Sans did not load/), expect.stringMatching(/TikTok Sans did not load/)])
      const customOnly = await composeSlideshow({ ...placard, slides: [placard.slides[0]!] }, join(out, 'customonly'), { ratios: ['9x16'], browserFactory: blocked })
      expect(customOnly.checks.filter((c) => c.rule === 'font')).toEqual([])
      const nativeBlocked = await composeSlideshow(spec, join(out, 'nativeblocked'), { ratios: ['9x16'], browserFactory: blocked })
      expect(nativeBlocked.checks.find((c) => c.rule === 'font')?.message).toMatch(/TikTok Sans did not load/)
    } finally {
      server.close()
      rmSync(out, { recursive: true, force: true })
    }
  }, 120000)
})
