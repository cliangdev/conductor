'use client'

// Brand Kit › Slideshow style. Optional: off means organic slideshow slides use the native look
// (bundled font, built-in colours). On, the kit carries a SlideshowStyle that the slideshow tools read.
// Nothing here is a brand constant — the starting values are neutral and every one is editable.

import { useEffect, useRef, useState } from 'react'

import styleRules from '@cliangdev/creative-render/slideshow-style.json'
import { fontFamilyCss as listToCss, fontFamilyValid } from '@cliangdev/creative-render/slideshow-style'

import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { Switch } from '@/components/ui/switch'
import {
  SLIDESHOW_COLOUR_KEYS,
  type SlideshowColourKey,
  type SlideshowDefaultText,
  type SlideshowStyle,
} from '@/components/marketing/brand/types'

/**
 * The exact native look (the `native` block of conductor-creative/slideshow-style.json, the same file the CLI and
 * backend are tested against), so turning the section on changes nothing until the person edits. No font family:
 * unset means the bundled TikTok Sans.
 */
export const NATIVE_SLIDESHOW_STYLE: SlideshowStyle = { ...styleRules.native } as SlideshowStyle

const COLOUR_LABELS: Record<SlideshowColourKey, string> = {
  boxBg: 'Box background',
  boxInk: 'Box text',
  darkBg: 'Dark background',
  darkInk: 'Dark text',
  strokeInk: 'Outlined text',
  outline: 'Outline',
  accent: 'Accent',
}

const TEXT_STYLES: { value: SlideshowDefaultText; label: string }[] = [
  { value: 'box', label: 'Box (text on a card)' },
  { value: 'dark', label: 'Dark (text on a dark wash)' },
  { value: 'stroke', label: 'Stroke (outlined text)' },
]

const HEX = new RegExp(styleRules.hexPattern)

/** Drop a key when its value is empty, so unset properties never reach the wire as ''. */
function withValue<K extends keyof SlideshowStyle>(
  style: SlideshowStyle,
  key: K,
  value: SlideshowStyle[K] | '' | undefined,
): SlideshowStyle {
  const next = { ...style }
  if (value === '' || value === undefined) delete next[key]
  else next[key] = value as SlideshowStyle[K]
  return next
}

function intOrUndefined(raw: string): number | undefined {
  if (raw.trim() === '') return undefined
  const n = Number(raw)
  return Number.isFinite(n) ? Math.trunc(n) : undefined
}

const FONT_URL = new RegExp(styleRules.fontUrlPattern)
/** The same rule the CLI and backend apply to fontUrl: an https URL of at most 2048 characters. */
const fontUrlValid = (url: string): boolean => url.length <= styleRules.maxLength.fontUrl && FONT_URL.test(url)

/** A family list the preview may put in CSS: the shared validity rule (length, names, forbidden characters). */
const familyUsable = (family: string): boolean =>
  fontFamilyValid(family, styleRules.forbiddenFontFamilyChars, styleRules.maxLength.fontFamily)

function rgba(hex: string, alpha: number): string {
  const n = parseInt(hex.slice(1), 16)
  return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${alpha})`
}

/** Colour if it is a full hex, else the native fallback — half-typed values never reach CSS. */
const colour = (value: string | undefined, fallback: string) => (value && HEX.test(value) ? value : fallback)

const fontFamilyCss = (family: string): string => listToCss(family, styleRules.genericFamilies)

/** The composer's fallbacks after the chosen family. */
const COMPOSER_FALLBACKS = '-apple-system,"Helvetica Neue",sans-serif'

/** The font-family the preview uses; undefined (inherit) when no family is set. */
export function previewFontFamily(family: string | null | undefined): string | undefined {
  const css = family ? fontFamilyCss(family) : ''
  return css ? `${css},${COMPOSER_FALLBACKS}` : undefined
}

/** Native colour as the 6-digit hex a colour input needs. */
const pickerValue = (value: string | undefined, key: SlideshowColourKey): string =>
  value && HEX.test(value) ? value : (NATIVE_SLIDESHOW_STYLE[key] as string)

const FONT_LINK_DEBOUNCE_MS = 500
const PREVIEW_MIN_HEIGHT = 48

/**
 * The URL the preview frame should load: follows `url` once it has been stable for a moment or on blur, so typing
 * does not fire a request per keystroke. The stylesheet is linked inside the preview's own sandboxed iframe, never
 * in the app's document.
 */
function useStableUrl(url: string | null, flush: boolean): string | null {
  const [stable, setStable] = useState<string | null>(null)
  useEffect(() => {
    if (flush) {
      setStable(url)
      return
    }
    const t = setTimeout(() => setStable(url), FONT_LINK_DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [url, flush])
  return stable
}

const STYLE_DEBOUNCE_MS = 150

/** The style the preview document is built from: follows `style` once it has been still for a moment, so a colour drag does not reload the frame per event. */
function useDebouncedStyle(style: SlideshowStyle): SlideshowStyle {
  const [debounced, setDebounced] = useState(style)
  const key = JSON.stringify(style)
  const latest = useRef(style)
  latest.current = style
  useEffect(() => {
    const t = setTimeout(() => setDebounced(latest.current), STYLE_DEBOUNCE_MS)
    return () => clearTimeout(t)
  }, [key])
  return debounced
}

const escAttr = (v: string): string => v.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** The preview's document: its own <link> to the font stylesheet plus the sample markup and CSS. */
export function previewDocument(style: SlideshowStyle, fontUrl: string | null): string {
  const n = NATIVE_SLIDESHOW_STYLE as Required<Omit<SlideshowStyle, 'fontFamily' | 'fontUrl'>>
  const boxBg = colour(style.boxBg, n.boxBg)
  const boxInk = colour(style.boxInk, n.boxInk)
  const darkBg = colour(style.darkBg, n.darkBg)
  const darkInk = colour(style.darkInk, n.darkInk)
  const strokeInk = colour(style.strokeInk, n.strokeInk)
  const outline = colour(style.outline, n.outline)
  const accent = colour(style.accent, n.accent)
  const family = style.fontFamily && familyUsable(style.fontFamily) ? previewFontFamily(style.fontFamily) : undefined
  const shadow = `0 0 3px ${outline}, 0 0 3px ${outline}, 0 0 4px ${outline}, 0 2px 6px ${rgba(outline, 0.8)}`
  const css =
    `html,body{margin:0}html{background:${darkBg}}body{box-sizing:border-box;padding:12px;background:${darkBg};` +
    `font:14px/1.3 ${family ?? 'system-ui,sans-serif'}}` +
    `.row{display:flex;flex-wrap:wrap;align-items:center;gap:12px}` +
    `.box{padding:4px 10px;border-radius:4px;font-weight:700;background:${boxBg};color:${boxInk}}` +
    `.dark{padding:4px 10px;border-radius:4px;font-weight:700;background:${darkBg};color:${darkInk};outline:1px solid rgba(128,128,128,.5)}` +
    `.stroke{font-weight:800;color:${strokeInk};text-shadow:${shadow}}` +
    `.sub{font-size:12px;font-weight:700;color:${strokeInk};text-shadow:${shadow}}` +
    `.callout{display:flex;align-items:center;gap:6px}` +
    `.dot{width:12px;height:12px;border-radius:50%;border:2px solid #fff;background:${accent}}` +
    `.ln{width:12px;height:1px;background:${accent}}` +
    `.pill{padding:2px 8px;border-radius:4px;font-size:12px;font-weight:700;background:${boxBg};color:${boxInk}}`
  return (
    `<!doctype html><html><head><meta charset="utf-8">` +
    (fontUrl ? `<link rel="stylesheet" href="${escAttr(fontUrl)}">` : '') +
    `<style>${css}</style></head><body><div class="row">` +
    `<span class="box">Your headline here</span>` +
    `<span class="dark">Dark text</span>` +
    `<span class="stroke">Your headline here</span>` +
    `<span class="sub">Sub text</span>` +
    `<span class="callout"><span class="dot"></span><span class="ln"></span><span class="pill">Callout</span></span>` +
    `</div></body></html>`
  )
}

/**
 * The live sample: rendered in a sandboxed iframe (no scripts) with its own font <link>, so a font stylesheet
 * the person is trying out never touches the app's document.
 */
function StylePreview({ style, urlBlurred }: { style: SlideshowStyle; urlBlurred: boolean }) {
  const fontUrl = style.fontUrl?.trim()
  const linkable = fontUrl && fontUrlValid(fontUrl) ? fontUrl : null
  const stableUrl = useStableUrl(linkable, urlBlurred)
  const settled = useDebouncedStyle(style)
  const ref = useRef<HTMLIFrameElement>(null)
  const [height, setHeight] = useState(PREVIEW_MIN_HEIGHT)
  // Fit the frame to its content (the sample wraps at narrow widths). The frame is same-origin so the page can read
  // its height, but it has no allow-scripts: nothing inside it can run.
  const fit = () => {
    const h = ref.current?.contentDocument?.body?.getBoundingClientRect().height
    if (h) setHeight(Math.max(PREVIEW_MIN_HEIGHT, Math.ceil(h)))
  }
  useEffect(() => {
    const el = ref.current
    if (!el || typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver(fit)
    ro.observe(el)
    return () => ro.disconnect()
  }, [])
  return (
    <iframe
      ref={ref}
      title="Slideshow style preview"
      data-testid="slideshow-style-preview"
      sandbox="allow-same-origin"
      srcDoc={previewDocument(settled, stableUrl)}
      onLoad={fit}
      style={{ height }}
      className="w-full rounded-md border-0"
    />
  )
}

export interface SlideshowStyleSectionProps {
  value: SlideshowStyle | null
  onChange: (next: SlideshowStyle | null) => void
  errorFor?: (field: string) => string | undefined
}

export function SlideshowStyleSection({ value, onChange, errorFor }: SlideshowStyleSectionProps) {
  const style = value
  const [urlBlurred, setUrlBlurred] = useState(false)
  const err = (field: string) => errorFor?.(`slideshowStyle.${field}`)
  const typedUrl = style?.fontUrl?.trim()
  // the https message waits for the same stable-input gate as the preview link (or blur), so typing "h", "ht" ... does not flash it
  const stableTyped = useStableUrl(typedUrl || null, urlBlurred)
  const localFontUrlError =
    typedUrl && !fontUrlValid(typedUrl) && stableTyped === typedUrl ? 'Use an https:// link to a CSS stylesheet.' : undefined

  return (
    <section className="space-y-3 rounded-lg border border-border p-4">
      <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Slideshow style</h3>
      <div className="flex items-center gap-2">
        <Switch
          id="kit-slideshow-style-on"
          checked={style !== null}
          onCheckedChange={(on) => onChange(on ? { ...NATIVE_SLIDESHOW_STYLE } : null)}
        />
        <Label htmlFor="kit-slideshow-style-on" className="mb-0">
          Use a slideshow style
        </Label>
      </div>
      {style === null ? (
        <p className="text-xs text-muted-foreground">Off: slideshow slides use the native look.</p>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <Label htmlFor="slideshow-font-family">Slide font family</Label>
              <Input
                id="slideshow-font-family"
                value={style.fontFamily ?? ''}
                onChange={(e) => onChange(withValue(style, 'fontFamily', e.target.value))}
                placeholder="TikTok Sans"
              />
              <p className="mt-1 text-[11px] text-muted-foreground">Empty uses the bundled TikTok Sans.</p>
              {err('fontFamily') && <p className="mt-1 text-xs text-destructive">{err('fontFamily')}</p>}
            </div>
            <div>
              <Label htmlFor="slideshow-font-url">Slide font URL</Label>
              <Input
                id="slideshow-font-url"
                value={style.fontUrl ?? ''}
                onChange={(e) => {
                  setUrlBlurred(false)
                  onChange(withValue(style, 'fontUrl', e.target.value))
                }}
                onBlur={() => setUrlBlurred(true)}
                placeholder="https://fonts.googleapis.com/…"
              />
              {(err('fontUrl') ?? localFontUrlError) && (
                <p className="mt-1 text-xs text-destructive">{err('fontUrl') ?? localFontUrlError}</p>
              )}
            </div>
          </div>

          <div className="grid grid-cols-2 gap-3">
            {SLIDESHOW_COLOUR_KEYS.map((key) => (
              <div key={key} className="space-y-1">
                <Label htmlFor={`slideshow-${key}`} className="text-xs">{COLOUR_LABELS[key]}</Label>
                <div className="flex items-center gap-1.5">
                  <input
                    type="color"
                    aria-label={`${COLOUR_LABELS[key]} colour`}
                    value={pickerValue(style[key] ?? undefined, key)}
                    onChange={(e) => onChange(withValue(style, key, e.target.value))}
                    className="h-7 w-7 shrink-0 cursor-pointer rounded border border-border-strong bg-transparent p-0"
                  />
                  <Input
                    id={`slideshow-${key}`}
                    value={style[key] ?? ''}
                    onChange={(e) => onChange(withValue(style, key, e.target.value))}
                    placeholder="#RRGGBB"
                    className="min-w-[6.5rem] font-mono text-xs tabular-nums"
                  />
                </div>
                {err(key) && <p className="text-xs text-destructive">{err(key)}</p>}
              </div>
            ))}
          </div>

          <div className="grid grid-cols-3 gap-3">
            <div>
              <Label htmlFor="slideshow-default-text">Default text style</Label>
              <Select
                id="slideshow-default-text"
                value={style.defaultText ?? ''}
                onChange={(e) =>
                  onChange(withValue(style, 'defaultText', e.target.value as SlideshowDefaultText | ''))
                }
              >
                <option value="">Not set</option>
                {TEXT_STYLES.map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </Select>
              {err('defaultText') && <p className="mt-1 text-xs text-destructive">{err('defaultText')}</p>}
            </div>
            <div>
              <Label htmlFor="slideshow-text-size">Headline size (px)</Label>
              <Input
                id="slideshow-text-size"
                type="number"
                min={24}
                max={120}
                value={style.textSize ?? ''}
                onChange={(e) => onChange(withValue(style, 'textSize', intOrUndefined(e.target.value)))}
                className="tabular-nums"
              />
              {err('textSize') && <p className="mt-1 text-xs text-destructive">{err('textSize')}</p>}
            </div>
            <div>
              <Label htmlFor="slideshow-sub-size">Sub text size (px)</Label>
              <Input
                id="slideshow-sub-size"
                type="number"
                min={16}
                max={96}
                value={style.subSize ?? ''}
                onChange={(e) => onChange(withValue(style, 'subSize', intOrUndefined(e.target.value)))}
                className="tabular-nums"
              />
              {err('subSize') && <p className="mt-1 text-xs text-destructive">{err('subSize')}</p>}
            </div>
          </div>

          <StylePreview style={style} urlBlurred={urlBlurred} />
        </>
      )}
    </section>
  )
}
