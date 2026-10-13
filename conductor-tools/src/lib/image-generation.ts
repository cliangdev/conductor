/**
 * Picks the photo route: the OpenAI Images API when the person has a key, Codex only when the caller
 * says the person agreed to spend their Codex quota. With neither, nothing runs.
 */
import { readConfig } from './config.js'
import { generateImagesWithCodex } from './codex-image.js'
import { IMAGE_QUALITIES, type GenerateImagesParams, type GenerateImagesResult, type ImageQuality } from './image-common.js'
import { generateImagesWithOpenAi } from './openai-image.js'

export const DEFAULT_IMAGE_MODEL = 'gpt-image-2.5-sunburst'
export const DEFAULT_IMAGE_QUALITY: ImageQuality = 'high'
export const CODEX_MODEL_LABEL = 'gpt-image-2'

export type OpenAiKeySource = 'CONDUCTOR_OPENAI_API_KEY' | 'OPENAI_API_KEY' | 'config'

export class CodexConfirmationRequired extends Error {
  readonly code = 'CODEX_CONFIRMATION_REQUIRED'
  constructor() {
    super('No OpenAI API key is configured; Codex needs the person to confirm first.')
    this.name = 'CodexConfirmationRequired'
  }
}

export function resolveOpenAiKey(): { key: string; source: OpenAiKeySource } | null {
  for (const source of ['CONDUCTOR_OPENAI_API_KEY', 'OPENAI_API_KEY'] as const) {
    const key = process.env[source]?.trim()
    if (key) return { key, source }
  }
  const key = readConfig()?.openaiApiKey?.trim()
  return key ? { key, source: 'config' } : null
}

const isQuality = (v: string | undefined): v is ImageQuality => !!v && (IMAGE_QUALITIES as readonly string[]).includes(v)

/** Explicit value, else `CONDUCTOR_IMAGE_QUALITY` (ignored unless valid), else `high`. An invalid explicit value throws. */
export function resolveQuality(explicit?: string): ImageQuality {
  if (explicit !== undefined) {
    if (!isQuality(explicit)) throw new Error(`quality must be one of ${IMAGE_QUALITIES.join(', ')}.`)
    return explicit
  }
  const env = process.env['CONDUCTOR_IMAGE_QUALITY']?.trim().toLowerCase()
  return isQuality(env) ? env : DEFAULT_IMAGE_QUALITY
}

export function resolveImageSettings(explicitQuality?: string): { model: string; quality: ImageQuality } {
  return {
    model: process.env['CONDUCTOR_IMAGE_MODEL']?.trim() || DEFAULT_IMAGE_MODEL,
    quality: resolveQuality(explicitQuality),
  }
}

export interface GenerateImagesRouteResult extends GenerateImagesResult {
  generator: { route: 'openai-api' | 'codex'; model: string }
}

export async function generateImages(params: GenerateImagesParams & { useCodex?: boolean }): Promise<GenerateImagesRouteResult> {
  const { useCodex, ...input } = params
  const { model, quality } = resolveImageSettings(input.quality)
  const rest = { ...input, quality }
  if (useCodex === true) {
    const result = await generateImagesWithCodex(rest)
    const agent = result.model === 'default' ? 'Codex default' : `Codex ${result.model}`
    return { ...result, generator: { route: 'codex', model: `${CODEX_MODEL_LABEL} via ${agent}` } }
  }
  const found = resolveOpenAiKey()
  if (!found) throw new CodexConfirmationRequired()
  const result = await generateImagesWithOpenAi(rest, { apiKey: found.key, model, quality, keySource: found.source })
  return { ...result, generator: { route: 'openai-api', model } }
}
