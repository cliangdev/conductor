import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fs from 'fs'

vi.mock('fs')

const mockFs = vi.mocked(fs)

const validConfig = {
  apiKey: 'test-api-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'user@example.com',
  apiUrl: 'http://localhost:8080',
}

describe('getConfig', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('returns config when file is valid', async () => {
    mockFs.readFileSync.mockReturnValue(JSON.stringify(validConfig))

    const { getConfig } = await import('../config.js')
    const result = getConfig()
    expect(result).toEqual(validConfig)
  })

  it('throws auth error when config file is missing', async () => {
    mockFs.readFileSync.mockImplementation(() => {
      throw new Error('ENOENT: no such file or directory')
    })

    const { getConfig } = await import('../config.js')
    expect(() => getConfig()).toThrow('Config not found — run conductor login')
  })

  it('throws error when config is missing required fields', async () => {
    mockFs.readFileSync.mockReturnValue(JSON.stringify({ apiKey: 'only-key' }))

    const { getConfig } = await import('../config.js')
    expect(() => getConfig()).toThrow('Invalid config')
  })
})

describe('MCP server auth error', () => {
  it('returns auth error message when config is missing', async () => {
    mockFs.readFileSync.mockImplementation(() => {
      throw new Error('ENOENT')
    })

    const { getConfig } = await import('../config.js')
    let errorMessage: string | undefined

    try {
      getConfig()
    } catch (err) {
      errorMessage = err instanceof Error ? err.message : String(err)
    }

    expect(errorMessage).toContain('run conductor login')
  })
})
