import { describe, it, expect, beforeEach, vi } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import * as os from 'os'

vi.mock('fs')
vi.mock('../api.js', () => ({
  apiPost: vi.fn(),
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

describe('createIssue', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)
    mockFs.readFileSync.mockImplementation(() => {
      throw new Error('ENOENT')
    })
  })

  it('calls backend POST and writes local file', async () => {
    const { apiPost } = await import('../api.js')
    const { createIssue } = await import('../tools/issues.js')

    vi.mocked(apiPost).mockResolvedValue({
      id: 'issue_abc',
      type: 'task',
      title: 'My Task',
      status: 'DRAFT',
    })

    const result = await createIssue({ type: 'task', title: 'My Task' }, config)

    expect(vi.mocked(apiPost)).toHaveBeenCalledWith(
      `/api/v1/projects/proj_123/issues`,
      { type: 'task', title: 'My Task', description: undefined },
      config
    )

    expect(mockFs.writeFileSync).toHaveBeenCalledWith(
      path.join(os.homedir(), '.conductor', 'proj_123', 'issues', 'issue_abc', 'issue.md'),
      expect.stringContaining('id: issue_abc'),
      'utf8'
    )

    expect(result).toMatchObject({
      issueId: 'issue_abc',
      type: 'task',
      title: 'My Task',
      status: 'DRAFT',
    })
  })

  it('writes local file at correct path with correct frontmatter', async () => {
    const { apiPost } = await import('../api.js')
    const { createIssue } = await import('../tools/issues.js')

    vi.mocked(apiPost).mockResolvedValue({
      id: 'issue_xyz',
      type: 'epic',
      title: 'My Epic',
      status: 'DRAFT',
    })

    await createIssue({ type: 'epic', title: 'My Epic', description: 'Some content' }, config)

    const writeCall = mockFs.writeFileSync.mock.calls[0]
    const content = writeCall?.[1] as string

    expect(content).toContain('---')
    expect(content).toContain('id: issue_xyz')
    expect(content).toContain('type: epic')
    expect(content).toContain('title: My Epic')
    expect(content).toContain('status: DRAFT')
    expect(content).toContain('Some content')
  })

  it('queues change and returns warning when backend fails', async () => {
    const { apiPost } = await import('../api.js')
    const { createIssue } = await import('../tools/issues.js')

    vi.mocked(apiPost).mockRejectedValue(new Error('Network error'))

    // Mock readFileSync for queue (returns empty queue)
    mockFs.readFileSync.mockImplementation((filePath) => {
      if (String(filePath).includes('sync-queue')) {
        throw new Error('ENOENT')
      }
      throw new Error('ENOENT')
    })

    const result = await createIssue({ type: 'task', title: 'Offline Task' }, config)

    expect(result).toMatchObject({
      type: 'task',
      title: 'Offline Task',
      status: 'DRAFT',
      warning: 'Sync failed — change queued',
    })
    expect(typeof result['queueSize']).toBe('number')
    expect(mockFs.writeFileSync).toHaveBeenCalled()
  })
})

describe('setIssueStatus', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockFs.mkdirSync.mockReturnValue(undefined)
    mockFs.writeFileSync.mockReturnValue(undefined)
  })

  it('updates frontmatter status and calls PATCH', async () => {
    const { apiPatch } = await import('../api.js')
    const { setIssueStatus } = await import('../tools/issues.js')

    vi.mocked(apiPatch).mockResolvedValue({
      id: 'issue_abc',
      type: 'task',
      title: 'My Task',
      status: 'IN_PROGRESS',
    })

    const existingContent = `---\nid: issue_abc\ntype: task\ntitle: My Task\nstatus: DRAFT\n---\n\nDescription`
    mockFs.readFileSync.mockReturnValue(existingContent)

    const result = await setIssueStatus({ issueId: 'issue_abc', status: 'IN_PROGRESS' }, config)

    expect(vi.mocked(apiPatch)).toHaveBeenCalledWith(
      `/api/v1/projects/proj_123/issues/issue_abc`,
      { status: 'IN_PROGRESS' },
      config
    )

    const writeCall = mockFs.writeFileSync.mock.calls[0]
    const updatedContent = writeCall?.[1] as string
    expect(updatedContent).toContain('status: IN_PROGRESS')
    expect(updatedContent).not.toContain('status: DRAFT')

    expect(result).toMatchObject({ issueId: 'issue_abc', status: 'IN_PROGRESS' })
  })

  it('queues change and returns warning when backend PATCH fails', async () => {
    const { apiPatch } = await import('../api.js')
    const { setIssueStatus } = await import('../tools/issues.js')

    vi.mocked(apiPatch).mockRejectedValue(new Error('Server error'))

    mockFs.readFileSync.mockImplementation((filePath) => {
      const fp = String(filePath)
      if (fp.includes('sync-queue')) throw new Error('ENOENT')
      return `---\nid: issue_abc\ntype: task\ntitle: My Task\nstatus: DRAFT\n---\n\n`
    })

    const result = await setIssueStatus({ issueId: 'issue_abc', status: 'DONE' }, config)

    expect(result).toMatchObject({
      issueId: 'issue_abc',
      status: 'DONE',
      warning: 'Sync failed — change queued',
    })
  })
})
