import { describe, it, expect, beforeEach, vi } from 'vitest'
import { Command } from 'commander'

const mockReadConfig = vi.fn()
const mockWriteConfig = vi.fn()

vi.mock('../lib/config.js', () => ({
  readConfig: mockReadConfig,
  writeConfig: mockWriteConfig,
}))

const mockConfig = {
  apiKey: 'cond1234abcd5678',
  projectId: 'proj_123',
  projectName: 'My Project',
  email: 'user@example.com',
  apiUrl: 'https://example.com',
}

function makeProgram() {
  const program = new Command()
  program.exitOverride()
  return program
}

describe('config show command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('prints error and exits 1 when no config exists', async () => {
    mockReadConfig.mockReturnValue(null)

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'show'])

    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('No config found'))
    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('prints all config fields when config exists', async () => {
    mockReadConfig.mockReturnValue(mockConfig)

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'show'])

    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('https://example.com')
    expect(allOutput).toContain('proj_123')
    expect(allOutput).toContain('My Project')
    expect(allOutput).toContain('user@example.com')

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('redacts apiKey: shows first 4 + ... + last 4 for long keys', async () => {
    mockReadConfig.mockReturnValue(mockConfig)

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'show'])

    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('cond...5678')
    expect(allOutput).not.toContain('cond1234abcd5678')

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('shows *** for apiKey of 8 chars or fewer', async () => {
    mockReadConfig.mockReturnValue({ ...mockConfig, apiKey: 'short123' })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'show'])

    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('***')
    expect(allOutput).not.toContain('short123')

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})

describe('config use command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('switches to local and prints the URL', async () => {
    mockReadConfig.mockReturnValue({ ...mockConfig })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'use', 'local'])

    expect(mockWriteConfig).toHaveBeenCalledWith(
      expect.objectContaining({ apiUrl: 'http://localhost:8080', frontendUrl: 'http://localhost:3000' })
    )
    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('local')
    expect(allOutput).toContain('http://localhost:8080')
    expect(allOutput).toContain('http://localhost:3000')

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('switches to prod and prints the URL', async () => {
    mockReadConfig.mockReturnValue({ ...mockConfig })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'use', 'prod'])

    expect(mockWriteConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        apiUrl: 'https://conductor-backend-199707291514.us-central1.run.app',
        frontendUrl: 'https://conductor-frontend-199707291514.us-central1.run.app',
      })
    )
    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('prod')
    expect(allOutput).toContain('https://conductor-backend-199707291514.us-central1.run.app')
    expect(allOutput).toContain('https://conductor-frontend-199707291514.us-central1.run.app')

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('prints error and exits 1 for unknown environment', async () => {
    mockReadConfig.mockReturnValue({ ...mockConfig })

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'use', 'staging'])

    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('Unknown environment'))
    expect(exitSpy).toHaveBeenCalledWith(1)
    expect(mockWriteConfig).not.toHaveBeenCalled()

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('prints error and exits 1 when no config exists', async () => {
    mockReadConfig.mockReturnValue(null)

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'use', 'local'])

    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('No config found'))
    expect(exitSpy).toHaveBeenCalledWith(1)
    expect(mockWriteConfig).not.toHaveBeenCalled()

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})

describe('config set-url command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
  })

  it('updates apiUrl and prints confirmation for valid https URL', async () => {
    mockReadConfig.mockReturnValue({ ...mockConfig })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'set-url', 'https://new.example.com'])

    expect(mockWriteConfig).toHaveBeenCalledWith(
      expect.objectContaining({ apiUrl: 'https://new.example.com' })
    )
    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('https://new.example.com')

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('updates apiUrl and prints confirmation for valid http URL', async () => {
    mockReadConfig.mockReturnValue({ ...mockConfig })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'set-url', 'http://localhost:8080'])

    expect(mockWriteConfig).toHaveBeenCalledWith(
      expect.objectContaining({ apiUrl: 'http://localhost:8080' })
    )

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('leaves other config fields unchanged after set-url', async () => {
    const original = { ...mockConfig }
    mockReadConfig.mockReturnValue(original)

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'set-url', 'https://new.example.com'])

    expect(mockWriteConfig).toHaveBeenCalledWith(
      expect.objectContaining({
        apiKey: mockConfig.apiKey,
        projectId: mockConfig.projectId,
        projectName: mockConfig.projectName,
        email: mockConfig.email,
      })
    )

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('prints error and exits 1 for URL without http/https prefix', async () => {
    mockReadConfig.mockReturnValue({ ...mockConfig })

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'set-url', 'example.com'])

    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('http'))
    expect(exitSpy).toHaveBeenCalledWith(1)
    expect(mockWriteConfig).not.toHaveBeenCalled()

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('prints error and exits 1 when no config exists', async () => {
    mockReadConfig.mockReturnValue(null)

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerConfig } = await import('../commands/config.js')
    const program = makeProgram()
    registerConfig(program)

    await program.parseAsync(['node', 'conductor', 'config', 'set-url', 'https://example.com'])

    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('No config found'))
    expect(exitSpy).toHaveBeenCalledWith(1)
    expect(mockWriteConfig).not.toHaveBeenCalled()

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})
