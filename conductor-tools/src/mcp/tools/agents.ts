import { Config } from '../config.js'
import { apiGet } from '../api.js'

/**
 * Agent discovery for workflow authoring. Call list_agents to learn which named AI Agents a
 * project has (slug + provider + state) before referencing one from a workflow `agent` step.
 */

export async function listAgents(_params: Record<string, never>, config: Config): Promise<unknown[]> {
  return apiGet<unknown[]>(`/api/v1/projects/${config.projectId}/agents`, config)
}
