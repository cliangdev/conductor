import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')

/**
 * The folders the running daemon is watching, written by the daemon each time its watch set changes. A
 * separate file from daemon-state.json because the poller and run queue rewrite that one field by field.
 * `conductor init` reads it to tell whether the daemon already picked up a newly linked repo.
 */
export const WATCH_REGISTRY_PATH = path.join(CONDUCTOR_DIR, 'daemon-watch.json')

export interface WatchRegistry {
  pid: number
  paths: string[]
  updatedAt: string
}

export function writeWatchRegistry(pid: number, paths: string[]): void {
  try {
    fs.mkdirSync(CONDUCTOR_DIR, { recursive: true })
    const entry: WatchRegistry = { pid, paths: [...paths].sort(), updatedAt: new Date().toISOString() }
    fs.writeFileSync(WATCH_REGISTRY_PATH, JSON.stringify(entry, null, 2), 'utf8')
  } catch (err) {
    console.error('Could not write daemon-watch.json:', err)
  }
}

export function readWatchRegistry(): WatchRegistry | null {
  try {
    const parsed = JSON.parse(fs.readFileSync(WATCH_REGISTRY_PATH, 'utf8')) as WatchRegistry
    if (typeof parsed?.pid !== 'number' || !Array.isArray(parsed.paths)) return null
    return parsed
  } catch {
    return null
  }
}

export function deleteWatchRegistry(): void {
  try {
    fs.unlinkSync(WATCH_REGISTRY_PATH)
  } catch {
    // Already gone — nothing to do.
  }
}

/** Which folders to start and stop watching to go from `current` to `next`. */
export function diffWatchPaths(current: Iterable<string>, next: Iterable<string>): { added: string[]; removed: string[] } {
  const currentSet = new Set(current)
  const nextSet = new Set(next)
  return {
    added: [...nextSet].filter((p) => !currentSet.has(p)),
    removed: [...currentSet].filter((p) => !nextSet.has(p)),
  }
}
