import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

// Mock fs module
vi.mock('fs')

const mockFs = vi.mocked(fs)

describe('config', () => {
  const configDir = path.join(os.homedir(), '.conductor')
  const configPath = path.join(configDir, 'config.json')

  const validConfig = {
    apiKey: 'test-api-key',
    projectId: 'proj_123',
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

  describe('readConfig', () => {
    it('returns null when config file does not exist', async () => {
      mockFs.readFileSync.mockImplementation(() => {
        throw new Error('ENOENT: no such file or directory')
      })

      const { readConfig } = await import('../lib/config.js')
      const result = readConfig()
      expect(result).toBeNull()
    })

    it('returns null when config file has invalid JSON', async () => {
      mockFs.readFileSync.mockReturnValue('not-valid-json')

      const { readConfig } = await import('../lib/config.js')
      const result = readConfig()
      expect(result).toBeNull()
    })

    it('returns null when config file is missing required fields', async () => {
      mockFs.readFileSync.mockReturnValue(JSON.stringify({ apiKey: 'key' }))

      const { readConfig } = await import('../lib/config.js')
      const result = readConfig()
      expect(result).toBeNull()
    })

    it('returns config when file is valid', async () => {
      mockFs.readFileSync.mockReturnValue(JSON.stringify(validConfig))

      const { readConfig } = await import('../lib/config.js')
      const result = readConfig()
      expect(result).toEqual(validConfig)
    })
  })

  describe('writeConfig', () => {
    it('creates directory and writes config file', async () => {
      mockFs.mkdirSync.mockReturnValue(undefined)
      mockFs.writeFileSync.mockReturnValue(undefined)

      const { writeConfig } = await import('../lib/config.js')
      writeConfig(validConfig)

      expect(mockFs.mkdirSync).toHaveBeenCalledWith(configDir, { recursive: true })
      expect(mockFs.writeFileSync).toHaveBeenCalledWith(
        configPath,
        JSON.stringify(validConfig, null, 2),
        'utf8'
      )
    })
  })

  describe('clearConfig', () => {
    it('deletes the config file', async () => {
      mockFs.unlinkSync.mockReturnValue(undefined)

      const { clearConfig } = await import('../lib/config.js')
      clearConfig()

      expect(mockFs.unlinkSync).toHaveBeenCalledWith(configPath)
    })

    it('does not throw when config file does not exist', async () => {
      mockFs.unlinkSync.mockImplementation(() => {
        throw new Error('ENOENT')
      })

      const { clearConfig } = await import('../lib/config.js')
      expect(() => clearConfig()).not.toThrow()
    })
  })

  describe('loadConfigOrExit', () => {
    it('returns config when config is valid', async () => {
      mockFs.readFileSync.mockReturnValue(JSON.stringify(validConfig))

      const { loadConfigOrExit } = await import('../lib/config.js')
      const result = loadConfigOrExit()
      expect(result).toEqual(validConfig)
    })

    it('exits with code 78 when config is missing', async () => {
      mockFs.readFileSync.mockImplementation(() => {
        throw new Error('ENOENT: no such file or directory')
      })

      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

      const { loadConfigOrExit } = await import('../lib/config.js')
      loadConfigOrExit()

      expect(exitSpy).toHaveBeenCalledWith(78)
      expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('No config found'))

      consoleSpy.mockRestore()
      exitSpy.mockRestore()
    })

    it('exits with code 78 when config is invalid', async () => {
      mockFs.readFileSync.mockReturnValue(JSON.stringify({ apiKey: 'only-key' }))

      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
      const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

      const { loadConfigOrExit } = await import('../lib/config.js')
      loadConfigOrExit()

      expect(exitSpy).toHaveBeenCalledWith(78)

      consoleSpy.mockRestore()
      exitSpy.mockRestore()
    })
  })
})
