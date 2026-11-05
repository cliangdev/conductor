import { describe, it, expect, vi, beforeEach } from 'vitest'

const { apiPatch, apiPost } = vi.hoisted(() => ({ apiPatch: vi.fn(), apiPost: vi.fn() }))
vi.mock('./api', () => ({
  apiGet: vi.fn(),
  apiPost,
  apiPatch,
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

import { addDependency, forecastLine, patchDelivery, priorityLabel, PRIORITIES } from './delivery'

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

describe('forecastLine', () => {
  const fmt = (d: string) => `<${d}>`
  const base = { state: 'OK' as const, remaining: 4, samples: 12, p50Date: '2026-11-12', p85Date: '2026-11-20' }

  it('names the terminal states', () => {
    expect(forecastLine({ state: 'DONE', remaining: 0, samples: 0 }, fmt)).toBe('Done')
    expect(forecastLine({ state: 'NO_PLAN', remaining: 0, samples: 0 }, fmt)).toBe('No plan yet')
  })

  it('says how many more completed tasks are needed', () => {
    expect(forecastLine({ state: 'INSUFFICIENT_DATA', remaining: 5, samples: 3, needed: 8 }, fmt)).toBe(
      'Needs 8 completed tasks (3 so far), 5 more'
    )
  })

  it('shows both percentiles and the appetite fit', () => {
    expect(forecastLine({ ...base, appetiteFit: 'FITS' }, fmt)).toBe('P50 <2026-11-12> · P85 <2026-11-20> · fits appetite')
    expect(forecastLine({ ...base, appetiteFit: 'TIGHT' }, fmt)).toBe('P50 <2026-11-12> · P85 <2026-11-20> · tight for the appetite')
  })

  it('counts the days over the appetite', () => {
    expect(forecastLine({ ...base, appetiteFit: 'EXCEEDS', appetiteEndsOn: '2026-11-15' }, fmt)).toContain('exceeds the appetite by 5 days')
    expect(forecastLine({ ...base, appetiteFit: 'EXCEEDS', appetiteEndsOn: '2026-11-19' }, fmt)).toContain('by 1 day')
  })

  it('counts whole days across a clock change', () => {
    // 2026-11-01 is the US fall-back day: a UTC-based or hour-based count would be off by one there.
    expect(forecastLine({ ...base, p85Date: '2026-11-03', appetiteFit: 'EXCEEDS', appetiteEndsOn: '2026-10-31' }, fmt)).toContain('by 3 days')
    expect(forecastLine({ ...base, p85Date: '2026-03-10', appetiteFit: 'EXCEEDS', appetiteEndsOn: '2026-03-07' }, fmt)).toContain('by 3 days')
  })

  it('says whether the milestone fits, alone or beside the appetite', () => {
    expect(forecastLine({ ...base, milestoneFit: 'FITS' }, fmt)).toBe('P50 <2026-11-12> · P85 <2026-11-20> · fits the milestone')
    expect(forecastLine({ ...base, milestoneFit: 'TIGHT' }, fmt)).toContain('tight for the milestone')
    expect(forecastLine({ ...base, appetiteFit: 'FITS', milestoneFit: 'TIGHT' }, fmt)).toBe(
      'P50 <2026-11-12> · P85 <2026-11-20> · fits appetite · tight for the milestone'
    )
  })

  it('counts the days a milestone is missed by when its target is known', () => {
    expect(forecastLine({ ...base, milestoneFit: 'EXCEEDS' }, fmt, '2026-11-18')).toContain('misses the milestone by 2 days')
    expect(forecastLine({ ...base, milestoneFit: 'EXCEEDS' }, fmt, '2026-11-19')).toContain('misses the milestone by 1 day')
    expect(forecastLine({ ...base, milestoneFit: 'EXCEEDS' }, fmt)).toMatch(/misses the milestone$/)
  })

  it('omits P85 in cycle-time mode and the fit when there is none', () => {
    expect(forecastLine({ state: 'OK', mode: 'CYCLE_TIME', remaining: 2, samples: 4, p50Date: '2026-11-12' }, fmt)).toBe('P50 <2026-11-12>')
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
