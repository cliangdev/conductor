import { readFile } from 'node:fs/promises'
import { Config } from '../config.js'
import { apiPost } from '../api.js'

/**
 * Imports an outside marketing change log (a local JSONL file, one record per line) into the project's
 * MARKETING_CHANGES workflow (`/marketing/changes/import`). The file is read and checked here first: one bad
 * line stops the whole import before anything is sent, so a half-read file never reaches the server. What
 * each record becomes (status, type, verdict) is decided by the backend and is reported back, never guessed
 * on this side.
 */

/** The backend's cap per request; a longer file is sent in consecutive requests of this size. */
export const MAX_RECORDS_PER_REQUEST = 500
/** How many skipped and refused records are listed; the totals are always given. */
export const MAX_LISTED_NOTES = 50
/** How many bad lines are named in the error. */
const MAX_BAD_LINES_LISTED = 10

export interface ImportChangesParams {
  filePath: string
  source?: string
}

interface ImportNote {
  id: string
  reason: string
}

interface ImportItem {
  id: string
  displayId: string
  recordId: string
  status: string
}

interface ImportResponse {
  created: number
  alreadyImported: number
  skipped: ImportNote[]
  refused: ImportNote[]
  items: ImportItem[]
}

type ChangeRecord = Record<string, unknown>

/**
 * Reads one JSON object per non-blank line. Throws one error naming every bad line (up to ten) so the
 * whole file can be fixed in one pass; nothing is returned for a file with any bad line.
 */
export function parseChangeLog(text: string): ChangeRecord[] {
  const records: ChangeRecord[] = []
  const bad: string[] = []
  text.split(/\r?\n/).forEach((line, index) => {
    if (!line.trim()) return
    const lineNumber = index + 1
    let parsed: unknown
    try {
      parsed = JSON.parse(line)
    } catch (err) {
      bad.push(`line ${lineNumber} is not valid JSON (${err instanceof Error ? err.message : String(err)})`)
      return
    }
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      bad.push(`line ${lineNumber} is not a JSON object`)
      return
    }
    records.push(parsed as ChangeRecord)
  })
  if (bad.length > 0) {
    const listed = bad.slice(0, MAX_BAD_LINES_LISTED).join('; ')
    const more = bad.length > MAX_BAD_LINES_LISTED ? `; and ${bad.length - MAX_BAD_LINES_LISTED} more` : ''
    throw new Error(`The file has ${bad.length} bad line${bad.length === 1 ? '' : 's'}: ${listed}${more}. Nothing was sent.`)
  }
  return records
}

function capped<T>(list: T[]): T[] {
  return list.slice(0, MAX_LISTED_NOTES)
}

export async function importMarketingChanges(config: Config, params: ImportChangesParams): Promise<Record<string, unknown>> {
  if (!params.filePath?.trim()) throw new Error('filePath is required: the JSONL change log on this machine')
  let text: string
  try {
    text = await readFile(params.filePath, 'utf8')
  } catch (err) {
    throw new Error(`Cannot read "${params.filePath}": ${err instanceof Error ? err.message : String(err)}`)
  }
  const records = parseChangeLog(text)
  if (records.length === 0) throw new Error(`"${params.filePath}" has no records: expected one JSON object per line`)

  const source = params.source?.trim()
  const endpoint = `/api/v2/projects/${config.projectId}/marketing/changes/import`
  const total: ImportResponse = { created: 0, alreadyImported: 0, skipped: [], refused: [], items: [] }
  for (let start = 0; start < records.length; start += MAX_RECORDS_PER_REQUEST) {
    const body: Record<string, unknown> = { records: records.slice(start, start + MAX_RECORDS_PER_REQUEST) }
    if (source) body['source'] = source
    let part: ImportResponse
    try {
      part = await apiPost<ImportResponse>(endpoint, body, config)
    } catch (err) {
      const reason = err instanceof Error ? err.message : String(err)
      if (total.created === 0 && total.alreadyImported === 0) throw err
      throw new Error(
        `${reason} (${total.created} change${total.created === 1 ? ' was' : 's were'} already created by earlier requests; ` +
          'importing the file again is safe, it skips every record already imported)'
      )
    }
    total.created += part.created
    total.alreadyImported += part.alreadyImported
    total.skipped.push(...part.skipped)
    total.refused.push(...part.refused)
    total.items.push(...part.items)
  }

  const byStatus: Record<string, number> = {}
  for (const item of total.items) byStatus[item.status] = (byStatus[item.status] ?? 0) + 1
  const summary: Record<string, unknown> = {
    records: records.length,
    created: total.created,
    alreadyImported: total.alreadyImported,
    skipped: capped(total.skipped),
    refused: capped(total.refused),
  }
  if (total.skipped.length > MAX_LISTED_NOTES) summary['skippedTotal'] = total.skipped.length
  if (total.refused.length > MAX_LISTED_NOTES) summary['refusedTotal'] = total.refused.length
  if (total.created > 0) {
    summary['createdByStatus'] = byStatus
    summary['displayIds'] = total.items.map((i) => i.displayId)
  }
  return summary
}
