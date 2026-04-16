import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { Command } from 'commander'
import chalk from 'chalk'
import { readConfig } from '../lib/config.js'
import { readDaemonState } from '../daemon/state.js'

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

export function registerStatus(program: Command): void {
  program
    .command('status')
    .description('Show current Conductor status')
    .action(() => {
      const config = readConfig()

      if (!config) {
        console.log(`Auth:      ${chalk.red('✗')} Not authenticated — run conductor login`)
        return
      }

      const daemonRunning = isDaemonRunning()
      const queueCount = getQueueCount()

      const watchDir = config.localPath
        ? `${config.localPath}/.conductor/issues`
        : chalk.yellow('not set — run conductor init')

      console.log(`Auth:      ${chalk.green('✓')} Logged in as ${config.email}`)
      console.log(`Project:   ${config.projectName} (${config.projectId})`)
      console.log(
        `Daemon:    ${daemonRunning ? chalk.green('✓ Running') : chalk.red('✗ Not running')}`
      )
      console.log(`Watch dir: ${watchDir}`)
      console.log(`API URL:   ${config.apiUrl}`)
      console.log(`Queue:     ${queueCount} pending changes`)

      if (daemonRunning) {
        const state = readDaemonState()
        if (state) {
          const pollInterval = state.pollMode === 'active' ? '5s' : '60s'
          console.log(`Poll mode: ${state.pollMode} (${pollInterval})`)
          const lastPoll = state.lastPollAt
            ? formatRelativeTime(state.lastPollAt)
            : 'never'
          console.log(`Last poll: ${lastPoll}`)
          console.log(`Events:    ${state.eventsThisSession} this session`)
        }
      }
    })
}
