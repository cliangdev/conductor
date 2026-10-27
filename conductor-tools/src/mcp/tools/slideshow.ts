import { readFile } from 'node:fs/promises'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { jpegPreview } from '../../lib/image-common.js'
import {
  composeSlideshow,
  SLIDE_RATIOS,
  SlideshowSpecError,
  kitStyleLayer,
  mergeSlideshowStyles,
  validateSlideshowStyle,
  type ResolvedSlideshowStyle,
  type SlideshowStyle,
  type SlideRatio,
  type SlideshowCheck,
} from '../../lib/slideshow/index.js'
import { findProjectRoot, stamp } from './creative-drafts.js'
import type { Config } from '../config.js'
import { getBrandKit } from './creatives.js'

/**
 * compose_slideshow: builds native-looking organic slideshow slides (TikTok / Reels style) on this machine and
 * returns the PNG paths plus layout checks. Nothing is sent to Conductor; the paths go to
 * preview_creative_draft({kind: "CAROUSEL", slidePaths}) next.
 */

export const SLIDESHOWS_SUBDIR = join('.conductor', 'slideshows')

export interface ComposeSlideshowParams {
  spec?: unknown
  specPath?: string
  ratios?: string[]
  draftDir?: string
  /** Style the slides from this Brand Kit's slideshowStyle (omit for the native look). */
  brandKitId?: string
  /** One-off style over the kit's style (or the native look): absent field = inherit, null = reset to native. */
  style?: SlideshowStyle | null
}

export interface ComposeSlideshowOptions {
  projectRoot?: string
  now?: Date
  /** Needed only when brandKitId is passed. */
  config?: Config
  /** Tests: supply the kit lookup. */
  loadKit?: (kitId: string) => Promise<Record<string, unknown>>
  log?: (message: string) => void
}

export type ComposeSlideshowToolResult =
  | {
      ok: true
      outDir: string
      specPath: string
      slidePaths: Partial<Record<SlideRatio, string[]>>
      checks: SlideshowCheck[]
      passed: boolean
      provenance: { recorded: number; needed: number[]; aiGenerated: boolean }
      /** The full style used (also written to spec.json as `resolvedStyle`); pass it as `slideStyle` on preview_creative_draft / create_creative. */
      style: ResolvedSlideshowStyle
      nextStep: string
      image?: { data: Buffer; mimeType: string }
    }
  | { ok: false; error: string; errors?: string[] }

function provenanceNote(p: { needed: number[]; aiGenerated: boolean }): string {
  let note = ''
  if (p.needed.length > 0) {
    note += ` Slides ${p.needed.join(', ')} have no provenance (an input image has no .provenance.json): commit_creative_draft will need source and licence for them.`
  }
  if (p.aiGenerated) note += ' An input image is AI-generated: make sure the AI label is on (aiGenerated: true at commit if a slide has no sidecar).'
  return note
}

export async function composeSlideshowTool(
  params: ComposeSlideshowParams,
  options: ComposeSlideshowOptions = {}
): Promise<ComposeSlideshowToolResult> {
  const hasSpec = params.spec !== undefined && params.spec !== null
  const hasPath = typeof params.specPath === 'string' && params.specPath.trim() !== ''
  if (hasSpec === hasPath) return { ok: false, error: 'Pass exactly one of spec (an object) or specPath (a JSON file).' }

  let ratios: SlideRatio[] | undefined
  if (params.ratios !== undefined) {
    if (!Array.isArray(params.ratios) || params.ratios.length === 0 || params.ratios.some((r) => !SLIDE_RATIOS.includes(r as SlideRatio))) {
      return { ok: false, error: `ratios must be a non-empty array drawn from ${SLIDE_RATIOS.join(', ')}.` }
    }
    ratios = [...new Set(params.ratios)] as SlideRatio[]
  }

  const projectRoot = options.projectRoot ?? findProjectRoot()
  let spec: unknown = params.spec
  let specDir = projectRoot
  if (hasPath) {
    const file = isAbsolute(params.specPath!) ? params.specPath! : resolve(projectRoot, params.specPath!)
    try {
      spec = JSON.parse(await readFile(file, 'utf8'))
    } catch (err) {
      return { ok: false, error: `Could not read specPath ${file}: ${err instanceof Error ? err.message : String(err)}` }
    }
    specDir = dirname(file)
  }
  if (typeof spec !== 'object' || spec === null) return { ok: false, error: 'spec must be an object with { id, slides }.' }

  // Style layers: native look < Brand Kit < the spec's own `style` < inline `style`. Each layer is validated
  // once, here; an absent field inherits, an explicit null resets that field to the native value (a kit's
  // nulls mean "not set"). An earlier output's `resolvedStyle` is never read.
  const specStyle = (spec as { style?: unknown }).style
  const inlineProblems = params.style == null ? [] : validateSlideshowStyle(params.style, 'style')
  if (inlineProblems.length > 0) return { ok: false, error: 'The style is invalid.', errors: inlineProblems }
  const specProblems = specStyle == null ? [] : validateSlideshowStyle(specStyle, 'spec.style')
  if (specProblems.length > 0) return { ok: false, error: 'The style is invalid.', errors: specProblems }
  let kitStyle: SlideshowStyle | undefined
  if (params.brandKitId) {
    const load = options.loadKit ?? (options.config ? (kitId: string) => getBrandKit({ kitId }, options.config!) : undefined)
    if (!load) return { ok: false, error: 'brandKitId needs a connected project; run conductor login or omit brandKitId.' }
    let kit: Record<string, unknown>
    try {
      kit = await load(params.brandKitId)
    } catch (err) {
      return { ok: false, error: `Could not read Brand Kit ${params.brandKitId}: ${err instanceof Error ? err.message : String(err)}` }
    }
    const kitName = String(kit['name'] ?? params.brandKitId)
    const ss = kit['slideshowStyle']
    if (ss === null || ss === undefined || (typeof ss === 'object' && Object.keys(ss as object).length === 0)) {
      return {
        ok: false,
        error: `Brand Kit "${kitName}" has no slideshow style. Compose without brandKitId for the native look, pass an inline style, or set one with update_brand_kit({kitId, slideshowStyle}).`,
      }
    }
    const kitProblems = validateSlideshowStyle(ss, `Brand Kit "${kitName}" slideshowStyle`, true)
    if (kitProblems.length > 0) {
      return {
        ok: false,
        error: `Brand Kit "${kitName}" has an invalid slideshow style. Fix it with update_brand_kit({kitId, slideshowStyle}) or pass an inline style instead.`,
        errors: kitProblems,
      }
    }
    kitStyle = kitStyleLayer(ss)
  }
  const resolvedStyle: ResolvedSlideshowStyle = mergeSlideshowStyles(kitStyle, specStyle as SlideshowStyle | null | undefined, params.style)

  const id = String((spec as { id?: unknown }).id ?? 'slideshow').replace(/[^A-Za-z0-9._-]+/g, '-').slice(0, 40)
  const outDir = params.draftDir
    ? resolve(projectRoot, params.draftDir)
    : join(projectRoot, SLIDESHOWS_SUBDIR, `${stamp(options.now ?? new Date())}-${id}`)

  try {
    const result = await composeSlideshow(spec, outDir, { ratios, specDir, log: options.log, resolvedStyle })
    const first = result.slidePaths['9x16']?.[0] ?? Object.values(result.slidePaths)[0]?.[0]
    const image = first ? await jpegPreview(first, result.outDir) : undefined
    return {
      ok: true,
      outDir: result.outDir,
      specPath: result.specPath,
      slidePaths: result.slidePaths,
      checks: result.checks,
      passed: result.passed,
      provenance: result.provenance,
      style: result.style,
      nextStep: result.passed
        ? 'Call preview_creative_draft({kind: "CAROUSEL", slidePaths, slideStyle: style, caption, slideAltText}) to get the contact sheet and readiness, show it to the person, and call commit_creative_draft only after they approve. Warnings are advisory: fix them if cheap.' +
          provenanceNote(result.provenance)
        : 'Fix the spec for every check with severity "error" and call compose_slideshow again BEFORE showing anything to the person.',
      ...(image ? { image } : {}),
    }
  } catch (err) {
    if (err instanceof SlideshowSpecError) return { ok: false, error: 'The slideshow spec is invalid.', errors: err.errors }
    return { ok: false, error: err instanceof Error ? err.message : String(err) }
  }
}
