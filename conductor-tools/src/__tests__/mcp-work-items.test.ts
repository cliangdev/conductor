import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
  isClientError: (err: unknown) => err instanceof Error && /API error 4\d\d\b/.test(err.message),
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
import { queueChange } from '../mcp/queue.js'
import {
  createWorkItem,
  updateWorkItem,
  setWorkItemStatus,
  listWorkItems,
  getWorkItem,
} from '../mcp/tools/issues.js'
import { listWorkItemComments } from '../mcp/tools/comments.js'

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

  it('create_work_item resolves the project directory through the projects map, not only the top-level localPath', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w2', displayId: 'C-2', status: 'DRAFT' })
    const multiProject: Config = {
      ...config,
      localPath: undefined as unknown as string,
      projects: { 'proj-1': { localPath: '/tmp/proj', projectName: 'P' } },
    }
    const result = await createWorkItem({ type: 'POST', title: 'T', workflow: 'MARKETING' }, multiProject)
    expect(result).not.toHaveProperty('error')
    expect(apiPost).toHaveBeenCalled()
  })

  it('create_work_item surfaces a permanent 4xx instead of queuing a phantom item', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockRejectedValue(
      new Error("API error 400: Type 'BAD' is not allowed by workflow ENGINEERING")
    )
    const result = await createWorkItem({ type: 'BAD', title: 'T', workflow: 'ENGINEERING' }, config)
    expect(result.error).toContain('Work Item not created')
    expect(result.issueId).toBeUndefined()
    expect(queueChange).not.toHaveBeenCalled()
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

  it('list_work_item_comments passes anchor and outdated through untouched', async () => {
    const comments = [
      {
        id: 'c1',
        documentName: 'prd.html',
        lineNumber: null,
        quotedText: 'twenty of 26',
        anchor: { exact: 'twenty of 26', prefix: 'about ', suffix: ' users', sectionId: 'problem', sectionTitle: 'Problem' },
        outdated: true,
        content: 'Check this number',
      },
      { id: 'c2', lineNumber: 4, quotedText: 'x', anchor: null, outdated: false, content: 'md comment' },
    ]
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue(comments)

    const result = await listWorkItemComments({ issueId: 'w1' }, config)

    expect(result).toEqual(comments)
  })

describe('tags reach the API from MCP', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates a Work Item with tags', async () => {
    (apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1', displayId: 'CLT-1', status: 'DRAFT' })

    await createWorkItem(
      { workflow: 'MARKETING', type: 'POST', title: 'Autumn', tags: ['autumn-campaign', 'paid'] },
      config
    )

    expect((apiPost as ReturnType<typeof vi.fn>).mock.calls[0][1]).toMatchObject({
      tags: ['autumn-campaign', 'paid'],
    })
  })

  it('sends tags whole on update, so editing a title never drops one', async () => {
    (apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1' })

    await updateWorkItem({ issueId: 'w1', tags: ['evergreen'] }, config)

    expect((apiPatch as ReturnType<typeof vi.fn>).mock.calls[0][1]).toEqual({ tags: ['evergreen'] })
  })

  it('leaves tags alone when the field is omitted', async () => {
    (apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1' })

    await updateWorkItem({ issueId: 'w1', title: 'New title' }, config)

    expect((apiPatch as ReturnType<typeof vi.fn>).mock.calls[0][1]).toEqual({ title: 'New title' })
  })

  it('filters the list by tag', async () => {
    (apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])

    await listWorkItems({ workflow: 'MARKETING', tag: 'autumn-campaign' }, config)

    expect((apiGet as ReturnType<typeof vi.fn>).mock.calls[0][0]).toContain('tag=autumn-campaign')
  })
})

})

describe('fields and due dates reach the API from MCP', () => {
  beforeEach(() => vi.clearAllMocks())

  it('creates a Work Item with fields and a due date', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1', displayId: 'C-1', status: 'PLANNED' })
    await createWorkItem(
      {
        workflow: 'MARKETING_CHANGES',
        type: 'LISTING',
        title: 'Shorter title',
        fields: { hypothesis: 'Clicks go up' },
        dueOn: '2026-11-05',
      },
      config
    )
    const body = (apiPost as ReturnType<typeof vi.fn>).mock.calls[0][1]
    expect(body.fields).toEqual({ hypothesis: 'Clicks go up' })
    expect(body.dueOn).toBe('2026-11-05')
  })

  it('sends fields and dueOn on update, and nothing for what was omitted', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1' })
    await updateWorkItem({ issueId: 'w1', fields: { verdict: 'worked', control: null }, dueOn: '2026-12-01' }, config)
    expect((apiPatch as ReturnType<typeof vi.fn>).mock.calls[0][1]).toEqual({
      fields: { verdict: 'worked', control: null },
      dueOn: '2026-12-01',
    })

    ;(apiPatch as ReturnType<typeof vi.fn>).mockClear()
    await updateWorkItem({ issueId: 'w1', title: 'Renamed' }, config)
    expect((apiPatch as ReturnType<typeof vi.fn>).mock.calls[0][1]).toEqual({ title: 'Renamed' })
  })

  it('clears the due date by sending an empty string for null', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'w1' })
    await updateWorkItem({ issueId: 'w1', dueOn: null }, config)
    expect((apiPatch as ReturnType<typeof vi.fn>).mock.calls[0][1]).toEqual({ dueOn: '' })
  })

  it('surfaces a rejected fields payload as an error instead of queuing it', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('API error 400: Invalid fields: unknown field'))
    const result = await updateWorkItem({ issueId: 'w1', fields: { mood: 'x' } }, config)
    expect(result['error']).toContain('Invalid fields')
    expect(queueChange).not.toHaveBeenCalled()
  })

  it('lists overdue items with dueBefore', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    await listWorkItems({ workflow: 'MARKETING_CHANGES', dueBefore: '2026-10-22' }, config)
    expect(apiGet).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items?workflow=MARKETING_CHANGES&dueBefore=2026-10-22',
      config
    )
  })
})
