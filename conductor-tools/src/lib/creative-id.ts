/**
 * Creatives are shown to people by display id — "12a", "12b" — on the web page, in list_creatives and in
 * the copyable "render this" commands, while the API addresses them by UUID. Tools and the CLI accept
 * either and resolve a display id here, so a model or a person can pass what they were shown.
 */
import type { Config } from '../mcp/config.js'
import { apiGet } from '../mcp/api.js'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const DISPLAY_ID = /^\d+[a-z]$/i

export async function resolveCreativeId(idOrDisplayId: string, config: Config): Promise<string> {
  const value = idOrDisplayId.trim()
  if (UUID.test(value) || !DISPLAY_ID.test(value)) return value
  const creatives = await apiGet<Array<{ id: string; displayId?: string }>>(
    `/api/v2/projects/${config.projectId}/marketing/creatives`,
    config
  )
  const match = creatives.find((c) => (c.displayId ?? '').toLowerCase() === value.toLowerCase())
  if (!match) throw new Error(`No Creative ${value} in this workspace — call list_creatives for the ids that exist.`)
  return match.id
}
