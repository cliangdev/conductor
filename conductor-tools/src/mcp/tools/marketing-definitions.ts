import { Config } from '../config.js'
import { apiGet } from '../api.js'

/**
 * What every marketing metric counts (`/marketing/definitions`): the source report or field, the numerator and
 * denominator in a fixed vocabulary, the traffic source type, what is excluded and the time zone that decides a
 * row's day. One compact object per metric, nulls dropped, per `docs/mcp-tool-guidelines.md`.
 */

export const MARKETING_CHANNELS = ['WEB', 'SEARCH', 'SOCIAL', 'ADS', 'STORE', 'PRODUCT'] as const

interface MetricDefinition {
  source: string
  numerator: string
  denominator?: string | null
  sourceType?: string | null
  exclusions?: string | null
  timeZone?: string | null
}

interface DefinitionEntry {
  metricRef: string
  channel: string
  key: string
  label: string
  unit: string
  aggregation: string
  definition: MetricDefinition
}

interface DefinitionsResponse {
  definitions: DefinitionEntry[]
}

function compactDefinition(d: MetricDefinition): Record<string, string> {
  const out: Record<string, string> = { source: d.source, numerator: d.numerator }
  if (d.denominator) out['denominator'] = d.denominator
  if (d.sourceType) out['sourceType'] = d.sourceType
  if (d.exclusions) out['exclusions'] = d.exclusions
  if (d.timeZone) out['timeZone'] = d.timeZone
  return out
}

export async function getMarketingDefinitions(
  config: Config,
  params: { channel?: string }
): Promise<Record<string, unknown>> {
  const channel = params.channel?.trim().toUpperCase()
  if (channel !== undefined && !(MARKETING_CHANNELS as readonly string[]).includes(channel)) {
    throw new Error(`Unknown channel "${params.channel}": use one of ${MARKETING_CHANNELS.join(', ')}`)
  }
  const response = await apiGet<DefinitionsResponse>(
    `/api/v2/projects/${config.projectId}/marketing/definitions`,
    config
  )
  const entries = channel ? response.definitions.filter((d) => d.channel === channel) : response.definitions
  return {
    definitions: entries.map((d) => ({
      metricRef: d.metricRef,
      label: d.label,
      unit: d.unit,
      aggregation: d.aggregation,
      definition: compactDefinition(d.definition),
    })),
  }
}
