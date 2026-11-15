import { spawn } from 'child_process'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import { fileURLToPath } from 'url'
import { Command } from 'commander'
import { readConfig } from '../lib/config.js'
import { refreshInstalledPluginAssets } from '../lib/plugin-assets.js'
import { readWatchRegistry } from '../daemon/watch-registry.js'
import { readDaemonState } from '../daemon/state.js'

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
  refreshInstalledPluginAssets({ log: (message) => console.error(message) })

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

export type EnsureWatchingResult =
  /** No daemon was running; one was started and reads the current config. */
  | 'started'
  /** The running daemon already watches the folder (it reloads config.json on its own). */
  | 'watching'
  /** The running daemon didn't pick the folder up (it predates config reloading) and was restarted. */
  | 'restarted'
  /** Same as 'restarted' was needed, but a workflow run is in flight — restarting would kill it. */
  | 'busy'
  | 'failed'

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

function runningDaemonPid(): number | null {
  if (!fs.existsSync(PID_FILE)) return null
  const pid = parseInt(fs.readFileSync(PID_FILE, 'utf8').trim(), 10)
  return Number.isFinite(pid) && isProcessAlive(pid) ? pid : null
}

/**
 * Make sure the sync daemon watches `watchPath` after `conductor init` linked a repo. A daemon started by
 * this version reloads config.json and lists its folders in daemon-watch.json; one started by an older
 * version never does, so it is restarted — unless a workflow run is in flight, which a restart would kill.
 */
export async function ensureDaemonWatching(
  watchPath: string,
  opts: { waitMs?: number; pollMs?: number } = {},
): Promise<EnsureWatchingResult> {
  const waitMs = opts.waitMs ?? 3000
  const pollMs = opts.pollMs ?? 250

  const pid = runningDaemonPid()
  if (pid === null) return (await startDaemon()) ? 'started' : 'failed'

  for (let waited = 0; ; waited += pollMs) {
    const registry = readWatchRegistry()
    if (registry?.pid === pid && registry.paths.includes(watchPath)) return 'watching'
    if (waited >= waitMs) break
    await sleep(pollMs)
  }

  const inFlight = readDaemonState()?.activeRuns?.some((r) => r.status === 'running') ?? false
  if (inFlight) return 'busy'

  try {
    process.kill(pid, 'SIGTERM')
  } catch {
    // Exited on its own in the meantime.
  }
  for (let waited = 0; waited < 5000 && isProcessAlive(pid); waited += pollMs) {
    await sleep(pollMs)
  }
  try {
    fs.unlinkSync(PID_FILE)
  } catch {
    // stop already removed it
  }
  return (await startDaemon()) ? 'restarted' : 'failed'
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
