import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

vi.mock('fs')
vi.mock('child_process')

const mockReadConfig = vi.fn()
vi.mock('../lib/config.js', () => ({ readConfig: mockReadConfig, CONFIG_PATH: '/tmp/conductor-config.json' }))
vi.mock('../lib/plugin-assets.js', () => ({ refreshInstalledPluginAssets: vi.fn() }))

const mockReadWatchRegistry = vi.fn()
vi.mock('../daemon/watch-registry.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../daemon/watch-registry.js')>()
  return { ...actual, readWatchRegistry: mockReadWatchRegistry, writeWatchRegistry: vi.fn(), deleteWatchRegistry: vi.fn() }
})
const mockReadDaemonState = vi.fn()
vi.mock('../daemon/state.js', () => ({
  readDaemonState: mockReadDaemonState,
  writeDaemonState: vi.fn(),
  deleteDaemonState: vi.fn(),
}))

const mockFs = vi.mocked(fs)
const mockChildProcess = vi.mocked(await import('child_process'))

const PID_FILE = path.join(os.homedir(), '.conductor', 'daemon.pid')
const NEW_REPO = '/home/user/new-repo/.conductor/issues'

const baseConfig = {
  apiKey: 'k',
  projectId: 'proj_a',
  projectName: 'A',
  email: 'a@example.com',
  apiUrl: 'http://localhost:8080',
}

describe('reloadWatchPaths', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.spyOn(console, 'log').mockImplementation(() => undefined)
    mockFs.readdirSync.mockReturnValue([])
  })
  afterEach(() => vi.restoreAllMocks())

  const fakeWatcher = () => ({ add: vi.fn(), unwatch: vi.fn() })

  it('starts watching a repo that conductor init added to the projects map', async () => {
    const { reloadWatchPaths } = await import('../daemon/watcher.js')
    const watcher = fakeWatcher()
    const current = new Set(['/home/user/a/.conductor/issues'])
    const result = reloadWatchPaths(watcher, current, {
      ...baseConfig,
      projects: {
        proj_a: { localPath: '/home/user/a', projectName: 'A' },
        proj_b: { localPath: '/home/user/new-repo', projectName: 'B' },
      },
    })
    expect(result.added).toEqual([NEW_REPO])
    expect(watcher.add).toHaveBeenCalledWith([NEW_REPO])
    expect(watcher.unwatch).not.toHaveBeenCalled()
    expect(current.has(NEW_REPO)).toBe(true)
  })

  it('stops watching a repo dropped from the projects map', async () => {
    const { reloadWatchPaths } = await import('../daemon/watcher.js')
    const watcher = fakeWatcher()
    const current = new Set(['/home/user/a/.conductor/issues', NEW_REPO])
    reloadWatchPaths(watcher, current, {
      ...baseConfig,
      projects: { proj_a: { localPath: '/home/user/a', projectName: 'A' } },
    })
    expect(watcher.unwatch).toHaveBeenCalledWith([NEW_REPO])
    expect([...current]).toEqual(['/home/user/a/.conductor/issues'])
  })

  it('changes nothing when the config cannot be read or lists no folders', async () => {
    const { reloadWatchPaths } = await import('../daemon/watcher.js')
    const watcher = fakeWatcher()
    const current = new Set(['/home/user/a/.conductor/issues'])
    reloadWatchPaths(watcher, current, null)
    reloadWatchPaths(watcher, current, { ...baseConfig, projects: {} })
    expect(watcher.add).not.toHaveBeenCalled()
    expect(watcher.unwatch).not.toHaveBeenCalled()
    expect(current.size).toBe(1)
  })

  it('is a no-op when the watch set already matches', async () => {
    const { reloadWatchPaths } = await import('../daemon/watcher.js')
    const watcher = fakeWatcher()
    const current = new Set(['/home/user/a/.conductor/issues'])
    const result = reloadWatchPaths(watcher, current, {
      ...baseConfig,
      projects: { proj_a: { localPath: '/home/user/a', projectName: 'A' } },
    })
    expect(result).toEqual({ added: [], removed: [] })
  })
})

describe('ensureDaemonWatching', () => {
  let pidFileExists: boolean
  let pidFileContents: string
  let alive: Set<number>

  beforeEach(() => {
    vi.resetModules()
    vi.spyOn(console, 'log').mockImplementation(() => undefined)
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    mockReadConfig.mockReturnValue(baseConfig)
    mockReadWatchRegistry.mockReset()
    mockReadDaemonState.mockReset()

    pidFileExists = true
    pidFileContents = '111'
    alive = new Set([111])

    mockFs.existsSync.mockImplementation((p) => (p === PID_FILE ? pidFileExists : false))
    mockFs.readFileSync.mockImplementation(((p: fs.PathOrFileDescriptor) => {
      if (p === PID_FILE) return pidFileContents
      throw new Error('ENOENT')
    }) as typeof fs.readFileSync)
    mockFs.unlinkSync.mockImplementation((p) => {
      if (p === PID_FILE) pidFileExists = false
    })
    mockFs.writeFileSync.mockImplementation(((p: fs.PathOrFileDescriptor, data: string) => {
      if (p === PID_FILE) {
        pidFileExists = true
        pidFileContents = data
      }
    }) as typeof fs.writeFileSync)
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.openSync.mockReturnValue(3 as unknown as number)

    vi.spyOn(process, 'kill').mockImplementation(((pid: number, signal?: string | number) => {
      if (signal === 'SIGTERM') {
        alive.delete(pid)
        return true
      }
      if (!alive.has(pid)) throw new Error('ESRCH')
      return true
    }) as typeof process.kill)

    mockChildProcess.spawn.mockReset()
    mockChildProcess.spawn.mockImplementation((() => {
      alive.add(222)
      return { pid: 222, unref: vi.fn() }
    }) as unknown as typeof import('child_process').spawn)
  })
  afterEach(() => vi.restoreAllMocks())

  const run = async () => {
    const { ensureDaemonWatching } = await import('../commands/start.js')
    return ensureDaemonWatching(NEW_REPO, { waitMs: 0, pollMs: 1 })
  }

  it('starts a daemon when none is running', async () => {
    pidFileExists = false
    alive.clear()
    expect(await run()).toBe('started')
    expect(mockChildProcess.spawn).toHaveBeenCalledTimes(1)
  })

  it('leaves a daemon alone once it reports watching the new repo', async () => {
    mockReadWatchRegistry.mockReturnValue({ pid: 111, paths: [NEW_REPO], updatedAt: '' })
    expect(await run()).toBe('watching')
    expect(process.kill).not.toHaveBeenCalledWith(111, 'SIGTERM')
    expect(mockChildProcess.spawn).not.toHaveBeenCalled()
  })

  it('restarts an older daemon that never picks up the new repo', async () => {
    mockReadWatchRegistry.mockReturnValue(null)
    mockReadDaemonState.mockReturnValue({ activeRuns: [] })
    expect(await run()).toBe('restarted')
    expect(process.kill).toHaveBeenCalledWith(111, 'SIGTERM')
    expect(mockChildProcess.spawn).toHaveBeenCalledTimes(1)
  })

  it('does not trust a registry left behind by a different daemon process', async () => {
    mockReadWatchRegistry.mockReturnValue({ pid: 999, paths: [NEW_REPO], updatedAt: '' })
    mockReadDaemonState.mockReturnValue({ activeRuns: [] })
    expect(await run()).toBe('restarted')
  })

  it('does not kill a daemon that is running a workflow job', async () => {
    mockReadWatchRegistry.mockReturnValue(null)
    mockReadDaemonState.mockReturnValue({
      activeRuns: [{ runId: 'r', issueTitle: 't', jobName: 'j', status: 'running', startedAt: '' }],
    })
    expect(await run()).toBe('busy')
    expect(process.kill).not.toHaveBeenCalledWith(111, 'SIGTERM')
    expect(mockChildProcess.spawn).not.toHaveBeenCalled()
  })
})
