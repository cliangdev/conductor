import { Config } from '../lib/config.js'
import { writeDaemonState, readDaemonState } from './state.js'

export type PollMode = 'idle' | 'active'

export interface PollConfig {
  idleIntervalMs: number
  activeIntervalMs: number
  activeWindowMs: number
  maxConsecutiveErrors: number
}

export type EventHandler = (events: DaemonEvent[]) => Promise<void>

export interface DaemonEvent {
  eventId: string
  type: string
  payload: Record<string, unknown>
}

const DEFAULT_CONFIG: PollConfig = {
  idleIntervalMs: 60_000,
  activeIntervalMs: 5_000,
  activeWindowMs: 300_000,
  maxConsecutiveErrors: 3,
}

export function startPoller(
  getConfig: () => Config,
  onEvent: EventHandler,
  options?: Partial<PollConfig>
): { stop: () => void } {
  const pollConfig: PollConfig = { ...DEFAULT_CONFIG, ...options }

  let stopped = false
  let timer: ReturnType<typeof setTimeout> | null = null
  let mode: PollMode = 'idle'
  let lastEventAt: number | null = null
  let consecutiveErrors = 0
  let eventsThisSession = 0

  async function poll(): Promise<void> {
    if (stopped) return

    const config = getConfig()
    const url = `${config.apiUrl}/api/v1/projects/${config.projectId}/daemon/events`

    try {
      const response = await fetch(url, {
        headers: {
          Authorization: `Bearer ${config.apiKey}`,
          'Content-Type': 'application/json',
        },
      })

      if (!response.ok) {
        throw new Error(`Poll failed with status ${response.status}`)
      }

      const data = (await response.json()) as { events: DaemonEvent[] }
      const events = data.events ?? []

      consecutiveErrors = 0

      if (events.length > 0) {
        mode = 'active'
        lastEventAt = Date.now()
        eventsThisSession += events.length
        await onEvent(events)
      } else {
        // No events — determine mode based on activeWindowMs
        if (lastEventAt !== null && Date.now() - lastEventAt < pollConfig.activeWindowMs) {
          mode = 'active'
        } else {
          mode = 'idle'
        }
      }
    } catch (err) {
      consecutiveErrors++
      mode = 'idle'

      if (consecutiveErrors >= pollConfig.maxConsecutiveErrors) {
        console.warn('Poll endpoint returning errors, continuing at idle interval')
      } else {
        console.error(`Poll error: ${(err as Error).message}`)
      }
    }

    // Update daemon-state.json after every poll
    const existingState = readDaemonState()
    writeDaemonState({
      pid: existingState?.pid ?? process.pid,
      startedAt: existingState?.startedAt ?? new Date().toISOString(),
      pollMode: mode,
      lastPollAt: new Date().toISOString(),
      consecutiveErrors,
      eventsThisSession,
      activeRuns: existingState?.activeRuns ?? [],
      syncQueueSize: existingState?.syncQueueSize ?? 0,
    })

    if (!stopped) {
      scheduleNext()
    }
  }

  function scheduleNext(): void {
    if (stopped) return
    const intervalMs = mode === 'active' ? pollConfig.activeIntervalMs : pollConfig.idleIntervalMs
    timer = setTimeout(() => {
      poll().catch(console.error)
    }, intervalMs)
  }

  // Start the polling loop
  scheduleNext()

  return {
    stop(): void {
      stopped = true
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
    },
  }
}
