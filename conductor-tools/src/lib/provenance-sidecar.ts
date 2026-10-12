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
}

export const sidecarPath = (filePath: string): string => `${filePath}.provenance.json`

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
