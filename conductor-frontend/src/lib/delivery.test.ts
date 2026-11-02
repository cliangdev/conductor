import { describe, it, expect, vi, beforeEach } from 'vitest'

const { apiPatch, apiPost } = vi.hoisted(() => ({ apiPatch: vi.fn(), apiPost: vi.fn() }))
vi.mock('./api', () => ({
  apiGet: vi.fn(),
  apiPost,
  apiPatch,
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

import { addDependency, patchDelivery, priorityLabel, PRIORITIES } from './delivery'

describe('priorityLabel', () => {
  it('names each priority', () => {
    expect(priorityLabel('P0')).toBe('P0 · Urgent')
    expect(priorityLabel('P1')).toBe('P1 · High')
    expect(priorityLabel('P2')).toBe('P2 · Medium')
    expect(priorityLabel('P3')).toBe('P3 · Low')
  })

  it('says "No priority" for none', () => {
    expect(priorityLabel(null)).toBe('No priority')
    expect(priorityLabel(undefined)).toBe('No priority')
  })

  it('lists the priorities most urgent first', () => {
    expect(PRIORITIES).toEqual(['P0', 'P1', 'P2', 'P3'])
  })
})

describe('patchDelivery', () => {
  beforeEach(() => {
    apiPatch.mockReset()
    apiPatch.mockResolvedValue({ id: 'wi-1' })
  })

  it('sends set fields as they are', async () => {
    await patchDelivery('p1', 'wi-1', { priority: 'P1', appetite: '2 weeks' }, 'tok')
    expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/p1/work-items/wi-1', { priority: 'P1', appetite: '2 weeks' }, 'tok')
  })

  it('maps null to an empty string, which clears the field server-side', async () => {
    await patchDelivery('p1', 'wi-1', { priority: null, milestoneId: null, appetite: null }, 'tok')
    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v2/projects/p1/work-items/wi-1',
      { priority: '', milestoneId: '', appetite: '' },
      'tok'
    )
  })

  it('leaves out fields that are undefined', async () => {
    await patchDelivery('p1', 'wi-1', { priority: 'P0', milestoneId: undefined }, 'tok')
    expect(apiPatch.mock.calls[0][1]).toEqual({ priority: 'P0' })
  })
})

describe('addDependency', () => {
  it('posts "this kind related" on the first item', async () => {
    apiPost.mockResolvedValue({})
    await addDependency('p1', 'blocker', 'BLOCKS', 'blocked', 'tok')
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/p1/work-items/blocker/dependencies',
      { kind: 'BLOCKS', relatedWorkItemId: 'blocked' },
      'tok'
    )
  })
})
