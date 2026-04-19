import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'

vi.mock('fs')

const mockFs = vi.mocked(fs)

const HOME = os.homedir()
const CONDUCTOR_DIR = path.join(HOME, '.conductor')
const PID_FILE = path.join(CONDUCTOR_DIR, 'daemon.pid')
const CLAUDE_DIR = path.join(HOME, '.claude')
const SETTINGS_JSON = path.join(CLAUDE_DIR, 'settings.json')

// Dynamically import the module under test after mocks are in place
async function loadModule() {
  vi.resetModules()
  return import('../../scripts/preuninstall.js')
}

// ─── stopDaemon ───────────────────────────────────────────────────────────────

describe('stopDaemon', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('is a no-op when daemon.pid does not exist', async () => {
    mockFs.readFileSync.mockImplementation(() => { throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' }) })

    const killSpy = vi.spyOn(process, 'kill').mockReturnValue(true)
    const { stopDaemon } = await loadModule()
    await expect(stopDaemon()).resolves.toBeUndefined()
    expect(killSpy).not.toHaveBeenCalled()
    killSpy.mockRestore()
  })

  it('sends SIGTERM when daemon is running', async () => {
    mockFs.readFileSync.mockReturnValue('12345')
    const killSpy = vi.spyOn(process, 'kill').mockReturnValue(true)
    vi.useFakeTimers()

    const { stopDaemon } = await loadModule()
    const promise = stopDaemon()
    await vi.runAllTimersAsync()
    await promise

    expect(killSpy).toHaveBeenCalledWith(12345, 'SIGTERM')
    killSpy.mockRestore()
    vi.useRealTimers()
  })

  it('does not throw when process.kill throws ESRCH (process not running)', async () => {
    mockFs.readFileSync.mockReturnValue('99999')
    const killSpy = vi.spyOn(process, 'kill').mockImplementation(() => { throw Object.assign(new Error('ESRCH'), { code: 'ESRCH' }) })

    const { stopDaemon } = await loadModule()
    await expect(stopDaemon()).resolves.toBeUndefined()
    killSpy.mockRestore()
  })
})

// ─── removeClaudeAssets ───────────────────────────────────────────────────────

describe('removeClaudeAssets', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('is a no-op when ~/.claude/commands/conductor/ does not exist', async () => {
    mockFs.existsSync.mockReturnValue(false)
    mockFs.rmSync.mockReturnValue(undefined)

    const { removeClaudeAssets } = await loadModule()
    removeClaudeAssets()

    expect(mockFs.rmSync).not.toHaveBeenCalled()
  })

  it('removes ~/.claude/commands/conductor/ when it exists', async () => {
    mockFs.existsSync.mockImplementation((p) =>
      String(p) === path.join(CLAUDE_DIR, 'commands', 'conductor')
    )
    mockFs.rmSync.mockReturnValue(undefined)

    const { removeClaudeAssets } = await loadModule()
    removeClaudeAssets()

    expect(mockFs.rmSync).toHaveBeenCalledWith(
      path.join(CLAUDE_DIR, 'commands', 'conductor'),
      { recursive: true, force: true }
    )
  })

  it('does not throw when rmSync fails', async () => {
    mockFs.existsSync.mockReturnValue(true)
    mockFs.rmSync.mockImplementation(() => { throw new Error('EPERM') })
    mockFs.readdirSync.mockReturnValue([])

    const { removeClaudeAssets } = await loadModule()
    expect(() => removeClaudeAssets()).not.toThrow()
  })
})

// ─── cleanSettings ────────────────────────────────────────────────────────────

describe('cleanSettings', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('is a no-op when settings.json does not exist', async () => {
    mockFs.readFileSync.mockImplementation(() => { throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' }) })
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { cleanSettings } = await loadModule()
    cleanSettings()

    expect(mockFs.writeFileSync).not.toHaveBeenCalled()
  })

  it('removes mcp__conductor__ entries from allow array and writes back', async () => {
    const settings = {
      allow: [
        'mcp__conductor__list_issues',
        'mcp__conductor__get_issue',
        'some_other_permission',
      ],
    }
    mockFs.readFileSync.mockReturnValue(JSON.stringify(settings))
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { cleanSettings } = await loadModule()
    cleanSettings()

    const written = JSON.parse(mockFs.writeFileSync.mock.calls[0][1] as string)
    expect(written.allow).toEqual(['some_other_permission'])
    expect(written.allow).not.toContain('mcp__conductor__list_issues')
    expect(written.allow).not.toContain('mcp__conductor__get_issue')
  })

  it('leaves non-conductor allow entries intact', async () => {
    const settings = {
      allow: ['some_tool', 'another_tool'],
      theme: 'dark',
    }
    mockFs.readFileSync.mockReturnValue(JSON.stringify(settings))
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { cleanSettings } = await loadModule()
    cleanSettings()

    const written = JSON.parse(mockFs.writeFileSync.mock.calls[0][1] as string)
    expect(written.allow).toEqual(['some_tool', 'another_tool'])
    expect(written.theme).toBe('dark')
  })

  it('handles settings.json with no allow array gracefully', async () => {
    const settings = { theme: 'light' }
    mockFs.readFileSync.mockReturnValue(JSON.stringify(settings))
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { cleanSettings } = await loadModule()
    expect(() => cleanSettings()).not.toThrow()
  })

  it('does not throw on malformed JSON', async () => {
    mockFs.readFileSync.mockReturnValue('not valid json {{')
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { cleanSettings } = await loadModule()
    expect(() => cleanSettings()).not.toThrow()
    expect(mockFs.writeFileSync).not.toHaveBeenCalled()
  })
})

// ─── cleanMcpJson ─────────────────────────────────────────────────────────────

describe('cleanMcpJson', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('is a no-op when .mcp.json does not exist', async () => {
    mockFs.readFileSync.mockImplementation(() => { throw Object.assign(new Error('ENOENT'), { code: 'ENOENT' }) })
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { cleanMcpJson } = await loadModule()
    cleanMcpJson()

    expect(mockFs.writeFileSync).not.toHaveBeenCalled()
  })

  it('removes mcpServers.conductor key and writes back', async () => {
    const mcpJson = {
      mcpServers: {
        conductor: { command: 'node', args: ['dist/index.js'] },
        other: { command: 'other-cmd', args: [] },
      },
    }
    mockFs.readFileSync.mockReturnValue(JSON.stringify(mcpJson))
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { cleanMcpJson } = await loadModule()
    cleanMcpJson()

    const written = JSON.parse(mockFs.writeFileSync.mock.calls[0][1] as string)
    expect(written.mcpServers.conductor).toBeUndefined()
    expect(written.mcpServers.other).toBeDefined()
  })

  it('does not throw when mcpServers key is absent', async () => {
    const mcpJson = { version: 1 }
    mockFs.readFileSync.mockReturnValue(JSON.stringify(mcpJson))
    mockFs.writeFileSync.mockReturnValue(undefined)

    const { cleanMcpJson } = await loadModule()
    expect(() => cleanMcpJson()).not.toThrow()
  })

  it('does not throw on malformed JSON', async () => {
    mockFs.readFileSync.mockReturnValue('{bad json')

    const { cleanMcpJson } = await loadModule()
    expect(() => cleanMcpJson()).not.toThrow()
  })
})
