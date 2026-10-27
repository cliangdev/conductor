import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {
  FONT_URL_PATTERN,
  GENERIC_FAMILIES,
  HEX_PATTERN,
  MAX_FONT_FAMILY,
  MAX_FONT_URL,
  NATIVE_STYLE,
  STYLE_BOUNDS,
  STYLE_TEXT_KINDS,
  fontFamilyCss,
  fontFamilyNames,
  isGenericFamily,
  validateSlideshowStyle,
} from '../lib/slideshow/style.js'
import { brandKitSlug } from '../lib/slug.js'

// conductor-creative/slideshow-style.json is the shared source of the style rules (the backend asserts the same
// file in SlideshowStylesParityTest). Skipped, never failed, when the sibling package is not checked out.
const file = join(dirname(fileURLToPath(import.meta.url)), '..', '..', '..', 'conductor-creative', 'slideshow-style.json')

describe.skipIf(!existsSync(file))('slideshow style constants equal conductor-creative/slideshow-style.json', () => {
  const rules = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {}
  const source = (re: RegExp) => re.source.replace(/\\\//g, '/')

  it('native values', () => {
    const { fontFamily, fontUrl, ...native } = NATIVE_STYLE
    expect(fontFamily).toBeNull()
    expect(fontUrl).toBeNull()
    expect(native).toEqual(rules.native)
  })

  it('keywords, kinds, bounds, lengths and patterns', () => {
    expect([...GENERIC_FAMILIES]).toEqual(rules.genericFamilies)
    expect([...STYLE_TEXT_KINDS]).toEqual(rules.defaultTextKinds)
    expect(STYLE_BOUNDS).toEqual(rules.bounds)
    expect({ fontFamily: MAX_FONT_FAMILY, fontUrl: MAX_FONT_URL }).toEqual(rules.maxLength)
    expect(source(HEX_PATTERN)).toBe(rules.hexPattern)
    expect(source(FONT_URL_PATTERN)).toBe(rules.fontUrlPattern)
  })

  it('forbidden font-family characters', () => {
    const bad = (c: string) => validateSlideshowStyle({ fontFamily: `A${c}B` }).length > 0
    for (const c of rules.forbiddenFontFamilyChars as string) expect(bad(c), `char ${c}`).toBe(true)
    expect(rules.forbidControlCharsInFontFamily).toBe(true)
    expect(bad('\u0007')).toBe(true)
    for (let code = 0x20; code < 0x7f; code++) {
      const c = String.fromCharCode(code)
      if (c !== ',' && !(rules.forbiddenFontFamilyChars as string).includes(c)) expect(bad(c), `char ${c}`).toBe(false)
    }
  })
})

// Behavioural parity: the web form runs conductor-creative/slideshow-style.js, the CLI its TypeScript copy. Both run
// over the same inputs and must agree. Skipped when the sibling package is absent.
const jsFile = join(dirname(file), 'slideshow-style.js')

describe.skipIf(!existsSync(file) || !existsSync(jsFile))('slideshow-style.js behaves like the CLI helpers', () => {
  const FAMILIES = ['Inter, sans-serif', '-apple-system, BlinkMacSystemFont, sans-serif', '8bit Wonder', 'Söhne', '","', ',', '', '   ', ' Open Sans ,, Lora ', 'SANS-SERIF, blinkmacsystemfont', 'A'.repeat(201), `${'A'.repeat(195)}, Bold`, 'A;B', 'A\u0007B']
  const rules = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {}
  const load = async () => (await import(pathToFileURL(jsFile).href)) as Record<string, (...a: unknown[]) => unknown>

  it('family names, CSS and generic check agree', async () => {
    const shared = await load()
    for (const f of FAMILIES) {
      expect(shared.fontFamilyNames!(f), `names ${f}`).toEqual(fontFamilyNames(f))
      expect(shared.fontFamilyCss!(f, rules.genericFamilies), `css ${f}`).toBe(fontFamilyCss(f))
      for (const name of fontFamilyNames(f)) expect(shared.isGenericFamily!(name, rules.genericFamilies), `generic ${name}`).toBe(isGenericFamily(name))
    }
  })

  it('family validity agrees with validateSlideshowStyle', async () => {
    const shared = await load()
    for (const f of FAMILIES) {
      expect(shared.fontFamilyValid!(f, rules.forbiddenFontFamilyChars, rules.maxLength.fontFamily), `valid ${JSON.stringify(f)}`).toBe(validateSlideshowStyle({ fontFamily: f }).length === 0)
    }
  })

  it('derives the same Brand Kit slug as the CLI, equal to the table the web form test asserts', async () => {
    const shared = await load()
    const table: Array<[string, string]> = [
      ['Café Summer', 'cafe-summer'],
      ['Holiday Drop!', 'holiday-drop'],
      ['x'.repeat(70), 'x'.repeat(60)],
      [`${'word '.repeat(14)}end`, `${'word-'.repeat(12)}`.replace(/-$/, '')],
      ['!!!', 'kit'],
      ['  Ünïcode  Ñame ', 'unicode-name'],
    ]
    for (const [name, expected] of table) {
      expect(brandKitSlug(name), name).toBe(expected)
      expect(shared.brandKitSlug!(name), name).toBe(expected)
    }
  })
})
