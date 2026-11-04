import { describe, it, expect } from 'vitest'
import { buildPreviewHtml, changeLabel, creativeRatios, fieldLabel, mediaIdOf, specMediaIds, staleAltSlides, withDisplayUrls } from './slideText'
import { BG_ID, SPEC } from './slideTextFixtures'

describe('fieldLabel', () => {
  it.each([
    ['texts[0].t', 'Text 1'],
    ['texts[2].t', 'Text 3'],
    ['callouts[0].label', 'Callout'],
    ['callouts[1].label', 'Callout 2'],
    ['items[2]', 'Note item 3'],
    ['items[0].t', 'Note item 1'],
    ['bubbles[1].t', 'Bubble 2'],
    ['rows[0].k', 'Tier 1 label'],
    ['rows[1].items[2]', 'Tier 2 item 3'],
    ['sections[0].items[1]', 'Section 1 item 2'],
    ['title', 'Title'],
    ['unknown.path', 'unknown.path'],
  ])('%s -> %s', (path, label) => {
    expect(fieldLabel(path)).toBe(label)
  })

  it('names a change by slide and field', () => {
    expect(changeLabel({ slide: 3, path: 'texts[0].t' })).toBe('Slide 3 · Text 1')
  })
})

describe('preview HTML', () => {
  const urlFor = (id: string) => (id === BG_ID ? 'https://storage.example/bg.jpg' : null)

  it('finds and swaps media refs', () => {
    expect(mediaIdOf(`media:${BG_ID}`)).toBe(BG_ID)
    expect(mediaIdOf('https://x/y.png')).toBeNull()
    expect(specMediaIds(SPEC)).toEqual([BG_ID])
    expect(withDisplayUrls(SPEC, urlFor).slides[0]!.bg).toBe('https://storage.example/bg.jpg')
    expect(SPEC.slides[0]!.bg).toBe(`media:${BG_ID}`) // the original is untouched
  })

  it('builds the slide with the edit applied, the media URL and the font folder', () => {
    const html = buildPreviewHtml({ slideshowSpec: SPEC, slideStyle: null }, [{ slide: 1, path: 'texts[0].t', text: 'Brand new\nhook' }], 0, '9x16', urlFor)!
    expect(html).toContain('Brand new<br>hook')
    expect(html).not.toContain('Hook line')
    expect(html).toContain('src="https://storage.example/bg.jpg"')
    expect(html).toContain('/slideshow-fonts/tiktok-sans-700.ttf')
    expect(html).toContain('__CHECKS')
  })

  it('returns null without a spec or for a slide that does not exist', () => {
    expect(buildPreviewHtml({ slideshowSpec: null }, [], 0, '9x16', urlFor)).toBeNull()
    expect(buildPreviewHtml({ slideshowSpec: SPEC }, [], 9, '9x16', urlFor)).toBeNull()
  })

  it('follows the Creative’s ratios', () => {
    expect(creativeRatios({ '4x5': ['a'] })).toEqual(['4x5'])
    expect(creativeRatios({ '9x16': ['a'], '4x5': ['b'] })).toEqual(['9x16', '4x5'])
    expect(creativeRatios({ default: ['a'] })).toEqual(['9x16', '4x5'])
  })
})

describe('staleAltSlides', () => {
  const diff = [{ slide: 2, path: 'texts[0].t', old: 'Save time', new: 'Save money' }]
  it('keeps a flagged slide whose alt text still quotes the old words', () => {
    expect(staleAltSlides([2], diff, ['', 'Poster that says Save time on dinner'])).toEqual([2])
  })
  it('drops it once the alt text no longer quotes them, or is empty', () => {
    expect(staleAltSlides([2], diff, ['', 'Poster that says Save money'])).toEqual([])
    expect(staleAltSlides([2], diff, ['', ''])).toEqual([])
  })
  it('trusts the flag when the diff has nothing for the slide', () => {
    expect(staleAltSlides([1], [], ['Some alt'])).toEqual([1])
    expect(staleAltSlides(null, diff, ['x'])).toEqual([])
  })
})
