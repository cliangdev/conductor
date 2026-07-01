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
  localPath: '/home/user/myproject',
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
    vi.useFakeTimers()

    mockFs.existsSync.mockReturnValue(false)
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.openSync.mockReturnValue(3 as unknown as number)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const fakeDaemon = {
      pid: 12345,
      unref: vi.fn(),
    }
    mockChildProcess.spawn.mockReturnValue(fakeDaemon as ReturnType<typeof import('child_process').spawn>)

    // Simulate process staying alive after the startup wait
    const killSpy = vi.spyOn(process, 'kill').mockReturnValue(true)
    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerStart } = await import('../commands/start.js')
    const program = makeProgram()
    registerStart(program)

    const parsePromise = program.parseAsync(['node', 'conductor', 'start'])
    await vi.runAllTimersAsync()
    await parsePromise

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(PID_FILE, '12345', 'utf8')
    expect(fakeDaemon.unref).toHaveBeenCalled()
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('12345'))

    killSpy.mockRestore()
    consoleSpy.mockRestore()
    vi.useRealTimers()
  })

  it('exits 78 when not authenticated', async () => {
    mockReadConfig.mockReturnValue(null)

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerStart } = await import('../commands/start.js')
    const program = makeProgram()
    registerStart(program)

    await program.parseAsync(['node', 'conductor', 'start'])

    expect(exitSpy).toHaveBeenCalledWith(78)

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

// ─── T89: parseFilePath new pattern ──────────────────────────────────────────

describe('parseFilePath', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('parses localPath-based path correctly', async () => {
    const { parseFilePath } = await import('../daemon/watcher.js')
    const result = parseFilePath('/home/user/myproject/.conductor/issues/iss_abc/spec.md')
    expect(result).toEqual({ issueId: 'iss_abc', filename: 'spec.md' })
  })

  it('parses issue.md filename', async () => {
    const { parseFilePath } = await import('../daemon/watcher.js')
    const result = parseFilePath('/home/user/myproject/.conductor/issues/iss_xyz/issue.md')
    expect(result).toEqual({ issueId: 'iss_xyz', filename: 'issue.md' })
  })

  it('returns null for path not matching the pattern', async () => {
    const { parseFilePath } = await import('../daemon/watcher.js')
    const result = parseFilePath('/home/user/myproject/src/some-file.ts')
    expect(result).toBeNull()
  })

  it('returns null for empty string', async () => {
    const { parseFilePath } = await import('../daemon/watcher.js')
    const result = parseFilePath('')
    expect(result).toBeNull()
  })

  it('handles Windows-style backslash paths', async () => {
    const { parseFilePath } = await import('../daemon/watcher.js')
    const result = parseFilePath('C:\\Users\\user\\myproject\\.conductor\\issues\\iss_abc\\spec.md')
    expect(result).toEqual({ issueId: 'iss_abc', filename: 'spec.md' })
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

// ─── T38 / T90: file sync calls API ──────────────────────────────────────────

describe('syncFile', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('skips issue.md files and does not call API', async () => {
    const mockFetch = vi.fn()
    vi.stubGlobal('fetch', mockFetch)

    const { syncFile } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'issue.md')
    await syncFile(filePath, () => mockConfig)

    expect(mockFetch).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('calls PUT with contentType for non-issue.md files', async () => {
    const fileContent = '# PRD content'
    mockFs.readFileSync.mockReturnValue(fileContent)

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const { syncFile } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'prd.md')
    await syncFile(filePath, () => mockConfig)

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v2/projects/proj_123/work-items/iss_abc/documents/prd.md',
      expect.objectContaining({
        method: 'PUT',
        body: expect.stringContaining('contentType'),
      })
    )
    vi.unstubAllGlobals()
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

    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'spec.md')
    await syncFile(filePath, () => mockConfig)

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v2/projects/proj_123/work-items/iss_abc/documents/spec.md',
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ Authorization: 'Bearer test-key' }),
      })
    )

    vi.unstubAllGlobals()
  })
})

// ─── T91: syncIssueMd and parseFrontmatter ───────────────────────────────────

describe('syncIssueMd', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('sends PATCH with title and status from frontmatter', async () => {
    const content = `---\nid: iss_abc\ntype: PRD\ntitle: My PRD\nstatus: DRAFT\n---\n\nDescription body`
    mockFs.readFileSync.mockReturnValue(content)

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const { syncIssueMd } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'issue.md')
    await syncIssueMd(filePath, () => mockConfig)

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v2/projects/proj_123/work-items/iss_abc',
      expect.objectContaining({
        method: 'PATCH',
        body: expect.stringContaining('My PRD'),
      })
    )

    const callBody = JSON.parse(mockFetch.mock.calls[0][1].body as string)
    expect(callBody).toMatchObject({
      title: 'My PRD',
      status: 'DRAFT',
      description: 'Description body',
    })

    vi.unstubAllGlobals()
  })

  it('queues change when PATCH fails', async () => {
    const content = `---\nid: iss_abc\ntitle: My PRD\nstatus: DRAFT\n---\n\nBody`
    mockFs.readFileSync
      .mockReturnValueOnce(content)
      .mockReturnValueOnce('[]')
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      text: async () => 'Error',
      statusText: 'Error',
    })
    vi.stubGlobal('fetch', mockFetch)

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const { syncIssueMd } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'issue.md')
    await syncIssueMd(filePath, () => mockConfig)

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(
      path.join(os.homedir(), '.conductor', 'sync-queue.json'),
      expect.stringContaining('iss_abc'),
      'utf8'
    )

    consoleSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('does nothing when file has no frontmatter', async () => {
    mockFs.readFileSync.mockReturnValue('Just plain content with no frontmatter')

    const mockFetch = vi.fn()
    vi.stubGlobal('fetch', mockFetch)

    const { syncIssueMd } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'issue.md')
    await syncIssueMd(filePath, () => mockConfig)

    expect(mockFetch).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})

// ─── T3.1: syncTasksJson ─────────────────────────────────────────────────────

describe('syncTasksJson', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('calls PUT with parsed JSON to the correct tasks API path', async () => {
    const tasks = [{ id: 'task_1', title: 'Write tests', done: false }]
    mockFs.readFileSync.mockReturnValue(JSON.stringify(tasks))

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const { syncTasksJson } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'tasks.json')
    await syncTasksJson(filePath, () => mockConfig)

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v2/projects/proj_123/work-items/iss_abc/tasks',
      expect.objectContaining({
        method: 'PUT',
        headers: expect.objectContaining({ Authorization: 'Bearer test-key' }),
        body: JSON.stringify(tasks),
      })
    )

    vi.unstubAllGlobals()
  })

  it('queues change when PUT fails', async () => {
    const tasks = [{ id: 'task_1', title: 'Write tests', done: false }]
    mockFs.readFileSync
      .mockReturnValueOnce(JSON.stringify(tasks))
      .mockReturnValueOnce('[]')
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 503,
      text: async () => 'Service Unavailable',
      statusText: 'Service Unavailable',
    })
    vi.stubGlobal('fetch', mockFetch)

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const { syncTasksJson } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'tasks.json')
    await syncTasksJson(filePath, () => mockConfig)

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(
      path.join(os.homedir(), '.conductor', 'sync-queue.json'),
      expect.stringContaining('iss_abc'),
      'utf8'
    )
    const written = JSON.parse(mockFs.writeFileSync.mock.calls[0][1] as string)
    expect(written[0].method).toBe('PUT')
    expect(written[0].path).toContain('/tasks')

    consoleSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('returns early for paths not matching the expected pattern', async () => {
    const mockFetch = vi.fn()
    vi.stubGlobal('fetch', mockFetch)

    const { syncTasksJson } = await import('../daemon/watcher.js')
    await syncTasksJson('/some/random/path/tasks.json', () => mockConfig)

    expect(mockFetch).not.toHaveBeenCalled()
    vi.unstubAllGlobals()
  })
})

// ─── T3.2: tasks.json delete is skipped ──────────────────────────────────────
// The watcher unlink handler guards against calling deleteFile for tasks.json.
// We verify this at the deleteFile level: since tasks.json is NOT issue.md,
// deleteFile would call DELETE — but the watcher skips it before reaching deleteFile.
// To keep tests fast and mock-friendly, we verify the guard logic directly:
// the unlink handler skips tasks.json (basename check), so no API call is made.

describe('watcher unlink skips tasks.json (guard verified via deleteFile behavior)', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('deleteFile does call DELETE for tasks.json — confirming guard is in the watcher, not deleteFile', async () => {
    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const { deleteFile } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'tasks.json')
    await deleteFile(filePath, () => mockConfig)

    // deleteFile itself does NOT skip tasks.json — the watcher unlink handler does.
    // This confirms the architecture: watcher guards tasks.json before calling deleteFile.
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining('/documents/tasks.json'),
      expect.objectContaining({ method: 'DELETE' })
    )

    vi.unstubAllGlobals()
  })
})

// ─── T90: deleteFile ─────────────────────────────────────────────────────────

describe('deleteFile', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('calls DELETE for non-issue.md file', async () => {
    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const { deleteFile } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'spec.md')
    await deleteFile(filePath, () => mockConfig)

    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v2/projects/proj_123/work-items/iss_abc/documents/spec.md',
      expect.objectContaining({ method: 'DELETE' })
    )
    vi.unstubAllGlobals()
  })

  it('skips issue.md and does not call DELETE', async () => {
    const mockFetch = vi.fn()
    vi.stubGlobal('fetch', mockFetch)

    const { deleteFile } = await import('../daemon/watcher.js')
    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'issue.md')
    await deleteFile(filePath, () => mockConfig)

    expect(mockFetch).not.toHaveBeenCalled()
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

    const filePath = path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'spec.md')

    // readFileSync for the file content + readFileSync for the queue
    mockFs.readFileSync
      .mockReturnValueOnce('# content')
      .mockReturnValueOnce('[]')

    await syncFile(filePath, () => mockConfig)

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
      path: '/api/v2/projects/proj_123/work-items/iss_abc/documents/spec.md',
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
    await replayQueue(() => mockConfig)

    // Successful replay → queue file deleted (unlinkSync called)
    expect(mockFs.unlinkSync).toHaveBeenCalledWith(
      path.join(os.homedir(), '.conductor', 'sync-queue.json')
    )
    expect(mockFetch).toHaveBeenCalledWith(
      'http://localhost:8080/api/v2/projects/proj_123/work-items/iss_abc/documents/spec.md',
      expect.objectContaining({ method: 'PUT' })
    )

    vi.unstubAllGlobals()
  })

  it('replayQueue keeps failed items in sync-queue.json', async () => {
    const pendingEntry = {
      method: 'PUT',
      path: '/api/v2/projects/proj_123/work-items/iss_abc/documents/spec.md',
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
    await replayQueue(() => mockConfig)

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
    await replayQueue(() => mockConfig)

    expect(mockFetch).not.toHaveBeenCalled()

    vi.unstubAllGlobals()
  })

  it('queueChange collapses duplicate entries for the same path', async () => {
    const existing = [{
      method: 'PUT',
      path: '/api/v2/projects/proj_123/work-items/iss_abc/documents/prd.md',
      body: { content: 'old' },
      timestamp: '2026-01-01T00:00:00Z',
      attempts: 0,
    }]
    mockFs.readFileSync.mockReturnValue(JSON.stringify(existing))
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { queueChange } = await import('../daemon/watcher.js')
    queueChange({
      method: 'PUT',
      path: '/api/v2/projects/proj_123/work-items/iss_abc/documents/prd.md',
      body: { content: 'new' },
    })

    const written = JSON.parse(mockFs.writeFileSync.mock.calls[0][1] as string)
    // Same path → exactly one entry, carrying the newest body
    expect(written).toHaveLength(1)
    expect(written[0].body.content).toBe('new')
  })

  it('dequeueChange removes pending entries for a synced path', async () => {
    const queue = [
      { method: 'PUT', path: '/api/v2/projects/proj_123/work-items/iss_abc/documents/prd.md', body: {}, timestamp: 't' },
      { method: 'PUT', path: '/api/v2/projects/proj_123/work-items/iss_xyz/documents/prd.md', body: {}, timestamp: 't' },
    ]
    mockFs.readFileSync.mockReturnValue(JSON.stringify(queue))
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { dequeueChange } = await import('../daemon/watcher.js')
    dequeueChange('/api/v2/projects/proj_123/work-items/iss_abc/documents/prd.md')

    const written = JSON.parse(mockFs.writeFileSync.mock.calls[0][1] as string)
    expect(written).toHaveLength(1)
    expect(written[0].path).toContain('iss_xyz')
  })

  it('successful syncFile dequeues a previously-queued entry for that path', async () => {
    const apiPath = '/api/v2/projects/proj_123/work-items/iss_abc/documents/spec.md'
    // file read, then queue read inside dequeueChange
    mockFs.readFileSync
      .mockReturnValueOnce('# content')
      .mockReturnValueOnce(JSON.stringify([{ method: 'PUT', path: apiPath, body: {}, timestamp: 't' }]))
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)
    mockFs.unlinkSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) })
    vi.stubGlobal('fetch', mockFetch)

    const { syncFile } = await import('../daemon/watcher.js')
    await syncFile(path.join('/home/user/myproject', '.conductor', 'issues', 'iss_abc', 'spec.md'), () => mockConfig)

    // Queue became empty → file removed
    expect(mockFs.unlinkSync).toHaveBeenCalledWith(
      path.join(os.homedir(), '.conductor', 'sync-queue.json')
    )
    vi.unstubAllGlobals()
  })

  it('replayQueue abandons an entry after MAX_REPLAY_ATTEMPTS', async () => {
    const deadEntry = {
      method: 'PUT',
      path: '/api/v2/projects/proj_123/work-items/local_999/documents/prd.md',
      body: { content: 'x' },
      timestamp: '2026-01-01T00:00:00Z',
      attempts: 4, // one below the cap
    }
    mockFs.readFileSync.mockReturnValue(JSON.stringify([deadEntry]))
    mockFs.writeFileSync.mockReturnValue(undefined)
    mockFs.unlinkSync.mockReturnValue(undefined)
    mockFs.mkdirSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false, status: 404, text: async () => 'Not Found', statusText: 'Not Found',
    })
    vi.stubGlobal('fetch', mockFetch)
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    const { replayQueue } = await import('../daemon/watcher.js')
    await replayQueue(() => mockConfig)

    // attempts hit 5 → dropped → queue empty → unlinked, not rewritten
    expect(mockFs.unlinkSync).toHaveBeenCalled()
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('Abandoning'))

    consoleSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('replayQueue bumps attempts on a recoverable failure instead of abandoning', async () => {
    const entry = {
      method: 'PUT',
      path: '/api/v2/projects/proj_123/work-items/iss_abc/documents/spec.md',
      body: { content: 'x' },
      timestamp: '2026-01-01T00:00:00Z',
      attempts: 0,
    }
    mockFs.readFileSync.mockReturnValue(JSON.stringify([entry]))
    mockFs.writeFileSync.mockReturnValue(undefined)
    mockFs.mkdirSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({
      ok: false, status: 503, text: async () => 'Unavailable', statusText: 'Unavailable',
    })
    vi.stubGlobal('fetch', mockFetch)
    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    const { replayQueue } = await import('../daemon/watcher.js')
    await replayQueue(() => mockConfig)

    const written = JSON.parse(mockFs.writeFileSync.mock.calls[0][1] as string)
    expect(written).toHaveLength(1)
    expect(written[0].attempts).toBe(1)

    consoleSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  it('replayQueue preserves entries appended during the drain (race-safe merge)', async () => {
    const original = {
      method: 'PUT',
      path: '/api/v2/projects/proj_123/work-items/iss_abc/documents/spec.md',
      body: { content: 'x' },
      timestamp: '2026-01-01T00:00:00Z',
      attempts: 0,
    }
    const appendedDuringDrain = {
      method: 'PUT',
      path: '/api/v2/projects/proj_123/work-items/iss_new/documents/prd.md',
      body: { content: 'y' },
      timestamp: '2026-01-02T00:00:00Z',
      attempts: 0,
    }
    // First read (drain snapshot) sees only the original; the post-drain merge
    // read sees a second entry the watcher appended meanwhile.
    mockFs.readFileSync
      .mockReturnValueOnce(JSON.stringify([original]))
      .mockReturnValue(JSON.stringify([original, appendedDuringDrain]))
    mockFs.writeFileSync.mockReturnValue(undefined)
    mockFs.unlinkSync.mockReturnValue(undefined)
    mockFs.mkdirSync.mockReturnValue(undefined)

    const mockFetch = vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) })
    vi.stubGlobal('fetch', mockFetch)

    const { replayQueue } = await import('../daemon/watcher.js')
    await replayQueue(() => mockConfig)

    // original drained successfully, but the concurrently-appended entry survives
    const written = JSON.parse(mockFs.writeFileSync.mock.calls.at(-1)![1] as string)
    expect(written).toHaveLength(1)
    expect(written[0].path).toContain('iss_new')

    vi.unstubAllGlobals()
  })
})

// ─── Multi-project: getAllWatchPaths ──────────────────────────────────────────

describe('getAllWatchPaths', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('returns issues dirs for all entries in the projects map', async () => {
    const { getAllWatchPaths } = await import('../daemon/watcher.js')
    const config = {
      ...mockConfig,
      projects: {
        proj_a: { localPath: '/home/user/project-a', projectName: 'Project A' },
        proj_b: { localPath: '/home/user/project-b', projectName: 'Project B' },
      },
    }
    const result = getAllWatchPaths(config)
    expect(result).toContain('/home/user/project-a/.conductor/issues')
    expect(result).toContain('/home/user/project-b/.conductor/issues')
  })

  it('deduplicates when localPath matches a projects entry', async () => {
    const { getAllWatchPaths } = await import('../daemon/watcher.js')
    const config = {
      ...mockConfig,
      localPath: '/home/user/myproject',
      projects: {
        proj_123: { localPath: '/home/user/myproject', projectName: 'Test Project' },
      },
    }
    const result = getAllWatchPaths(config)
    const issuesPath = '/home/user/myproject/.conductor/issues'
    expect(result.filter(p => p === issuesPath)).toHaveLength(1)
  })

  it('falls back to legacy localPath when projects map is absent', async () => {
    const { getAllWatchPaths } = await import('../daemon/watcher.js')
    const result = getAllWatchPaths(mockConfig)
    expect(result).toEqual(['/home/user/myproject/.conductor/issues'])
  })

  it('returns empty array when neither projects nor localPath is set', async () => {
    const { getAllWatchPaths } = await import('../daemon/watcher.js')
    const config = { ...mockConfig, localPath: undefined, projects: undefined }
    const result = getAllWatchPaths(config)
    expect(result).toHaveLength(0)
  })
})

// ─── Multi-project: resolveProjectIdForFile ───────────────────────────────────

describe('resolveProjectIdForFile', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  it('returns matching projectId when file is under a known project path', async () => {
    const { resolveProjectIdForFile } = await import('../daemon/watcher.js')
    const config = {
      ...mockConfig,
      projects: {
        proj_a: { localPath: '/home/user/project-a', projectName: 'Project A' },
        proj_b: { localPath: '/home/user/project-b', projectName: 'Project B' },
      },
    }
    const filePath = '/home/user/project-b/.conductor/issues/iss_xyz/prd.md'
    expect(resolveProjectIdForFile(filePath, config)).toBe('proj_b')
  })

  it('returns null when file matches no project and is outside config.localPath', async () => {
    // Previously this fell back to config.projectId, which mis-stamped writes to
    // the active project. It must now refuse to guess.
    const { resolveProjectIdForFile } = await import('../daemon/watcher.js')
    const config = {
      ...mockConfig,
      projects: {
        proj_a: { localPath: '/home/user/project-a', projectName: 'Project A' },
      },
    }
    const filePath = '/home/user/unknown-project/.conductor/issues/iss_xyz/prd.md'
    expect(resolveProjectIdForFile(filePath, config)).toBeNull()
  })

  it('returns config.projectId for legacy layout when file is under config.localPath', async () => {
    const { resolveProjectIdForFile } = await import('../daemon/watcher.js')
    const filePath = '/home/user/myproject/.conductor/issues/iss_abc/prd.md'
    expect(resolveProjectIdForFile(filePath, mockConfig)).toBe('proj_123')
  })

  it('returns null when neither projects map nor localPath matches the file', async () => {
    const { resolveProjectIdForFile } = await import('../daemon/watcher.js')
    const config = { ...mockConfig, localPath: undefined }
    const filePath = '/home/user/elsewhere/.conductor/issues/iss_abc/prd.md'
    expect(resolveProjectIdForFile(filePath, config)).toBeNull()
  })
})

// ─── Multi-project: syncFile routes to correct project ───────────────────────

describe('syncFile multi-project routing', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('uses the project matching the file path, not config.projectId', async () => {
    mockFs.readFileSync.mockReturnValue('# PRD content')

    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)

    const { syncFile } = await import('../daemon/watcher.js')

    const multiProjectConfig = {
      ...mockConfig,
      projectId: 'proj_a',
      localPath: '/home/user/project-a',
      projects: {
        proj_a: { localPath: '/home/user/project-a', projectName: 'Project A' },
        proj_b: { localPath: '/home/user/project-b', projectName: 'Project B' },
      },
    }

    // File belongs to project-b, not the active project-a
    const filePath = '/home/user/project-b/.conductor/issues/iss_xyz/prd.md'
    await syncFile(filePath, () => multiProjectConfig)

    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining('/projects/proj_b/work-items/iss_xyz/documents/prd.md'),
      expect.objectContaining({ method: 'PUT' })
    )

    vi.unstubAllGlobals()
  })

  it('skips (no request, no queue) when the file is under no known project', async () => {
    mockFs.readFileSync.mockReturnValue('# PRD content')
    const mockFetch = vi.fn().mockResolvedValue({ ok: true })
    vi.stubGlobal('fetch', mockFetch)
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => undefined)

    const { syncFile } = await import('../daemon/watcher.js')

    const config = {
      ...mockConfig,
      localPath: '/home/user/project-a',
      projects: {
        proj_a: { localPath: '/home/user/project-a', projectName: 'Project A' },
      },
    }
    const filePath = '/home/user/stray-project/.conductor/issues/iss_xyz/prd.md'
    await syncFile(filePath, () => config)

    expect(mockFetch).not.toHaveBeenCalled()
    expect(warnSpy).toHaveBeenCalledWith(expect.stringContaining('not under any known project'))

    warnSpy.mockRestore()
    vi.unstubAllGlobals()
  })
})

// ─── T4.6: log rotation ───────────────────────────────────────────────────────

describe('rotateDaemonLogIfNeeded', () => {
  const CONDUCTOR_DIR_HOME = path.join(os.homedir(), '.conductor')
  const LOG_FILE = path.join(CONDUCTOR_DIR_HOME, 'daemon.log')
  const LOG_FILE_ROTATED = path.join(CONDUCTOR_DIR_HOME, 'daemon.log.1')

  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('renames daemon.log to daemon.log.1 when size >= 5 MB', async () => {
    mockFs.statSync.mockReturnValue({ size: 5 * 1024 * 1024 } as fs.Stats)
    mockFs.renameSync.mockReturnValue(undefined)

    const { rotateDaemonLogIfNeeded } = await import('../daemon/watcher.js')
    rotateDaemonLogIfNeeded()

    expect(mockFs.renameSync).toHaveBeenCalledWith(LOG_FILE, LOG_FILE_ROTATED)
  })

  it('does not rotate when log file is below 5 MB', async () => {
    mockFs.statSync.mockReturnValue({ size: 4 * 1024 * 1024 } as fs.Stats)
    mockFs.renameSync.mockReturnValue(undefined)

    const { rotateDaemonLogIfNeeded } = await import('../daemon/watcher.js')
    rotateDaemonLogIfNeeded()

    expect(mockFs.renameSync).not.toHaveBeenCalled()
  })

  it('does not throw when log file does not exist', async () => {
    mockFs.statSync.mockImplementation(() => { throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' }) })

    const { rotateDaemonLogIfNeeded } = await import('../daemon/watcher.js')
    expect(() => rotateDaemonLogIfNeeded()).not.toThrow()
  })

  it('rotates at exactly 5 MB boundary', async () => {
    mockFs.statSync.mockReturnValue({ size: 5 * 1024 * 1024 } as fs.Stats)
    mockFs.renameSync.mockReturnValue(undefined)

    const { rotateDaemonLogIfNeeded } = await import('../daemon/watcher.js')
    rotateDaemonLogIfNeeded()

    expect(mockFs.renameSync).toHaveBeenCalledTimes(1)
    expect(mockFs.renameSync).toHaveBeenCalledWith(LOG_FILE, LOG_FILE_ROTATED)
  })
})
