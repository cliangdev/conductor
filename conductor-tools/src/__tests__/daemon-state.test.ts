import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

// ─── Mocks ────────────────────────────────────────────────────────────────────

vi.mock('fs')

const mockFs = vi.mocked(fs)

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')
const STATE_FILE = path.join(CONDUCTOR_DIR, 'daemon-state.json')

// ─── Fixtures ─────────────────────────────────────────────────────────────────

const validState = {
  pid: 12345,
  startedAt: '2026-04-15T10:00:00.000Z',
  pollMode: 'idle' as const,
  lastPollAt: null,
  consecutiveErrors: 0,
  eventsThisSession: 0,
  activeRuns: [],
  syncQueueSize: 0,
}

const validStateWithRun = {
  pid: 99999,
  startedAt: '2026-04-15T12:00:00.000Z',
  pollMode: 'active' as const,
  lastPollAt: '2026-04-15T12:05:00.000Z',
  consecutiveErrors: 2,
  eventsThisSession: 7,
  activeRuns: [
    {
      runId: 'run_abc',
      issueTitle: 'Build new feature',
      jobName: 'code-gen',
      status: 'running' as const,
      startedAt: '2026-04-15T12:04:00.000Z',
    },
  ],
  syncQueueSize: 3,
}

// ─── STATE_FILE_PATH ──────────────────────────────────────────────────────────

describe('STATE_FILE_PATH', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('points to ~/.conductor/daemon-state.json', async () => {
    const { STATE_FILE_PATH } = await import('../daemon/state.js')
    expect(STATE_FILE_PATH).toBe(STATE_FILE)
  })
})

// ─── writeDaemonState ─────────────────────────────────────────────────────────

describe('writeDaemonState', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('writes JSON to ~/.conductor/daemon-state.json', async () => {
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { writeDaemonState } = await import('../daemon/state.js')
    writeDaemonState(validState)

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(
      STATE_FILE,
      JSON.stringify(validState, null, 2),
      'utf8'
    )
  })

  it('creates the ~/.conductor directory if it does not exist', async () => {
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { writeDaemonState } = await import('../daemon/state.js')
    writeDaemonState(validState)

    expect(mockFs.mkdirSync).toHaveBeenCalledWith(CONDUCTOR_DIR, { recursive: true })
  })

  it('serializes activeRuns correctly', async () => {
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { writeDaemonState } = await import('../daemon/state.js')
    writeDaemonState(validStateWithRun)

    const writtenJson = mockFs.writeFileSync.mock.calls[0][1] as string
    const parsed = JSON.parse(writtenJson)
    expect(parsed.activeRuns).toHaveLength(1)
    expect(parsed.activeRuns[0].runId).toBe('run_abc')
    expect(parsed.activeRuns[0].status).toBe('running')
  })
})

// ─── readDaemonState ──────────────────────────────────────────────────────────

describe('readDaemonState', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('returns null when file does not exist', async () => {
    mockFs.readFileSync.mockImplementation(() => {
      throw Object.assign(new Error('ENOENT: no such file or directory'), { code: 'ENOENT' })
    })

    const { readDaemonState } = await import('../daemon/state.js')
    const result = readDaemonState()
    expect(result).toBeNull()
  })

  it('returns null when file contains invalid JSON', async () => {
    mockFs.readFileSync.mockReturnValue('not-valid-json')

    const { readDaemonState } = await import('../daemon/state.js')
    const result = readDaemonState()
    expect(result).toBeNull()
  })

  it('returns parsed DaemonState when file is valid', async () => {
    mockFs.readFileSync.mockReturnValue(JSON.stringify(validState))

    const { readDaemonState } = await import('../daemon/state.js')
    const result = readDaemonState()
    expect(result).toEqual(validState)
  })

  it('returns state with activeRuns populated', async () => {
    mockFs.readFileSync.mockReturnValue(JSON.stringify(validStateWithRun))

    const { readDaemonState } = await import('../daemon/state.js')
    const result = readDaemonState()
    expect(result).not.toBeNull()
    expect(result!.activeRuns).toHaveLength(1)
    expect(result!.activeRuns[0].jobName).toBe('code-gen')
  })

  it('reads from the correct file path', async () => {
    mockFs.readFileSync.mockReturnValue(JSON.stringify(validState))

    const { readDaemonState } = await import('../daemon/state.js')
    readDaemonState()

    expect(mockFs.readFileSync).toHaveBeenCalledWith(STATE_FILE, 'utf8')
  })
})

// ─── deleteDaemonState ────────────────────────────────────────────────────────

describe('deleteDaemonState', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('removes the state file', async () => {
    mockFs.unlinkSync.mockReturnValue(undefined)

    const { deleteDaemonState } = await import('../daemon/state.js')
    deleteDaemonState()

    expect(mockFs.unlinkSync).toHaveBeenCalledWith(STATE_FILE)
  })

  it('does not throw when file does not exist (ENOENT)', async () => {
    mockFs.unlinkSync.mockImplementation(() => {
      throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' })
    })

    const { deleteDaemonState } = await import('../daemon/state.js')
    expect(() => deleteDaemonState()).not.toThrow()
  })

  it('re-throws errors that are not ENOENT', async () => {
    mockFs.unlinkSync.mockImplementation(() => {
      throw Object.assign(new Error('EACCES: permission denied'), { code: 'EACCES' })
    })

    const { deleteDaemonState } = await import('../daemon/state.js')
    expect(() => deleteDaemonState()).toThrow('EACCES')
  })
})
