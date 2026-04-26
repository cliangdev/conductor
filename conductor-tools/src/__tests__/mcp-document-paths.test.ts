import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import * as os from 'os'
import * as path from 'path'

vi.mock('../mcp/api.js', () => ({
  apiPost: vi.fn(),
  apiGet: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
}))

vi.mock('../mcp/queue.js', () => ({
  queueChange: vi.fn().mockReturnValue(1),
}))

import { scaffoldDocument } from '../mcp/tools/documents.js'
import { createIssue, getIssue } from '../mcp/tools/issues.js'
import { apiPost, apiGet } from '../mcp/api.js'
import type { Config } from '../mcp/config.js'

const mockedApiPost = vi.mocked(apiPost)
const mockedApiGet = vi.mocked(apiGet)

let tmpRoot: string
let baseConfig: Config

beforeEach(() => {
  tmpRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'conductor-mcp-paths-'))
  baseConfig = {
    apiKey: 'k',
    projectId: 'proj_test',
    projectName: 'Test',
    email: 'u@example.com',
    apiUrl: 'http://localhost:8080',
    localPath: tmpRoot,
  }
  mockedApiPost.mockReset()
  mockedApiGet.mockReset()
})

afterEach(() => {
  fs.rmSync(tmpRoot, { recursive: true, force: true })
})

describe('scaffoldDocument absolutePath', () => {
  const issueId = 'iss_abc123'

  it('returns an absolute path that the Write tool can use directly', async () => {
    mockedApiPost.mockResolvedValueOnce({ id: 'doc_1', filename: 'prd.md', issueId })

    const result = await scaffoldDocument({ issueId, filename: 'prd.md' }, baseConfig)

    expect(result['absolutePath']).toBe(
      path.join(tmpRoot, '.conductor', 'issues', issueId, 'prd.md')
    )
    expect(path.isAbsolute(result['absolutePath'] as string)).toBe(true)
    expect(result['localPath']).toBe(`.conductor/issues/${issueId}/prd.md`)
    expect(fs.existsSync(result['absolutePath'] as string)).toBe(true)
  })

  it('returns absolutePath when the file already exists', async () => {
    const filePath = path.join(tmpRoot, '.conductor', 'issues', issueId, 'prd.md')
    fs.mkdirSync(path.dirname(filePath), { recursive: true })
    fs.writeFileSync(filePath, 'existing content', 'utf8')

    const result = await scaffoldDocument({ issueId, filename: 'prd.md' }, baseConfig)

    expect(result['alreadyExists']).toBe(true)
    expect(result['absolutePath']).toBe(filePath)
    expect(mockedApiPost).not.toHaveBeenCalled()
  })

  it('returns absolutePath even when backend sync fails (queued path)', async () => {
    mockedApiPost.mockRejectedValueOnce(new Error('network down'))

    const result = await scaffoldDocument({ issueId, filename: 'prd.md' }, baseConfig)

    expect(result['absolutePath']).toBe(
      path.join(tmpRoot, '.conductor', 'issues', issueId, 'prd.md')
    )
    expect(result['warning']).toMatch(/queued/i)
  })
})

describe('createIssue absolutePath', () => {
  it('returns absolute issue directory path', async () => {
    mockedApiPost.mockResolvedValueOnce({
      id: 'iss_xyz',
      displayId: 'COND-1',
      type: 'PRD',
      title: 'T',
      status: 'DRAFT',
    })

    const result = await createIssue(
      { type: 'PRD', title: 'Test PRD' },
      baseConfig
    )

    const expected = path.join(tmpRoot, '.conductor', 'issues', 'iss_xyz') + path.sep
    expect(result['absolutePath']).toBe(expected)
    expect(path.isAbsolute(result['absolutePath'] as string)).toBe(true)
    expect(result['localPath']).toBe('.conductor/issues/iss_xyz/')
  })
})

describe('getIssue absolutePath', () => {
  const issueId = 'iss_local'

  it('returns absolutePath for a local issue', async () => {
    const issueDir = path.join(tmpRoot, '.conductor', 'issues', issueId)
    fs.mkdirSync(issueDir, { recursive: true })
    fs.writeFileSync(path.join(issueDir, 'issue.md'), '---\nid: iss_local\n---\n', 'utf8')

    const result = await getIssue({ issueId }, baseConfig)

    expect(result['source']).toBe('local')
    expect(result['absolutePath']).toBe(issueDir + path.sep)
    expect(result['localPath']).toBe(`.conductor/issues/${issueId}/`)
  })

  it('returns absolutePath for a remote-only issue', async () => {
    mockedApiGet.mockResolvedValueOnce({
      id: issueId,
      displayId: 'COND-9',
      type: 'PRD',
      title: 'T',
      status: 'DRAFT',
    })

    const result = await getIssue({ issueId }, baseConfig)

    expect(result['absolutePath']).toBe(
      path.join(tmpRoot, '.conductor', 'issues', issueId) + path.sep
    )
    expect(result['localPath']).toBe(`.conductor/issues/${issueId}/`)
  })
})
