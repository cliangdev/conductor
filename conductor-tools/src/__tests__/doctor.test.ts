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

const mockCheckCodexCli = vi.fn()
vi.mock('../lib/codex-image.js', () => ({ checkCodexCli: mockCheckCodexCli }))

vi.mock('fs')

const mockFs = vi.mocked(fs)

describe('doctor command', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    delete process.env['CONDUCTOR_OPENAI_API_KEY']
    delete process.env['OPENAI_API_KEY']
    delete process.env['CONDUCTOR_IMAGE_MODEL']
    delete process.env['CONDUCTOR_IMAGE_QUALITY']
    mockCheckCodexCli.mockResolvedValue({ state: 'ready', version: 'codex-cli 0.162.1' })
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

  describe('image generation check', () => {
    const runJson = async () => {
      mockFs.existsSync.mockReturnValue(true)
      mockReadConfig.mockReturnValue({ apiKey: 'k', projectId: 'p', projectName: 'T', email: 'e@x.test', apiUrl: 'http://localhost:8080' })
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
      const writeSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)
      const { registerDoctor } = await import('../commands/doctor.js')
      const program = new Command()
      program.exitOverride()
      registerDoctor(program)
      await program.parseAsync(['node', 'conductor', 'doctor', '--json'])
      const parsed = JSON.parse(writeSpy.mock.calls.map(c => c[0]).join('')) as { checks: Array<{ name: string; status: string; message: string }> }
      const exitCode = exitSpy.mock.calls[0]?.[0]
      writeSpy.mockRestore()
      exitSpy.mockRestore()
      vi.unstubAllGlobals()
      return { check: parsed.checks.find(c => c.name === 'image-generation'), exitCode }
    }

    it('passes with the version when installed and logged in', async () => {
      const { check, exitCode } = await runJson()
      expect(check?.status).toBe('pass')
      expect(check?.message).toContain('codex-cli 0.162.1')
      expect(check?.message).toContain("Image generation: Codex (the skills' default)")
      expect(check?.message).toContain('conductor config set-openai-api-key')
      expect(exitCode).toBe(0)
    })

    const withKey = async (fetchImpl: ReturnType<typeof vi.fn>) => {
      process.env['OPENAI_API_KEY'] = 'sk-test'
      mockFs.existsSync.mockReturnValue(true)
      mockReadConfig.mockReturnValue({ apiKey: 'k', projectId: 'p', projectName: 'T', email: 'e@x.test', apiUrl: 'http://localhost:8080' })
      vi.stubGlobal('fetch', fetchImpl)
      const writeSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)
      const { registerDoctor } = await import('../commands/doctor.js')
      const program = new Command()
      program.exitOverride()
      registerDoctor(program)
      await program.parseAsync(['node', 'conductor', 'doctor', '--json'])
      const parsed = JSON.parse(writeSpy.mock.calls.map(c => c[0]).join('')) as { checks: Array<{ name: string; status: string; message: string }> }
      const exitCode = exitSpy.mock.calls[0]?.[0]
      writeSpy.mockRestore()
      exitSpy.mockRestore()
      vi.unstubAllGlobals()
      delete process.env['OPENAI_API_KEY']
      return { check: parsed.checks.find(c => c.name === 'image-generation'), exitCode }
    }

    it('passes on the OpenAI route when the model is reachable', async () => {
      const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200 })
      const { check } = await withKey(fetchMock)
      expect(check?.status).toBe('pass')
      expect(check?.message).toContain('fallback: Image generation: OpenAI API (gpt-image-2.5-sunburst, high), key from OPENAI_API_KEY')
      expect(check?.message).toContain("Image generation: Codex (the skills' default)")
      expect(fetchMock).toHaveBeenCalledWith('https://api.openai.com/v1/models/gpt-image-2.5-sunburst', expect.objectContaining({ headers: { Authorization: 'Bearer sk-test' } }))
      expect(mockCheckCodexCli).toHaveBeenCalled()
    })

    it('warns, without failing, when OpenAI rejects the key or is unreachable', async () => {
      mockCheckCodexCli.mockResolvedValue({ state: 'missing' })
      const rejected = await withKey(vi.fn().mockResolvedValue({ ok: false, status: 401 }))
      expect(rejected.check?.status).toBe('warn')
      expect(rejected.check?.message).toContain('key rejected')
      const missing = await withKey(vi.fn().mockResolvedValue({ ok: false, status: 404 }))
      expect(missing.check?.message).toContain('model not available')
      const down = await withKey(vi.fn((url: string) => (new URL(url).hostname === 'api.openai.com' ? Promise.reject(new Error('offline')) : Promise.resolve({ ok: true, status: 200 }))))
      expect(down.check?.message).toContain('unreachable')
      expect(down.exitCode).toBe(0)
    })

    it('warns, without failing the exit code, when logged out', async () => {
      mockCheckCodexCli.mockResolvedValue({ state: 'logged-out', version: 'codex-cli 0.162.1' })
      const { check, exitCode } = await runJson()
      expect(check?.status).toBe('warn')
      expect(check?.message).toContain('codex login')
      expect(exitCode).toBe(0)
    })

    it('warns, without failing the exit code, when missing', async () => {
      mockCheckCodexCli.mockResolvedValue({ state: 'missing' })
      const { check, exitCode } = await runJson()
      expect(check?.status).toBe('warn')
      expect(check?.message).toContain('npm i -g @openai/codex')
      expect(exitCode).toBe(0)
    })

    it('prints a warning line in pretty mode', async () => {
      mockCheckCodexCli.mockResolvedValue({ state: 'missing' })
      mockFs.existsSync.mockReturnValue(true)
      mockReadConfig.mockReturnValue({ apiKey: 'k', projectId: 'p', projectName: 'T', email: 'e@x.test', apiUrl: 'http://localhost:8080' })
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
      const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {})
      const { registerDoctor } = await import('../commands/doctor.js')
      const program = new Command()
      program.exitOverride()
      registerDoctor(program)
      await program.parseAsync(['node', 'conductor', 'doctor'])
      expect(logSpy.mock.calls.map(c => String(c[0])).join('\n')).toContain("Image generation: Codex (the skills' default)")
      logSpy.mockRestore()
      vi.unstubAllGlobals()
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
