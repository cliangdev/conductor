import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { Command } from 'commander'

export function formatRelativeTime(dateStr: string): string {
  const diffMs = Date.now() - new Date(dateStr).getTime()
  const diffSec = Math.floor(diffMs / 1000)
  if (diffSec < 60) return `${diffSec}s ago`
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`
  return `${Math.floor(diffSec / 3600)}h ago`
}

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')
const DAEMON_PID_PATH = path.join(CONDUCTOR_DIR, 'daemon.pid')
const SYNC_QUEUE_PATH = path.join(CONDUCTOR_DIR, 'sync-queue.json')
export const DAEMON_LOG_PATH = path.join(CONDUCTOR_DIR, 'daemon.log')

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

export function getDaemonPid(): number | null {
  try {
    const raw = fs.readFileSync(DAEMON_PID_PATH, 'utf8').trim()
    const pid = parseInt(raw, 10)
    if (isNaN(pid)) return null
    process.kill(pid, 0)
    return pid
  } catch {
    return null
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

export function getLogFileSizeMb(): number | null {
  try {
    const stat = fs.statSync(DAEMON_LOG_PATH)
    return Math.round((stat.size / (1024 * 1024)) * 10) / 10
  } catch {
    return null
  }
}

export function registerStatus(program: Command): void {
  program
    .command('status')
    .description('Show daemon and sync queue status')
    .option('--json', 'Output status as JSON')
    .addHelpText('after', `
Examples:
  conductor status
  conductor status --json`)
    .action((options: { json?: boolean }) => {
      const running = isDaemonRunning()
      const pid = getDaemonPid()
      const queueSize = getQueueCount()
      const logSizeMb = getLogFileSizeMb()

      if (options.json) {
        let uptime: string | null = null
        if (running && pid !== null) {
          // Uptime is best-effort; daemon.log is not the source — use state file if needed
          uptime = null
        }
        const output = {
          daemon: {
            running,
            pid: running ? pid : null,
            uptime,
          },
          syncQueue: {
            size: queueSize,
          },
          log: {
            path: DAEMON_LOG_PATH,
            sizeMb: logSizeMb,
          },
        }
        process.stdout.write(JSON.stringify(output, null, 2) + '\n')
        process.exit(0)
        return
      }

      if (running) {
        console.log(`Daemon: running (PID ${pid})`)
      } else {
        console.log('Daemon: not running')
      }
      console.log(`Sync queue: ${queueSize} item(s)`)
      if (logSizeMb !== null) {
        console.log(`Log: ${DAEMON_LOG_PATH} (${logSizeMb.toFixed(1)} MB)`)
      } else {
        console.log(`Log: ${DAEMON_LOG_PATH} (not found)`)
      }
    })
}
