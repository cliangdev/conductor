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

import { apiGet, apiPost, apiPatch } from '../mcp/api.js'
import { queueChange } from '../mcp/queue.js'
import {
  listWorkflows,
  getAvailableTransitions,
  transitionWorkItem,
  recordAsset,
  reportStepRun,
} from '../mcp/tools/workflows.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
}

describe('workflow-aware MCP tools', () => {
  beforeEach(() => vi.clearAllMocks())

  it('list_workflows GETs the workflows resource and flattens each entry', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      {
        id: 'wf-1',
        slug: 'ENGINEERING',
        name: 'Engineering',
        area: 'ENGINEERING',
        noun: 'Issue',
        kind: 'LIFECYCLE',
        state: 'PUBLISHED',
        version: 1,
        definition: { types: ['PRD'], statuses: [{ id: 'DRAFT' }] },
      },
    ])
    const result = await listWorkflows({}, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows', config)
    expect(result).toEqual([
      {
        slug: 'ENGINEERING',
        name: 'Engineering',
        area: 'ENGINEERING',
        noun: 'Issue',
        kind: 'LIFECYCLE',
        state: 'PUBLISHED',
        version: 1,
        workflowId: 'wf-1',
        types: ['PRD'],
        statuses: [{ id: 'DRAFT' }],
      },
    ])
  })

  it('list_workflows pushes the kind filter server-side via ?lifecycle', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'wf-2', name: 'SEO report', kind: 'AUTOMATION' },
    ])
    await listWorkflows({ kind: 'AUTOMATION' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows?lifecycle=false', config)

    ;(apiGet as ReturnType<typeof vi.fn>).mockClear()
    await listWorkflows({ kind: 'LIFECYCLE' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows?lifecycle=true', config)
  })

  it('get_available_transitions GETs the v2 work-item projection', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue({ workflow: 'ENGINEERING', transitions: [] })
    await getAvailableTransitions({ issueId: 'i1' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/i1/available-transitions', config)
  })

  it('transition_work_item PATCHes the v2 work-item status', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ status: 'IN_REVIEW' })
    await transitionWorkItem({ issueId: 'i1', toStatus: 'IN_REVIEW' }, config)
    expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/i1', { status: 'IN_REVIEW' }, config)
  })

  it('transition_work_item queues on failure', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'))
    const result = await transitionWorkItem({ issueId: 'i1', toStatus: 'DONE' }, config)
    expect(queueChange).toHaveBeenCalled()
    expect(result.warning).toContain('queued')
  })

  it('record_asset POSTs to the v2 work-item assets resource', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'a1' })
    await recordAsset({ issueId: 'i1', type: 'github_pr', kind: 'link', ref: 'https://x/pr/1' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/i1/assets',
      { type: 'github_pr', kind: 'link', ref: 'https://x/pr/1', label: undefined, done: undefined },
      config
    )
  })

  it('report_step_run POSTs to the v2 work-item step-runs resource without issueId in the body', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 's1' })
    await reportStepRun(
      { issueId: 'i1', stepKind: 'skill', status: 'AWAITING_REVIEW', inputBrief: 'do x', reportedBy: 'me', skill: 'conductor:implement' },
      config
    )
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/i1/step-runs',
      { stepKind: 'skill', status: 'AWAITING_REVIEW', inputBrief: 'do x', reportedBy: 'me', skill: 'conductor:implement' },
      config
    )
  })
})
