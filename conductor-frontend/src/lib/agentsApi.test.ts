import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  listAgents,
  createAgent,
  updateAgent,
  deleteAgent,
  listAgentTools,
  listAgentProviders,
  setProviderCredential,
  getProviderCredentialStatus,
  deleteProviderCredential,
} from './api'

function okResponse(body: unknown) {
  return {
    ok: true,
    status: 200,
    headers: { get: () => 'application/json' },
    json: async () => body,
  } as unknown as Response
}

function stubFetch() {
  const fetchMock = vi.fn().mockResolvedValue(okResponse([]))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
})

const P = 'proj-1'
const TOK = 'tok'

describe('agent API helpers', () => {
  it('listAgents GETs the project agents collection', async () => {
    const f = stubFetch()
    await listAgents(P, TOK)
    const [url, opts] = f.mock.calls[0]
    expect(url).toContain(`/api/v1/projects/${P}/agents`)
    expect(opts?.headers).toMatchObject({ Authorization: `Bearer ${TOK}` })
  })

  it('createAgent POSTs the body to the collection', async () => {
    const f = stubFetch().mockResolvedValue(okResponse({ id: 'a1' }))
    await createAgent(P, { name: 'Marketer', provider: 'claude', toolIds: ['connector:posthog/x'] }, TOK)
    const [url, opts] = f.mock.calls[0]
    expect(url).toContain(`/api/v1/projects/${P}/agents`)
    expect(opts?.method).toBe('POST')
    expect(JSON.parse(opts?.body as string)).toMatchObject({ name: 'Marketer', provider: 'claude' })
  })

  it('updateAgent PATCHes the agent resource', async () => {
    const f = stubFetch().mockResolvedValue(okResponse({ id: 'a1' }))
    await updateAgent(P, 'a1', { state: 'ACTIVE' }, TOK)
    const [url, opts] = f.mock.calls[0]
    expect(url).toContain(`/api/v1/projects/${P}/agents/a1`)
    expect(opts?.method).toBe('PATCH')
    expect(JSON.parse(opts?.body as string)).toEqual({ state: 'ACTIVE' })
  })

  it('deleteAgent DELETEs the agent resource', async () => {
    const f = stubFetch().mockResolvedValue({ ok: true, status: 204, headers: { get: () => '0' } } as unknown as Response)
    await deleteAgent(P, 'a1', TOK)
    const [url, opts] = f.mock.calls[0]
    expect(url).toContain(`/api/v1/projects/${P}/agents/a1`)
    expect(opts?.method).toBe('DELETE')
  })

  it('listAgentTools and listAgentProviders hit their read endpoints', async () => {
    const f = stubFetch()
    await listAgentTools(P, TOK)
    await listAgentProviders(P, TOK)
    expect(f.mock.calls[0][0]).toContain(`/api/v1/projects/${P}/agents/tools`)
    expect(f.mock.calls[1][0]).toContain(`/api/v1/projects/${P}/agents/providers`)
  })

  it('credential helpers target the per-provider credential endpoint', async () => {
    const f = stubFetch().mockResolvedValue(okResponse({ provider: 'claude', configured: true }))
    await getProviderCredentialStatus(P, 'claude', TOK)
    await setProviderCredential(P, 'claude', 'sk-123', TOK)
    await deleteProviderCredential(P, 'claude', TOK)

    expect(f.mock.calls[0][0]).toContain(`/api/v1/projects/${P}/agents/providers/claude/credential`)
    expect(f.mock.calls[0][1]?.method ?? 'GET').toBe('GET')

    expect(f.mock.calls[1][1]?.method).toBe('PUT')
    expect(JSON.parse(f.mock.calls[1][1]?.body as string)).toEqual({ apiKey: 'sk-123' })

    expect(f.mock.calls[2][1]?.method).toBe('DELETE')
  })
})
