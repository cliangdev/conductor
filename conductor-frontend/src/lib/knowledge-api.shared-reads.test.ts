import { describe, it, expect, vi, beforeEach } from 'vitest'

const apiGet = vi.fn()
vi.mock('@/lib/api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/api')>()),
  apiGet: (...args: unknown[]) => apiGet(...args),
  apiPatch: vi.fn().mockResolvedValue({}),
}))

import { getKnowledgeIndex, listKnowledgeDomains, resetKnowledgeSharedReads, updateKnowledgeDomain } from './knowledge-api'

describe('knowledge shared reads', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiGet.mockImplementation(async (url: string) => (url.endsWith('/index') ? { path: 'index.md' } : []))
    resetKnowledgeSharedReads()
  })

  it('fetches the index once for the layout and the page reading it together', async () => {
    const [a, b] = await Promise.all([getKnowledgeIndex('p1', 't'), getKnowledgeIndex('p1', 't')])
    await getKnowledgeIndex('p1', 't')
    expect(a).toBe(b)
    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('fetches the domains list once for the rail footer and the page', async () => {
    await Promise.all([listKnowledgeDomains('p1', 't'), listKnowledgeDomains('p1', 't')])
    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('keeps projects apart and re-reads after a domain write', async () => {
    await listKnowledgeDomains('p1', 't')
    await listKnowledgeDomains('p2', 't')
    expect(apiGet).toHaveBeenCalledTimes(2)
    await updateKnowledgeDomain('p1', 'eng', {}, 't')
    await listKnowledgeDomains('p1', 't')
    expect(apiGet).toHaveBeenCalledTimes(3)
  })

  it('does not keep a failed read', async () => {
    apiGet.mockRejectedValueOnce(new Error('boom'))
    await expect(getKnowledgeIndex('p1', 't')).rejects.toThrow('boom')
    await getKnowledgeIndex('p1', 't')
    expect(apiGet).toHaveBeenCalledTimes(2)
  })
})
