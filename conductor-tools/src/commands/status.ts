import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

export function formatRelativeTime(dateStr: string): string {
  const diffMs = Date.now() - new Date(dateStr).getTime()
  const diffSec = Math.floor(diffMs / 1000)
  if (diffSec < 60) return `${diffSec}s ago`
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`
  return `${Math.floor(diffSec / 3600)}h ago`
}

const DAEMON_PID_PATH = path.join(os.homedir(), '.conductor', 'daemon.pid')
const SYNC_QUEUE_PATH = path.join(os.homedir(), '.conductor', 'sync-queue.json')

export function isDaemonRunning(): boolean {
  try {
    const raw = fs.readFileSync(DAEMON_PID_PATH, 'utf8').trim()
    const pid = parseInt(raw, 10)
    if (isNaN(pid)) {
      fs.unlinkSync(DAEMON_PID_PATH)
      return false
    }
    process.kill(pid, 0)
    return true
  } catch (err) {
    // Process not found — clean up stale PID file
    if ((err as NodeJS.ErrnoException).code === 'ESRCH') {
      try { fs.unlinkSync(DAEMON_PID_PATH) } catch { /* already gone */ }
    }
    return false
  }
}

export function getQueueCount(): number {
  try {
    const raw = fs.readFileSync(SYNC_QUEUE_PATH, 'utf8')
    const parsed = JSON.parse(raw) as unknown
    if (Array.isArray(parsed)) return parsed.length
    return 0
  } catch {
    return 0
  }
}

