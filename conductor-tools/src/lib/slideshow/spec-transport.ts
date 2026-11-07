/**
 * Moving a slideshow spec from this machine to a Creative. A composed slideshow's spec names its images as local
 * files; the Creative stores the spec with each image as `media:<uuid>` (a media library item). These helpers
 * validate and normalise the spec before upload and swap the references; the uploading itself lives with the MCP
 * tools (mcp/tools/slideshow-spec.ts).
 */

import { isAbsolute, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { mapSpecImages } from './compose.js'
import { SLIDESHOW_LAYOUT, validateSlideshowSpec, type SlideshowSpec } from './spec.js'
import { listTextFields, type TextField } from './text-fields.js'

export const MEDIA_REF = /^media:([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i
export const mediaRef = (id: string): string => `media:${id}`
export const isMediaRef = (value: string): boolean => MEDIA_REF.test(value)

export interface PreparedSpec {
  /** The spec with `resolvedStyle` removed and every local image an absolute path (or an existing `media:` ref). */
  spec: SlideshowSpec
  /** compose_slideshow's `resolvedStyle` when the file was an output spec.json; the Creative's `slideStyle`. */
  resolvedStyle?: Record<string, unknown>
}

export type PrepareResult = ({ ok: true } & PreparedSpec) | { ok: false; errors: string[] }

/**
 * Validates a spec read from a JSON file and resolves its image paths against `specDir`. Accepts a source spec
 * (relative image paths) and a compose_slideshow output spec.json (absolute paths plus `resolvedStyle`). Refuses
 * web URLs and inline data: images, which cannot be uploaded as a file. Never throws.
 */
export function prepareSpecForCreative(raw: unknown, specDir: string): PrepareResult {
  if (typeof raw !== 'object' || raw === null || Array.isArray(raw)) return { ok: false, errors: ['spec must be an object with { id, slides }'] }
  const { resolvedStyle, ...rest } = raw as Record<string, unknown>
  const errors = validateSlideshowSpec(rest)
  if (errors.length > 0) return { ok: false, errors }
  const problems: string[] = []
  const spec = mapSpecImages(rest as unknown as SlideshowSpec, (value, where) => {
    if (!value || isMediaRef(value)) return value
    if (/^https?:\/\//i.test(value)) {
      problems.push(`${where}: "${value}" is a web URL; download the image and use its path`)
      return value
    }
    if (value.startsWith('data:')) {
      problems.push(`${where}: an inline data: image cannot be stored with the Creative; save it to a file and use its path`)
      return value
    }
    if (value.startsWith('file:')) return fileURLToPath(value)
    return isAbsolute(value) ? value : resolve(specDir, value)
  })
  if (problems.length > 0) return { ok: false, errors: problems }
  const style = typeof resolvedStyle === 'object' && resolvedStyle !== null && !Array.isArray(resolvedStyle) ? (resolvedStyle as Record<string, unknown>) : undefined
  // Stored with the layout generation this CLI composes, so a later text edit re-renders it the same way.
  const stamped = spec.layout === undefined ? { ...spec, layout: SLIDESHOW_LAYOUT } : spec
  return { ok: true, spec: stamped, ...(style ? { resolvedStyle: style } : {}) }
}

/** Every distinct image a spec names, in first-use order (media refs included). */
export function specImages(spec: SlideshowSpec): string[] {
  const seen = new Set<string>()
  mapSpecImages(spec, (value) => {
    if (value) seen.add(value)
    return value
  })
  return [...seen]
}

/** Just the images that are files on this machine (not already `media:` refs). */
export const specLocalImages = (spec: SlideshowSpec): string[] => specImages(spec).filter((v) => !isMediaRef(v))

/** A spec above this many JSON characters is summarised in get_creative instead of listed (the text fields carry what an agent edits). */
export const SPEC_INLINE_LIMIT = 8000

/**
 * What get_creative shows for a stored spec: the spec itself while it is small, otherwise a one-line summary, plus a
 * compact list of its editable text fields. Never both in full, so a long slideshow does not spend the context twice.
 */
export function slideshowSpecView(
  spec: unknown,
  slides?: Record<string, unknown> | null
): { slideshowSpec: unknown; textFields: TextField[]; textEditable?: false; textEditNote?: string } | undefined {
  if (typeof spec !== 'object' || spec === null || Array.isArray(spec)) return undefined
  const textFields = listTextFields(spec)
  const size = JSON.stringify(spec).length
  const s = spec as { id?: unknown; slides?: unknown }
  const slideshowSpec =
    size <= SPEC_INLINE_LIMIT
      ? spec
      : { id: s.id, slides: Array.isArray(s.slides) ? s.slides.length : 0, omitted: `The spec is ${size} characters, so it is not repeated here; textFields lists every editable text (slide numbers start at 1, path is the field in that slide).` }
  return { slideshowSpec, textFields, ...(hasOneByOneList(slides) ? { textEditable: false as const, textEditNote: TEXT_EDIT_UNSUPPORTED_1X1 } : {}) }
}

/** The backend's refusal (422 slideTextEditUnsupportedRatio) for a Creative with 1x1 slides; the same words. */
export const TEXT_EDIT_UNSUPPORTED_1X1 =
  "Text editing isn't available for Creatives with 1x1 slides yet. Re-compose it locally with compose_slideshow."

/** Whether the Creative's `slides` map has a non-empty 1x1 list (a `default` list of square images is only caught by the backend). */
function hasOneByOneList(slides: Record<string, unknown> | null | undefined): boolean {
  const list = slides?.['1x1']
  return Array.isArray(list) && list.length > 0
}
