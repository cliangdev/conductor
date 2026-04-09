import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

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
      path.join(os.homedir(), '.conductor', 'proj_123', 'issues', 'issue_abc', 'spec.md'),
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
      path.join(os.homedir(), '.conductor', 'proj_123', 'issues', 'issue_abc', 'spec.md')
    )

    expect(vi.mocked(apiDelete)).toHaveBeenCalledWith(
      `/api/v1/projects/proj_123/issues/issue_abc/documents/doc_001`,
      config
    )

    expect(result).toEqual({ success: true })
  })
})
