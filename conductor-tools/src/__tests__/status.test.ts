import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'

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
})
