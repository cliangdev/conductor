import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { useDraftPosts } from './useDraftPosts'

const API = 'https://api.test'

function json(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, headers: { get: () => 'application/json' }, json: async () => body }
}

const MARKETING_WORKFLOW = { id: 'wf-1', projectId: 'proj-a', name: 'Posts', slug: 'MARKETING', area: 'marketing', enabled: true, kind: 'LIFECYCLE' }
const ENGINEERING_WORKFLOW = { id: 'wf-2', projectId: 'proj-a', name: 'Issues', slug: 'ENGINEERING', area: 'engineering', enabled: true, kind: 'LIFECYCLE' }

const MARKETING_VIEW = {
  slug: 'MARKETING',
  noun: 'Post',
  area: 'marketing',
  defaultView: 'calendar',
  version: 1,
  types: ['POST'],
  assetTypes: ['instagram_post'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open', initial: true },
    { id: 'SCHEDULED', label: 'Scheduled', category: 'in_progress' },
  ],
  transitions: [],
}

const ENGINEERING_VIEW = {
  slug: 'ENGINEERING',
  noun: 'Issue',
  area: 'engineering',
  defaultView: 'list',
  version: 1,
  types: ['ISSUE'],
  assetTypes: ['github_pr'],
  statuses: [{ id: 'BACKLOG', label: 'Backlog', category: 'open', initial: true }],
  transitions: [],
}

const fetchMock = vi.fn(async (url: string) => {
  if (url.includes('/workflows?lifecycle=true')) return json(200, [MARKETING_WORKFLOW, ENGINEERING_WORKFLOW])
  if (url.endsWith('/workflows/by-slug/MARKETING')) return json(200, MARKETING_VIEW)
  if (url.endsWith('/workflows/by-slug/ENGINEERING')) return json(200, ENGINEERING_VIEW)
  if (url.includes('/work-items?workflow=MARKETING')) {
    return json(200, [
      { id: 'wi-1', displayId: 'MK-18', title: 'Week 3 launch', type: 'POST', status: 'DRAFT', createdAt: '', updatedAt: '' },
      { id: 'wi-2', displayId: 'MK-20', title: 'Already scheduled', type: 'POST', status: 'SCHEDULED', createdAt: '', updatedAt: '' },
    ])
  }
  if (url.includes('/work-items?workflow=ENGINEERING')) {
    return json(200, [{ id: 'wi-9', displayId: 'ENG-1', title: 'Fix a bug', type: 'ISSUE', status: 'BACKLOG', createdAt: '', updatedAt: '' }])
  }
  throw new Error(`unexpected GET ${url}`)
})

beforeEach(() => {
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

describe('useDraftPosts', () => {
  it('only returns Posts from publish-capable Workflows, in their initial (Draft) status', async () => {
    const { result } = renderHook(() => useDraftPosts('proj-a', 'tok'))

    await waitFor(() => expect(result.current.posts).not.toBeNull())

    expect(result.current.posts).toEqual([
      { id: 'wi-1', displayId: 'MK-18', title: 'Week 3 launch', workflowSlug: 'MARKETING', area: 'marketing', noun: 'Post' },
    ])
    expect(result.current.postWorkflows).toEqual([{ slug: 'MARKETING', area: 'marketing', noun: 'Post' }])
  })
})
