import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { execSync } from 'child_process'

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

/** How a project id was resolved for the current request. */
export type ProjectResolutionSource = 'env' | 'cwd' | 'fallback'

export interface ProjectResolution {
  projectId: string
  source: ProjectResolutionSource
  /**
   * True when we fell back to the mutable active `projectId` while the cwd is
   * inside a git work-tree that matches no configured project. Writing in this
   * state silently targets whatever project happens to be "active" — the class
   * of bug that mis-stamped issues into the wrong project. Callers must refuse
   * to act when this is set.
   */
  mismatch: boolean
}

/** Returns the best cwd→project match, or null if nothing matches. */
function matchProjectByCwd(projects: Record<string, ProjectEntry>, cwd: string): string | null {
  const normalizedCwd = cwd.replace(/\\/g, '/')
  let bestMatch: { projectId: string; pathLen: number } | null = null
  for (const [projectId, proj] of Object.entries(projects)) {
    const normalizedPath = proj.localPath.replace(/\\/g, '/')
    if (normalizedCwd === normalizedPath || normalizedCwd.startsWith(normalizedPath + '/')) {
      if (!bestMatch || normalizedPath.length > bestMatch.pathLen) {
        bestMatch = { projectId, pathLen: normalizedPath.length }
      }
    }
  }
  return bestMatch?.projectId ?? null
}

/** True if `dir` is inside a git work-tree. Used to decide when to fail closed. */
function isInsideGitWorkTree(dir: string): boolean {
  try {
    const out = execSync('git rev-parse --is-inside-work-tree', {
      cwd: dir,
      encoding: 'utf8',
      stdio: ['pipe', 'pipe', 'pipe'],
    }).trim()
    return out === 'true'
  } catch {
    return false
  }
}

/**
 * Resolve which project a request targets, in priority order:
 *  1. `CONDUCTOR_PROJECT_ID` env — the durable per-repo binding written into
 *     `.mcp.json` by `conductor init`. Survives global-config rewrites.
 *  2. cwd → `projects` map match (longest path wins).
 *  3. fallback to the active `projectId`. Flagged as a `mismatch` when the cwd
 *     is inside a git repo that matches no project, so callers can fail closed
 *     instead of silently writing to the wrong project.
 *
 * `insideGitRepo` is injectable for testing.
 */
export function resolveProject(
  config: Config,
  cwd: string = process.cwd(),
  insideGitRepo: (dir: string) => boolean = isInsideGitWorkTree
): ProjectResolution {
  const envId = process.env['CONDUCTOR_PROJECT_ID']
  if (envId) return { projectId: envId, source: 'env', mismatch: false }

  const hasMap = config.projects && Object.keys(config.projects).length > 0
  if (hasMap) {
    const matched = matchProjectByCwd(config.projects as Record<string, ProjectEntry>, cwd)
    if (matched) return { projectId: matched, source: 'cwd', mismatch: false }
    // A real map exists but cwd matches nothing: only safe to fall back when we
    // are NOT inside a git repo (e.g. server launched from $HOME). Inside a repo
    // this means the repo isn't linked — refuse rather than guess.
    return { projectId: config.projectId, source: 'fallback', mismatch: insideGitRepo(cwd) }
  }

  return { projectId: config.projectId, source: 'fallback', mismatch: false }
}

/** Back-compat string wrapper around {@link resolveProject}. */
export function resolveProjectIdByCwd(config: Config, cwd: string = process.cwd()): string {
  return resolveProject(config, cwd).projectId
}

export function getConfig(): Config {
  try {
    const raw = fs.readFileSync(CONFIG_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    if (!isConfig(parsed)) {
      throw new Error('Invalid config: missing required fields')
    }
    // Synthesize projects map from legacy single-project fields for backward compat.
    // Treat an empty map the same as a missing one — kept in sync with the CLI
    // reader (`lib/config.ts`) so a fix to one path can't be missed on the other.
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
  } catch (err) {
    if (err instanceof Error && err.message.startsWith('Invalid config')) {
      throw err
    }
    throw new Error('Config not found — run conductor login')
  }
}
