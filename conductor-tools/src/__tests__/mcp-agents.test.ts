import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

import { apiGet, apiPost } from '../mcp/api.js'
import { listAgents, createAgent } from '../mcp/tools/agents.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
}

describe('agent MCP tools', () => {
  beforeEach(() => vi.clearAllMocks())

  it('list_agents GETs the project agents resource', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([{ id: 'agent-1', slug: 'marketer' }])
    const result = await listAgents({}, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/agents', config)
    expect(result).toEqual([{ id: 'agent-1', slug: 'marketer' }])
  })

  it('create_agent POSTs required fields only when optionals are omitted', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'agent-1' })
    await createAgent({ name: 'Marketer', provider: 'claude' }, config)
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/agents',
      { name: 'Marketer', provider: 'claude' },
      config
    )
  })

  it('create_agent forwards optional fields, including config.runtime, when provided', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'agent-1' })
    await createAgent(
      {
        name: 'Marketer',
        provider: 'claude',
        slug: 'marketer',
        description: 'Runs campaigns',
        model: 'claude-opus-4-8',
        systemPrompt: 'You are a marketer',
        config: { temperature: 0.5, maxTokens: 4096, maxToolTurns: 10, runtime: 'claude-code' },
        toolIds: ['connector:posthog/web_analytics_summary'],
        state: 'ACTIVE',
        avatarEmoji: '📣',
        avatarColor: 'rose',
      },
      config
    )
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/agents',
      {
        name: 'Marketer',
        provider: 'claude',
        slug: 'marketer',
        description: 'Runs campaigns',
        model: 'claude-opus-4-8',
        systemPrompt: 'You are a marketer',
        config: { temperature: 0.5, maxTokens: 4096, maxToolTurns: 10, runtime: 'claude-code' },
        toolIds: ['connector:posthog/web_analytics_summary'],
        state: 'ACTIVE',
        avatarEmoji: '📣',
        avatarColor: 'rose',
      },
      config
    )
  })

  it('create_agent rejects an invalid config.runtime value without calling apiPost', async () => {
    await expect(
      createAgent(
        { name: 'Marketer', provider: 'claude', config: { runtime: 'not-a-real-runtime' as 'api' } },
        config
      )
    ).rejects.toThrow(/Invalid agent runtime/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('create_agent accepts both valid runtime values', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'agent-1' })
    await createAgent({ name: 'A', provider: 'claude', config: { runtime: 'api' } }, config)
    await createAgent({ name: 'B', provider: 'claude', config: { runtime: 'claude-code' } }, config)
    expect(apiPost).toHaveBeenCalledTimes(2)
  })
})
