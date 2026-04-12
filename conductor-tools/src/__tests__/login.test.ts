import { describe, it, expect, beforeEach, vi } from 'vitest'

describe('login command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('shows already-logged-in message when config exists and no --force flag', async () => {
    vi.doMock('../lib/config.js', () => ({
      readConfig: vi.fn().mockReturnValue({
        apiKey: 'existing-key',
        projectId: 'proj_123',
        projectName: 'My Project',
        email: 'user@example.com',
        apiUrl: 'http://localhost:8080',
      }),
      writeConfig: vi.fn(),
      clearConfig: vi.fn(),
    }))

    // isKeyValid makes a fetch call — stub it to return 200 (valid key)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 200 }))

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { Command } = await import('commander')
    const { registerLogin } = await import('../commands/login.js')

    const program = new Command()
    program.exitOverride()
    registerLogin(program)

    await program.parseAsync(['node', 'conductor', 'login'])

    expect(consoleSpy).toHaveBeenCalledWith(
      expect.stringContaining('Already logged in as user@example.com')
    )
    expect(exitSpy).toHaveBeenCalledWith(0)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
    vi.unstubAllGlobals()
  })
})

describe('logout command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('clears config and prints logged out', async () => {
    const clearConfigMock = vi.fn()
    vi.doMock('../lib/config.js', () => ({
      readConfig: vi.fn(),
      writeConfig: vi.fn(),
      clearConfig: clearConfigMock,
    }))

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { Command } = await import('commander')
    const { registerLogout } = await import('../commands/logout.js')

    const program = new Command()
    program.exitOverride()
    registerLogout(program)

    await program.parseAsync(['node', 'conductor', 'logout'])

    expect(clearConfigMock).toHaveBeenCalled()
    expect(consoleSpy).toHaveBeenCalledWith('Logged out.')

    consoleSpy.mockRestore()
  })
})
