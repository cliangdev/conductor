import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

export interface Config {
  apiKey: string
  projectId: string
  projectName: string
  email: string
  apiUrl: string
}

export const CONFIG_PATH = path.join(os.homedir(), '.conductor', 'config.json')

export function readConfig(): Config | null {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    if (!isConfig(parsed)) return null
    return parsed
  } catch {
    return null
  }
}

export function writeConfig(config: Config): void {
  const dir = path.dirname(CONFIG_PATH)
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(CONFIG_PATH, JSON.stringify(config, null, 2), 'utf8')
}

export function clearConfig(): void {
  try {
    fs.unlinkSync(CONFIG_PATH)
  } catch {
    // File may not exist; that's fine
  }
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
