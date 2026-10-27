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
const FAMILY = /^[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}$/
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)

/** Every problem with a (partial) style; empty = valid. Same rules as the backend. */
export function validateSlideshowStyle(style: unknown, at = 'style'): string[] {
  if (!isObj(style)) return [`${at} must be an object`]
  const errors: string[] = []
  for (const key of Object.keys(style)) {
    if (!(STYLE_KEYS as readonly string[]).includes(key)) errors.push(`${at}.${key} is not a style field; use ${STYLE_KEYS.join(', ')}`)
  }
  const v = (k: string) => style[k]
  if (v('fontFamily') != null && !(typeof v('fontFamily') === 'string' && FAMILY.test(v('fontFamily') as string))) {
    errors.push(`${at}.fontFamily must be a plain font family name (letters, digits, spaces, "." "_" "-")`)
  }
  if (v('fontUrl') != null) {
    const url = v('fontUrl')
    let ok = typeof url === 'string' && /^https?:\/\/[^\s"'<>()\\]+$/i.test(url)
    if (ok) {
      try {
        new URL(url as string)
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

/**
 * Layers the given styles over the native look, later layers winning. A layer's null/absent field leaves the
 * layer below untouched, except a layer that sets `fontFamily` also takes its `fontUrl` (null clears it),
 * so a new family never inherits the previous layer's stylesheet.
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
    if (layer.fontFamily != null) {
      out.fontFamily = layer.fontFamily
      out.fontUrl = layer.fontUrl ?? null
    } else if (layer.fontUrl != null) {
      out.fontUrl = layer.fontUrl
    }
  })
  return out
}
