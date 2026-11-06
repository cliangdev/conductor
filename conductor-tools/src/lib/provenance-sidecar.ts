import { readFile, writeFile } from 'node:fs/promises'

/** Provenance recorded on a media file when it is uploaded. */
export interface SidecarProvenance {
  source: string
  licence: string
  aiGenerated: boolean
}

/** The on-disk shape: provenance plus optional generation detail kept for the record. */
export interface SidecarData extends SidecarProvenance {
  prompt?: string
  revisedPrompt?: string
  model?: string
  quality?: string
  route?: string
  tier?: string
  resolution?: string
  generateAudio?: boolean
  estimatedCostUsd?: number
  sourceImagePath?: string
  lastFramePath?: string
  generatedAt?: string
  /** Google embeds SynthID in Veo and Omni output. */
  synthId?: boolean
  durationSeconds?: number
}

export const sidecarPath = (filePath: string): string => `${filePath}.provenance.json`

/** The API's limit on a media item's `source` and `licence` (CreateCreativePhotoRequest maxLength). */
export const PROVENANCE_FIELD_MAX = 200

/**
 * Fits a free-text provenance field into the API's limit, cutting at a word boundary and ending with an
 * ellipsis. Sources and licences are written by people and agents and often quote a full retailer URL; an
 * over-long one used to fail the whole upload with a bare "Validation failed". Applied once, at the upload
 * boundary (see {@link fitProvenanceForUpload}); sidecars on disk keep the full text.
 */
export function clipProvenanceField(value: string): string {
  if (value.length <= PROVENANCE_FIELD_MAX) return value
  const room = PROVENANCE_FIELD_MAX - 1
  const cut = value.lastIndexOf(' ', room)
  return `${value.slice(0, cut > room * 0.6 ? cut : room).trimEnd()}…`
}

/**
 * Clips `source` and `licence` for upload and returns a warning per field that was shortened, naming the
 * file, so a cut attribution is never silent. The full text stays in the local sidecar or the caller's input.
 */
export function fitProvenanceForUpload<T extends { source?: string; licence?: string }>(
  fields: T,
  who: string
): { fields: T; warnings: string[] } {
  const warnings: string[] = []
  const out = { ...fields }
  for (const key of ['source', 'licence'] as const) {
    const value = fields[key]
    if (typeof value === 'string' && value.length > PROVENANCE_FIELD_MAX) {
      out[key] = clipProvenanceField(value) as T[typeof key]
      warnings.push(
        `${who}: ${key} was ${value.length} characters; shortened to ${PROVENANCE_FIELD_MAX} for Conductor. The full text stays in the local sidecar/source.`
      )
    }
  }
  return { fields: out, warnings }
}

/**
 * Reads `<file>.provenance.json`. Returns null when there is none; throws a clear error when it exists
 * but is not valid JSON or has the wrong field types — a bad sidecar must not silently upload as "unknown".
 */
export async function readProvenanceSidecar(filePath: string): Promise<SidecarProvenance | null> {
  const p = sidecarPath(filePath)
  let raw: string
  try {
    raw = await readFile(p, 'utf8')
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') return null
    throw err
  }
  let data: unknown
  try {
    data = JSON.parse(raw)
  } catch {
    throw new Error(`${p} is not valid JSON.`)
  }
  const o = data as Record<string, unknown>
  if (!o || typeof o !== 'object' || Array.isArray(o)) throw new Error(`${p} must be a JSON object.`)
  if (typeof o['source'] !== 'string') throw new Error(`${p}: "source" must be a string.`)
  if (typeof o['licence'] !== 'string') throw new Error(`${p}: "licence" must be a string.`)
  if (typeof o['aiGenerated'] !== 'boolean') throw new Error(`${p}: "aiGenerated" must be a boolean.`)
  return { source: o['source'], licence: o['licence'], aiGenerated: o['aiGenerated'] }
}

export async function writeProvenanceSidecar(filePath: string, data: SidecarData): Promise<void> {
  await writeFile(sidecarPath(filePath), JSON.stringify(data, null, 2) + '\n')
}
