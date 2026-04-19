import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import { Command } from 'commander'

const mockReadConfig = vi.fn()

vi.mock('../lib/config.js', () => ({
  readConfig: mockReadConfig,
  CONFIG_PATH: '/mock/home/.conductor/config.json',
}))

const mockGetPluginInstallStatus = vi.fn()
const mockGetAssetSrcDir = vi.fn()

vi.mock('../lib/plugin-assets.js', () => ({
  getAssetSrcDir: mockGetAssetSrcDir,
  getPluginInstallStatus: mockGetPluginInstallStatus,
}))

vi.mock('../lib/next-steps.js', () => ({
  printNextSteps: vi.fn(),
}))

vi.mock('fs')

const mockFs = vi.mocked(fs)

describe('doctor command', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockGetAssetSrcDir.mockReturnValue('/mock/assets')
    mockGetPluginInstallStatus.mockReturnValue({ location: 'global', outdated: false })
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

  describe('conductor doctor --json', () => {
    it('outputs JSON with checks array and exits 1 when config missing', async () => {
      mockFs.existsSync.mockReturnValue(false)
      mockReadConfig.mockReturnValue(null)

      const writeSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

      const { registerDoctor } = await import('../commands/doctor.js')
      const program = new Command()
      program.exitOverride()
      registerDoctor(program)

      await program.parseAsync(['node', 'conductor', 'doctor', '--json'])

      const output = writeSpy.mock.calls.map(c => c[0]).join('')
      const parsed = JSON.parse(output) as { checks: Array<{ name: string; status: string; message: string }> }
      expect(Array.isArray(parsed.checks)).toBe(true)
      expect(parsed.checks.length).toBeGreaterThan(0)
      expect(parsed.checks.every(c => 'name' in c && 'status' in c && 'message' in c)).toBe(true)

      const configCheck = parsed.checks.find(c => c.name === 'config')
      expect(configCheck?.status).toBe('fail')

      expect(exitSpy).toHaveBeenCalledWith(1)

      writeSpy.mockRestore()
      exitSpy.mockRestore()
    })

    it('outputs JSON with all pass checks and exits 0', async () => {
      mockFs.existsSync.mockReturnValue(true)
      mockReadConfig.mockReturnValue({
        apiKey: 'test-key',
        projectId: 'proj_123',
        projectName: 'Test',
        email: 'user@example.com',
        apiUrl: 'http://localhost:8080',
      })
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))

      const writeSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

      const { registerDoctor } = await import('../commands/doctor.js')
      const program = new Command()
      program.exitOverride()
      registerDoctor(program)

      await program.parseAsync(['node', 'conductor', 'doctor', '--json'])

      const output = writeSpy.mock.calls.map(c => c[0]).join('')
      const parsed = JSON.parse(output) as { checks: Array<{ name: string; status: string; message: string }> }
      const failures = parsed.checks.filter(c => c.status === 'fail')
      expect(failures.length).toBe(0)
      expect(exitSpy).toHaveBeenCalledWith(0)

      vi.unstubAllGlobals()
      writeSpy.mockRestore()
      exitSpy.mockRestore()
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
