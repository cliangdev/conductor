import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { buildSlideHtml, type SlideshowSpec } from '../lib/slideshow/index.js'
import { bundledFamiliesFor } from '../lib/slideshow/html.js'

// The goldens are the HTML the TikTok Sans slide types (photo, screen) produced before the bundled Inter / Source
// Serif 4 / Courier Prime fonts were added. The slides that use TikTok Sans must render the same, so the only
// differences allowed are the ones listed here: the measuring script (new rules), and the font-family declarations
// of the system-UI slide types (whose rules sit in every sheet but apply to no photo or screen slide).
const here = join(dirname(fileURLToPath(import.meta.url)), 'fixtures')
const golden = (name: string) => readFileSync(join(here, name), 'utf8')
const base = 'file:///fonts/'

/** New stylesheet declarations -> the declarations they replaced. */
const INTENDED: Array<[string, string]> = [
  // the 9:16 top overlay band grew to 290 and the right to 162: the native text container and the default tops moved clear of them
  ['.txt{position:absolute;left:70px;right:180px;', '.txt{position:absolute;left:70px;right:150px;'],
  ['.tier{position:absolute;left:0;right:0;top:var(--top,310px)', '.tier{position:absolute;left:0;right:0;top:var(--top,250px)'],
  ['.week{position:absolute;left:60px;right:60px;top:var(--top,310px)', '.week{position:absolute;left:60px;right:60px;top:var(--top,260px)'],
  ['.receipt{position:absolute;left:150px;right:150px;top:var(--top,310px)', '.receipt{position:absolute;left:150px;right:150px;top:var(--top,250px)'],
  ['gap:26px;padding:14px 0;border-top:2px solid #ececf0', 'gap:26px;padding:20px 0;border-top:2px solid #ececf0'],
  // a re-render of a saved slide (text edit) keeps the old text width and week spacing
  ['#s.legacy .txt:not(.full){right:150px}\n#s.legacy .week .d{padding:20px 0}\n', ''],
  ['font-family:"Inter",sans-serif;padding:0 70px}', 'font-family:-apple-system,"SF Pro Text","Helvetica Neue",sans-serif;padding:0 70px}'],
  ['font:600 24px "Inter",sans-serif;', 'font:600 24px -apple-system,"Helvetica Neue",sans-serif;'],
  ['font-family:"Inter",sans-serif;background:#fff;border-radius:36px', 'font-family:-apple-system,"Helvetica Neue",sans-serif;background:#fff;border-radius:36px'],
  ['font-family:"Courier Prime",monospace;', 'font-family:"Courier New",Courier,monospace;'],
  ['font-family:"Source Serif 4",serif;', 'font-family:Georgia,"Times New Roman",serif;'],
  ['background:#fff;font-family:"Inter",sans-serif}', 'background:#fff;font-family:-apple-system,"SF Pro Text","Helvetica Neue",sans-serif}'],
]
const withoutScript = (html: string) => html.slice(0, html.lastIndexOf('<script>'))
const undoIntended = (html: string) => INTENDED.reduce((h, [now, was]) => {
  expect(h, `expected the new declaration ${now}`).toContain(now)
  return h.replace(now, was)
}, html)

const photo: SlideshowSpec = {
  id: 'g',
  slides: [{ type: 'photo', bg: 'file:///a.jpg', dim: true, grade: true, texts: [{ t: 'Hook line here', y: 30 }, { t: 'second', style: 'dark' }, { t: 'by the way', y: 60, style: 'stroke' }, { t: 'sub text', style: 'sub' }] }],
}
const screen: SlideshowSpec = {
  id: 'g',
  slides: [{ type: 'screen', shot: 'file:///s.png', phoneY: 30, callouts: [{ x: 50, y: 60, label: 'tap here' }], texts: [{ t: 'Look at this', y: 22 }] }],
}

describe('TikTok Sans slides are unchanged by the bundled system-UI fonts', () => {
  const cases: Array<[string, SlideshowSpec, '9x16' | '4x5', string]> = [
    ['photo 9x16', photo, '9x16', 'slideshow-photo-9x16.golden.html'],
    ['photo 4x5', photo, '4x5', 'slideshow-photo-4x5.golden.html'],
    ['screen 9x16', screen, '9x16', 'slideshow-screen-9x16.golden.html'],
  ]
  for (const [name, spec, ratio, file] of cases) {
    it(`${name}: same markup and stylesheet, no font it never loads`, () => {
      const now = buildSlideHtml(spec, 0, ratio, base)
      expect(undoIntended(withoutScript(now))).toBe(withoutScript(golden(file)))
      expect(now).not.toMatch(/inter-\d|source-serif|courier-prime/)
      expect(now).not.toContain('"bundled"')
    })
  }
})

describe('bundled fonts', () => {
  const html = (slide: SlideshowSpec['slides'][number]) => buildSlideHtml({ id: 't', slides: [slide] }, 0, '9x16', base)

  it('maps each system-UI slide type to the family it draws with', () => {
    expect(bundledFamiliesFor({ type: 'notes' })).toEqual(['Inter'])
    expect(bundledFamiliesFor({ type: 'week' })).toEqual(['Inter'])
    expect(bundledFamiliesFor({ type: 'messages' })).toEqual(['Inter'])
    expect(bundledFamiliesFor({ type: 'placard' })).toEqual(['Source Serif 4'])
    expect(bundledFamiliesFor({ type: 'receipt' })).toEqual(['Courier Prime'])
    expect(bundledFamiliesFor({ type: 'grid', photos: [{ src: 'a' }] })).toEqual([])
    expect(bundledFamiliesFor({ type: 'grid', photos: [{ src: 'a', badge: '2x' }] })).toEqual(['Inter'])
    for (const t of ['photo', 'screen', 'stack', 'tier'] as const) expect(bundledFamiliesFor({ type: t }), t).toEqual([])
  })

  it('declares only the faces the slide uses, from the fonts folder', () => {
    const notes = html({ type: 'notes', title: 'T' })
    expect(notes).toContain('@font-face{font-family:"Inter";font-weight:400;src:url(file:///fonts/inter-400.woff2) format("woff2")}')
    expect(notes).toContain('inter-600.woff2')
    expect(notes).toContain('inter-700.woff2')
    expect(notes).not.toContain('courier-prime')
    expect(html({ type: 'receipt', head: 'R', sections: [{ h: 'a', items: [] }] })).toContain('url(file:///fonts/courier-prime-700.ttf) format("truetype")')
    expect(html({ type: 'placard', title: 'P' })).toContain('url(file:///fonts/source-serif-4-400.woff2)')
  })

  it('uses no Apple or system font name on the system-UI slides', () => {
    for (const slide of [
      { type: 'notes' as const, title: 'T' },
      { type: 'week' as const, days: [{ d: 'Mon', t: 'x' }] },
      { type: 'messages' as const, bubbles: [{ from: 'me' as const, t: 'hi' }] },
      { type: 'receipt' as const, head: 'R', sections: [{ h: 'a', items: [] }] },
      { type: 'placard' as const, title: 'P' },
    ]) {
      const css = html(slide).match(/<style>([\s\S]*)<\/style>/)![1]!
      // the only -apple-system left is the root #s rule shared by every slide (TikTok Sans first); the rules of these types have none
      const typeRules = css.split('\n').filter((l) => /^\.(notes|msgs|week|receipt|placard|badge)/.test(l))
      expect(typeRules.join('\n')).not.toMatch(/apple-system|Helvetica|SF Pro|Georgia|Times|Courier New|Courier,/)
    }
  })

  it('tells the measuring script which families and weights to require', () => {
    const meta = JSON.parse(html({ type: 'notes', title: 'T' }).match(/window\.__META=(.*?);<\/script>/)![1]!)
    expect(meta.bundled).toEqual([{ family: 'Inter', weights: [400, 600, 700] }])
    expect(JSON.parse(html({ type: 'receipt', head: 'R', sections: [{ h: 'a', items: [] }] }).match(/window\.__META=(.*?);<\/script>/)![1]!).bundled).toEqual([{ family: 'Courier Prime', weights: [400, 700] }])
  })
})
