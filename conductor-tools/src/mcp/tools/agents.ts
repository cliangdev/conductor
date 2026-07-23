import { Config } from '../config.js'
import { apiGet, apiPost } from '../api.js'

/**
 * Agent discovery for workflow authoring. Call list_agents to learn which named AI Agents a
 * project has (slug + provider + state) before referencing one from a workflow `agent` step.
 */

export async function listAgents(_params: Record<string, never>, config: Config): Promise<unknown[]> {
  return apiGet<unknown[]>(`/api/v1/projects/${config.projectId}/agents`, config)
}

/** {@code AgentConfig.runtime} -- mirrors the enum AgentService validates on the backend write path. */
const VALID_RUNTIMES = new Set(['api', 'claude-code'])

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
 * `config.runtime` value so a bad value never round-trips to the backend.
 */
export async function createAgent(
  params: CreateAgentParams,
  config: Config
): Promise<Record<string, unknown>> {
  const runtime = params.config?.runtime
  if (runtime !== undefined && !VALID_RUNTIMES.has(runtime)) {
    throw new Error(
      `Invalid agent runtime: ${runtime} (expected one of ${[...VALID_RUNTIMES].join(', ')})`
    )
  }

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
