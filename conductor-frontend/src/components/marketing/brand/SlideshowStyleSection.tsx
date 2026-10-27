'use client'

// Brand Kit › Slideshow style. Optional: off means organic slideshow slides use the native look
// (bundled font, built-in colours). On, the kit carries a SlideshowStyle that the slideshow tools read.
// Nothing here is a brand constant — the starting values are neutral and every one is editable.

import { useEffect } from 'react'

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
 * The exact native look (mirrors NATIVE_STYLE in conductor-tools/src/lib/slideshow/style.ts), so turning the
 * section on changes nothing until the person edits. No font family: unset means the bundled TikTok Sans.
 */
export const NATIVE_SLIDESHOW_STYLE: SlideshowStyle = {
  boxBg: '#ffffff',
  boxInk: '#111111',
  darkBg: '#111111',
  darkInk: '#ffffff',
  outline: '#000000',
  accent: '#ff2d55',
  defaultText: 'box',
  textSize: 60,
  subSize: 44,
}

const COLOUR_LABELS: Record<SlideshowColourKey, string> = {
  boxBg: 'Box background',
  boxInk: 'Box text',
  darkBg: 'Dark background',
  darkInk: 'Dark text',
  outline: 'Outline',
  accent: 'Accent',
}

const TEXT_STYLES: { value: SlideshowDefaultText; label: string }[] = [
  { value: 'box', label: 'Box (text on a card)' },
  { value: 'dark', label: 'Dark (text on a dark wash)' },
  { value: 'stroke', label: 'Stroke (outlined text)' },
]

const HEX = /^#[0-9a-fA-F]{6}$/

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

const HTTP_URL = /^https?:\/\/[^\s"'<>\\]+$/

function rgba(hex: string, alpha: number): string {
  const n = parseInt(hex.slice(1), 16)
  return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${alpha})`
}

/** Colour if it is a full hex, else the native fallback — half-typed values never reach CSS. */
const colour = (value: string | undefined, fallback: string) => (value && HEX.test(value) ? value : fallback)

function StylePreview({ style }: { style: SlideshowStyle }) {
  const fontUrl = style.fontUrl?.trim()
  const linkable = fontUrl && HTTP_URL.test(fontUrl) ? fontUrl : null

  // Load the font the renderer will load; remove the link on change or unmount.
  useEffect(() => {
    if (!linkable) return
    const link = document.createElement('link')
    link.rel = 'stylesheet'
    link.href = linkable
    document.head.appendChild(link)
    return () => {
      link.remove()
    }
  }, [linkable])

  const n = NATIVE_SLIDESHOW_STYLE as Required<Omit<SlideshowStyle, 'fontFamily' | 'fontUrl'>>
  const boxBg = colour(style.boxBg, n.boxBg)
  const boxInk = colour(style.boxInk, n.boxInk)
  const darkBg = colour(style.darkBg, n.darkBg)
  const darkInk = colour(style.darkInk, n.darkInk)
  const outline = colour(style.outline, n.outline)
  const accent = colour(style.accent, n.accent)
  const family = style.fontFamily?.trim()
  const sample = 'Your headline here'
  const shadow = `0 0 3px ${outline}, 0 0 3px ${outline}, 0 0 4px ${outline}, 0 2px 6px ${rgba(outline, 0.8)}`
  return (
    <div
      data-testid="slideshow-style-preview"
      className="flex flex-wrap items-center gap-3 rounded-md p-3"
      style={{ background: darkBg, fontFamily: family ? `${family}, sans-serif` : undefined }}
    >
      <span className="rounded px-2.5 py-1 text-sm font-bold" style={{ background: boxBg, color: boxInk }}>
        {sample}
      </span>
      <span className="text-sm font-extrabold" style={{ color: darkInk, textShadow: shadow }}>
        {sample}
      </span>
      <span className="text-xs font-bold" style={{ color: darkInk, textShadow: shadow }}>
        Sub text
      </span>
      <span className="flex items-center gap-1.5" data-testid="slideshow-style-callout">
        <span
          className="h-3 w-3 rounded-full border-2 border-solid"
          style={{ background: accent, borderColor: '#ffffff' }}
          aria-hidden
        />
        <span className="h-px w-3" style={{ background: accent }} aria-hidden />
        <span className="rounded px-2 py-0.5 text-xs font-bold" style={{ background: boxBg, color: boxInk }}>
          Callout
        </span>
      </span>
    </div>
  )
}

export interface SlideshowStyleSectionProps {
  value: SlideshowStyle | null
  onChange: (next: SlideshowStyle | null) => void
  errorFor?: (field: string) => string | undefined
}

export function SlideshowStyleSection({ value, onChange, errorFor }: SlideshowStyleSectionProps) {
  const style = value
  const err = (field: string) => errorFor?.(`slideshowStyle.${field}`)

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
                onChange={(e) => onChange(withValue(style, 'fontUrl', e.target.value))}
                placeholder="https://fonts.googleapis.com/…"
              />
              {err('fontUrl') && <p className="mt-1 text-xs text-destructive">{err('fontUrl')}</p>}
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
                    value={HEX.test(style[key] ?? '') ? (style[key] as string) : '#888888'}
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

          <StylePreview style={style} />
        </>
      )}
    </section>
  )
}
