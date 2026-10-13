/**
 * Picks the photo route: the OpenAI Images API when the person has a key, Codex only when the caller
 * says the person agreed to spend their Codex quota. With neither, nothing runs.
 */
import { readConfig } from './config.js'
import { generateImagesWithCodex } from './codex-image.js'
import type { GenerateImagesParams, GenerateImagesResult } from './image-common.js'
import { generateImagesWithOpenAi } from './openai-image.js'

export const DEFAULT_IMAGE_MODEL = 'gpt-image-2.5-sunburst'
export const DEFAULT_IMAGE_QUALITY = 'high'
export const CODEX_MODEL_LABEL = 'gpt-image-2 (Codex image_gen)'

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

export function resolveImageSettings(): { model: string; quality: string } {
  return {
    model: process.env['CONDUCTOR_IMAGE_MODEL']?.trim() || DEFAULT_IMAGE_MODEL,
    quality: process.env['CONDUCTOR_IMAGE_QUALITY']?.trim() || DEFAULT_IMAGE_QUALITY,
  }
}

export interface GenerateImagesRouteResult extends GenerateImagesResult {
  generator: { route: 'openai-api' | 'codex'; model: string }
}

export async function generateImages(params: GenerateImagesParams & { useCodex?: boolean }): Promise<GenerateImagesRouteResult> {
  const { useCodex, ...rest } = params
  if (useCodex === true) {
    const result = await generateImagesWithCodex(rest)
    return { ...result, generator: { route: 'codex', model: CODEX_MODEL_LABEL } }
  }
  const found = resolveOpenAiKey()
  if (!found) throw new CodexConfirmationRequired()
  const { model, quality } = resolveImageSettings()
  const result = await generateImagesWithOpenAi(rest, { apiKey: found.key, model, quality, keySource: found.source })
  return { ...result, generator: { route: 'openai-api', model } }
}
