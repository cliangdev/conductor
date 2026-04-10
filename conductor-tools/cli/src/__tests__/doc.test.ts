import { describe, it, expect, beforeEach, vi } from 'vitest'
import { Command } from 'commander'
import * as fs from 'fs'

const mockReadConfig = vi.fn()
const mockApiGet = vi.fn()
const mockApiPost = vi.fn()
const mockApiPatch = vi.fn()

vi.mock('../lib/config.js', () => ({
  readConfig: mockReadConfig,
}))

vi.mock('../lib/api.js', () => ({
  apiGet: mockApiGet,
  apiPost: mockApiPost,
  apiPatch: mockApiPatch,
  ApiError: class ApiError extends Error {
    constructor(message: string, public readonly statusCode: number) {
      super(message)
      this.name = 'ApiError'
    }
  },
}))

vi.mock('fs')

const mockFs = vi.mocked(fs)

const mockConfig = {
  apiKey: 'test-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'test@example.com',
  apiUrl: 'http://localhost:8080',
}

function makeProgram() {
  const program = new Command()
  program.exitOverride()
  return program
}

describe('doc add command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    mockReadConfig.mockReturnValue(mockConfig)
  })

  it('reads file, detects .md content type, and calls POST', async () => {
    mockFs.readFileSync.mockReturnValue('# My doc content')
    mockApiPost.mockResolvedValue({ id: 'doc_xyz', filename: 'spec.md' })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerDoc } = await import('../commands/doc.js')
    const program = makeProgram()
    registerDoc(program)

    await program.parseAsync(['node', 'conductor', 'doc', 'add', 'iss_abc123', '/path/to/spec.md'])

    expect(mockFs.readFileSync).toHaveBeenCalledWith('/path/to/spec.md', 'utf8')
    expect(mockApiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj_123/issues/iss_abc123/documents',
      { filename: 'spec.md', content: '# My doc content', contentType: 'text/markdown' },
      'test-key',
      'http://localhost:8080'
    )
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('spec.md'))
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('iss_abc123'))

    consoleSpy.mockRestore()
  })

  it('detects .txt content type', async () => {
    mockFs.readFileSync.mockReturnValue('plain text content')
    mockApiPost.mockResolvedValue({ id: 'doc_xyz', filename: 'notes.txt' })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerDoc } = await import('../commands/doc.js')
    const program = makeProgram()
    registerDoc(program)

    await program.parseAsync(['node', 'conductor', 'doc', 'add', 'iss_abc123', '/path/to/notes.txt'])

    expect(mockApiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj_123/issues/iss_abc123/documents',
      { filename: 'notes.txt', content: 'plain text content', contentType: 'text/plain' },
      'test-key',
      'http://localhost:8080'
    )

    consoleSpy.mockRestore()
  })

  it('defaults to application/octet-stream for unknown extensions', async () => {
    mockFs.readFileSync.mockReturnValue('binary data')
    mockApiPost.mockResolvedValue({ id: 'doc_xyz', filename: 'data.bin' })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerDoc } = await import('../commands/doc.js')
    const program = makeProgram()
    registerDoc(program)

    await program.parseAsync(['node', 'conductor', 'doc', 'add', 'iss_abc123', '/path/to/data.bin'])

    expect(mockApiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj_123/issues/iss_abc123/documents',
      { filename: 'data.bin', content: 'binary data', contentType: 'application/octet-stream' },
      'test-key',
      'http://localhost:8080'
    )

    consoleSpy.mockRestore()
  })

  it('exits 1 on API error', async () => {
    mockFs.readFileSync.mockReturnValue('content')
    const { ApiError } = await import('../lib/api.js')
    mockApiPost.mockRejectedValue(new ApiError('Upload failed', 500))

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerDoc } = await import('../commands/doc.js')
    const program = makeProgram()
    registerDoc(program)

    await program.parseAsync(['node', 'conductor', 'doc', 'add', 'iss_abc123', '/path/to/spec.md'])

    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})

describe('doc list command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    mockReadConfig.mockReturnValue(mockConfig)
  })

  it('fetches and renders documents table', async () => {
    mockApiGet.mockResolvedValue([
      { id: 'doc_1', filename: 'spec.md', contentType: 'text/markdown', createdAt: '2026-01-01T00:00:00Z' },
      { id: 'doc_2', filename: 'notes.txt', contentType: 'text/plain', createdAt: '2026-01-02T00:00:00Z' },
    ])

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerDoc } = await import('../commands/doc.js')
    const program = makeProgram()
    registerDoc(program)

    await program.parseAsync(['node', 'conductor', 'doc', 'list', 'iss_abc123'])

    expect(mockApiGet).toHaveBeenCalledWith(
      '/api/v1/projects/proj_123/issues/iss_abc123/documents',
      'test-key',
      'http://localhost:8080'
    )

    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('spec.md')
    expect(allOutput).toContain('notes.txt')
    expect(allOutput).toContain('text/markdown')

    consoleSpy.mockRestore()
  })

  it('exits 1 on API error', async () => {
    const { ApiError } = await import('../lib/api.js')
    mockApiGet.mockRejectedValue(new ApiError('Not found', 404))

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerDoc } = await import('../commands/doc.js')
    const program = makeProgram()
    registerDoc(program)

    await program.parseAsync(['node', 'conductor', 'doc', 'list', 'iss_abc123'])

    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})
