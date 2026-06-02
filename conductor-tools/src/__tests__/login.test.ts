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

describe('buildRefreshedConfig', () => {
  beforeEach(() => {
    vi.resetModules()
  })

  const auth = {
    apiKey: 'uk_newkey',
    email: 'user@example.com',
    projectId: 'payload_proj',
    projectName: 'Payload Project',
    apiUrl: 'http://localhost:8080',
    frontendUrl: 'http://localhost:3000',
  }

  it('on re-auth, keeps the active project + projects map and only refreshes the credential', async () => {
    const { buildRefreshedConfig } = await import('../commands/login.js')
    const existing = {
      apiKey: 'uk_oldkey',
      projectId: 'rexcipe',
      projectName: 'Rexcipe Engineering',
      email: 'user@example.com',
      apiUrl: 'http://localhost:8080',
      localPath: '/home/user/nexus',
      projects: {
        rexcipe: { localPath: '/home/user/nexus', projectName: 'Rexcipe Engineering' },
      },
    }

    const result = buildRefreshedConfig(existing, auth)

    // Credential refreshed...
    expect(result.apiKey).toBe('uk_newkey')
    // ...but the active project and multi-project map are NOT clobbered by the payload.
    expect(result.projectId).toBe('rexcipe')
    expect(result.projectName).toBe('Rexcipe Engineering')
    expect(result.localPath).toBe('/home/user/nexus')
    expect(result.projects).toEqual(existing.projects)
  })

  it('on first login, adopts the project returned by the auth flow', async () => {
    const { buildRefreshedConfig } = await import('../commands/login.js')
    const result = buildRefreshedConfig(null, auth)
    expect(result.apiKey).toBe('uk_newkey')
    expect(result.projectId).toBe('payload_proj')
    expect(result.projectName).toBe('Payload Project')
  })
})

describe('ensureDurableApiKey', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('creates and returns a durable uk_ key when none is reusable', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, json: async () => [] }) // GET list
      .mockResolvedValueOnce({ ok: true, json: async () => ({ key: 'uk_created' }) }) // POST create
    vi.stubGlobal('fetch', fetchMock)

    const { ensureDurableApiKey } = await import('../commands/login.js')
    const key = await ensureDurableApiKey('http://localhost:8080', 'eyJ.jwt.token')

    expect(key).toBe('uk_created')
    expect(fetchMock).toHaveBeenLastCalledWith(
      'http://localhost:8080/api/v1/api-keys',
      expect.objectContaining({ method: 'POST' })
    )
    vi.unstubAllGlobals()
  })

  it('reuses an existing retrievable CLI key', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => [{ key: 'uk_existing', label: 'CLI key' }],
    })
    vi.stubGlobal('fetch', fetchMock)

    const { ensureDurableApiKey } = await import('../commands/login.js')
    const key = await ensureDurableApiKey('http://localhost:8080', 'eyJ.jwt.token')

    expect(key).toBe('uk_existing')
    expect(fetchMock).toHaveBeenCalledTimes(1) // no POST needed
    vi.unstubAllGlobals()
  })

  it('falls back to the JWT when key issuance is unavailable', async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error('network down'))
    vi.stubGlobal('fetch', fetchMock)

    const { ensureDurableApiKey } = await import('../commands/login.js')
    const key = await ensureDurableApiKey('http://localhost:8080', 'eyJ.jwt.token')

    expect(key).toBe('eyJ.jwt.token')
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
