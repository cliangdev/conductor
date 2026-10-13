/**
 * Photo generation through the OpenAI Images API. The key comes from the person (env or
 * `conductor config set-openai-api-key`) and is billed to them directly; nothing goes through Conductor.
 * Text-only scenes use /images/generations; scenes with reference photos use /images/edits.
 * Each image gets a `<file>.provenance.json` sidecar, same as the Codex route.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { basename, extname, join, resolve } from 'node:path'
import { ASPECT_SIZES, jpegPreview, sceneRules, validImage, type GeneratedImage, type GenerateImagesParams, type GenerateImagesResult } from './image-common.js'
import { writeProvenanceSidecar } from './provenance-sidecar.js'

const API_BASE = 'https://api.openai.com/v1'

export interface OpenAiImageConfig {
  apiKey: string
  model: string
  quality: string
  /** Where the key came from, named in the "rejected" error. */
  keySource?: string
}

const MIME: Record<string, string> = {
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
}

interface ApiError {
  message?: string
  code?: string
  type?: string
}

async function readApiError(res: Response): Promise<ApiError> {
  try {
    const body = (await res.json()) as { error?: ApiError }
    return body.error ?? {}
  } catch {
    return {}
  }
}

function failure(res: Response, err: ApiError, keySource: string | undefined): Error {
  const message = err.message ?? res.statusText ?? 'unknown error'
  if (res.status === 401) {
    return new Error(
      `OpenAI API key was rejected${keySource ? ` (from ${keySource})` : ''} — check it, or set a new one with \`conductor config set-openai-api-key <key>\`.`
    )
  }
  if (res.status === 429 || err.code === 'insufficient_quota' || err.code === 'billing_hard_limit_reached') {
    return new Error(`OpenAI refused the request: out of quota, over a billing limit or rate limited (${res.status}). OpenAI says: ${message}`)
  }
  return new Error(`OpenAI image generation failed (${res.status}): ${message}`)
}

export async function generateImagesWithOpenAi(params: GenerateImagesParams, cfg: OpenAiImageConfig): Promise<GenerateImagesResult> {
  const { outDir, onProgress } = params
  const aspect = params.aspect ?? 'portrait'
  const count = params.count ?? 1
  const refs = params.referenceImagePaths ?? []
  const timeoutMs = params.timeoutMs ?? 300_000
  const prompt = sceneRules(params.prompt, aspect, refs.length > 0)
  const size = ASPECT_SIZES[aspect]

  await mkdir(outDir, { recursive: true })
  const outRoot = resolve(outDir)

  let url: string
  let init: RequestInit
  if (refs.length === 0) {
    url = `${API_BASE}/images/generations`
    init = {
      method: 'POST',
      headers: { Authorization: `Bearer ${cfg.apiKey}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ model: cfg.model, prompt, size, quality: cfg.quality, n: count }),
    }
  } else {
    url = `${API_BASE}/images/edits`
    const form = new FormData()
    form.append('model', cfg.model)
    form.append('prompt', prompt)
    form.append('size', size)
    form.append('quality', cfg.quality)
    form.append('n', String(count))
    for (const ref of refs) {
      const type = MIME[extname(ref).toLowerCase()] ?? 'image/png'
      form.append('image[]', new Blob([new Uint8Array(await readFile(ref))], { type }), basename(ref))
    }
    init = { method: 'POST', headers: { Authorization: `Bearer ${cfg.apiKey}` }, body: form }
  }

  onProgress?.(`Calling OpenAI ${cfg.model} (${cfg.quality})`)
  let res: Response
  try {
    res = await fetch(url, { ...init, signal: AbortSignal.timeout(timeoutMs) })
  } catch (err) {
    const name = (err as Error).name
    if (name === 'TimeoutError' || name === 'AbortError') {
      throw new Error(`OpenAI image generation timed out after ${Math.round(timeoutMs / 1000)} s`)
    }
    throw new Error(`OpenAI image generation failed: ${(err as Error).message}`)
  }
  if (!res.ok) throw failure(res, await readApiError(res), cfg.keySource)

  const body = (await res.json()) as { data?: Array<{ b64_json?: string; revised_prompt?: string }> }
  const data = body.data ?? []
  const found: GeneratedImage[] = []
  for (const item of data) {
    if (found.length >= count || typeof item.b64_json !== 'string') continue
    const dest = join(outRoot, `image-${found.length + 1}.png`)
    await writeFile(dest, Buffer.from(item.b64_json, 'base64'))
    const img = await validImage(dest)
    if (img) found.push(img)
  }
  if (found.length === 0) throw new Error('OpenAI returned no usable image.')

  const revisedPrompt = data[0]?.revised_prompt ?? ''
  const date = new Date().toISOString().slice(0, 10)
  const provenance = { source: `Generated with OpenAI ${cfg.model} (API) on ${date}`, licence: 'Generated, house use', aiGenerated: true as const }
  for (const img of found) {
    await writeProvenanceSidecar(img.path, { ...provenance, prompt: params.prompt, revisedPrompt })
  }

  const preview = await jpegPreview(found[0]!.path, outRoot)
  return { files: found, provenance, revisedPrompt, outDir: outRoot, ...(preview ? { preview } : {}) }
}
