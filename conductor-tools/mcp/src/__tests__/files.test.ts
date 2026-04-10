import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'

vi.mock('fs')

const mockFs = vi.mocked(fs)

const configWithLocalPath = {
  apiKey: 'test-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'user@test.com',
  apiUrl: 'http://localhost:8080',
  localPath: '/home/user/myproject',
}

const configWithoutLocalPath = {
  apiKey: 'test-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'user@test.com',
  apiUrl: 'http://localhost:8080',
}

describe('getLocalIssueDir', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('throws error when localPath is missing from config', async () => {
    const { getLocalIssueDir } = await import('../files.js')
    expect(() => getLocalIssueDir(configWithoutLocalPath, 'issue_abc')).toThrow(
      'Run conductor init to set up local project directory'
    )
  })

  it('returns correct path when localPath is present', async () => {
    const { getLocalIssueDir } = await import('../files.js')
    const result = getLocalIssueDir(configWithLocalPath, 'issue_abc')
    expect(result).toBe(path.join('/home/user/myproject', '.conductor', 'issues', 'issue_abc'))
  })
})

describe('issueFilePath', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('returns issue.md path under localPath', async () => {
    const { issueFilePath } = await import('../files.js')
    const result = issueFilePath(configWithLocalPath, 'issue_xyz')
    expect(result).toBe(
      path.join('/home/user/myproject', '.conductor', 'issues', 'issue_xyz', 'issue.md')
    )
  })

  it('throws when localPath is absent', async () => {
    const { issueFilePath } = await import('../files.js')
    expect(() => issueFilePath(configWithoutLocalPath, 'issue_xyz')).toThrow(
      'Run conductor init to set up local project directory'
    )
  })
})

describe('writeIssueFile', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)
  })

  it('writes file at correct path', async () => {
    const { writeIssueFile } = await import('../files.js')
    writeIssueFile(configWithLocalPath, 'issue_abc', 'content')
    const expectedPath = path.join(
      '/home/user/myproject',
      '.conductor',
      'issues',
      'issue_abc',
      'issue.md'
    )
    expect(mockFs.writeFileSync).toHaveBeenCalledWith(expectedPath, 'content', 'utf8')
  })
})

describe('readIssueFile', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('returns file content when file exists', async () => {
    mockFs.readFileSync.mockReturnValue('# Issue content')
    const { readIssueFile } = await import('../files.js')
    const result = readIssueFile(configWithLocalPath, 'issue_abc')
    expect(result).toBe('# Issue content')
  })

  it('returns null when file does not exist', async () => {
    mockFs.readFileSync.mockImplementation(() => {
      throw new Error('ENOENT')
    })
    const { readIssueFile } = await import('../files.js')
    const result = readIssueFile(configWithLocalPath, 'issue_abc')
    expect(result).toBeNull()
  })
})

describe('deleteDocumentFile', () => {
  beforeEach(() => {
    vi.resetAllMocks()
  })

  it('unlinks file at correct path', async () => {
    mockFs.unlinkSync.mockReturnValue(undefined)
    const { deleteDocumentFile } = await import('../files.js')
    deleteDocumentFile(configWithLocalPath, 'issue_abc', 'spec.md')
    const expectedPath = path.join(
      '/home/user/myproject',
      '.conductor',
      'issues',
      'issue_abc',
      'spec.md'
    )
    expect(mockFs.unlinkSync).toHaveBeenCalledWith(expectedPath)
  })

  it('does not throw when file does not exist', async () => {
    mockFs.unlinkSync.mockImplementation(() => {
      throw new Error('ENOENT')
    })
    const { deleteDocumentFile } = await import('../files.js')
    expect(() => deleteDocumentFile(configWithLocalPath, 'issue_abc', 'spec.md')).not.toThrow()
  })
})
