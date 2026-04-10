import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'

vi.mock('fs')
vi.mock('../api.js', () => ({
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiPatch: vi.fn(),
  apiGet: vi.fn(),
}))

const mockFs = vi.mocked(fs)

const config = {
  apiKey: 'test-key',
  projectId: 'proj_123',
  projectName: 'Test Project',
  email: 'user@test.com',
  apiUrl: 'http://localhost:8080',
  localPath: '/home/user/myproject',
}

describe('createDocument', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)
  })

  it('writes file locally and calls POST documents API', async () => {
    const { apiPost } = await import('../api.js')
    const { createDocument } = await import('../tools/documents.js')

    vi.mocked(apiPost).mockResolvedValue({
      id: 'doc_001',
      filename: 'spec.md',
      issueId: 'issue_abc',
    })

    const result = await createDocument(
      { issueId: 'issue_abc', filename: 'spec.md', content: '# Spec\n\nContent here' },
      config
    )

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(
      path.join('/home/user/myproject', '.conductor', 'issues', 'issue_abc', 'spec.md'),
      '# Spec\n\nContent here',
      'utf8'
    )

    expect(vi.mocked(apiPost)).toHaveBeenCalledWith(
      `/api/v1/projects/proj_123/issues/issue_abc/documents`,
      { filename: 'spec.md', content: '# Spec\n\nContent here', contentType: 'text/markdown' },
      config
    )

    expect(result).toMatchObject({
      documentId: 'doc_001',
      filename: 'spec.md',
      issueId: 'issue_abc',
    })
  })
})

describe('scaffoldDocument', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)
  })

  it('returns error when localPath is missing', async () => {
    const { scaffoldDocument } = await import('../tools/documents.js')
    const configWithoutLocalPath = { ...config, localPath: undefined }
    const result = await scaffoldDocument(
      { issueId: 'issue_abc', filename: 'prd.md' },
      configWithoutLocalPath
    )
    expect(result).toMatchObject({ error: expect.stringContaining('conductor init') })
  })

  it('returns existing path without overwriting when file exists', async () => {
    mockFs.existsSync.mockReturnValue(true)

    const { scaffoldDocument } = await import('../tools/documents.js')
    const result = await scaffoldDocument(
      { issueId: 'issue_abc', filename: 'prd.md' },
      config
    )
    expect(result).toMatchObject({
      localPath: '.conductor/issues/issue_abc/prd.md',
      alreadyExists: true,
    })
    expect(mockFs.writeFileSync).not.toHaveBeenCalled()
  })

  it('creates empty file and registers with backend', async () => {
    mockFs.existsSync.mockReturnValue(false)
    const { apiPost } = await import('../api.js')
    vi.mocked(apiPost).mockResolvedValue({ id: 'doc_001', filename: 'prd.md', issueId: 'issue_abc' })

    const { scaffoldDocument } = await import('../tools/documents.js')
    const result = await scaffoldDocument(
      { issueId: 'issue_abc', filename: 'prd.md' },
      config
    )

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(
      path.join('/home/user/myproject', '.conductor', 'issues', 'issue_abc', 'prd.md'),
      '',
      'utf8'
    )
    expect(result).toMatchObject({
      documentId: 'doc_001',
      localPath: '.conductor/issues/issue_abc/prd.md',
    })
  })

  it('creates local file and queues when backend fails', async () => {
    mockFs.existsSync.mockReturnValue(false)
    mockFs.readFileSync.mockImplementation(() => { throw new Error('ENOENT') })

    const { apiPost } = await import('../api.js')
    vi.mocked(apiPost).mockRejectedValue(new Error('Network error'))

    const { scaffoldDocument } = await import('../tools/documents.js')
    const result = await scaffoldDocument(
      { issueId: 'issue_abc', filename: 'prd.md' },
      config
    )

    expect(mockFs.writeFileSync).toHaveBeenCalled()
    expect(result).toMatchObject({
      localPath: '.conductor/issues/issue_abc/prd.md',
      warning: 'Backend sync failed — queued for retry',
    })
  })
})

describe('deleteDocument', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFs.unlinkSync.mockReturnValue(undefined)
  })

  it('removes local file and calls DELETE documents API', async () => {
    const { apiDelete } = await import('../api.js')
    const { deleteDocument } = await import('../tools/documents.js')

    vi.mocked(apiDelete).mockResolvedValue(undefined)

    const result = await deleteDocument(
      { issueId: 'issue_abc', documentId: 'doc_001', filename: 'spec.md' },
      config
    )

    expect(mockFs.unlinkSync).toHaveBeenCalledWith(
      path.join('/home/user/myproject', '.conductor', 'issues', 'issue_abc', 'spec.md')
    )

    expect(vi.mocked(apiDelete)).toHaveBeenCalledWith(
      `/api/v1/projects/proj_123/issues/issue_abc/documents/doc_001`,
      config
    )

    expect(result).toEqual({ success: true })
  })
})
