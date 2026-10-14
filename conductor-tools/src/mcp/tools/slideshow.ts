import { readFile } from 'node:fs/promises'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { jpegPreview } from '../../lib/image-common.js'
import {
  composeSlideshow,
  SLIDE_RATIOS,
  SlideshowSpecError,
  type SlideRatio,
  type SlideshowCheck,
} from '../../lib/slideshow/index.js'
import { findProjectRoot, stamp } from './creative-drafts.js'

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
}

export interface ComposeSlideshowOptions {
  projectRoot?: string
  now?: Date
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
      nextStep: string
      image?: { data: Buffer; mimeType: string }
    }
  | { ok: false; error: string; errors?: string[] }

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

  const id = String((spec as { id?: unknown }).id ?? 'slideshow').replace(/[^A-Za-z0-9._-]+/g, '-').slice(0, 40)
  const outDir = params.draftDir
    ? resolve(projectRoot, params.draftDir)
    : join(projectRoot, SLIDESHOWS_SUBDIR, `${stamp(options.now ?? new Date())}-${id}`)

  try {
    const result = await composeSlideshow(spec, outDir, { ratios, specDir, log: options.log })
    const first = result.slidePaths['9x16']?.[0] ?? Object.values(result.slidePaths)[0]?.[0]
    const image = first ? await jpegPreview(first, result.outDir) : undefined
    return {
      ok: true,
      outDir: result.outDir,
      specPath: result.specPath,
      slidePaths: result.slidePaths,
      checks: result.checks,
      passed: result.passed,
      nextStep: result.passed
        ? 'Call preview_creative_draft({kind: "CAROUSEL", slidePaths, caption, slideAltText}) to get the contact sheet and readiness, show it to the person, and call commit_creative_draft only after they approve. Warnings are advisory: fix them if cheap.'
        : 'Fix the spec for every check with severity "error" and call compose_slideshow again BEFORE showing anything to the person.',
      ...(image ? { image } : {}),
    }
  } catch (err) {
    if (err instanceof SlideshowSpecError) return { ok: false, error: 'The slideshow spec is invalid.', errors: err.errors }
    return { ok: false, error: err instanceof Error ? err.message : String(err) }
  }
}
