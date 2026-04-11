import { describe, it, expect, beforeEach, vi } from 'vitest'
import { Command } from 'commander'

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

describe('issue create command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    mockReadConfig.mockReturnValue(mockConfig)
  })

  it('calls POST with correct body and prints issue ID', async () => {
    mockApiPost.mockResolvedValue({ id: 'iss_abc123', title: 'Test PRD', status: 'DRAFT', type: 'PRD' })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync(['node', 'conductor', 'issue', 'create', '--type', 'PRD', '--title', 'Test PRD'])

    expect(mockApiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj_123/issues',
      { type: 'PRD', title: 'Test PRD' },
      'test-key',
      'http://localhost:8080'
    )
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('iss_abc123'))
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('Test PRD'))

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('includes description in POST body when provided', async () => {
    mockApiPost.mockResolvedValue({ id: 'iss_abc123', title: 'Test PRD', status: 'DRAFT', type: 'PRD' })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync([
      'node', 'conductor', 'issue', 'create',
      '--type', 'PRD', '--title', 'Test PRD', '--description', 'some desc'
    ])

    expect(mockApiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj_123/issues',
      { type: 'PRD', title: 'Test PRD', description: 'some desc' },
      'test-key',
      'http://localhost:8080'
    )

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })

  it('exits 1 on API error', async () => {
    const { ApiError } = await import('../lib/api.js')
    mockApiPost.mockRejectedValue(new ApiError('Server error', 500))

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync(['node', 'conductor', 'issue', 'create', '--type', 'PRD', '--title', 'Fail'])

    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})

describe('issue list command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    mockReadConfig.mockReturnValue(mockConfig)
  })

  it('renders issues in table format', async () => {
    mockApiGet.mockResolvedValue([
      { id: 'iss_abc123', type: 'PRD', status: 'DRAFT', title: 'My Feature PRD' },
      { id: 'iss_def456', type: 'BUG_REPORT', status: 'IN_REVIEW', title: 'Another issue' },
    ])

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync(['node', 'conductor', 'issue', 'list'])

    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('iss_abc123')
    expect(allOutput).toContain('My Feature PRD')
    expect(allOutput).toContain('iss_def456')
    expect(allOutput).toContain('Another issue')
    expect(allOutput).toContain('ID')
    expect(allOutput).toContain('TYPE')
    expect(allOutput).toContain('STATUS')
    expect(allOutput).toContain('TITLE')

    consoleSpy.mockRestore()
  })

  it('passes type and status filters to API', async () => {
    mockApiGet.mockResolvedValue([])

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync(['node', 'conductor', 'issue', 'list', '--type', 'PRD', '--status', 'DRAFT'])

    expect(mockApiGet).toHaveBeenCalledWith(
      '/api/v1/projects/proj_123/issues?type=PRD&status=DRAFT',
      'test-key',
      'http://localhost:8080'
    )

    consoleSpy.mockRestore()
  })

  it('exits 1 on API error', async () => {
    const { ApiError } = await import('../lib/api.js')
    mockApiGet.mockRejectedValue(new ApiError('Server error', 500))

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync(['node', 'conductor', 'issue', 'list'])

    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})

describe('issue get command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    mockReadConfig.mockReturnValue(mockConfig)
  })

  it('fetches and prints issue details', async () => {
    mockApiGet.mockResolvedValue({
      id: 'iss_abc123',
      type: 'PRD',
      status: 'DRAFT',
      title: 'My Feature PRD',
      description: 'A description',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-02T00:00:00Z',
    })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync(['node', 'conductor', 'issue', 'get', 'iss_abc123'])

    const allOutput = consoleSpy.mock.calls.map((c) => c[0]).join('\n')
    expect(allOutput).toContain('iss_abc123')
    expect(allOutput).toContain('PRD')
    expect(allOutput).toContain('DRAFT')
    expect(allOutput).toContain('My Feature PRD')

    consoleSpy.mockRestore()
  })
})

describe('issue set-status command', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.resetAllMocks()
    mockReadConfig.mockReturnValue(mockConfig)
  })

  it('calls PATCH and prints success message', async () => {
    mockApiPatch.mockResolvedValue({ id: 'iss_abc123', status: 'IN_REVIEW' })

    const consoleSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync(['node', 'conductor', 'issue', 'set-status', 'iss_abc123', 'IN_REVIEW'])

    expect(mockApiPatch).toHaveBeenCalledWith(
      '/api/v1/projects/proj_123/issues/iss_abc123',
      { status: 'IN_REVIEW' },
      'test-key',
      'http://localhost:8080'
    )
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('iss_abc123'))
    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('IN_REVIEW'))

    consoleSpy.mockRestore()
  })

  it('prints API error and exits 1 on invalid status', async () => {
    const { ApiError } = await import('../lib/api.js')
    mockApiPatch.mockRejectedValue(new ApiError('Invalid status transition', 400))

    const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const exitSpy = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)

    const { registerIssue } = await import('../commands/issue.js')
    const program = makeProgram()
    registerIssue(program)

    await program.parseAsync(['node', 'conductor', 'issue', 'set-status', 'iss_abc123', 'INVALID'])

    expect(consoleSpy).toHaveBeenCalledWith(expect.stringContaining('Invalid status transition'))
    expect(exitSpy).toHaveBeenCalledWith(1)

    consoleSpy.mockRestore()
    exitSpy.mockRestore()
  })
})
