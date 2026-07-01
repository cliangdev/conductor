import { Config } from '../config.js'
import { apiGet, apiPost } from '../api.js'

/**
 * Project-scoped skill registry tools. A lifecycle Workflow may bind a Claude Code skill from a transition
 * step, but Publish rejects a skill the project hasn't registered. list_skills discovers what's bindable
 * (built-ins + registered); register_skill adds a project skill so an authored Workflow can bind it and
 * publish without a backend redeploy.
 */

export async function listSkills(_params: Record<string, never>, config: Config): Promise<unknown[]> {
  return apiGet<unknown[]>(`/api/v1/projects/${config.projectId}/skills`, config)
}

export async function registerSkill(
  params: { skillId: string; label?: string; description?: string },
  config: Config
): Promise<Record<string, unknown>> {
  return apiPost<Record<string, unknown>>(
    `/api/v1/projects/${config.projectId}/skills`,
    { id: params.skillId, label: params.label, description: params.description },
    config
  )
}
