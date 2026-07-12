import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
}))

import { apiGet } from '../mcp/api.js'
import { listIntegrationTools, listConnectorCatalog } from '../mcp/tools/integrations.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
}

describe('integration MCP tools', () => {
  beforeEach(() => vi.clearAllMocks())

  it('list_integration_tools GETs the active-connections tools resource', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([{ connectorId: 'posthog' }])
    const result = await listIntegrationTools({}, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/integrations/tools', config)
    expect(result).toEqual([{ connectorId: 'posthog' }])
  })

  it('list_connector_catalog GETs the catalog resource', async () => {
    ;(apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      { id: 'posthog', name: 'PostHog', connected: false, activeConnectionIds: [] },
    ])
    const result = await listConnectorCatalog({}, config)
    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/integrations/catalog', config)
    expect(result).toEqual([{ id: 'posthog', name: 'PostHog', connected: false, activeConnectionIds: [] }])
  })
})
