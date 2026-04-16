import { describe, it, expect, beforeEach, vi } from 'vitest'

// ─── Mocks ────────────────────────────────────────────────────────────────────

vi.mock('../lib/config.js', () => ({ readConfig: vi.fn() }))
vi.mock('../commands/status.js', () => ({
  isDaemonRunning: vi.fn(),
  getQueueCount: vi.fn(),
  formatRelativeTime: vi.fn(),
}))
vi.mock('../daemon/state.js', () => ({ readDaemonState: vi.fn() }))

// ─── Fixtures ─────────────────────────────────────────────────────────────────

const validConfig = {
  apiKey: 'key',
  projectId: 'proj_123',
  projectName: 'My Project',
  email: 'user@example.com',
  apiUrl: 'http://localhost:8080',
}

const validState = {
  pid: 12345,
  startedAt: new Date(Date.now() - 5 * 60 * 1000).toISOString(), // 5 min ago
  pollMode: 'active' as const,
  lastPollAt: new Date(Date.now() - 30_000).toISOString(),
  consecutiveErrors: 0,
  eventsThisSession: 12,
  activeRuns: [],
  syncQueueSize: 0,
}

// Strip ANSI escape codes for readable assertions
function strip(s: string): string {
  return s.replace(/\x1B\[[0-9;]*m/g, '')
}

function stripLines(lines: string[]): string[] {
  return lines.map(strip)
}

// ─── visLen ───────────────────────────────────────────────────────────────────

describe('visLen', () => {
  it('returns length of plain string', async () => {
    const { visLen } = await import('../commands/dashboard.js')
    expect(visLen('hello')).toBe(5)
  })

  it('returns 0 for empty string', async () => {
    const { visLen } = await import('../commands/dashboard.js')
    expect(visLen('')).toBe(0)
  })

  it('ignores ANSI color codes', async () => {
    const { visLen } = await import('../commands/dashboard.js')
    expect(visLen('\x1B[32mhello\x1B[0m')).toBe(5)
  })

  it('handles multiple ANSI sequences', async () => {
    const { visLen } = await import('../commands/dashboard.js')
    expect(visLen('\x1B[1m\x1B[32mhello\x1B[0m world\x1B[0m')).toBe(11)
  })
})

// ─── rpad ─────────────────────────────────────────────────────────────────────

describe('rpad', () => {
  it('pads a plain string to the given width', async () => {
    const { rpad } = await import('../commands/dashboard.js')
    expect(rpad('hi', 5)).toBe('hi   ')
  })

  it('does not truncate strings longer than width', async () => {
    const { rpad } = await import('../commands/dashboard.js')
    expect(rpad('toolong', 4)).toBe('toolong')
  })

  it('pads based on visible length ignoring ANSI codes', async () => {
    const { rpad } = await import('../commands/dashboard.js')
    const colored = '\x1B[32mhi\x1B[0m' // visible length = 2
    const result = rpad(colored, 5)
    // visible content = 'hi' (2) + 3 spaces = 5
    expect(result.replace(/\x1B\[[0-9;]*m/g, '')).toBe('hi   ')
  })

  it('returns string unchanged when width equals visible length', async () => {
    const { rpad } = await import('../commands/dashboard.js')
    expect(rpad('abc', 3)).toBe('abc')
  })
})

// ─── formatUptime ─────────────────────────────────────────────────────────────

describe('formatUptime', () => {
  it('formats seconds as Xm Ys when under an hour', async () => {
    const { formatUptime } = await import('../commands/dashboard.js')
    const startedAt = new Date(Date.now() - 5 * 60 * 1000 - 30 * 1000).toISOString()
    expect(formatUptime(startedAt)).toBe('5m 30s')
  })

  it('formats as Xh Ym when over an hour', async () => {
    const { formatUptime } = await import('../commands/dashboard.js')
    const startedAt = new Date(Date.now() - 2 * 3600 * 1000 - 15 * 60 * 1000).toISOString()
    expect(formatUptime(startedAt)).toBe('2h 15m')
  })

  it('formats 0m 0s for just-started daemon', async () => {
    const { formatUptime } = await import('../commands/dashboard.js')
    const startedAt = new Date(Date.now()).toISOString()
    expect(formatUptime(startedAt)).toBe('0m 0s')
  })
})

// ─── formatRunDuration ────────────────────────────────────────────────────────

describe('formatRunDuration', () => {
  it('formats as Xs when under a minute', async () => {
    const { formatRunDuration } = await import('../commands/dashboard.js')
    const startedAt = new Date(Date.now() - 45_000).toISOString()
    expect(formatRunDuration(startedAt)).toBe('45s')
  })

  it('formats as Xm Ys when under an hour', async () => {
    const { formatRunDuration } = await import('../commands/dashboard.js')
    const startedAt = new Date(Date.now() - 2 * 60 * 1000 - 15 * 1000).toISOString()
    expect(formatRunDuration(startedAt)).toBe('2m 15s')
  })

  it('formats as Xh Ym when over an hour', async () => {
    const { formatRunDuration } = await import('../commands/dashboard.js')
    const startedAt = new Date(Date.now() - 3 * 3600 * 1000 - 10 * 60 * 1000).toISOString()
    expect(formatRunDuration(startedAt)).toBe('3h 10m')
  })
})

// ─── truncate ─────────────────────────────────────────────────────────────────

describe('truncate', () => {
  it('returns string unchanged when within length', async () => {
    const { truncate } = await import('../commands/dashboard.js')
    expect(truncate('hello', 10)).toBe('hello')
  })

  it('truncates with ellipsis when over length', async () => {
    const { truncate } = await import('../commands/dashboard.js')
    const result = truncate('hello world', 8)
    expect(result).toHaveLength(8)
    expect(result.endsWith('…')).toBe(true)
  })

  it('returns string unchanged when exactly at length', async () => {
    const { truncate } = await import('../commands/dashboard.js')
    expect(truncate('hello', 5)).toBe('hello')
  })
})

// ─── buildLines ───────────────────────────────────────────────────────────────

describe('buildLines', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  async function setup(opts: {
    config?: typeof validConfig | null
    daemonRunning?: boolean
    queueCount?: number
    state?: typeof validState | null
  }) {
    const { readConfig } = await import('../lib/config.js')
    const { isDaemonRunning, getQueueCount, formatRelativeTime } = await import('../commands/status.js')
    const { readDaemonState } = await import('../daemon/state.js')

    vi.mocked(readConfig).mockReturnValue(opts.config === undefined ? validConfig : opts.config)
    vi.mocked(isDaemonRunning).mockReturnValue(opts.daemonRunning ?? false)
    vi.mocked(getQueueCount).mockReturnValue(opts.queueCount ?? 0)
    vi.mocked(readDaemonState).mockReturnValue(opts.state ?? null)
    vi.mocked(formatRelativeTime).mockReturnValue('30s ago')

    const { buildLines } = await import('../commands/dashboard.js')
    return buildLines
  }

  it('shows project name and email in header', async () => {
    const buildLines = await setup({ daemonRunning: false })
    const lines = stripLines(buildLines(false))
    expect(lines[0]).toContain('My Project')
    expect(lines[0]).toContain('user@example.com')
  })

  it('shows fallback when no config', async () => {
    const buildLines = await setup({ config: null, daemonRunning: false })
    const lines = stripLines(buildLines(false))
    expect(lines[0]).toContain('not configured')
  })

  it('shows not-running state when daemon is stopped', async () => {
    const buildLines = await setup({ daemonRunning: false })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).toContain('not running')
    expect(lines.join('\n')).toContain('conductor start')
  })

  it('shows running status and PID when daemon is up with state', async () => {
    const buildLines = await setup({ daemonRunning: true, state: validState })
    const lines = stripLines(buildLines(false))
    const content = lines.join('\n')
    expect(content).toContain('running')
    expect(content).toContain('12345')
  })

  it('shows unknown PID when daemon runs but state file is missing', async () => {
    const buildLines = await setup({ daemonRunning: true, state: null })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).toContain('unknown')
  })

  it('shows queue count when items are pending', async () => {
    const buildLines = await setup({ daemonRunning: true, queueCount: 29, state: validState })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).toContain('29')
  })

  it('shows poll mode and interval', async () => {
    const buildLines = await setup({ daemonRunning: true, state: validState })
    const lines = stripLines(buildLines(false))
    const content = lines.join('\n')
    expect(content).toContain('active')
    expect(content).toContain('5s interval')
  })

  it('shows 60s interval for idle poll mode', async () => {
    const buildLines = await setup({
      daemonRunning: true,
      state: { ...validState, pollMode: 'idle' },
    })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).toContain('60s interval')
  })

  it('shows error count when consecutive errors > 0', async () => {
    const buildLines = await setup({
      daemonRunning: true,
      state: { ...validState, consecutiveErrors: 3 },
    })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).toContain('3 consecutive')
  })

  it('shows no active runs placeholder', async () => {
    const buildLines = await setup({ daemonRunning: true, state: validState })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).toContain('No active runs')
  })

  it('renders run table with running status', async () => {
    const stateWithRun = {
      ...validState,
      activeRuns: [{
        runId: 'run_abc123',
        issueTitle: 'Fix the bug',
        jobName: 'code-gen',
        status: 'running' as const,
        startedAt: new Date(Date.now() - 45_000).toISOString(),
      }],
    }
    const buildLines = await setup({ daemonRunning: true, state: stateWithRun })
    const lines = stripLines(buildLines(false))
    const content = lines.join('\n')
    expect(content).toContain('run_abc1')   // first 8 chars of runId
    expect(content).toContain('Fix the bug')
    expect(content).toContain('running')
    expect(content).toContain('45s')
  })

  it('renders completed run with done status', async () => {
    const stateWithRun = {
      ...validState,
      activeRuns: [{
        runId: 'run_done',
        issueTitle: 'Merged feature',
        jobName: 'code-gen',
        status: 'completed' as const,
        startedAt: new Date(Date.now() - 3 * 60 * 1000).toISOString(),
      }],
    }
    const buildLines = await setup({ daemonRunning: true, state: stateWithRun })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).toContain('done')
  })

  it('renders failed run with failed status', async () => {
    const stateWithRun = {
      ...validState,
      activeRuns: [{
        runId: 'run_fail',
        issueTitle: 'Broken build',
        jobName: 'code-gen',
        status: 'failed' as const,
        startedAt: new Date(Date.now() - 60_000).toISOString(),
      }],
    }
    const buildLines = await setup({ daemonRunning: true, state: stateWithRun })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).toContain('failed')
  })

  it('truncates long issue titles in run table', async () => {
    const longTitle = 'A'.repeat(50)
    const stateWithRun = {
      ...validState,
      activeRuns: [{
        runId: 'run_trunc',
        issueTitle: longTitle,
        jobName: 'code-gen',
        status: 'running' as const,
        startedAt: new Date(Date.now() - 10_000).toISOString(),
      }],
    }
    const buildLines = await setup({ daemonRunning: true, state: stateWithRun })
    const lines = stripLines(buildLines(false))
    const runLine = lines.find(l => l.includes('run_trun'))!
    // Title column is 30 chars wide, should be truncated
    expect(runLine).not.toContain(longTitle)
    expect(runLine).toContain('…')
  })

  it('appends footer with timestamp in live mode', async () => {
    const buildLines = await setup({ daemonRunning: false })
    const liveLines = buildLines(true)
    const staticLines = buildLines(false)
    expect(liveLines.length).toBe(staticLines.length + 1)
    expect(strip(liveLines[liveLines.length - 1])).toContain('Ctrl+C to exit')
  })

  it('does not append footer in non-live mode', async () => {
    const buildLines = await setup({ daemonRunning: false })
    const lines = stripLines(buildLines(false))
    expect(lines.join('\n')).not.toContain('Ctrl+C')
  })
})
