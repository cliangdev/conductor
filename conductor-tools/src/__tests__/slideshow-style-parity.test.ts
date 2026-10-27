import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  FONT_URL_PATTERN,
  GENERIC_FAMILIES,
  HEX_PATTERN,
  MAX_FONT_FAMILY,
  MAX_FONT_URL,
  NATIVE_STYLE,
  STYLE_BOUNDS,
  STYLE_TEXT_KINDS,
  validateSlideshowStyle,
} from '../lib/slideshow/style.js'

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
