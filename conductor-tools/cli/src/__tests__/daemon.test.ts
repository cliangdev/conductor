import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { Command } from 'commander'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

// ─── Mocks ───────────────────────────────────────────────────────────────────

vi.mock('fs')
vi.mock('child_process')

const mockFs = vi.mocked(fs)
const mockChildProcess = vi.mocked(await import('child_process'))

const mockReadConfig = vi.fn()
vi.mock('../lib/config.js', () => ({ readConfig: mockReadConfig }))

const mockConfig = {
  apiKey: 'test-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'test@example.com',
  apiUrl: 'http://localhost:8080',
}

const CONDUCTOR_DIR = path.join(os.homedir(), '.conductor')
const PID_FILE = path.join(CONDUCTOR_DIR, 'daemon.pid')

function makeProgram() {
  const program = new Command()
  program.exitOverride()
  return program
}

// ─── T35: start command ───────────────────────────────────────────────────────

describe('conductor start', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    mockReadConfig.mockReturnValue(mockConfig)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('creates daemon.pid file with spawned process PID', async () => {
    mockFs.existsSync.mockReturnValue(false)
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.openSync.mockReturnValue(3 as unknown as number)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const fakeDaemon = {
      pid: 12345,
      unref: vi.fn(),
    }
    mockChildProcess.spawn.mockReturnValue(fakeDaemon as ReturnType<typeof import('child_process').spawn>)

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerStart } = await import('../commands/start.js')
    const program = makeProgram()
    registerStart(program)

    await program.parseAsync(['node', 'conductor', 'start'])

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(PID_FILE, '12345', 'utf8')
    expect(fakeDaemon.unref).toHaveBeenCalled()
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('12345'))

    consoleSpy.mockRestore()
  })

  it('exits 1 when not authenticated', async () => {
    mockReadConfig.mockReturnValue(null)

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerStart } = await import('../commands/start.js')
    const program = makeProgram()
    registerStart(program)

    await program.parseAsync(['node', 'conductor', 'start'])

    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('skips spawn when daemon is already running', async () => {
    mockFs.existsSync.mockReturnValue(true)
    mockFs.readFileSync.mockReturnValue('99999')

    const killSpy = vi.spyOn(process, 'kill').mockImplementation(() => true)
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerStart } = await import('../commands/start.js')
    const program = makeProgram()
    registerStart(program)

    await program.parseAsync(['node', 'conductor', 'start'])

    expect(mockChildProcess.spawn).not.toHaveBeenCalled()
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('99999'))

    killSpy.mockRestore()
    consoleSpy.mockRestore()
  })

  it('removes stale pid file and starts daemon when previous process is gone', async () => {
    mockFs.existsSync.mockReturnValue(true)
    mockFs.readFileSync.mockReturnValue('88888')
    mockFs.unlinkSync.mockReturnValue(undefined)
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.openSync.mockReturnValue(3 as unknown as number)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const killSpy = vi.spyOn(process, 'kill').mockImplementation(() => {
      throw new Error('ESRCH')
    })

    const fakeDaemon = { pid: 77777, unref: vi.fn() }
    mockChildProcess.spawn.mockReturnValue(fakeDaemon as ReturnType<typeof import('child_process').spawn>)

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerStart } = await import('../commands/start.js')
    const program = makeProgram()
    registerStart(program)

    await program.parseAsync(['node', 'conductor', 'start'])

    expect(mockFs.unlinkSync).toHaveBeenCalledWith(PID_FILE)
    expect(mockFs.writeFileSync).toHaveBeenCalledWith(PID_FILE, '77777', 'utf8')

    killSpy.mockRestore()
    consoleSpy.mockRestore()
  })
})

// ─── T35: stop command ────────────────────────────────────────────────────────

describe('conductor stop', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('sends SIGTERM and removes pid file when daemon is running', async () => {
    mockFs.existsSync.mockReturnValue(true)
    mockFs.readFileSync.mockReturnValue('54321')
    mockFs.unlinkSync.mockReturnValue(undefined)

    const killSpy = vi.spyOn(process, 'kill').mockImplementation(() => true)
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerStop } = await import('../commands/stop.js')
    const program = makeProgram()
    registerStop(program)

    await program.parseAsync(['node', 'conductor', 'stop'])

    expect(killSpy).toHaveBeenCalledWith(54321, 'SIGTERM')
    expect(mockFs.unlinkSync).toHaveBeenCalledWith(PID_FILE)
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('54321'))

    killSpy.mockRestore()
    consoleSpy.mockRestore()
  })

  it('removes stale pid file when process no longer exists', async () => {
    mockFs.existsSync.mockReturnValue(true)
    mockFs.readFileSync.mockReturnValue('54321')
    mockFs.unlinkSync.mockReturnValue(undefined)

    const killSpy = vi.spyOn(process, 'kill').mockImplementation(() => {
      throw new Error('ESRCH')
    })
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerStop } = await import('../commands/stop.js')
    const program = makeProgram()
    registerStop(program)

    await program.parseAsync(['node', 'conductor', 'stop'])

    expect(mockFs.unlinkSync).toHaveBeenCalledWith(PID_FILE)
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('stale'))

    killSpy.mockRestore()
    consoleSpy.mockRestore()
  })

  it('prints message when daemon is not running', async () => {
    mockFs.existsSync.mockReturnValue(false)

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerStop } = await import('../commands/stop.js')
    const program = makeProgram()
    registerStop(program)

    await program.parseAsync(['node', 'conductor', 'stop'])

    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('not running'))

    consoleSpy.mockRestore()
  })
})

// ─── T38: debounce behavior ───────────────────────────────────────────────────

describe('debounce', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('calls function once after delay even with rapid successive calls', async () => {
    const { debounce, debounceTimers } = await import('../daemon/watcher.js')
    debounceTimers.clear()

    const fn = vi.fn()

    debounce('file.txt', fn, 500)
    debounce('file.txt', fn, 500)
    debounce('file.txt', fn, 500)

    expect(fn).not.toHaveBeenCalled()

    vi.advanceTimersByTime(500)

    expect(fn).toHaveBeenCalledTimes(1)
  })

  it('calls function separately for different keys', async () => {
    const { debounce, debounceTimers } = await import('../daemon/watcher.js')
    debounceTimers.clear()

    const fn1 = vi.fn()
    const fn2 = vi.fn()

    debounce('file1.txt', fn1, 500)
    debounce('file2.txt', fn2, 500)

    vi.advanceTimersByTime(500)

    expect(fn1).toHaveBeenCalledTimes(1)
    expect(fn2).toHaveBeenCalledTimes(1)
  })
})

// ─── T38: file sync calls API ─────────────────────────────────────────────────

describe('syncFile', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('reads file and calls PUT API within expected flow', async () => {
    const fileContent = '# Issue content'
    mockFs.readFileSync.mockReturnValue(fileContent)

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({}),
    })
    vi.stubGlobal('fetch', mockFetch)

    // Suppress queue writes on success
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { syncFile } = await import('../daemon/watcher.js')

    const filePath = path.join(os.homedir(), '.conductor', 'proj_123', 'issues', 'iss_abc', 'spec.md')
    await syncFile(filePath, mockConfig)

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/projects/proj_123/issues/iss_abc/documents/spec.md',
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ Authorization: 'Bearer test-key' }),
      })
    )

    vi.unstubAllGlobals()
  })
})

// ─── T40: offline queue ───────────────────────────────────────────────────────

describe('queueChange and replayQueue', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('writes failed sync to sync-queue.json', async () => {
    mockFs.readFileSync.mockReturnValue('[]')
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      text: async () => 'Service Unavailable',
      statusText: 'Service Unavailable',
    })
    vi.stubGlobal('fetch', mockFetch)

    const { syncFile } = await import('../daemon/watcher.js')

    const filePath = path.join(os.homedir(), '.conductor', 'proj_123', 'issues', 'iss_abc', 'spec.md')

    // readFileSync for the file content + readFileSync for the queue
    mockFs.readFileSync
      .mockReturnValueOnce('# content')
      .mockReturnValueOnce('[]')

    await syncFile(filePath, mockConfig)

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(
      path.join(os.homedir(), '.conductor', 'sync-queue.json'),
      expect.stringContaining('PUT'),
      'utf8'
    )

    vi.unstubAllGlobals()
  })

  it('replayQueue replays items and removes successful ones', async () => {
    const pendingEntry = {
      method: 'PUT',
      path: '/api/v1/projects/proj_123/issues/iss_abc/documents/spec.md',
      body: { content: 'hello', filename: 'spec.md' },
      timestamp: '2026-01-01T00:00:00Z',
    }

    mockFs.readFileSync.mockReturnValue(JSON.stringify([pendingEntry]))
    mockFs.unlinkSync.mockReturnValue(undefined)
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({}),
    })
    vi.stubGlobal('fetch', mockFetch)

    const { replayQueue } = await import('../daemon/watcher.js')
    await replayQueue(mockConfig)

    // Successful replay → queue file deleted (unlinkSync called)
    expect(mockFs.unlinkSync).toHaveBeenCalledWith(
      path.join(os.homedir(), '.conductor', 'sync-queue.json')
    )
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v1/projects/proj_123/issues/iss_abc/documents/spec.md',
      expect.objectContaining({ method: 'PUT' })
    )

    vi.unstubAllGlobals()
  })

  it('replayQueue keeps failed items in sync-queue.json', async () => {
    const pendingEntry = {
      method: 'PUT',
      path: '/api/v1/projects/proj_123/issues/iss_abc/documents/spec.md',
      body: { content: 'hello', filename: 'spec.md' },
      timestamp: '2026-01-01T00:00:00Z',
    }

    mockFs.readFileSync.mockReturnValue(JSON.stringify([pendingEntry]))
    mockFs.writeFileSync.mockReturnValue(undefined)
    mockFs.mkdirSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      text: async () => 'Service Unavailable',
      statusText: 'Service Unavailable',
    })
    vi.stubGlobal('fetch', mockFetch)

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    const { replayQueue } = await import('../daemon/watcher.js')
    await replayQueue(mockConfig)

    // Failed replay → queue file rewritten with remaining entries
    expect(mockFs.writeFileSync).toHaveBeenCalledWith(
      path.join(os.homedir(), '.conductor', 'sync-queue.json'),
      expect.stringContaining('spec.md'),
      'utf8'
    )

    consoleSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('replayQueue does nothing when queue is empty', async () => {
    mockFs.readFileSync.mockImplementation(() => {
      throw new Error('ENOENT')
    })

    const mockFetch = vi.fn()
    vi.stubGlobal('fetch', mockFetch)

    const { replayQueue } = await import('../daemon/watcher.js')
    await replayQueue(mockConfig)

    expect(mockFetch).not.toHaveBeenCalled()

    vi.unstubAllGlobals()
  })
})
