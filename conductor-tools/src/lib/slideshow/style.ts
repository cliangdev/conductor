/**
 * Slideshow style: the look of the composer's native text and callouts. The native look (bundled TikTok
 * Sans, white/black boxes, #ff2d55 callouts) is the default; a Brand Kit's `slideshowStyle` and/or an inline
 * `style` override any of it. Mirrors the backend's `SlideshowStyle` schema.
 */

export const STYLE_TEXT_KINDS = ['box', 'dark', 'stroke'] as const
export type StyleTextKind = (typeof STYLE_TEXT_KINDS)[number]
export const STYLE_COLOR_KEYS = ['boxBg', 'boxInk', 'darkBg', 'darkInk', 'outline', 'accent'] as const
export const STYLE_KEYS = ['fontFamily', 'fontUrl', ...STYLE_COLOR_KEYS, 'defaultText', 'textSize', 'subSize'] as const

/** A partial style as stored on a Brand Kit or Creative (null/absent = not set). */
export interface SlideshowStyle {
  fontFamily?: string | null
  fontUrl?: string | null
  boxBg?: string | null
  boxInk?: string | null
  darkBg?: string | null
  darkInk?: string | null
  outline?: string | null
  accent?: string | null
  defaultText?: StyleTextKind | null
  textSize?: number | null
  subSize?: number | null
}

/** Every colour/size set; only the font may be null (null family = bundled TikTok Sans). */
export interface ResolvedSlideshowStyle {
  fontFamily: string | null
  fontUrl: string | null
  boxBg: string
  boxInk: string
  darkBg: string
  darkInk: string
  outline: string
  accent: string
  defaultText: StyleTextKind
  textSize: number
  subSize: number
}

export const NATIVE_STYLE: Readonly<ResolvedSlideshowStyle> = Object.freeze({
  fontFamily: null,
  fontUrl: null,
  boxBg: '#ffffff',
  boxInk: '#111111',
  darkBg: '#111111',
  darkInk: '#ffffff',
  outline: '#000000',
  accent: '#ff2d55',
  defaultText: 'box',
  textSize: 60,
  subSize: 44,
})

const HEX = /^#[0-9a-fA-F]{6}$/
// eslint-disable-next-line no-control-regex
const FAMILY_BAD = /[\u0000-\u001f\u007f-\u009f"';{}<>\\]/
const FONT_URL = /^https?:\/\/[^\s"'<>\\]+$/
export const GENERIC_FAMILIES = ['serif', 'sans-serif', 'monospace', 'cursive', 'fantasy', 'system-ui', 'ui-sans-serif', 'ui-serif', 'ui-monospace', 'ui-rounded', 'emoji', 'math', 'fangsong'] as const

/** The family names of a font-family list: split on commas, trimmed, blanks dropped. */
export function fontFamilyNames(family: string): string[] {
  return family.split(',').map((f) => f.trim()).filter(Boolean)
}

/** A font-family list as CSS: each family quoted unless it is a generic keyword. */
export function fontFamilyCss(family: string): string {
  return fontFamilyNames(family)
    .map((f) => ((GENERIC_FAMILIES as readonly string[]).includes(f.toLowerCase()) ? f.toLowerCase() : `"${f}"`))
    .join(',')
}

const sameFamily = (a: string, b: string): boolean => a.trim().toLowerCase() === b.trim().toLowerCase()
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/**
 * Every problem with a (partial) style; empty = valid. Same rules as the backend (fontFamily: trimmed, blank =
 * unset, 1-200 chars, a comma-separated CSS family list without control characters or any of " ' ; { } < > and backslash;
 * fontUrl: blank = unset, http(s), <= 2048 chars, no whitespace or " ' < > \). Unknown keys are errors unless
 * `ignoreUnknown` (a style read from a Brand Kit).
 */
export function validateSlideshowStyle(style: unknown, at = 'style', ignoreUnknown = false): string[] {
  if (!isObj(style)) return [`${at} must be an object`]
  const errors: string[] = []
  if (!ignoreUnknown) {
    for (const key of Object.keys(style)) {
      if (!(STYLE_KEYS as readonly string[]).includes(key)) errors.push(`${at}.${key} is not a style field; use ${STYLE_KEYS.join(', ')}`)
    }
  }
  const v = (k: string) => style[k]
  if (v('fontFamily') != null) {
    const f = v('fontFamily')
    const t = typeof f === 'string' ? f.trim() : ''
    if (typeof f !== 'string' || (t !== '' && (t.length > 200 || FAMILY_BAD.test(t) || fontFamilyNames(t).length === 0))) {
      errors.push(`${at}.fontFamily must be a font name or comma-separated list up to 200 characters, without quotes or any of ; { } < > \\`)
    }
  }
  if (v('fontUrl') != null) {
    const url = v('fontUrl')
    const t = typeof url === 'string' ? url.trim() : ''
    let ok = typeof url === 'string' && (t === '' || (t.length <= 2048 && FONT_URL.test(t)))
    if (ok && t !== '') {
      try {
        new URL(t)
      } catch {
        ok = false
      }
    }
    if (!ok) errors.push(`${at}.fontUrl must be an http(s) URL to a CSS stylesheet (for example a Google Fonts link)`)
  }
  for (const k of STYLE_COLOR_KEYS) {
    if (v(k) != null && !(typeof v(k) === 'string' && HEX.test(v(k) as string))) errors.push(`${at}.${k} must be a #RRGGBB hex colour, got ${JSON.stringify(v(k))}`)
  }
  if (v('defaultText') != null && !(STYLE_TEXT_KINDS as readonly unknown[]).includes(v('defaultText'))) {
    errors.push(`${at}.defaultText must be one of ${STYLE_TEXT_KINDS.join(', ')}`)
  }
  const int = (k: string, lo: number, hi: number) => {
    const n = v(k)
    if (n != null && !(typeof n === 'number' && Number.isInteger(n) && n >= lo && n <= hi)) errors.push(`${at}.${k} must be a whole number of pixels from ${lo} to ${hi}`)
  }
  int('textSize', 24, 120)
  int('subSize', 16, 96)
  return errors
}

export class SlideshowStyleError extends Error {
  constructor(public readonly errors: string[]) {
    super(`Invalid slideshow style:\n${errors.map((e) => `  - ${e}`).join('\n')}`)
    this.name = 'SlideshowStyleError'
  }
}

/** Drops keys that are not style fields (a Brand Kit's style may carry fields a newer backend added). */
export function pickKnownStyleFields(style: unknown): SlideshowStyle | undefined {
  if (!isObj(style)) return undefined
  const out: Record<string, unknown> = {}
  for (const k of STYLE_KEYS) if (k in style) out[k] = style[k]
  return out as SlideshowStyle
}

const blank = (s: string | null | undefined): string | null => (typeof s === 'string' && s.trim() !== '' ? s.trim() : null)

/**
 * Layers the given styles over the native look, later layers winning. A layer's null/absent field leaves the
 * layer below untouched, except a layer that sets a *different* `fontFamily` (case-insensitive) and gives no
 * `fontUrl` clears the inherited `fontUrl`, so a new family never inherits the previous layer's stylesheet.
 */
export function resolveSlideshowStyle(...layers: Array<SlideshowStyle | null | undefined>): ResolvedSlideshowStyle {
  const out: ResolvedSlideshowStyle = { ...NATIVE_STYLE }
  layers.forEach((layer, i) => {
    if (layer == null) return
    const errors = validateSlideshowStyle(layer, layers.length > 1 ? `style[${i}]` : 'style')
    if (errors.length > 0) throw new SlideshowStyleError(errors)
    for (const k of STYLE_COLOR_KEYS) if (layer[k] != null) out[k] = layer[k]!.toLowerCase()
    if (layer.defaultText != null) out.defaultText = layer.defaultText
    if (layer.textSize != null) out.textSize = layer.textSize
    if (layer.subSize != null) out.subSize = layer.subSize
    const family = blank(layer.fontFamily)
    const url = blank(layer.fontUrl)
    if (family != null) {
      if (out.fontFamily == null || !sameFamily(out.fontFamily, family)) out.fontUrl = null
      out.fontFamily = family
    }
    if (url != null) out.fontUrl = url
  })
  return out
}
