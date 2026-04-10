import { spawn } from 'child_process'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { Command } from 'commander'
import { readConfig } from '../lib/config.js'

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')
export const PID_FILE = path.join(CONDUCTOR_DIR, 'daemon.pid')
export const LOG_FILE = path.join(CONDUCTOR_DIR, 'daemon.log')

export async function startDaemon(): Promise<void> {
  const config = readConfig()
  if (!config) {
    console.error('Not authenticated — run conductor login')
    process.exit(1)
    return
  }

  if (fs.existsSync(PID_FILE)) {
    const pid = parseInt(fs.readFileSync(PID_FILE, 'utf8').trim(), 10)
    try {
      process.kill(pid, 0)
      console.log(`Daemon already running (PID ${pid})`)
      return
    } catch {
      // Stale PID file — remove it and continue
      fs.unlinkSync(PID_FILE)
    }
  }

  fs.mkdirSync(CONDUCTOR_DIR, { recursive: true })
  const logStream = fs.openSync(LOG_FILE, 'a')

  const watcherPath = path.join(__dirname, '..', 'daemon', 'watcher.js')

  const daemon = spawn(process.execPath, [watcherPath], {
    detached: true,
    stdio: ['ignore', logStream, logStream],
  })

  daemon.unref()
  fs.writeFileSync(PID_FILE, String(daemon.pid), 'utf8')
  console.log(`Daemon started (PID ${daemon.pid})`)
}

export function registerStart(program: Command): void {
  program
    .command('start')
    .description('Start the file watcher daemon')
    .action(startDaemon)
}
