import { describe, it, expect } from 'vitest'
import { buildSlideHtml, yPx, validateSlideshowSpec, type SlideshowSpec } from '../lib/slideshow/index.js'

const base = 'file:///fonts/'
const html = (slide: SlideshowSpec['slides'][number], ratio: '9x16' | '4x5' = '9x16', extra: Partial<SlideshowSpec> = {}) =>
  buildSlideHtml({ id: 't', slides: [slide], ...extra }, 0, ratio, base)

describe('buildSlideHtml', () => {
  it('renders a photo slide as bg + texts with the right frame height', () => {
    const out = html({ bg: 'file:///a.png', dim: true, texts: [{ t: 'hello', y: 20 }, { t: 'sub', style: 'sub' }] })
    expect(out).toContain('<img class="bg')
    expect(out).toContain('src="file:///a.png"')
    expect(out).toContain('class="dim"')
    expect(out).toContain('--H:1920px')
    expect(out).toContain('url(file:///fonts/tiktok-sans-700.ttf)')
    expect(html({ bg: 'x' }, '4x5')).toContain('--H:1350px')
  })

  it('stacks texts without y into the previous group', () => {
    const out = html({ texts: [{ t: 'one', y: 10 }, { t: 'two' }, { t: 'three', y: 60 }] })
    expect(out.match(/class="txt /g)).toHaveLength(2)
    expect(out.indexOf('one')).toBeLessThan(out.indexOf('two'))
    expect(out.indexOf('two')).toBeLessThan(out.indexOf('class="txt ', out.indexOf('two')))
  })

  it('escapes text and attributes', () => {
    const out = html({ texts: [{ t: '<script>alert(1)</script> & "q"', y: 30 }] })
    expect(out).toContain('&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot;')
    expect(out).not.toContain('<script>alert(1)')
    const meta = html({ texts: [{ t: '</script><b>', y: 30 }] })
    expect(meta).not.toContain('</script><b>')
  })

  it('maps y into the centre band on 4:5 exactly like the original', () => {
    expect(yPx(50, '9x16')).toBe(960)
    expect(yPx(50, '4x5')).toBe(960 - 285)
    expect(yPx(0, '4x5')).toBe(70)
    expect(yPx(100, '4x5')).toBe(1210)
    expect(html({ texts: [{ t: 'a', y: 50 }] }, '4x5')).toContain('top:675px')
  })

  it('renders every slide type', () => {
    expect(html({ type: 'screen', shot: 's.png', callouts: [{ x: 50, y: 60, label: 'tap here' }] })).toMatch(/class="phone".*class="pill".*tap here/s)
    expect(html({ type: 'notes', title: 'List', items: ['a', { t: 'b', done: true }] })).toContain('&#x2039; Notes')
    expect(html({ type: 'grid', cols: 4, photos: [{ src: 'a.png', x: true, badge: '0:40' }] })).toContain('class="badge"')
    expect(html({ type: 'stack', photos: [{ src: 'a.png', label: 'Before' }, { src: 'b.png', label: 'After' }] })).toMatch(/Before.*After/s)
    expect(html({ type: 'tier', title: 'T', rows: [{ k: 'S', c: '#f00', items: ['x'] }] })).toContain('class="k"')
    expect(html({ type: 'week', days: [{ d: 'Mon', t: 'Tacos' }] })).toContain('Tacos')
    expect(html({ type: 'placard', title: 'Exhibit', lines: [{ k: 'Medium:', v: 'video' }] })).toContain('class="placard"')
    const r = html({ type: 'receipt', head: 'SHOP', sections: [{ h: 'A', items: ['i'] }], count: '3' })
    expect(r).toContain('SHOP')
    expect(r).not.toContain('GROCERY RUN')
  })

  it('renders a messages thread with me/them bubbles and uses top/top4', () => {
    const slide = { type: 'messages' as const, title: 'Mom', top: 400, top4: 80, bubbles: [{ from: 'me' as const, t: 'hi' }, { from: 'them' as const, t: 'yo' }] }
    const out = html(slide)
    expect(out).toContain('class="b me"')
    expect(out).toContain('class="b them"')
    expect(out).toContain('--top:400px')
    expect(html(slide, '4x5')).toContain('--top:80px')
    expect(out).toContain('>Mom<')
  })

  it('renders stack labels as box pills', () => {
    const out = html({ type: 'stack', photos: [{ src: 'a.png', label: 'Before' }, { src: 'b.png' }] })
    expect(out).toContain('class="box" data-fg data-ts>Before')
    expect(out.match(/class="lbl"/g)).toHaveLength(1)
  })
})

describe('validateSlideshowSpec', () => {
  const ok = (slides: unknown[]) => validateSlideshowSpec({ id: 'x', slides })
  it('accepts a minimal photo slide', () => {
    expect(ok([{ bg: 'a.png', texts: [{ t: 'hi', y: 10 }] }])).toEqual([])
  })
  it('rejects bad ids, empty and oversized slides', () => {
    expect(validateSlideshowSpec({ id: '../x', slides: [{}] }).join()).toMatch(/id is required/)
    expect(validateSlideshowSpec({ id: 'x', slides: [] }).join()).toMatch(/non-empty/)
    expect(ok(Array.from({ length: 36 }, () => ({}))).join()).toMatch(/maximum is 35/)
  })
  it('names unknown types and missing required fields', () => {
    expect(ok([{ type: 'poster' }]).join()).toMatch(/type "poster" is unknown/)
    expect(ok([{ type: 'screen' }]).join()).toMatch(/shot/)
    expect(ok([{ type: 'placard' }]).join()).toMatch(/title/)
    expect(ok([{ type: 'receipt', sections: [{ h: 'a', items: [] }] }]).join()).toMatch(/head/)
    expect(ok([{ type: 'grid' }]).join()).toMatch(/photos/)
    expect(ok([{ type: 'tier' }]).join()).toMatch(/rows/)
    expect(ok([{ type: 'week' }]).join()).toMatch(/days/)
    expect(ok([{ type: 'messages' }]).join()).toMatch(/bubbles/)
    expect(ok([{ type: 'messages', bubbles: [{ from: 'you', t: 'x' }] }]).join()).toMatch(/"me" or "them"/)
  })
  it('checks text fields', () => {
    expect(ok([{ texts: [{ t: 'a', y: 120 }] }]).join()).toMatch(/y must be a number from 0 to 100/)
    expect(ok([{ texts: [{ t: 'a' }] }]).join()).toMatch(/first text/)
    expect(ok([{ texts: [{ t: 'a', y: 1, style: 'neon' }] }]).join()).toMatch(/style "neon"/)
    expect(ok([{ texts: [{ y: 1 }] }]).join()).toMatch(/\.t is required/)
  })
  it('checks callouts and colours', () => {
    expect(ok([{ type: 'screen', shot: 's', callouts: [{ x: 150, y: 1, label: 'a' }] }]).join()).toMatch(/callouts\[0\]\.x/)
    expect(ok([{ bgColor: 'red;} body{display:none' }]).join()).toMatch(/bgColor/)
  })
})
