import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'

vi.mock('../lib/config.js', () => ({
  readConfig: vi.fn(),
  CONFIG_PATH: '/mock/home/.conductor/config.json',
}))

vi.mock('fs')

const mockFs = vi.mocked(fs)

describe('doctor command', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('checkConfigFile', () => {
    it('returns true when config file exists', async () => {
      mockFs.existsSync.mockReturnValue(true)

      const { checkConfigFile } = await import('../commands/doctor.js')
      expect(checkConfigFile()).toBe(true)
    })

    it('returns false when config file does not exist', async () => {
      mockFs.existsSync.mockReturnValue(false)

      const { checkConfigFile } = await import('../commands/doctor.js')
      expect(checkConfigFile()).toBe(false)
    })
  })

  describe('checkMcpJson', () => {
    it('returns true when .mcp.json exists in working dir', async () => {
      mockFs.existsSync.mockReturnValue(true)

      const { checkMcpJson } = await import('../commands/doctor.js')
      expect(checkMcpJson('/some/dir')).toBe(true)
      expect(mockFs.existsSync).toHaveBeenCalledWith('/some/dir/.mcp.json')
    })

    it('returns false when .mcp.json is missing', async () => {
      mockFs.existsSync.mockReturnValue(false)

      const { checkMcpJson } = await import('../commands/doctor.js')
      expect(checkMcpJson('/some/dir')).toBe(false)
    })
  })

  describe('checkApiHealth', () => {
    it('returns true when API responds with 200', async () => {
      const mockFetch = vi.fn().mockResolvedValue({ ok: true })
      vi.stubGlobal('fetch', mockFetch)

      const { checkApiHealth } = await import('../commands/doctor.js')
      const result = await checkApiHealth('http://localhost:8080')

      expect(result).toBe(true)
      expect(mockFetch).toHaveBeenCalledWith('http://localhost:8080/api/v1/health')

      vi.unstubAllGlobals()
    })

    it('returns false when API is unreachable', async () => {
      const mockFetch = vi.fn().mockRejectedValue(new Error('ECONNREFUSED'))
      vi.stubGlobal('fetch', mockFetch)

      const { checkApiHealth } = await import('../commands/doctor.js')
      const result = await checkApiHealth('http://localhost:8080')

      expect(result).toBe(false)

      vi.unstubAllGlobals()
    })

    it('returns false when API responds with non-200 status', async () => {
      const mockFetch = vi.fn().mockResolvedValue({ ok: false, status: 503 })
      vi.stubGlobal('fetch', mockFetch)

      const { checkApiHealth } = await import('../commands/doctor.js')
      const result = await checkApiHealth('http://localhost:8080')

      expect(result).toBe(false)

      vi.unstubAllGlobals()
    })
  })
})
