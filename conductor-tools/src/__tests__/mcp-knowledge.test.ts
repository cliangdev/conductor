import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

import { apiGet, apiPost } from '../mcp/api.js'
import {
  submitKnowledgeSource,
  readKnowledgeSources,
  searchKnowledge,
  readKnowledgePages,
  writeKnowledgePages,
} from '../mcp/tools/knowledge.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
}

describe('knowledge MCP tools', () => {
  beforeEach(() => vi.clearAllMocks())

  it('submit_knowledge_source POSTs only the provided fields', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ sourceId: 's1', status: 'ACCEPTED' })
    const result = await submitKnowledgeSource({ sourceType: 'observation', payload: 'hello' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/knowledge/sources',
      { sourceType: 'observation', payload: 'hello' },
      config
    )
    expect(result).toEqual({ sourceId: 's1', status: 'ACCEPTED' })
  })

  it('submit_knowledge_source passes DUPLICATE status straight through', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ sourceId: 's1', status: 'DUPLICATE' })
    const result = await submitKnowledgeSource(
      { sourceType: 'observation', payload: 'hello', dedupKey: 'k1' },
      config
    )
    expect(result).toEqual({ sourceId: 's1', status: 'DUPLICATE' })
  })

  it('submit_knowledge_source includes all optional fields when given', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ sourceId: 's2', status: 'ACCEPTED' })
    await submitKnowledgeSource(
      {
        sourceType: 'github_pr',
        sourceRef: 'https://github.com/x/y/pull/1',
        title: 'A PR',
        contentType: 'text/markdown',
        occurredAt: '2026-07-01T00:00:00Z',
        dedupKey: 'pr-1',
        metadata: { repo: 'x/y' },
      },
      config
    )
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/knowledge/sources',
      {
        sourceType: 'github_pr',
        sourceRef: 'https://github.com/x/y/pull/1',
        title: 'A PR',
        contentType: 'text/markdown',
        occurredAt: '2026-07-01T00:00:00Z',
        dedupKey: 'pr-1',
        metadata: { repo: 'x/y' },
      },
      config
    )
  })

  it('read_knowledge_sources GETs the sources resource with ids joined as CSV', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([{ sourceId: 's1' }, { sourceId: 's2' }])
    const result = await readKnowledgeSources({ ids: ['s1', 's2'] }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/knowledge/sources?ids=s1%2Cs2', config)
    expect(result).toEqual([{ sourceId: 's1' }, { sourceId: 's2' }])
  })

  it('read_knowledge_sources passes purgedAt through untouched for a retention-compacted source', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      { sourceId: 's1', payload: null, purgedAt: '2026-06-01T00:00:00Z' },
      { sourceId: 's2', payload: 'still here', purgedAt: null },
    ])
    const result = await readKnowledgeSources({ ids: ['s1', 's2'] }, config)
    expect(result).toEqual([
      { sourceId: 's1', payload: null, purgedAt: '2026-06-01T00:00:00Z' },
      { sourceId: 's2', payload: 'still here', purgedAt: null },
    ])
  })

  it('search_knowledge GETs the search resource with q required and optional filters', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([{ path: 'a.md', rank: 0.9 }])
    await searchKnowledge({ q: 'deploy' }, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/knowledge/search?q=deploy', config)

    ;(apiGet as ReturnType<typeof vi.fn>).mockClear()
    await searchKnowledge({ q: 'deploy', type: 'runbook', pathPrefix: 'ops/', limit: 5 }, config)
    expect(apiGet).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/knowledge/search?q=deploy&type=runbook&pathPrefix=ops%2F&limit=5',
      config
    )
  })

  it('read_knowledge_pages GETs the pages resource with paths joined as CSV', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([{ path: 'index.md', version: 3 }])
    const result = await readKnowledgePages({ paths: ['index.md', 'ops/deploy.md'] }, config)
    expect(apiGet).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/knowledge/pages?paths=index.md%2Cops%2Fdeploy.md',
      config
    )
    expect(result).toEqual([{ path: 'index.md', version: 3 }])
  })

  it('write_knowledge_pages POSTs writes and optional sourceIds', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({
      results: [{ path: 'ops/deploy.md', version: 2, contentHash: 'abc' }],
    })
    const result = await writeKnowledgePages(
      {
        writes: [{ path: 'ops/deploy.md', content: '# Deploy', baseVersion: 1 }],
        sourceIds: ['s1'],
      },
      config
    )
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/knowledge/pages/batch-write',
      { writes: [{ path: 'ops/deploy.md', content: '# Deploy', baseVersion: 1 }], sourceIds: ['s1'] },
      config
    )
    expect(result).toEqual({ results: [{ path: 'ops/deploy.md', version: 2, contentHash: 'abc' }] })
  })

  it('write_knowledge_pages omits sourceIds from the body when not given', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ results: [] })
    await writeKnowledgePages({ writes: [{ path: 'a.md', content: 'x' }] }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/knowledge/pages/batch-write',
      { writes: [{ path: 'a.md', content: 'x' }] },
      config
    )
  })

  it('write_knowledge_pages surfaces a 409 conflict as structured data, not a thrown error', async () => {
    const conflictBody = JSON.stringify({
      type: 'about:blank',
      title: 'Conflict',
      conflicts: [{ path: 'a.md', currentVersion: 4, currentContent: '# Current' }],
    })
    // vi.fn's rejected-promise mock can trip an unhandled-rejection warning even when the caller
    // awaits and catches it — use a plain stub function instead (see repo memory:
    // reference_vitest_rejected_promise_mock).
    const rejecting = () => Promise.reject(new Error(`API error 409: ${conflictBody}`))
    ;(apiPost as unknown as ReturnType<typeof vi.fn>).mockImplementation(rejecting)

    const result = await writeKnowledgePages(
      { writes: [{ path: 'a.md', content: 'stale write', baseVersion: 1 }] },
      config
    )

    expect(result['conflict']).toBe(true)
    expect(result['conflicts']).toEqual([{ path: 'a.md', currentVersion: 4, currentContent: '# Current' }])
    expect(typeof result['message']).toBe('string')
  })

  it('write_knowledge_pages rethrows non-409 errors (e.g. 422 invalid frontmatter)', async () => {
    const rejecting = () => Promise.reject(new Error('API error 422: {"title":"Invalid frontmatter"}'))
    ;(apiPost as unknown as ReturnType<typeof vi.fn>).mockImplementation(rejecting)

    await expect(
      writeKnowledgePages({ writes: [{ path: 'a.md', content: 'bad' }] }, config)
    ).rejects.toThrow('API error 422')
  })
})
