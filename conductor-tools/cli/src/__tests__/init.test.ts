import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import * as child_process from 'child_process'

vi.mock('../lib/config.js', () => ({
  readConfig: vi.fn(),
  writeConfig: vi.fn(),
}))

vi.mock('fs')
vi.mock('child_process')

const mockFs = vi.mocked(fs)
const mockChildProcess = vi.mocked(child_process)

// Real fs for tests that actually need filesystem operations
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const realFs = await vi.importActual<typeof import('fs')>('fs')

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
    // Default: execSync returns cwd (no git root)
    mockChildProcess.execSync.mockReturnValue('/tmp/myproject\n' as unknown as Buffer)
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

  it('creates legacy issues directory on first run', async () => {
    const { readConfig } = await import('../lib/config.js')
    vi.mocked(readConfig).mockReturnValue(validConfig)

    mockFs.mkdirSync.mockReturnValue(undefined)
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
        command: 'conductor',
        args: ['mcp'],
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
        command: 'conductor',
        args: ['mcp'],
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

describe('getProjectRoot', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('returns git toplevel when inside a git repo', async () => {
    mockChildProcess.execSync.mockReturnValue('/home/user/myrepo\n' as unknown as Buffer)
    const { getProjectRoot } = await import('../commands/init.js')
    const result = getProjectRoot('/home/user/myrepo/subdir')
    expect(result).toBe('/home/user/myrepo')
    expect(mockChildProcess.execSync).toHaveBeenCalledWith(
      'git rev-parse --show-toplevel',
      expect.objectContaining({ cwd: '/home/user/myrepo/subdir' })
    )
  })

  it('returns workingDir when not inside a git repo', async () => {
    mockChildProcess.execSync.mockImplementation(() => {
      throw new Error('not a git repository')
    })
    const { getProjectRoot } = await import('../commands/init.js')
    const result = getProjectRoot('/some/dir')
    expect(result).toBe('/some/dir')
  })
})

describe('ensureGitignore', () => {
  let tmpDir: string

  beforeEach(() => {
    vi.resetAllMocks()
    tmpDir = realFs.mkdtempSync(path.join(os.tmpdir(), 'conductor-test-'))
    // Delegate fs calls to real fs for these integration tests
    mockFs.readFileSync.mockImplementation((...args) =>
      (realFs.readFileSync as (...a: unknown[]) => unknown)(...args) as ReturnType<typeof fs.readFileSync>
    )
    mockFs.writeFileSync.mockImplementation((...args) =>
      (realFs.writeFileSync as (...a: unknown[]) => void)(...args)
    )
    mockFs.mkdirSync.mockImplementation((...args) =>
      (realFs.mkdirSync as (...a: unknown[]) => unknown)(...args) as ReturnType<typeof fs.mkdirSync>
    )
  })

  afterEach(() => {
    realFs.rmSync(tmpDir, { recursive: true, force: true })
  })

  it('creates .gitignore with .conductor/ when file does not exist', async () => {
    const { ensureGitignore } = await import('../commands/init.js')
    ensureGitignore(tmpDir)
    const content = realFs.readFileSync(path.join(tmpDir, '.gitignore'), 'utf8')
    expect(content).toContain('.conductor/')
  })

  it('appends .conductor/ to existing .gitignore', async () => {
    const gitignorePath = path.join(tmpDir, '.gitignore')
    realFs.writeFileSync(gitignorePath, 'node_modules/\ndist/\n', 'utf8')
    const { ensureGitignore } = await import('../commands/init.js')
    ensureGitignore(tmpDir)
    const content = realFs.readFileSync(gitignorePath, 'utf8')
    expect(content).toContain('node_modules/')
    expect(content).toContain('.conductor/')
  })

  it('does not duplicate .conductor/ if already present', async () => {
    const gitignorePath = path.join(tmpDir, '.gitignore')
    realFs.writeFileSync(gitignorePath, 'node_modules/\n.conductor/\n', 'utf8')
    const { ensureGitignore } = await import('../commands/init.js')
    ensureGitignore(tmpDir)
    const content = realFs.readFileSync(gitignorePath, 'utf8')
    const matches = content.split('\n').filter(l => l.trim() === '.conductor/')
    expect(matches).toHaveLength(1)
  })

  it('adds newline before entry when existing file has no trailing newline', async () => {
    const gitignorePath = path.join(tmpDir, '.gitignore')
    realFs.writeFileSync(gitignorePath, 'node_modules/', 'utf8')
    const { ensureGitignore } = await import('../commands/init.js')
    ensureGitignore(tmpDir)
    const content = realFs.readFileSync(gitignorePath, 'utf8')
    expect(content).toBe('node_modules/\n.conductor/\n')
  })
})

describe('writeSkillFile', () => {
  let tmpDir: string

  beforeEach(() => {
    vi.resetAllMocks()
    tmpDir = realFs.mkdtempSync(path.join(os.tmpdir(), 'conductor-skill-test-'))
    // Delegate fs calls to real fs for these integration tests
    mockFs.mkdirSync.mockImplementation((...args) =>
      (realFs.mkdirSync as (...a: unknown[]) => unknown)(...args) as ReturnType<typeof fs.mkdirSync>
    )
    mockFs.writeFileSync.mockImplementation((...args) =>
      (realFs.writeFileSync as (...a: unknown[]) => void)(...args)
    )
  })

  afterEach(() => {
    realFs.rmSync(tmpDir, { recursive: true, force: true })
  })

  it('creates .claude/skills/conductor.md', async () => {
    const { writeSkillFile } = await import('../commands/init.js')
    writeSkillFile(tmpDir)
    const skillPath = path.join(tmpDir, '.claude', 'skills', 'conductor.md')
    expect(realFs.existsSync(skillPath)).toBe(true)
  })

  it('skill file contains Phase 1 discovery prompt', async () => {
    const { writeSkillFile } = await import('../commands/init.js')
    writeSkillFile(tmpDir)
    const content = realFs.readFileSync(path.join(tmpDir, '.claude', 'skills', 'conductor.md'), 'utf8')
    expect(content).toContain('Phase 1')
    expect(content).toContain("What are you trying to build?")
  })

  it('skill file contains Phase 2 research instructions', async () => {
    const { writeSkillFile } = await import('../commands/init.js')
    writeSkillFile(tmpDir)
    const content = realFs.readFileSync(path.join(tmpDir, '.claude', 'skills', 'conductor.md'), 'utf8')
    expect(content).toContain('Phase 2')
    expect(content).toContain('CLAUDE.md')
  })

  it('skill file contains Phase 3 generate with PRD format', async () => {
    const { writeSkillFile } = await import('../commands/init.js')
    writeSkillFile(tmpDir)
    const content = realFs.readFileSync(path.join(tmpDir, '.claude', 'skills', 'conductor.md'), 'utf8')
    expect(content).toContain('Phase 3')
    expect(content).toContain('PRD Format')
  })

  it('skill file contains Phase 4 save with MCP call sequence', async () => {
    const { writeSkillFile } = await import('../commands/init.js')
    writeSkillFile(tmpDir)
    const content = realFs.readFileSync(path.join(tmpDir, '.claude', 'skills', 'conductor.md'), 'utf8')
    expect(content).toContain('Phase 4')
    expect(content).toContain('create_issue')
    expect(content).toContain('scaffold_document')
  })

  it('skill file contains all three supporting document templates', async () => {
    const { writeSkillFile } = await import('../commands/init.js')
    writeSkillFile(tmpDir)
    const content = realFs.readFileSync(path.join(tmpDir, '.claude', 'skills', 'conductor.md'), 'utf8')
    expect(content).toContain('architecture.md')
    expect(content).toContain('wireframes.md')
    expect(content).toContain('mockup.html')
  })
})

describe('T96: localPath saved to config on init', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockChildProcess.execSync.mockReturnValue('/tmp/myproject\n' as unknown as Buffer)
  })

  it('writes localPath to config after init', async () => {
    const { readConfig, writeConfig } = await import('../lib/config.js')
    const validConfig = {
      apiKey: 'test-api-key',
      projectId: 'proj_test123',
      projectName: 'Test Project',
      email: 'user@example.com',
      apiUrl: 'http://localhost:8080',
    }
    vi.mocked(readConfig).mockReturnValue(validConfig)
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.readFileSync.mockImplementation(() => { throw new Error('ENOENT') })
    mockFs.writeFileSync.mockReturnValue(undefined)

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { Command } = await import('commander')
    const { registerInit } = await import('../commands/init.js')

    const program = new Command()
    program.exitOverride()
    registerInit(program)

    await program.parseAsync(['node', 'conductor', 'init'])

    expect(vi.mocked(writeConfig)).toHaveBeenCalledWith(
      expect.objectContaining({ localPath: '/tmp/myproject' })
    )

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})
