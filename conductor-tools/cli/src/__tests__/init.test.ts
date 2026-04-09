import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

vi.mock('../lib/config.js', () => ({
  readConfig: vi.fn(),
}))

vi.mock('fs')

const mockFs = vi.mocked(fs)

describe('init command', () => {
  const projectId = 'proj_test123'
  const issuesDir = path.join(os.homedir(), '.conductor', projectId, 'issues')

  const validConfig = {
    apiKey: 'test-api-key',
    projectId,
    projectName: 'Test Project',
    email: 'user@example.com',
    apiUrl: 'http://localhost:8080',
  }

  beforeEach(() => {
    vi.resetAllMocks()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('exits with code 1 when not logged in', async () => {
    const { readConfig } = await import('../lib/config.js')
    vi.mocked(readConfig).mockReturnValue(null)

    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { Command } = await import('commander')
    const { registerInit } = await import('../commands/init.js')

    const program = new Command()
    program.exitOverride()
    registerInit(program)

    await program.parseAsync(['node', 'conductor', 'init'])

    expect(errorSpy).toHaveBeenCalledWith(
      expect.stringContaining('conductor login')
    )
    expect(exitSpy).toHaveBeenCalledWith(1)

    errorSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('creates issues directory on first run', async () => {
    const { readConfig } = await import('../lib/config.js')
    vi.mocked(readConfig).mockReturnValue(validConfig)

    mockFs.mkdirSync.mockReturnValue(undefined)
    // readFileSync throws (no existing .mcp.json)
    mockFs.readFileSync.mockImplementation(() => {
      throw new Error('ENOENT')
    })
    mockFs.writeFileSync.mockReturnValue(undefined)

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { Command } = await import('commander')
    const { registerInit } = await import('../commands/init.js')

    const program = new Command()
    program.exitOverride()
    registerInit(program)

    await program.parseAsync(['node', 'conductor', 'init'])

    expect(mockFs.mkdirSync).toHaveBeenCalledWith(issuesDir, { recursive: true })

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  describe('buildMcpJson', () => {
    it('adds conductor entry to empty mcp.json', async () => {
      const { buildMcpJson } = await import('../commands/init.js')
      const result = buildMcpJson({})
      expect(result.mcpServers?.['conductor']).toEqual({
        command: 'npx',
        args: ['@conductor/mcp'],
      })
    })

    it('preserves existing mcp.json entries', async () => {
      const { buildMcpJson } = await import('../commands/init.js')
      const existing = {
        mcpServers: {
          'other-server': { command: 'other', args: ['--flag'] },
        },
      }
      const result = buildMcpJson(existing)
      expect(result.mcpServers?.['other-server']).toEqual({
        command: 'other',
        args: ['--flag'],
      })
      expect(result.mcpServers?.['conductor']).toEqual({
        command: 'npx',
        args: ['@conductor/mcp'],
      })
    })

    it('preserves top-level keys other than mcpServers', async () => {
      const { buildMcpJson } = await import('../commands/init.js')
      const existing = {
        version: '1.0',
        mcpServers: {},
      }
      const result = buildMcpJson(existing)
      expect(result['version']).toBe('1.0')
    })
  })
})
