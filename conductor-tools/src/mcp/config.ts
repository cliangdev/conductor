import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

export interface ProjectEntry {
  localPath: string
  projectName: string
}

export interface Config {
  apiKey: string
  projectId: string
  projectName: string
  email: string
  apiUrl: string
  localPath?: string
  projects?: Record<string, ProjectEntry>
}

export const CONFIG_PATH = path.join(os.homedir(), '.conductor', 'config.json')

function isConfig(value: unknown): value is Config {
  if (typeof value !== 'object' || value === null) return false
  const obj = value as Record<string, unknown>
  return (
    typeof obj['apiKey'] === 'string' &&
    typeof obj['projectId'] === 'string' &&
    typeof obj['projectName'] === 'string' &&
    typeof obj['email'] === 'string' &&
    typeof obj['apiUrl'] === 'string'
  )
}

export function resolveProjectIdByCwd(config: Config, cwd: string = process.cwd()): string {
  if (!config.projects) return config.projectId
  const normalizedCwd = cwd.replace(/\\/g, '/')
  let bestMatch: { projectId: string; pathLen: number } | null = null
  for (const [projectId, proj] of Object.entries(config.projects)) {
    const normalizedPath = proj.localPath.replace(/\\/g, '/')
    if (normalizedCwd === normalizedPath || normalizedCwd.startsWith(normalizedPath + '/')) {
      if (!bestMatch || normalizedPath.length > bestMatch.pathLen) {
        bestMatch = { projectId, pathLen: normalizedPath.length }
      }
    }
  }
  return bestMatch?.projectId ?? config.projectId
}

export function getConfig(): Config {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    if (!isConfig(parsed)) {
      throw new Error('Invalid config: missing required fields')
    }
    // Synthesize projects map from legacy single-project fields for backward compat
    if (!parsed.projects && parsed.projectId && parsed.localPath) {
      parsed.projects = {
        [parsed.projectId]: { localPath: parsed.localPath, projectName: parsed.projectName },
      }
    }
    return parsed
  } catch (err) {
    if (err instanceof Error && err.message.startsWith('Invalid config')) {
      throw err
    }
    throw new Error('Config not found — run conductor login')
  }
}
