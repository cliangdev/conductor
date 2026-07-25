import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
}))

import { apiGet, apiPost, apiPatch, apiDelete } from '../mcp/api.js'
import { listAgents, createAgent, updateAgent, deleteAgent } from '../mcp/tools/agents.js'

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

  it('create_agent rejects an invalid avatarColor without calling apiPost', async () => {
    await expect(
      createAgent({ name: 'Marketer', provider: 'claude', avatarColor: 'purple' }, config)
    ).rejects.toThrow(/Invalid agent avatarColor/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('create_agent accepts every valid avatarColor token', async () => {
    ;(apiPost as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'agent-1' })
    const colors = ['gray', 'blue', 'amber', 'violet', 'teal', 'green', 'rose', 'slate']
    for (const avatarColor of colors) {
      await createAgent({ name: 'A', provider: 'claude', avatarColor }, config)
    }
    expect(apiPost).toHaveBeenCalledTimes(colors.length)
  })

  it('update_agent PATCHes only the supplied fields', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'agent-1' })
    await updateAgent({ agentId: 'agent-1', state: 'DRAFT' }, config)
    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/agents/agent-1',
      { state: 'DRAFT' },
      config
    )
  })

  it('update_agent forwards every optional field when provided', async () => {
    ;(apiPatch as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'agent-1' })
    await updateAgent(
      {
        agentId: 'agent-1',
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
    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v1/projects/proj-1/agents/agent-1',
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

  it('update_agent rejects invalid avatarColor and runtime values without calling apiPatch', async () => {
    await expect(
      updateAgent({ agentId: 'agent-1', avatarColor: 'purple' }, config)
    ).rejects.toThrow(/Invalid agent avatarColor/)
    await expect(
      updateAgent({ agentId: 'agent-1', config: { runtime: 'nope' as 'api' } }, config)
    ).rejects.toThrow(/Invalid agent runtime/)
    expect(apiPatch).not.toHaveBeenCalled()
  })

  it('delete_agent DELETEs the agent resource', async () => {
    ;(apiDelete as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
    const result = await deleteAgent({ agentId: 'agent-1' }, config)
    expect(apiDelete).toHaveBeenCalledWith('/api/v1/projects/proj-1/agents/agent-1', config)
    expect(result).toEqual({ success: true })
  })
})
