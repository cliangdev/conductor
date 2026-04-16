import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')

export const STATE_FILE_PATH = path.join(CONDUCTOR_DIR, 'daemon-state.json')

export interface ActiveRun {
  runId: string
  issueTitle: string
  jobName: string
  status: 'running' | 'completed' | 'failed'
  startedAt: string
}

export interface DaemonState {
  pid: number
  startedAt: string
  pollMode: 'idle' | 'active'
  lastPollAt: string | null
  consecutiveErrors: number
  eventsThisSession: number
  activeRuns: ActiveRun[]
  syncQueueSize: number
}

export function writeDaemonState(state: DaemonState): void {
  fs.mkdirSync(CONDUCTOR_DIR, { recursive: true })
  fs.writeFileSync(STATE_FILE_PATH, JSON.stringify(state, null, 2), 'utf8')
}

export function readDaemonState(): DaemonState | null {
  try {
    const raw = fs.readFileSync(STATE_FILE_PATH, 'utf8')
    return JSON.parse(raw) as DaemonState
  } catch {
    return null
  }
}

export function deleteDaemonState(): void {
  try {
    fs.unlinkSync(STATE_FILE_PATH)
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== 'ENOENT') {
      throw err
    }
  }
}
