import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  isClientError: (err: unknown) => err instanceof Error && /API error 4\d\d\b/.test(err.message),
}))
vi.mock('../mcp/queue.js', () => ({ queueChange: vi.fn(() => 1) }))
vi.mock('../mcp/files.js', () => ({
  writeIssueFile: vi.fn(),
  readIssueFile: vi.fn(() => null),
  resolveLocalPath: vi.fn(() => '/tmp/proj'),
}))

import { apiGet, apiPost, apiPatch, apiPut, apiDelete } from '../mcp/api.js'
import { writeIssueFile } from '../mcp/files.js'
import { createWorkItem, updateWorkItem, listWorkItems, buildListQuery, toCompactRow } from '../mcp/tools/issues.js'
import {
  getWorkItemTree,
  addDependency,
  removeDependency,
  setCriteria,
  markCriterionMet,
  setMilestone,
  reportAgentActivity,
} from '../mcp/tools/delivery.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const P = '/api/v2/projects/proj-1'
const mock = (fn: unknown) => fn as ReturnType<typeof vi.fn>

beforeEach(() => vi.resetAllMocks())

describe('list_work_items', () => {
  it('maps params to the query string, sending booleans only when true', () => {
    const qs = buildListQuery({
      parentId: 'any',
      rootId: 'r1',
      milestoneId: 'm1',
      priority: 'P1',
      assigneeId: 'u1',
      ready: true,
      blocked: false,
      stale: true,
      sort: 'priority',
    })
    const q = new URLSearchParams(qs)
    expect(Object.fromEntries(q)).toEqual({
      parentId: 'any',
      rootId: 'r1',
      milestoneId: 'm1',
      priority: 'P1',
      assigneeId: 'u1',
      ready: 'true',
      stale: 'true',
      sort: 'priority',
    })
  })

  it('returns compact rows', async () => {
    mock(apiGet).mockResolvedValue([
      {
        id: 'w1',
        displayId: 'C-1',
        title: 'T',
        type: 'TASK',
        status: 'OPEN',
        sequenceNumber: 1,
        priority: 'P0',
        childCount: 3,
        signals: { blocked: true, ready: false, stale: false },
        assignee: { userId: 'u1', name: 'Ann', avatarUrl: 'x' },
        description: 'long text we drop',
      },
      { id: 'w2', displayId: 'C-2', title: 'U', type: 'PRD', status: 'DRAFT' },
    ])
    const rows = await listWorkItems({ ready: true }, config)
    expect(apiGet).toHaveBeenCalledWith(`${P}/work-items?ready=true`, config)
    expect(rows[0]).toEqual({
      id: 'w1',
      displayId: 'C-1',
      title: 'T',
      type: 'TASK',
      status: 'OPEN',
      sequenceNumber: 1,
      priority: 'P0',
      childCount: 3,
      blocked: true,
      ready: false,
      assignee: { userId: 'u1', name: 'Ann' },
    })
    expect(rows[1]).toEqual({
      id: 'w2',
      displayId: 'C-2',
      title: 'U',
      type: 'PRD',
      status: 'DRAFT',
      sequenceNumber: undefined,
      priority: null,
      childCount: 0,
      blocked: false,
      ready: false,
    })
    expect(toCompactRow({ id: 'x' })).not.toHaveProperty('assignee')
  })
})

describe('create/update_work_item delivery params', () => {
  it('skips the local folder for a child task and omits localPath', async () => {
    mock(apiPost).mockResolvedValue({ id: 'c1', displayId: 'C-5', status: 'TODO' })
    const result = await createWorkItem(
      { type: 'TASK', title: 'Child', parentWorkItemId: 'p1', priority: 'P2', blockedBy: ['b1'] },
      config
    )
    expect(writeIssueFile).not.toHaveBeenCalled()
    expect(result).not.toHaveProperty('localPath')
    expect(result['issueId']).toBe('c1')
    expect(mock(apiPost).mock.calls[0]![1]).toMatchObject({ parentWorkItemId: 'p1', priority: 'P2', blockedBy: ['b1'] })
  })

  it('still writes the local folder for a top-level item', async () => {
    mock(apiPost).mockResolvedValue({ id: 'w1', displayId: 'C-1', status: 'DRAFT' })
    const result = await createWorkItem({ type: 'PRD', title: 'T', workflow: 'ENGINEERING' }, config)
    expect(writeIssueFile).toHaveBeenCalledTimes(1)
    expect(result['localPath']).toBe('.conductor/issues/w1/')
  })

  it('update sends assignee/parent/priority/milestone/appetite, null clearing with ""', async () => {
    mock(apiPatch).mockResolvedValue({})
    await updateWorkItem(
      { issueId: 'w1', assigneeId: 'u1', parentWorkItemId: null, priority: null, milestoneId: 'm1', appetite: '2w' },
      config
    )
    expect(mock(apiPatch).mock.calls[0]![1]).toEqual({
      assigneeId: 'u1',
      parentWorkItemId: '',
      priority: '',
      milestoneId: 'm1',
      appetite: '2w',
    })
  })
})

describe('delivery tools', () => {
  it('get_work_item_tree strips nulls and avatar URLs recursively', async () => {
    mock(apiGet).mockResolvedValue({
      id: 'a',
      title: 'Root',
      priority: null,
      assignee: { userId: 'u', name: 'N', avatarUrl: 'http://x' },
      children: [{ id: 'b', agentActivity: null, children: [] }],
    })
    expect(await getWorkItemTree({ issueId: 'a' }, config)).toEqual({
      id: 'a',
      title: 'Root',
      assignee: { userId: 'u', name: 'N' },
      children: [{ id: 'b', children: [] }],
    })
    expect(apiGet).toHaveBeenCalledWith(`${P}/work-items/a/tree`, config)
  })

  it('add/remove dependency', async () => {
    mock(apiPost).mockResolvedValue({
      blocks: [{ dependencyId: 'd0', id: 'z' }, { dependencyId: 'd1', id: 'b' }],
      blockedBy: [],
      discoveredFrom: [],
      discovered: [],
    })
    expect(await addDependency({ issueId: 'a', kind: 'BLOCKS', relatedIssueId: 'b' }, config)).toEqual({ dependencyId: 'd1' })
    expect(apiPost).toHaveBeenCalledWith(`${P}/work-items/a/dependencies`, { kind: 'BLOCKS', relatedWorkItemId: 'b' }, config)
    expect(await removeDependency({ issueId: 'a', dependencyId: 'd1' }, config)).toEqual({ removed: true })
    expect(apiDelete).toHaveBeenCalledWith(`${P}/work-items/a/dependencies/d1`, config)
  })

  it('set_criteria replaces all and returns ids', async () => {
    mock(apiPut).mockResolvedValue([{ criterionId: 'c1', externalRef: 'AC-1', text: 'x' }, { criterionId: 'c2', text: 'y' }])
    const out = await setCriteria({ issueId: 'a', criteria: [{ text: 'x', externalRef: 'AC-1' }, { text: 'y' }] }, config)
    expect(out).toEqual([{ criterionId: 'c1', externalRef: 'AC-1' }, { criterionId: 'c2', externalRef: null }])
    expect(mock(apiPut).mock.calls[0]![1]).toEqual([
      { text: 'x', kind: 'auto', externalRef: 'AC-1' },
      { text: 'y', kind: 'auto', externalRef: undefined },
    ])
  })

  describe('mark_criterion_met', () => {
    it('resolves externalRef via GET criteria, then computes the summary when the PATCH lacks it', async () => {
      mock(apiGet).mockResolvedValue([
        { criterionId: 'c1', externalRef: 'AC-1', met: false },
        { criterionId: 'c2', externalRef: 'AC-2', met: true },
      ])
      mock(apiPatch).mockResolvedValue({ criterionId: 'c1', met: true })
      const out = await markCriterionMet({ issueId: 'a', externalRef: 'AC-1', met: true, evidence: 'ok' }, config)
      expect(apiPatch).toHaveBeenCalledWith(`${P}/work-items/a/criteria/c1`, { met: true, evidence: 'ok' }, config)
      // second GET is the follow-up for the summary
      expect(apiGet).toHaveBeenCalledTimes(2)
      expect(out).toEqual({ criterionId: 'c1', met: true, criteriaMet: 1, criteriaTotal: 2 })
    })

    it('uses criterionId directly and the summary from the PATCH response', async () => {
      mock(apiPatch).mockResolvedValue({ criterionId: 'c1', met: false, criteriaMet: 0, criteriaTotal: 4 })
      const out = await markCriterionMet({ issueId: 'a', criterionId: 'c1', met: false }, config)
      expect(apiGet).not.toHaveBeenCalled()
      expect(out).toEqual({ criterionId: 'c1', met: false, criteriaMet: 0, criteriaTotal: 4 })
    })

    it('errors on an unknown externalRef without patching', async () => {
      mock(apiGet).mockResolvedValue([{ criterionId: 'c1', externalRef: 'AC-1' }])
      const out = await markCriterionMet({ issueId: 'a', externalRef: 'NOPE', met: true }, config)
      expect(out).toHaveProperty('error')
      expect(apiPatch).not.toHaveBeenCalled()
    })
  })

  describe('set_milestone upsert', () => {
    it('POSTs when no milestone has that name', async () => {
      mock(apiGet).mockResolvedValue([{ id: 'm1', name: 'Other' }])
      mock(apiPost).mockResolvedValue({ id: 'm2', name: 'Beta' })
      const out = await setMilestone({ name: ' Beta ', targetOn: '2026-12-01' }, config)
      expect(apiPost).toHaveBeenCalledWith(`${P}/milestones`, { name: 'Beta', targetOn: '2026-12-01' }, config)
      expect(apiPatch).not.toHaveBeenCalled()
      expect(out).toEqual({ id: 'm2', name: 'Beta' })
    })

    it('PATCHes when the name exists', async () => {
      mock(apiGet).mockResolvedValue([{ id: 'm1', name: 'Beta' }])
      const out = await setMilestone({ name: 'beta', state: 'CLOSED' }, config)
      expect(apiPatch).toHaveBeenCalledWith(`${P}/milestones/m1`, { state: 'CLOSED' }, config)
      expect(apiPost).not.toHaveBeenCalled()
      expect(out).toEqual({ id: 'm1', name: 'Beta' })
    })
  })

  it('report_agent_activity defaults mode and agentLabel', async () => {
    mock(apiPut).mockResolvedValue({})
    const out = await reportAgentActivity({ issueId: 'a', sessionKey: 's 1', activity: 'WORKING', note: 'n' }, config)
    expect(apiPut).toHaveBeenCalledWith(
      `${P}/work-items/a/agent-sessions/s%201`,
      { agentLabel: 'claude-code', mode: 'DELEGATE', activity: 'WORKING', note: 'n' },
      config
    )
    expect(out).toEqual({ sessionKey: 's 1', activity: 'WORKING' })
  })
})
