import { Config } from '../config.js'
import { apiGet } from '../api.js'

/**
 * Integration discovery tools for workflow authoring.
 * Always call list_integration_tools before designing a workflow — it tells Claude
 * which data sources are connected and what operations each supports, so the workflow
 * YAML can reference `uses: integration / connector: <id>` instead of raw HTTP steps
 * with manually interpolated secrets.
 */

export async function listIntegrationTools(_params: Record<string, never>, config: Config): Promise<unknown[]> {
  return apiGet<unknown[]>(`/api/v1/projects/${config.projectId}/integrations/tools`, config)
}
