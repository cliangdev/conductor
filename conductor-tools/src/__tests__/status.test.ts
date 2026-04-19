import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import { Command } from 'commander'

vi.mock('../lib/config.js', () => ({
  readConfig: vi.fn(),
}))

vi.mock('fs')

const mockFs = vi.mocked(fs)

describe('status command', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('isDaemonRunning', () => {
    it('returns false when pid file does not exist', async () => {
      mockFs.readFileSync.mockImplementation(() => {
        throw new Error('ENOENT')
      })

      const { isDaemonRunning } = await import('../commands/status.js')
      expect(isDaemonRunning()).toBe(false)
    })

    it('returns false when pid file has invalid content', async () => {
      mockFs.readFileSync.mockReturnValue('not-a-number')

      const { isDaemonRunning } = await import('../commands/status.js')
      expect(isDaemonRunning()).toBe(false)
    })

    it('returns true when process is alive', async () => {
      mockFs.readFileSync.mockReturnValue('12345')
      const killSpy = vi.spyOn(process, 'kill').mockReturnValue(true)

      const { isDaemonRunning } = await import('../commands/status.js')
      const result = isDaemonRunning()

      expect(result).toBe(true)
      expect(killSpy).toHaveBeenCalledWith(12345, 0)

      killSpy.mockRestore()
    })

    it('returns false when process does not exist', async () => {
      mockFs.readFileSync.mockReturnValue('99999')
      vi.spyOn(process, 'kill').mockImplementation(() => {
        throw Object.assign(new Error('ESRCH'), { code: 'ESRCH' })
      })

      const { isDaemonRunning } = await import('../commands/status.js')
      expect(isDaemonRunning()).toBe(false)
    })
  })

  describe('getQueueCount', () => {
    it('returns 0 when queue file does not exist', async () => {
      mockFs.readFileSync.mockImplementation(() => {
        throw new Error('ENOENT')
      })

      const { getQueueCount } = await import('../commands/status.js')
      expect(getQueueCount()).toBe(0)
    })

    it('returns 0 when queue file is empty array', async () => {
      mockFs.readFileSync.mockReturnValue('[]')

      const { getQueueCount } = await import('../commands/status.js')
      expect(getQueueCount()).toBe(0)
    })

    it('returns count of items in queue', async () => {
      mockFs.readFileSync.mockReturnValue('[{"id":1},{"id":2},{"id":3}]')

      const { getQueueCount } = await import('../commands/status.js')
      expect(getQueueCount()).toBe(3)
    })

    it('returns 0 when queue file has invalid JSON', async () => {
      mockFs.readFileSync.mockReturnValue('invalid-json')

      const { getQueueCount } = await import('../commands/status.js')
      expect(getQueueCount()).toBe(0)
    })
  })

  describe('getLogFileSizeMb', () => {
    it('returns size in MB when log file exists', async () => {
      mockFs.statSync.mockReturnValue({ size: 5 * 1024 * 1024 } as fs.Stats)

      const { getLogFileSizeMb } = await import('../commands/status.js')
      expect(getLogFileSizeMb()).toBe(5)
    })

    it('returns null when log file does not exist', async () => {
      mockFs.statSync.mockImplementation(() => { throw new Error('ENOENT') })

      const { getLogFileSizeMb } = await import('../commands/status.js')
      expect(getLogFileSizeMb()).toBeNull()
    })

    it('rounds to 1 decimal place', async () => {
      mockFs.statSync.mockReturnValue({ size: 1.55 * 1024 * 1024 } as fs.Stats)

      const { getLogFileSizeMb } = await import('../commands/status.js')
      expect(getLogFileSizeMb()).toBe(1.6)
    })
  })

  describe('conductor status --json', () => {
    beforeEach(() => {
      vi.resetModules()
    })

    it('outputs valid JSON with daemon running=false when no pid file', async () => {
      mockFs.readFileSync.mockImplementation(() => { throw new Error('ENOENT') })
      mockFs.statSync.mockImplementation(() => { throw new Error('ENOENT') })

      const writeSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

      const { registerStatus } = await import('../commands/status.js')
      const program = new Command()
      program.exitOverride()
      registerStatus(program)

      await program.parseAsync(['node', 'conductor', 'status', '--json'])

      expect(exitSpy).toHaveBeenCalledWith(0)
      const output = writeSpy.mock.calls.map(c => c[0]).join('')
      const parsed = JSON.parse(output) as {
        daemon: { running: boolean; pid: number | null; uptime: string | null }
        syncQueue: { size: number }
        log: { path: string; sizeMb: number | null }
      }
      expect(parsed.daemon.running).toBe(false)
      expect(parsed.daemon.pid).toBeNull()
      expect(typeof parsed.syncQueue.size).toBe('number')
      expect(typeof parsed.log.path).toBe('string')

      writeSpy.mockRestore()
      exitSpy.mockRestore()
    })

    it('outputs daemon.running=true when daemon pid file valid and process alive', async () => {
      mockFs.readFileSync.mockReturnValue('12345')
      mockFs.statSync.mockImplementation(() => { throw new Error('ENOENT') })
      const killSpy = vi.spyOn(process, 'kill').mockReturnValue(true)

      const writeSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

      const { registerStatus } = await import('../commands/status.js')
      const program = new Command()
      program.exitOverride()
      registerStatus(program)

      await program.parseAsync(['node', 'conductor', 'status', '--json'])

      const output = writeSpy.mock.calls.map(c => c[0]).join('')
      const parsed = JSON.parse(output) as { daemon: { running: boolean; pid: number | null } }
      expect(parsed.daemon.running).toBe(true)
      expect(parsed.daemon.pid).toBe(12345)

      killSpy.mockRestore()
      writeSpy.mockRestore()
      exitSpy.mockRestore()
    })
  })
})
