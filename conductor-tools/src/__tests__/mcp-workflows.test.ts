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

import { apiGet, apiPost, apiPatch, apiDelete } from '../mcp/api.js'
import { queueChange } from '../mcp/queue.js'
import {
  listWorkflows,
  getAvailableTransitions,
  transitionWorkItem,
  recordAsset,
  reportStepRun,
  listWorkflowRuns,
  cancelWorkflowRun,
  listWorkflowSecrets,
  getWorkflowStepSchema,
  deleteWorkflow,
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

  it('list_workflow_runs GETs the runs resource with no query params by default', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([{ id: 'run-1', status: 'SUCCESS' }])
    const result = await listWorkflowRuns({ workflowId: 'wf-1' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows/wf-1/runs', config)
    expect(result).toEqual([{ id: 'run-1', status: 'SUCCESS' }])
  })

  it('list_workflow_runs forwards page/size as query params', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    await listWorkflowRuns({ workflowId: 'wf-1', page: 2, size: 10 }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows/wf-1/runs?page=2&size=10', config)
  })

  it('list_workflow_runs forwards a single status as one repeated query param', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    await listWorkflowRuns({ workflowId: 'wf-1', status: 'PENDING' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows/wf-1/runs?status=PENDING', config)
  })

  it('list_workflow_runs forwards multiple statuses as repeated query params', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([])
    await listWorkflowRuns({ workflowId: 'wf-1', status: ['PENDING', 'RUNNING'] }, config)
    expect(apiGet).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/workflows/wf-1/runs?status=PENDING&status=RUNNING',
      config
    )
  })

  it('list_workflow_runs passes waitReason through untouched (no field shaping)', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'run-1', status: 'PENDING', waitReason: 'AWAITING_RUNNER' },
      { id: 'run-2', status: 'SUCCESS', waitReason: null },
    ])
    const result = await listWorkflowRuns({ workflowId: 'wf-1' }, config)
    expect(result).toEqual([
      { id: 'run-1', status: 'PENDING', waitReason: 'AWAITING_RUNNER' },
      { id: 'run-2', status: 'SUCCESS', waitReason: null },
    ])
  })

  it('cancel_workflow_run POSTs an empty body to the run cancel resource', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'run-1', status: 'CANCELLING' })
    const result = await cancelWorkflowRun({ workflowId: 'wf-1', runId: 'run-1' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/workflows/wf-1/runs/run-1/cancel',
      {},
      config
    )
    expect(result).toEqual({ id: 'run-1', status: 'CANCELLING' })
  })

  it('list_workflow_secrets GETs the secrets resource and returns keys only, stripping other fields', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      { key: 'DISCORD_WEBHOOK_URL', createdAt: '2026-01-01', updatedAt: '2026-01-02', value: 'should-not-leak' },
      { key: 'API_TOKEN' },
    ])
    const result = await listWorkflowSecrets({}, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflow-secrets', config)
    expect(result).toEqual([{ key: 'DISCORD_WEBHOOK_URL' }, { key: 'API_TOKEN' }])
  })

  it('get_workflow_step_schema GETs the step-schema resource and passes the response through', async () => {
    const schema = {
      stepTypes: [
        { type: 'http', description: 'Make an HTTP request', fields: [{ name: 'url', type: 'STRING', required: true }] },
      ],
      interpolation: {
        roots: [{ name: 'inputs', description: 'Workflow dispatch inputs' }],
        functions: [{ name: 'toJson' }],
      },
    }
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue(schema)
    const result = await getWorkflowStepSchema({}, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows/step-schema', config)
    expect(result).toEqual(schema)
  })

  it('delete_workflow DELETEs the workflow resource', async () => {
    ;(apiDelete as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
    const result = await deleteWorkflow({ workflowId: 'wf-1' }, config)
    expect(apiDelete).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows/wf-1', config)
    expect(result).toEqual({ success: true })
  })
})
