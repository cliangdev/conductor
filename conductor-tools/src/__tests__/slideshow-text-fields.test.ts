import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { MAX_TEXT_LENGTH, TEXT_FIELD_PATTERNS, applyTextEdits, diffText, isEditablePath, listTextFields, patternOf } from '../lib/slideshow/text-fields.js'
import type { SlideshowSpec } from '../lib/slideshow/spec.js'

// conductor-creative/slideshow-text-fields.json is the shared registry (SlideshowTextFieldsParityTest asserts the same
// file on the backend). Skipped, never failed, when the sibling package is not checked out.
const file = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'conductor-creative', 'slideshow-text-fields.json')

describe.skipIf(!existsSync(file))('text-field registry equals conductor-creative/slideshow-text-fields.json', () => {
  const rules = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {}

  it('patterns and max length', () => {
    expect([...TEXT_FIELD_PATTERNS]).toEqual(rules.patterns)
    expect(MAX_TEXT_LENGTH).toBe(rules.maxTextLength)
  })

  it('every pattern starts at a known slide field of the spec type', () => {
    const known = new Set(['texts', 'callouts', 'title', 'date', 'items', 'paras', 'photos', 'rows', 'days', 'head', 'sections', 'count', 'ex', 'lines', 'bubbles'])
    for (const p of rules.patterns as string[]) expect(known.has(p.split(/[.[]/)[0]!), p).toBe(true)
  })
})

const spec = (): SlideshowSpec =>
  ({
    id: 't',
    slides: [
      { type: 'photo', bg: '/a.jpg', texts: [{ t: 'Hook line', y: 30 }, { t: 'second' }] },
      { type: 'screen', shot: '/s.png', callouts: [{ x: 10, y: 20, label: 'Tap here' }], texts: [{ t: 'Look', y: 20 }] },
      { type: 'notes', title: 'List', date: 'Today', items: ['milk', { t: 'eggs', done: true }], paras: ['para one'] },
      { type: 'grid', photos: [{ src: '/p.jpg', badge: '2x', label: 'cap' }, { src: '/q.jpg' }] },
      { type: 'tier', title: 'tiers', rows: [{ k: 'S', c: '#fff', items: ['a', 'b'] }, { k: 'A', c: '#000', items: ['c'] }] },
      { type: 'week', title: 'week', days: [{ d: 'Mon', t: 'run', icon: 'x' }, { d: 'Tue', t: 'rest', img: '/i.png' }] },
      { type: 'receipt', head: 'RECEIPT', sections: [{ h: 'Food', items: ['bread', 'jam'] }], count: '3' },
      { type: 'placard', title: 'Piece', ex: 'EXHIBIT', lines: [{ k: 'Year', v: '2024' }, { v: 'no key' }] },
      { type: 'messages', title: 'Chat', bubbles: [{ from: 'me', t: 'hi' }, { from: 'them', t: 'hello' }] },
    ],
  }) as SlideshowSpec

describe('patternOf / isEditablePath', () => {
  it('turns indexes into []', () => {
    expect(patternOf('rows[1].items[0]')).toBe('rows[].items[]')
    expect(patternOf('title')).toBe('title')
  })
  it('accepts registry paths only', () => {
    expect(isEditablePath('texts[0].t')).toBe(true)
    expect(isEditablePath('rows[12].items[3]')).toBe(true)
    expect(isEditablePath('title')).toBe(true)
    for (const bad of ['texts[0].y', 'bg', 'photos[0].src', 'texts[].t', 'texts', 'texts[0]', 'rows[0].c', '', '__proto__', 'texts[0].t.x', 'texts[-1].t', 'texts[0] .t', 'items[0].t.u', 'sections[0].items']) {
      expect(isEditablePath(bad), bad).toBe(false)
    }
  })
})

describe('listTextFields', () => {
  it('lists every editable string with a 1-based slide and a concrete path', () => {
    const fields = listTextFields(spec())
    const at = (slide: number) => fields.filter((f) => f.slide === slide).map((f) => `${f.path}=${f.text}`)
    expect(at(1)).toEqual(['texts[0].t=Hook line', 'texts[1].t=second'])
    expect(at(2)).toEqual(['callouts[0].label=Tap here', 'texts[0].t=Look'])
    expect(at(3)).toEqual(['title=List', 'date=Today', 'items[0]=milk', 'items[1].t=eggs', 'paras[0]=para one'])
    expect(at(4)).toEqual(['photos[0].badge=2x', 'photos[0].label=cap'])
    expect(at(5)).toEqual(['title=tiers', 'rows[0].k=S', 'rows[0].items[0]=a', 'rows[0].items[1]=b', 'rows[1].k=A', 'rows[1].items[0]=c'])
    expect(at(6)).toEqual(['title=week', 'days[0].d=Mon', 'days[0].t=run', 'days[0].icon=x', 'days[1].d=Tue', 'days[1].t=rest'])
    expect(at(7)).toEqual(['head=RECEIPT', 'sections[0].h=Food', 'sections[0].items[0]=bread', 'sections[0].items[1]=jam', 'count=3'])
    expect(at(8)).toEqual(['title=Piece', 'ex=EXHIBIT', 'lines[0].k=Year', 'lines[0].v=2024', 'lines[1].v=no key'])
    expect(at(9)).toEqual(['title=Chat', 'bubbles[0].t=hi', 'bubbles[1].t=hello'])
  })

  it('skips image refs, colours, absent fields and non-strings', () => {
    const paths = listTextFields(spec()).map((f) => f.path)
    expect(paths.some((p) => /src|bg|shot|\.c$|\.from|\.img/.test(p))).toBe(false)
    expect(listTextFields({ id: 'x', slides: [{ type: 'photo', title: 5, texts: [{ t: 7 }] }] })).toEqual([])
  })

  it('tolerates a malformed spec', () => {
    expect(listTextFields(null)).toEqual([])
    expect(listTextFields({ slides: 'no' })).toEqual([])
  })
})

describe('applyTextEdits', () => {
  it('replaces existing strings and returns a copy', () => {
    const original = spec()
    const out = applyTextEdits(original, [
      { slide: 1, path: 'texts[0].t', text: 'New hook' },
      { slide: 5, path: 'rows[1].items[0]', text: 'Z' },
      { slide: 3, path: 'items[0]', text: 'oat milk' },
      { slide: 3, path: 'items[1].t', text: 'duck eggs' },
    ])
    expect(out.slides[0]!.texts![0]!.t).toBe('New hook')
    expect(out.slides[4]!.rows![1]!.items[0]).toBe('Z')
    expect(out.slides[2]!.items![0]).toBe('oat milk')
    expect((out.slides[2]!.items![1] as { t: string; done?: boolean }).t).toBe('duck eggs')
    expect((out.slides[2]!.items![1] as { done?: boolean }).done).toBe(true)
    expect(original.slides[0]!.texts![0]!.t).toBe('Hook line')
    expect(out.slides[0]!.texts![0]!.y).toBe(30)
  })

  it('is atomic: a bad later edit throws and the input is untouched', () => {
    const original = spec()
    expect(() => applyTextEdits(original, [{ slide: 1, path: 'texts[0].t', text: 'x' }, { slide: 1, path: 'bg', text: 'y' }])).toThrow(/not an editable/)
    expect(original.slides[0]!.texts![0]!.t).toBe('Hook line')
  })

  it('refuses a slide out of range', () => {
    expect(() => applyTextEdits(spec(), [{ slide: 0, path: 'title', text: 'x' }])).toThrow(/does not exist/)
    expect(() => applyTextEdits(spec(), [{ slide: 10, path: 'title', text: 'x' }])).toThrow(/does not exist/)
    expect(() => applyTextEdits(spec(), [{ slide: 1.5, path: 'title', text: 'x' }])).toThrow(/does not exist/)
  })

  it('refuses a path outside the registry', () => {
    for (const path of ['bg', 'texts[0].y', 'photos[0].src', 'rows[0].c', 'bubbles[0].from', '__proto__.t', 'texts[].t']) {
      expect(() => applyTextEdits(spec(), [{ slide: 1, path, text: 'x' }]), path).toThrow()
    }
  })

  it('refuses a missing path and never adds entries or fields', () => {
    expect(() => applyTextEdits(spec(), [{ slide: 1, path: 'texts[5].t', text: 'x' }])).toThrow(/does not exist/)
    expect(() => applyTextEdits(spec(), [{ slide: 1, path: 'title', text: 'x' }])).toThrow(/does not exist/)
    expect(() => applyTextEdits(spec(), [{ slide: 8, path: 'lines[1].k', text: 'x' }])).toThrow(/does not exist/)
    expect(() => applyTextEdits(spec(), [{ slide: 4, path: 'photos[1].badge', text: 'x' }])).toThrow(/does not exist/)
  })

  it('refuses a non-string target', () => {
    // items[1] is an object, so only items[1].t is a text value
    expect(() => applyTextEdits(spec(), [{ slide: 3, path: 'items[1]', text: 'x' }])).toThrow(/not a text value/)
    expect(() => applyTextEdits({ id: 'x', slides: [{ title: 4 }] } as unknown as SlideshowSpec, [{ slide: 1, path: 'title', text: 'x' }])).toThrow(/not a text value/)
  })

  it('refuses text over the maximum and non-string text', () => {
    expect(() => applyTextEdits(spec(), [{ slide: 1, path: 'texts[0].t', text: 'x'.repeat(MAX_TEXT_LENGTH + 1) }])).toThrow(/maximum/)
    expect(applyTextEdits(spec(), [{ slide: 1, path: 'texts[0].t', text: 'x'.repeat(MAX_TEXT_LENGTH) }]).slides[0]!.texts![0]!.t).toHaveLength(MAX_TEXT_LENGTH)
    expect(() => applyTextEdits(spec(), [{ slide: 1, path: 'texts[0].t', text: 5 as unknown as string }])).toThrow(/string/)
  })

  it('accepts newlines and the empty string', () => {
    expect(applyTextEdits(spec(), [{ slide: 1, path: 'texts[1].t', text: 'a\nb' }]).slides[0]!.texts![1]!.t).toBe('a\nb')
    expect(applyTextEdits(spec(), [{ slide: 1, path: 'texts[1].t', text: '' }]).slides[0]!.texts![1]!.t).toBe('')
  })
})

describe('diffText', () => {
  it('is empty for equal specs', () => {
    expect(diffText(spec(), spec())).toEqual([])
  })

  it('reports changed fields with a 1-based slide', () => {
    const b = applyTextEdits(spec(), [{ slide: 2, path: 'callouts[0].label', text: 'Tap there' }, { slide: 6, path: 'days[1].t', text: 'sleep' }])
    expect(diffText(spec(), b)).toEqual([
      { slide: 2, path: 'callouts[0].label', old: 'Tap here', new: 'Tap there' },
      { slide: 6, path: 'days[1].t', old: 'rest', new: 'sleep' },
    ])
  })

  it('ignores non-text changes', () => {
    const b = spec()
    b.slides[0]!.bg = '/other.jpg'
    b.slides[0]!.texts![0]!.y = 10
    expect(diffText(spec(), b)).toEqual([])
  })

  it('uses null for a field present on one side only', () => {
    const b = spec()
    b.slides[7]!.lines![1]!.k = 'Medium'
    expect(diffText(spec(), b)).toEqual([{ slide: 8, path: 'lines[1].k', old: null, new: 'Medium' }])
    expect(diffText(b, spec())).toEqual([{ slide: 8, path: 'lines[1].k', old: 'Medium', new: null }])
  })

  it('only diffs the slides both specs have', () => {
    const b = spec()
    b.slides.push({ type: 'photo', texts: [{ t: 'extra', y: 20 }] })
    b.slides[0]!.texts![0]!.t = 'changed'
    expect(diffText(spec(), b)).toEqual([{ slide: 1, path: 'texts[0].t', old: 'Hook line', new: 'changed' }])
    expect(diffText(b, spec())).toEqual([{ slide: 1, path: 'texts[0].t', old: 'changed', new: 'Hook line' }])
  })
})
