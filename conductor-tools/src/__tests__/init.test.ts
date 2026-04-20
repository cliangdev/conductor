import { describe, it, expect, beforeEach, afterEach, beforeAll, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'
import * as child_process from 'child_process'
import * as readline from 'readline'
import * as oauthServer from '../lib/oauth-server.js'
import { apiGet, apiPost } from '../lib/api.js'

vi.mock('../lib/config.js', () => ({
  readConfig: vi.fn(),
  writeConfig: vi.fn(),
}))

vi.mock('fs')
vi.mock('child_process')
vi.mock('readline')
vi.mock('../lib/oauth-server.js', () => ({
  findAvailablePort: vi.fn(),
  waitForOAuthCallback: vi.fn(),
}))
vi.mock('open', () => ({ default: vi.fn() }))
vi.mock('../commands/start.js', () => ({ startDaemon: vi.fn().mockResolvedValue(true) }))
vi.mock('../lib/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  ApiError: class ApiError extends Error {
    statusCode: number
    constructor(message: string, statusCode: number) {
      super(message)
      this.statusCode = statusCode
    }
  },
}))

const mockFs = vi.mocked(fs)
const mockChildProcess = vi.mocked(child_process)
const mockReadline = vi.mocked(readline)

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const realFs = await vi.importActual<typeof import('fs')>('fs')

const validConfig = {
  apiKey: 'test-api-key',
  projectId: 'proj_test123',
  projectName: 'Test Project',
  email: 'user@example.com',
  apiUrl: 'http://localhost:8080',
}

function makeReadlineMock(...answers: string[]) {
  const mockRl = { question: vi.fn(), close: vi.fn() }
  let i = 0
  mockRl.question.mockImplementation((_: string, cb: (s: string) => void) => cb(answers[i++] ?? ''))
  mockReadline.createInterface.mockReturnValue(mockRl as unknown as readline.Interface)
  return mockRl
}

describe('init command', () => {
  const projectId = 'proj_test123'
  const issuesDir = path.join(os.homedir(), '.conductor', projectId, 'issues')

  beforeEach(() => {
    vi.resetAllMocks()
    mockChildProcess.execSync.mockReturnValue('/tmp/myproject\n' as unknown as Buffer)
    vi.mocked(oauthServer.findAvailablePort).mockResolvedValue(3131)
    vi.mocked(oauthServer.waitForOAuthCallback).mockResolvedValue({
      apiKey: 'new-api-key',
      email: 'new-user@example.com',
      projectId: '',
      projectName: '',
    })
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 200, ok: true }))
    vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('triggers browser login when not authenticated', async () => {
    const { readConfig } = await import('../lib/config.js')
    vi.mocked(readConfig).mockReturnValue(null)
    vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue([{ id: 'proj_abc', name: 'Alpha' }])
    makeReadlineMock('2')

    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.readFileSync.mockImplementation(() => { throw new Error('ENOENT') })
    mockFs.writeFileSync.mockReturnValue(undefined)
    vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { Command } = await import('commander')
    const { registerInit } = await import('../commands/init.js')
    const program = new Command()
    program.exitOverride()
    registerInit(program)

    await program.parseAsync(['node', 'conductor', 'init'])

    expect(vi.mocked(oauthServer.findAvailablePort)).toHaveBeenCalled()
    expect(vi.mocked(oauthServer.waitForOAuthCallback)).toHaveBeenCalled()
  })

  it('creates local issues directory on first run', async () => {
    const { readConfig } = await import('../lib/config.js')
    vi.mocked(readConfig).mockReturnValue(validConfig)

    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.readFileSync.mockImplementation(() => { throw new Error('ENOENT') })
    mockFs.writeFileSync.mockReturnValue(undefined)

    vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { Command } = await import('commander')
    const { registerInit } = await import('../commands/init.js')
    const program = new Command()
    program.exitOverride()
    registerInit(program)

    await program.parseAsync(['node', 'conductor', 'init', '--project-id', projectId])

    expect(mockFs.mkdirSync).toHaveBeenCalledWith(issuesDir, { recursive: true })
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

  describe('auth and project selection', () => {
    const projects = [
      { id: 'proj_abc', name: 'Alpha Project' },
      { id: 'proj_xyz', name: 'Beta Project' },
    ]

    beforeEach(() => {
      mockFs.mkdirSync.mockReturnValue(undefined)
      mockFs.readFileSync.mockImplementation(() => { throw new Error('ENOENT') })
      mockFs.writeFileSync.mockReturnValue(undefined)
      vi.spyOn(console, 'log').mockImplementation(() => undefined)
      vi.spyOn(console, 'error').mockImplementation(() => undefined)
      vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)
    })

    async function runInit(args: string[] = []) {
      const { Command } = await import('commander')
      const { registerInit } = await import('../commands/init.js')
      const program = new Command()
      program.exitOverride()
      registerInit(program)
      await program.parseAsync(['node', 'conductor', 'init', ...args])
    }

    it('triggers browser login when no config exists', async () => {
      const { readConfig } = await import('../lib/config.js')
      vi.mocked(readConfig).mockReturnValue(null)
      vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue(projects)
      makeReadlineMock('3') // picks "Beta Project" (3rd option)

      await runInit()

      expect(vi.mocked(oauthServer.findAvailablePort)).toHaveBeenCalled()
      expect(vi.mocked(oauthServer.waitForOAuthCallback)).toHaveBeenCalled()
      const { writeConfig } = await import('../lib/config.js')
      expect(vi.mocked(writeConfig)).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: 'proj_xyz', projectName: 'Beta Project' })
      )
    })

    it('triggers browser login when stored API key is invalid', async () => {
      const { readConfig } = await import('../lib/config.js')
      vi.mocked(readConfig).mockReturnValue(validConfig)
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401 }))
      vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue(projects)
      makeReadlineMock('2') // picks "Alpha Project"

      await runInit()

      expect(vi.mocked(oauthServer.findAvailablePort)).toHaveBeenCalled()
      const { writeConfig } = await import('../lib/config.js')
      expect(vi.mocked(writeConfig)).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: 'proj_abc', projectName: 'Alpha Project' })
      )
    })

    it('links to user-selected project from the list', async () => {
      const { readConfig } = await import('../lib/config.js')
      vi.mocked(readConfig).mockReturnValue(validConfig)
      vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue(projects)
      makeReadlineMock('2') // option 2 = "Alpha Project" (projects[0])

      await runInit()

      expect(vi.mocked(oauthServer.findAvailablePort)).not.toHaveBeenCalled()
      const { writeConfig } = await import('../lib/config.js')
      expect(vi.mocked(writeConfig)).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: 'proj_abc', projectName: 'Alpha Project' })
      )
    })

    it('prompts to create a project when none exist', async () => {
      const { readConfig } = await import('../lib/config.js')
      vi.mocked(readConfig).mockReturnValue(validConfig)
      vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue([])
      vi.mocked(apiPost as (...a: unknown[]) => unknown).mockResolvedValue({ id: 'proj_new', name: 'My New Project' })
      makeReadlineMock('My New Project') // askText for project name

      await runInit()

      expect(vi.mocked(apiPost)).toHaveBeenCalledWith(
        '/api/v1/projects',
        { name: 'My New Project' },
        validConfig.apiKey,
        expect.any(String)
      )
      const { writeConfig } = await import('../lib/config.js')
      expect(vi.mocked(writeConfig)).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: 'proj_new', projectName: 'My New Project' })
      )
    })

    it('creates a new project when user picks that option from the list', async () => {
      const { readConfig } = await import('../lib/config.js')
      vi.mocked(readConfig).mockReturnValue(validConfig)
      vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue(projects)
      vi.mocked(apiPost as (...a: unknown[]) => unknown).mockResolvedValue({ id: 'proj_created', name: 'Created Project' })
      makeReadlineMock('1', 'Created Project') // option 1 = "Create a new project", then name

      await runInit()

      expect(vi.mocked(apiPost)).toHaveBeenCalledWith(
        '/api/v1/projects',
        { name: 'Created Project' },
        validConfig.apiKey,
        expect.any(String)
      )
    })

    it('validates --project-id flag and skips interactive selection', async () => {
      const { readConfig } = await import('../lib/config.js')
      vi.mocked(readConfig).mockReturnValue(validConfig)
      vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue({ id: 'proj_other', name: 'Other Project' })

      await runInit(['--project-id', 'proj_other'])

      expect(vi.mocked(apiGet)).toHaveBeenCalledWith(
        '/api/v1/projects/proj_other',
        validConfig.apiKey,
        expect.any(String)
      )
      expect(mockReadline.createInterface).not.toHaveBeenCalled()
    })

    it('exits with error when --project-id project is not accessible', async () => {
      const { readConfig } = await import('../lib/config.js')
      vi.mocked(readConfig).mockReturnValue(validConfig)
      const err = Object.assign(new Error('Not found'), { statusCode: 404 })
      vi.mocked(apiGet as (...a: unknown[]) => unknown).mockRejectedValue(err)

      const errorSpy = vi.spyOn(console, 'error')
      const exitSpy = vi.spyOn(process, 'exit')

      await runInit(['--project-id', 'proj_bad'])

      expect(errorSpy).toHaveBeenCalledWith(expect.stringContaining('proj_bad'))
      expect(exitSpy).toHaveBeenCalledWith(1)
    })
  })
})

describe('getProjectRoot', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('returns git toplevel when inside a git repo', async () => {
    vi.mocked(child_process.execSync).mockReturnValue('/home/user/myrepo\n' as unknown as Buffer)
    const { getProjectRoot } = await import('../commands/init.js')
    const result = getProjectRoot('/home/user/myrepo/subdir')
    expect(result).toBe('/home/user/myrepo')
    expect(vi.mocked(child_process.execSync)).toHaveBeenCalledWith(
      'git rev-parse --show-toplevel',
      expect.objectContaining({ cwd: '/home/user/myrepo/subdir' })
    )
  })

  it('returns workingDir when not inside a git repo', async () => {
    vi.mocked(child_process.execSync).mockImplementation(() => {
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

describe('installPluginAssets', () => {
  let tmpDir: string
  let assetSrcDir: string

  beforeAll(async () => {
    const { getAssetSrcDir } = await import('../lib/plugin-assets.js')
    assetSrcDir = getAssetSrcDir()
  })

  beforeEach(() => {
    tmpDir = realFs.mkdtempSync(path.join(os.tmpdir(), 'conductor-plugin-test-'))
  })

  afterEach(() => {
    realFs.rmSync(tmpDir, { recursive: true, force: true })
  })

  it('returns installed and creates all plugin files on first run', async () => {
    const { installPluginAssets } = await import('../lib/plugin-assets.js')
    const status = installPluginAssets(tmpDir, assetSrcDir)
    expect(status).toBe('installed')
    expect(realFs.existsSync(path.join(tmpDir, 'commands', 'conductor', 'prd.md'))).toBe(true)
    expect(realFs.existsSync(path.join(tmpDir, 'commands', 'conductor', 'implement.md'))).toBe(true)
    expect(realFs.existsSync(path.join(tmpDir, 'agents', 'researcher.md'))).toBe(true)
    expect(realFs.existsSync(path.join(tmpDir, 'skills', 'ux-ui-design', 'SKILL.md'))).toBe(true)
    expect(realFs.existsSync(path.join(tmpDir, 'skills', 'ux-ui-design', 'references', 'design-tokens.md'))).toBe(true)
  })

  it('returns current when all files already match', async () => {
    const { installPluginAssets } = await import('../lib/plugin-assets.js')
    installPluginAssets(tmpDir, assetSrcDir)
    const status = installPluginAssets(tmpDir, assetSrcDir)
    expect(status).toBe('current')
  })

  it('returns updated when an existing file differs from bundled version', async () => {
    const { installPluginAssets } = await import('../lib/plugin-assets.js')
    installPluginAssets(tmpDir, assetSrcDir)
    const prdPath = path.join(tmpDir, 'commands', 'conductor', 'prd.md')
    realFs.writeFileSync(prdPath, 'outdated content', 'utf8')
    const status = installPluginAssets(tmpDir, assetSrcDir)
    expect(status).toBe('updated')
  })

  it('merges settings.json preserving existing keys', async () => {
    const { installPluginAssets } = await import('../lib/plugin-assets.js')
    const settingsPath = path.join(tmpDir, 'settings.json')
    realFs.writeFileSync(settingsPath, JSON.stringify({ theme: 'dark', permissions: { allow: ['existing_perm'] } }), 'utf8')
    installPluginAssets(tmpDir, assetSrcDir)
    const settings = JSON.parse(realFs.readFileSync(settingsPath, 'utf8'))
    expect(settings.theme).toBe('dark')
    expect(settings.permissions.allow).toContain('existing_perm')
    expect(settings.permissions.allow).toContain('mcp__conductor__*')
  })

  it('prd.md content contains Phase 1, Phase 2, Phase 3, Phase 4 and PRD format', async () => {
    const { installPluginAssets } = await import('../lib/plugin-assets.js')
    installPluginAssets(tmpDir, assetSrcDir)
    const content = realFs.readFileSync(path.join(tmpDir, 'commands', 'conductor', 'prd.md'), 'utf8')
    expect(content).toContain('Phase 1')
    expect(content).toContain('Phase 2')
    expect(content).toContain('Phase 3')
    expect(content).toContain('Phase 4')
    expect(content).toContain('create_issue')
    expect(content).toContain('scaffold_document')
  })
})

describe('multi-project: projects map is merged on init', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(child_process.execSync).mockReturnValue('/tmp/project-b\n' as unknown as Buffer)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 200, ok: true }))
    vi.spyOn(console, 'log').mockImplementation(() => undefined)
    vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)
    vi.mocked(fs.mkdirSync).mockReturnValue(undefined)
    vi.mocked(fs.readFileSync).mockImplementation(() => { throw new Error('ENOENT') })
    vi.mocked(fs.writeFileSync).mockReturnValue(undefined)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('adds new project to existing projects map without overwriting others', async () => {
    const { readConfig, writeConfig } = await import('../lib/config.js')
    const existingConfig = {
      ...validConfig,
      localPath: '/tmp/project-a',
      projects: {
        proj_test123: { localPath: '/tmp/project-a', projectName: 'Test Project' },
      },
    }
    vi.mocked(readConfig).mockReturnValue(existingConfig)

    const projectBId = 'proj_bbb'
    const { apiGet } = await import('../lib/api.js')
    vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue({ id: projectBId, name: 'Project B' })

    const { Command } = await import('commander')
    const { registerInit } = await import('../commands/init.js')
    const program = new Command()
    program.exitOverride()
    registerInit(program)
    await program.parseAsync(['node', 'conductor', 'init', '--project-id', projectBId])

    const savedConfig = vi.mocked(writeConfig).mock.calls.at(-1)?.[0]
    expect(savedConfig?.projects?.['proj_test123']).toEqual({
      localPath: '/tmp/project-a',
      projectName: 'Test Project',
    })
    expect(savedConfig?.projects?.[projectBId]).toEqual({
      localPath: '/tmp/project-b',
      projectName: 'Project B',
    })
  })

  it('creates projects map from legacy config on first multi-project init', async () => {
    const { readConfig, writeConfig } = await import('../lib/config.js')
    const legacyConfig = {
      ...validConfig,
      localPath: '/tmp/project-a',
    }
    vi.mocked(readConfig).mockReturnValue(legacyConfig)

    const { apiGet } = await import('../lib/api.js')
    vi.mocked(apiGet as (...a: unknown[]) => unknown).mockResolvedValue({ id: 'proj_bbb', name: 'Project B' })

    const { Command } = await import('commander')
    const { registerInit } = await import('../commands/init.js')
    const program = new Command()
    program.exitOverride()
    registerInit(program)
    await program.parseAsync(['node', 'conductor', 'init', '--project-id', 'proj_bbb'])

    const savedConfig = vi.mocked(writeConfig).mock.calls.at(-1)?.[0]
    // Original project preserved
    expect(savedConfig?.projects?.['proj_test123']).toEqual({
      localPath: '/tmp/project-a',
      projectName: 'Test Project',
    })
    // New project added
    expect(savedConfig?.projects?.['proj_bbb']).toEqual({
      localPath: '/tmp/project-b',
      projectName: 'Project B',
    })
  })
})

describe('T96: localPath saved to config on init', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(child_process.execSync).mockReturnValue('/tmp/myproject\n' as unknown as Buffer)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 200, ok: true }))
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('writes localPath to config after init', async () => {
    const { readConfig, writeConfig } = await import('../lib/config.js')
    vi.mocked(readConfig).mockReturnValue(validConfig)
    vi.mocked(fs.mkdirSync).mockReturnValue(undefined)
    vi.mocked(fs.readFileSync).mockImplementation(() => { throw new Error('ENOENT') })
    vi.mocked(fs.writeFileSync).mockReturnValue(undefined)

    vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { Command } = await import('commander')
    const { registerInit } = await import('../commands/init.js')

    const program = new Command()
    program.exitOverride()
    registerInit(program)

    await program.parseAsync(['node', 'conductor', 'init', '--project-id', validConfig.projectId])

    expect(vi.mocked(writeConfig)).toHaveBeenCalledWith(
      expect.objectContaining({ localPath: '/tmp/myproject' })
    )
  })
})
