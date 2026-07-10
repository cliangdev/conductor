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
  frontendUrl?: string
  localPath?: string
  maxConcurrentRuns?: number
  projects?: Record<string, ProjectEntry>
  /** `claude setup-token` output — enables the `claude-code` workflow step to run
   * under the owner's Pro/Max subscription instead of a metered API key. Never
   * transits the server; lives only in this local config file. */
  claudeCodeOauthToken?: string
}

export const CONFIG_PATH = path.join(os.homedir(), '.conductor', 'config.json')

export function readConfig(): Config | null {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    if (!isConfig(parsed)) return null
    // Synthesize projects map from legacy single-project fields for backward compat.
    // Treat an empty map the same as a missing one — otherwise file→project
    // resolution falls back to the mutable active projectId and mis-stamps writes.
    if (
      (!parsed.projects || Object.keys(parsed.projects).length === 0) &&
      parsed.projectId &&
      parsed.localPath
    ) {
      parsed.projects = {
        [parsed.projectId]: { localPath: parsed.localPath, projectName: parsed.projectName },
      }
    }
    return parsed
  } catch {
    return null
  }
}

export function writeConfig(config: Config): void {
  const dir = path.dirname(CONFIG_PATH)
  fs.mkdirSync(dir, { recursive: true })
  // mode is only applied by the OS when the file is newly created, so an
  // existing config.json (e.g. from before this field existed) needs an
  // explicit chmod too — the file holds an OAuth token and API key.
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2), { encoding: 'utf8', mode: 0o600 })
  fs.chmodSync(CONFIG_PATH, 0o600)
}

export function clearConfig(): void {
  try {
    fs.unlinkSync(CONFIG_PATH)
  } catch {
    // File may not exist; that's fine
  }
}

export function loadConfigOrExit(): Config {
  const config = readConfig()
  if (!config) {
    console.error('No config found — run conductor login first')
    process.exit(78)
  }
  return config
}

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
