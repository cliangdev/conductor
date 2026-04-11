import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { Command } from 'commander'
import chalk from 'chalk'
import { readConfig } from '../lib/config.js'

const DAEMON_PID_PATH = path.join(os.homedir(), '.conductor', 'daemon.pid')
const SYNC_QUEUE_PATH = path.join(os.homedir(), '.conductor', 'sync-queue.json')

export function isDaemonRunning(): boolean {
  try {
    const raw = fs.readFileSync(DAEMON_PID_PATH, 'utf8').trim()
    const pid = parseInt(raw, 10)
    if (isNaN(pid)) return false
    process.kill(pid, 0)
    return true
  } catch {
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
    })
}
