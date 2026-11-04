// Editable slide text on a slideshow (CAROUSEL) Creative: wire shapes and calls for the edit, its
// revisions, the Posts it reaches and where it can render; plus the pure helpers the editor shares
// (field labels, the preview's slide HTML, the check rule names). The page builder itself is the
// composer's own, generated into @cliangdev/creative-render/slideshow (see
// conductor-tools/scripts/sync-slideshow-core.mjs), so the preview builds the HTML the render job builds.

import { apiGet, apiPost } from '@/lib/api'
import { buildSlideHtml } from '@cliangdev/creative-render/slideshow/html.js'
import type { SlideRatio, SlideshowSpec } from '@cliangdev/creative-render/slideshow/spec.js'
import { resolveStyleLayers, type SlideshowStyle } from '@cliangdev/creative-render/slideshow/style.js'
import { applyTextEdits, type TextEdit } from '@cliangdev/creative-render/slideshow/text-fields.js'
import type { Creative, CreativeLatestRevision } from './types'

const base = (projectId: string, creativeId: string) => `/api/v2/projects/${projectId}/marketing/creatives/${creativeId}`

// ── Wire shapes ───────────────────────────────────────────────────────────────────────────────────

export interface TextChange {
  /** 1-based slide number. */
  slide: number
  /** A path within the slide, e.g. `texts[0].t`. */
  path: string
  old?: string | null
  new?: string | null
}

export type RevisionStatus = 'PENDING' | 'APPLIED' | 'FAILED'
export type RevisionSource = 'EDITOR' | 'REVIEWER' | 'MCP' | 'IMPORT' | 'COMMIT'

export interface SlideshowCheck {
  slide: number
  ratio: string
  rule: string
  severity: 'error' | 'warning'
  message: string
}

export interface CreativeRevision {
  id: string
  revisionNo: number
  creativeVersion: number
  status: RevisionStatus
  source: RevisionSource
  textDiff: TextChange[]
  checks?: SlideshowCheck[] | null
  authorUserId?: string | null
  /** Added to list items by the server (batched user lookup). */
  authorName?: string | null
  postId?: string | null
  reviewRound?: number | null
  error?: string | null
  createdAt: string
  appliedAt?: string | null
}

export interface CreativeRevisionDetail extends CreativeRevision {
  changedSlides: number[]
  /** 1-based slides whose alt text quoted replaced text; null until applied. */
  altTextFlags?: number[] | null
}

export interface SlideTextEditResponse {
  revision: CreativeRevision
  renderRun?: { runId: string; status: string } | null
}

export interface RenderAvailability {
  runtime: 'target' | 'self-hosted'
  targetName?: string | null
  /** Self-hosted only: whether a daemon has checked in recently; null when unknown. */
  daemonOnline?: boolean | null
}

export type AffectedOutcome = 'back-to-review' | 'new-round' | 'updated' | 'unchanged' | 'reviewer-fix'

export interface AffectedPost {
  workItemId: string
  displayId: string
  title: string
  status: string
  statusLabel: string
  outcome: AffectedOutcome
}

// ── Calls ─────────────────────────────────────────────────────────────────────────────────────────

export function createSlideTextEdit(
  projectId: string,
  creativeId: string,
  body: { version: number; edits: TextEdit[]; postId?: string },
  token: string,
): Promise<SlideTextEditResponse> {
  return apiPost<SlideTextEditResponse>(`${base(projectId, creativeId)}/slide-text-edits`, body, token)
}

export async function listCreativeRevisions(projectId: string, creativeId: string, token: string): Promise<CreativeRevision[]> {
  const res = await apiGet<{ revisions: CreativeRevision[] }>(`${base(projectId, creativeId)}/revisions`, token)
  return res.revisions ?? []
}

export function getCreativeRevision(projectId: string, creativeId: string, revisionId: string, token: string): Promise<CreativeRevisionDetail> {
  return apiGet<CreativeRevisionDetail>(`${base(projectId, creativeId)}/revisions/${revisionId}`, token)
}

export interface AffectedPosts {
  posts: AffectedPost[]
  /** Why this caller may not save (a reviewer of the Post who is not an editor, while other Posts using the
   *  Creative are approved or scheduled); null when they may. The save answers 409 with the same message. */
  blockedReason: string | null
}

export async function getAffectedPosts(projectId: string, creativeId: string, token: string, postId?: string): Promise<AffectedPosts> {
  const q = postId ? `?postId=${encodeURIComponent(postId)}` : ''
  const res = await apiGet<{ posts?: AffectedPost[]; blockedReason?: string | null }>(
    `${base(projectId, creativeId)}/affected-posts${q}`,
    token,
  )
  return { posts: res.posts ?? [], blockedReason: res.blockedReason ?? null }
}

/** The editor cannot re-render 1x1 slides yet (the server refuses with 422 `slideTextEditUnsupportedRatio`). */
export const SQUARE_SLIDES_NOTE = "Text editing isn't available for Creatives with 1x1 slides yet."

export function hasSquareSlides(slides: Record<string, string[]> | null | undefined): boolean {
  return (slides?.['1x1'] ?? []).length > 0
}

export function getRenderAvailability(projectId: string, creativeId: string, token: string): Promise<RenderAvailability> {
  return apiGet<RenderAvailability>(`${base(projectId, creativeId)}/render-availability`, token)
}

// ── Field labels ──────────────────────────────────────────────────────────────────────────────────

const n = (i: string) => String(Number(i) + 1)

/** Plain names for a slide's editable fields: "Text 1", "Callout", "Note item 3", "Bubble 2". */
export function fieldLabel(path: string): string {
  const m = (re: RegExp) => re.exec(path)
  let r: RegExpExecArray | null
  if ((r = m(/^texts\[(\d+)\]\.t$/))) return `Text ${n(r[1]!)}`
  if ((r = m(/^callouts\[(\d+)\]\.label$/))) return r[1] === '0' ? 'Callout' : `Callout ${n(r[1]!)}`
  if (path === 'title') return 'Title'
  if (path === 'date') return 'Date'
  if ((r = m(/^items\[(\d+)\](\.t)?$/))) return `Note item ${n(r[1]!)}`
  if ((r = m(/^paras\[(\d+)\]$/))) return `Paragraph ${n(r[1]!)}`
  if ((r = m(/^photos\[(\d+)\]\.badge$/))) return `Photo ${n(r[1]!)} badge`
  if ((r = m(/^photos\[(\d+)\]\.label$/))) return `Photo ${n(r[1]!)} label`
  if ((r = m(/^rows\[(\d+)\]\.k$/))) return `Tier ${n(r[1]!)} label`
  if ((r = m(/^rows\[(\d+)\]\.items\[(\d+)\]$/))) return `Tier ${n(r[1]!)} item ${n(r[2]!)}`
  if ((r = m(/^days\[(\d+)\]\.d$/))) return `Day ${n(r[1]!)} name`
  if ((r = m(/^days\[(\d+)\]\.t$/))) return `Day ${n(r[1]!)} text`
  if ((r = m(/^days\[(\d+)\]\.icon$/))) return `Day ${n(r[1]!)} icon`
  if (path === 'head') return 'Heading'
  if ((r = m(/^sections\[(\d+)\]\.h$/))) return `Section ${n(r[1]!)} heading`
  if ((r = m(/^sections\[(\d+)\]\.items\[(\d+)\]$/))) return `Section ${n(r[1]!)} item ${n(r[2]!)}`
  if (path === 'count') return 'Item count'
  if (path === 'ex') return 'Line above title'
  if ((r = m(/^lines\[(\d+)\]\.k$/))) return `Line ${n(r[1]!)} label`
  if ((r = m(/^lines\[(\d+)\]\.v$/))) return `Line ${n(r[1]!)}`
  if ((r = m(/^bubbles\[(\d+)\]\.t$/))) return `Bubble ${n(r[1]!)}`
  return path
}

/** "Slide 3 · Text 1". */
export const changeLabel = (c: Pick<TextChange, 'slide' | 'path'>): string => `Slide ${c.slide} · ${fieldLabel(c.path)}`

// ── Checks ────────────────────────────────────────────────────────────────────────────────────────

const RULE_LABEL: Record<string, string> = {
  safeZone: 'Safe zone',
  spill: 'Spills off the slide',
  wordsPerLine: 'Words per line',
  textSize: 'Text size',
  font: 'Font',
  image: 'Image',
  contrast: 'Contrast',
}
export const ruleLabel = (rule: string): string => RULE_LABEL[rule] ?? rule

/** Narrows loosely typed checks (the API sends them as open objects) to well-formed ones. */
export function asChecks(raw: unknown): SlideshowCheck[] {
  if (!Array.isArray(raw)) return []
  return raw.filter(
    (c): c is SlideshowCheck =>
      !!c && typeof c === 'object' && typeof (c as SlideshowCheck).rule === 'string' && typeof (c as SlideshowCheck).message === 'string',
  ).map((c) => ({ ...c, severity: c.severity === 'error' ? 'error' : 'warning' }))
}

// ── Preview HTML ──────────────────────────────────────────────────────────────────────────────────

/** Where the preview iframe loads the bundled fonts from (copied to public/ by scripts/copy-slideshow-fonts.mjs). */
export const FONT_BASE = '/slideshow-fonts/'

const MEDIA_REF = /^media:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i
export const mediaIdOf = (ref: string): string | null => MEDIA_REF.exec(ref)?.[1] ?? null

/** Every `media:<id>` the spec names, as media ids. */
export function specMediaIds(spec: SlideshowSpec): string[] {
  const ids = new Set<string>()
  for (const sl of spec.slides) {
    for (const v of [sl.bg, sl.shot, ...(sl.photos ?? []).map((p) => p.src), ...(sl.days ?? []).map((d) => d.img)]) {
      const id = v ? mediaIdOf(v) : null
      if (id) ids.add(id)
    }
  }
  return [...ids]
}

/** A copy of the spec with each `media:<id>` image swapped for the media's display URL (unknown ids are left as they are). */
export function withDisplayUrls(spec: SlideshowSpec, urlFor: (mediaId: string) => string | null | undefined): SlideshowSpec {
  const copy = JSON.parse(JSON.stringify(spec)) as SlideshowSpec
  const swap = (v: string | undefined): string | undefined => {
    if (!v) return v
    const id = mediaIdOf(v)
    return id ? (urlFor(id) ?? v) : v
  }
  for (const sl of copy.slides) {
    if (sl.bg) sl.bg = swap(sl.bg)
    if (sl.shot) sl.shot = swap(sl.shot)
    sl.photos?.forEach((p) => { p.src = swap(p.src)! })
    sl.days?.forEach((d) => { if (d.img) d.img = swap(d.img) })
  }
  return copy
}

/**
 * The slide's page for the preview: the spec with the edits applied and the media swapped for display URLs,
 * built by the composer's own builder with the Creative's recorded style (as the render job does).
 */
export function buildPreviewHtml(
  creative: Pick<Creative, 'slideshowSpec' | 'slideStyle'>,
  edits: readonly TextEdit[],
  slideIndex: number,
  ratio: SlideRatio,
  urlFor: (mediaId: string) => string | null | undefined,
): string | null {
  const spec = creative.slideshowSpec
  if (!spec || !spec.slides[slideIndex]) return null
  let edited = spec
  try {
    edited = applyTextEdits(spec, edits)
  } catch {
    edited = spec
  }
  const inline = creative.slideStyle && Object.keys(creative.slideStyle).length > 0 ? creative.slideStyle : null
  let style
  try {
    style = resolveStyleLayers({ spec: spec.style, inline: inline as SlideshowStyle | null })
  } catch {
    style = undefined
  }
  return buildSlideHtml(withDisplayUrls(edited, urlFor), slideIndex, ratio, FONT_BASE, style)
}

/** The ratios the Creative was rendered at (its `slides` lists); both when it names neither. */
export function creativeRatios(slides?: Record<string, string[]> | null): SlideRatio[] {
  const found = (['9x16', '4x5'] as const).filter((r) => (slides?.[r]?.length ?? 0) > 0)
  return found.length > 0 ? found : ['9x16', '4x5']
}

// ── Revision state ────────────────────────────────────────────────────────────────────────────────

export function failedChecksOf(latest: CreativeLatestRevision | null | undefined): SlideshowCheck[] {
  return latest?.status === 'FAILED' ? asChecks(latest.checks) : []
}

/** Slides (1-based) whose alt text still quotes text a revision replaced. */
export function staleAltSlides(
  flags: readonly number[] | null | undefined,
  diff: readonly TextChange[],
  slideAltText: readonly string[],
): number[] {
  return (flags ?? []).filter((slide) => {
    const alt = (slideAltText[slide - 1] ?? '').toLowerCase()
    if (!alt) return false
    const olds = diff.filter((d) => d.slide === slide && d.old && d.old.trim()).map((d) => d.old!.trim().toLowerCase())
    // When the diff is unavailable, trust the flag; otherwise it clears as soon as the alt text stops quoting the old text.
    return olds.length === 0 || olds.some((o) => alt.includes(o))
  })
}
