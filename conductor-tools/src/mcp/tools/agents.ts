import { Config } from '../config.js'
import { apiGet, apiPost, apiPatch, apiDelete } from '../api.js'

/**
 * Agent discovery for workflow authoring. Call list_agents to learn which named AI Agents a
 * project has (slug + provider + state) before referencing one from a workflow `agent` step.
 */

export async function listAgents(_params: Record<string, never>, config: Config): Promise<unknown[]> {
  return apiGet<unknown[]>(`/api/v1/projects/${config.projectId}/agents`, config)
}

/** {@code AgentConfig.runtime} -- mirrors the enum AgentService validates on the backend write path. */
const VALID_RUNTIMES = new Set(['api', 'claude-code'])

/** {@code Agent.avatarColor} -- mirrors the token set AgentService validates on the backend write path. */
const VALID_AVATAR_COLORS = new Set([
  'gray',
  'blue',
  'amber',
  'violet',
  'teal',
  'green',
  'rose',
  'slate',
])

function validateAgentFields(params: { config?: { runtime?: string }; avatarColor?: string }): void {
  const runtime = params.config?.runtime
  if (runtime !== undefined && !VALID_RUNTIMES.has(runtime)) {
    throw new Error(
      `Invalid agent runtime: ${runtime} (expected one of ${[...VALID_RUNTIMES].join(', ')})`
    )
  }
  const avatarColor = params.avatarColor
  if (avatarColor !== undefined && !VALID_AVATAR_COLORS.has(avatarColor)) {
    throw new Error(
      `Invalid agent avatarColor: ${avatarColor} (expected one of ${[...VALID_AVATAR_COLORS].join(', ')})`
    )
  }
}

export interface CreateAgentParams {
  name: string
  provider: string
  slug?: string
  description?: string
  model?: string
  systemPrompt?: string
  config?: {
    temperature?: number
    maxTokens?: number
    maxToolTurns?: number
    runtime?: 'api' | 'claude-code'
  }
  toolIds?: string[]
  state?: 'DRAFT' | 'ACTIVE'
  avatarEmoji?: string
  avatarColor?: string
}

/**
 * Creates a named AI Agent in the project. Fails client-side (no network call) on an invalid
 * `config.runtime` or `avatarColor` value so a bad value never round-trips to the backend.
 */
export async function createAgent(
  params: CreateAgentParams,
  config: Config
): Promise<Record<string, unknown>> {
  validateAgentFields(params)

  const body: Record<string, unknown> = {
    name: params.name,
    provider: params.provider,
  }
  if (params.slug !== undefined) body['slug'] = params.slug
  if (params.description !== undefined) body['description'] = params.description
  if (params.model !== undefined) body['model'] = params.model
  if (params.systemPrompt !== undefined) body['systemPrompt'] = params.systemPrompt
  if (params.config !== undefined) body['config'] = params.config
  if (params.toolIds !== undefined) body['toolIds'] = params.toolIds
  if (params.state !== undefined) body['state'] = params.state
  if (params.avatarEmoji !== undefined) body['avatarEmoji'] = params.avatarEmoji
  if (params.avatarColor !== undefined) body['avatarColor'] = params.avatarColor

  return apiPost<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/agents`,
    body,
    config
  )
}

export interface UpdateAgentParams extends Partial<CreateAgentParams> {
  agentId: string
}

/** Partial update — only supplied fields are sent, so omitted fields keep their stored value. */
export async function updateAgent(
  params: UpdateAgentParams,
  config: Config
): Promise<Record<string, unknown>> {
  validateAgentFields(params)

  const { agentId, ...rest } = params
  const body: Record<string, unknown> = {}
  if (rest.name !== undefined) body['name'] = rest.name
  if (rest.provider !== undefined) body['provider'] = rest.provider
  if (rest.slug !== undefined) body['slug'] = rest.slug
  if (rest.description !== undefined) body['description'] = rest.description
  if (rest.model !== undefined) body['model'] = rest.model
  if (rest.systemPrompt !== undefined) body['systemPrompt'] = rest.systemPrompt
  if (rest.config !== undefined) body['config'] = rest.config
  if (rest.toolIds !== undefined) body['toolIds'] = rest.toolIds
  if (rest.state !== undefined) body['state'] = rest.state
  if (rest.avatarEmoji !== undefined) body['avatarEmoji'] = rest.avatarEmoji
  if (rest.avatarColor !== undefined) body['avatarColor'] = rest.avatarColor

  return apiPatch<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/agents/${agentId}`,
    body,
    config
  )
}

export async function deleteAgent(
  params: { agentId: string },
  config: Config
): Promise<Record<string, unknown>> {
  await apiDelete(`/api/v1/projects/${config.projectId}/agents/${params.agentId}`, config)
  return { success: true }
}
