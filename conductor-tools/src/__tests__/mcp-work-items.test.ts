import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
}))
vi.mock('../mcp/queue.js', () => ({
  queueChange: vi.fn(() => 1),
}))
vi.mock('../mcp/files.js', () => ({
  writeIssueFile: vi.fn(),
  readIssueFile: vi.fn(() => null),
  resolveLocalPath: vi.fn(() => '/tmp/proj'),
}))

import { apiGet, apiPost, apiPatch } from '../mcp/api.js'
import {
  createIssue,
  updateIssue,
  setIssueStatus,
  listIssues,
  getIssue,
  createWorkItem,
  updateWorkItem,
  setWorkItemStatus,
  listWorkItems,
  getWorkItem,
} from '../mcp/tools/issues.js'
import { listIssueComments, listWorkItemComments } from '../mcp/tools/comments.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}

describe('canonical work_item MCP tools target v2', () => {
  beforeEach(() => vi.clearAllMocks())

  it('create_work_item POSTs to the v2 work-items collection', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1', displayId: 'C-1', status: 'DRAFT' })
    await createWorkItem({ type: 'PRD', title: 'T', workflow: 'ENGINEERING' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items',
      { type: 'PRD', title: 'T', description: undefined, workflow: 'ENGINEERING' },
      config
    )
  })

  it('update_work_item PATCHes the v2 item', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1' })
    await updateWorkItem({ issueId: 'w1', title: 'New' }, config)
    expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/w1', { title: 'New' }, config)
  })

  it('set_work_item_status PATCHes the v2 item status', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1' })
    await setWorkItemStatus({ issueId: 'w1', status: 'IN_REVIEW' }, config)
    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1',
      { status: 'IN_REVIEW' },
      config
    )
  })

  it('list_work_items GETs the v2 collection with filters', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    await listWorkItems({ type: 'PRD', status: 'DRAFT', workflow: 'ENGINEERING' }, config)
    expect(apiGet).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items?type=PRD&status=DRAFT&workflow=ENGINEERING',
      config
    )
  })

  it('get_work_item GETs the v2 item when no local file exists', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1' })
    await getWorkItem({ issueId: 'w1' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/w1', config)
  })

  it('list_work_item_comments targets the v2 comments sub-resource', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    await listWorkItemComments({ issueId: 'w1' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/w1/comments', config)
  })
})

describe('legacy issue MCP tools stay on v1', () => {
  beforeEach(() => vi.clearAllMocks())

  it('create_issue POSTs to the v1 issues collection', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'i1', displayId: 'C-1', status: 'DRAFT' })
    await createIssue({ type: 'PRD', title: 'T' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/issues',
      { type: 'PRD', title: 'T', description: undefined, workflow: undefined },
      config
    )
  })

  it('update_issue PATCHes the v1 item', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'i1' })
    await updateIssue({ issueId: 'i1', title: 'New' }, config)
    expect(apiPatch).toHaveBeenCalledWith('/api/v1/projects/proj-1/issues/i1', { title: 'New' }, config)
  })

  it('set_issue_status PATCHes the v1 item status', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'i1' })
    await setIssueStatus({ issueId: 'i1', status: 'DONE' }, config)
    expect(apiPatch).toHaveBeenCalledWith('/api/v1/projects/proj-1/issues/i1', { status: 'DONE' }, config)
  })

  it('list_issues GETs the v1 collection', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    await listIssues({ type: 'PRD' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/issues?type=PRD', config)
  })

  it('get_issue GETs the v1 item when no local file exists', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'i1' })
    await getIssue({ issueId: 'i1' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/issues/i1', config)
  })

  it('list_issue_comments stays on v1', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    await listIssueComments({ issueId: 'i1' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/issues/i1/comments', config)
  })
})
