import chokidar from 'chokidar'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { fileURLToPath } from 'url'
import { readConfig, Config } from '../lib/config.js'
import { writeDaemonState, deleteDaemonState } from './state.js'
import { startPoller } from './poller.js'

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')
export const SYNC_QUEUE_PATH = path.join(CONDUCTOR_DIR, 'sync-queue.json')

export interface QueueEntry {
  method: 'POST' | 'PUT' | 'DELETE'
  path: string
  body?: Record<string, unknown>
  timestamp: string
}

export const debounceTimers = new Map<string, ReturnType<typeof setTimeout>>()

export function debounce(key: string, fn: () => void, ms = 500): void {
  const existing = debounceTimers.get(key)
  if (existing) clearTimeout(existing)
  debounceTimers.set(key, setTimeout(() => {
    debounceTimers.delete(key)
    fn()
  }, ms))
}

export function parseFilePath(
  filePath: string
): { issueId: string; filename: string } | null {
  // Expected: {localPath}/.conductor/issues/{issueId}/{filename}
  const normalized = filePath.replace(/\\/g, '/')
  const match = normalized.match(/\.conductor\/issues\/([^/]+)\/([^/]+)$/)
  if (!match) return null
  return { issueId: match[1], filename: match[2] }
}

export function readQueue(): QueueEntry[] {
  try {
    const raw = fs.readFileSync(SYNC_QUEUE_PATH, 'utf8')
    return JSON.parse(raw) as QueueEntry[]
  } catch {
    return []
  }
}

export function writeQueue(entries: QueueEntry[]): void {
  if (entries.length === 0) {
    try {
      fs.unlinkSync(SYNC_QUEUE_PATH)
    } catch {
      // File may already not exist
    }
    return
  }
  fs.mkdirSync(path.dirname(SYNC_QUEUE_PATH), { recursive: true })
  fs.writeFileSync(SYNC_QUEUE_PATH, JSON.stringify(entries, null, 2), 'utf8')
}

export function queueChange(entry: Omit<QueueEntry, 'timestamp'>): void {
  const entries = readQueue()
  entries.push({ ...entry, timestamp: new Date().toISOString() })
  writeQueue(entries)
}

async function callApi(
  method: string,
  apiPath: string,
  body: Record<string, unknown> | undefined,
  getConfig: () => Config
): Promise<void> {
  const config = getConfig()
  const url = `${config.apiUrl}${apiPath}`
  const response = await fetch(url, {
    method,
    headers: {
      Authorization: `Bearer ${config.apiKey}`,
      'Content-Type': 'application/json',
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })
  if (!response.ok) {
    const text = await response.text().catch(() => response.statusText)
    throw new Error(`${method} ${apiPath} failed with status ${response.status}: ${text}`)
  }
}

export async function syncFile(filePath: string, getConfig: () => Config): Promise<void> {
  const parsed = parseFilePath(filePath)
  if (!parsed) return

  // issue.md is handled by syncIssueMd
  if (parsed.filename === 'issue.md') return

  let content: string
  try {
    content = fs.readFileSync(filePath, 'utf8')
  } catch {
    console.error(`Failed to read file: ${filePath}`)
    return
  }

  const config = getConfig()
  const apiPath = `/api/v1/projects/${config.projectId}/issues/${parsed.issueId}/documents/${encodeURIComponent(parsed.filename)}`
  const body = { content, contentType: 'text/markdown' }

  try {
    await callApi('PUT', apiPath, body, getConfig)
    console.log(`Synced: ${filePath}`)
  } catch (err) {
    console.error(`Sync failed, queuing: ${filePath} — ${(err as Error).message}`)
    queueChange({ method: 'PUT', path: apiPath, body })
  }
}

export async function deleteFile(filePath: string, getConfig: () => Config): Promise<void> {
  const parsed = parseFilePath(filePath)
  if (!parsed) return

  if (parsed.filename === 'issue.md') return

  const config = getConfig()
  const apiPath = `/api/v1/projects/${config.projectId}/issues/${parsed.issueId}/documents/${encodeURIComponent(parsed.filename)}`

  try {
    await callApi('DELETE', apiPath, undefined, getConfig)
    console.log(`Deleted: ${filePath}`)
  } catch (err) {
    console.error(`Delete failed, queuing: ${filePath} — ${(err as Error).message}`)
    queueChange({ method: 'DELETE', path: apiPath })
  }
}

function parseFrontmatter(content: string): { title?: string; status?: string; body?: string } {
  const match = content.match(/^---\n([\s\S]*?)\n---\n?([\s\S]*)$/)
  if (!match) return {}
  const frontmatterStr = match[1]
  const body = match[2].trim()

  const title = frontmatterStr.match(/^title:\s*(.+)$/m)?.[1]?.trim()
  const status = frontmatterStr.match(/^status:\s*(.+)$/m)?.[1]?.trim()
  return { title, status, body }
}

export async function syncIssueMd(filePath: string, getConfig: () => Config): Promise<void> {
  const parsed = parseFilePath(filePath)
  if (!parsed) return

  let content: string
  try {
    content = fs.readFileSync(filePath, 'utf8')
  } catch {
    console.error(`Failed to read file: ${filePath}`)
    return
  }

  const { title, status, body } = parseFrontmatter(content)
  const patchBody: Record<string, string> = {}
  if (title !== undefined) patchBody['title'] = title
  if (status !== undefined) patchBody['status'] = status
  if (body !== undefined) patchBody['description'] = body

  if (Object.keys(patchBody).length === 0) return

  const config = getConfig()
  const apiPath = `/api/v1/projects/${config.projectId}/issues/${parsed.issueId}`

  try {
    await callApi('PATCH', apiPath, patchBody, getConfig)
    console.log(`Synced issue.md: ${filePath}`)
  } catch (err) {
    console.error(`Issue sync failed, queuing: ${filePath} — ${(err as Error).message}`)
    queueChange({ method: 'PUT', path: apiPath, body: patchBody })
  }
}

export async function syncTasksJson(filePath: string, getConfig: () => Config): Promise<void> {
  const parsed = parseFilePath(filePath)
  if (!parsed) return

  let content: string
  try {
    content = fs.readFileSync(filePath, 'utf8')
  } catch {
    console.error(`Failed to read file: ${filePath}`)
    return
  }

  const tasks = JSON.parse(content)
  const config = getConfig()
  const apiPath = `/api/v1/projects/${config.projectId}/issues/${parsed.issueId}/tasks`

  try {
    await callApi('PUT', apiPath, tasks, getConfig)
    console.log(`Synced tasks.json: ${filePath}`)
  } catch (err) {
    console.error(`Tasks sync failed, queuing: ${filePath} — ${(err as Error).message}`)
    queueChange({ method: 'PUT', path: apiPath, body: tasks })
  }
}

export async function replayQueue(getConfig: () => Config): Promise<void> {
  const entries = readQueue()
  if (entries.length === 0) return

  console.log(`Replaying ${entries.length} queued item(s)...`)
  const remaining: QueueEntry[] = []

  for (const entry of entries) {
    try {
      await callApi(entry.method, entry.path, entry.body, getConfig)
      console.log(`Replayed: ${entry.method} ${entry.path}`)
    } catch (err) {
      console.error(`Replay failed for ${entry.method} ${entry.path}: ${(err as Error).message}`)
      remaining.push(entry)
    }
  }

  writeQueue(remaining)
  if (remaining.length === 0) {
    console.log('All queued items replayed successfully.')
  } else {
    console.log(`${remaining.length} item(s) still pending.`)
  }
}

export function startWatcher(getConfig: () => Config): void {
  const config = getConfig()
  if (!config.localPath) {
    console.error('localPath not set in config — run conductor init first')
    process.exit(1)
  }

  const watchPath = path.join(config.localPath, '.conductor', 'issues')
  console.log(`Watching: ${watchPath}`)

  const watcher = chokidar.watch(watchPath, { ignoreInitial: true, persistent: true, depth: 2 })

  watcher
    .on('add', (filePath) => debounce(filePath, () => {
      if (path.basename(filePath) === 'issue.md') {
        syncIssueMd(filePath, getConfig).catch(console.error)
      } else if (path.basename(filePath) === 'tasks.json') {
        syncTasksJson(filePath, getConfig).catch(console.error)
      } else {
        syncFile(filePath, getConfig).catch(console.error)
      }
    }))
    .on('change', (filePath) => debounce(filePath, () => {
      if (path.basename(filePath) === 'issue.md') {
        syncIssueMd(filePath, getConfig).catch(console.error)
      } else if (path.basename(filePath) === 'tasks.json') {
        syncTasksJson(filePath, getConfig).catch(console.error)
      } else {
        syncFile(filePath, getConfig).catch(console.error)
      }
    }))
    .on('unlink', (filePath) => debounce(filePath, () => {
      const basename = path.basename(filePath)
      if (basename !== 'issue.md' && basename !== 'tasks.json') {
        deleteFile(filePath, getConfig).catch(console.error)
      }
      // issue.md and tasks.json deletes are ignored
    }))

  process.on('SIGTERM', () => {
    watcher.close().then(() => {
      console.log('Watcher closed.')
      deleteDaemonState()
      process.exit(0)
    }).catch(() => {
      deleteDaemonState()
      process.exit(0)
    })
  })
}

// Only run as daemon entrypoint when this file is executed directly
const __filename = fileURLToPath(import.meta.url)
if (process.argv[1] === __filename) {
  const getConfig = (): Config => {
    const cfg = readConfig()
    if (!cfg) {
      console.error('Not authenticated — run conductor login')
      process.exit(1)
    }
    return cfg
  }

  // Validate config is present at startup
  getConfig()

  writeDaemonState({
    pid: process.pid,
    startedAt: new Date().toISOString(),
    pollMode: 'idle',
    lastPollAt: null,
    consecutiveErrors: 0,
    eventsThisSession: 0,
    activeRuns: [],
    syncQueueSize: 0,
  })

  replayQueue(getConfig).catch(console.error)
  startWatcher(getConfig)
  startPoller(getConfig, async (events) => {
    for (const event of events) {
      console.log(`Received event: ${event.type} (${event.eventId})`)
    }
  })
}
