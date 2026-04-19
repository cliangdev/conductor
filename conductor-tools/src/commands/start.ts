import { spawn } from 'child_process'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { fileURLToPath } from 'url'
import { Command } from 'commander'
import { readConfig } from '../lib/config.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')
export const PID_FILE = path.join(CONDUCTOR_DIR, 'daemon.pid')
export const LOG_FILE = path.join(CONDUCTOR_DIR, 'daemon.log')

function isProcessAlive(pid: number): boolean {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

function tailLog(lines = 5): string {
  try {
    const content = fs.readFileSync(LOG_FILE, 'utf8')
    return content.trim().split('\n').slice(-lines).join('\n')
  } catch {
    return '(no log output)'
  }
}

/** Returns true if the daemon started and stayed alive, false otherwise. */
export async function startDaemon(): Promise<boolean> {
  const config = readConfig()
  if (!config) {
    console.error('Not authenticated — run conductor login')
    process.exit(78)
    return false
  }

  if (fs.existsSync(PID_FILE)) {
    const pid = parseInt(fs.readFileSync(PID_FILE, 'utf8').trim(), 10)
    if (isProcessAlive(pid)) {
      console.log(`Daemon already running (PID ${pid})`)
      return true
    }
    fs.unlinkSync(PID_FILE)
  }

  fs.mkdirSync(CONDUCTOR_DIR, { recursive: true })
  const logStream = fs.openSync(LOG_FILE, 'a')

  const watcherPath = path.join(__dirname, '..', 'daemon', 'watcher.js')

  const daemon = spawn(process.execPath, [watcherPath], {
    detached: true,
    stdio: ['ignore', logStream, logStream],
  })

  const pid = daemon.pid!
  fs.writeFileSync(PID_FILE, String(pid), 'utf8')
  daemon.unref()

  // Wait briefly to catch immediate crashes
  await new Promise(resolve => setTimeout(resolve, 600))

  if (!isProcessAlive(pid)) {
    fs.unlinkSync(PID_FILE)
    console.error(`Daemon failed to start. Last log output:\n${tailLog()}`)
    return false
  }

  console.log(`Daemon started (PID ${pid})`)
  return true
}

export function registerStart(program: Command): void {
  program
    .command('start')
    .description('Start the file watcher daemon')
    .addHelpText('after', `
Examples:
  conductor start`)
    .action(async () => { await startDaemon() })
}
