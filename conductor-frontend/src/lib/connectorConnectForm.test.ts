import { describe, expect, it } from 'vitest'
import { buildConnectionPayload, defaultConnectionLabel } from './connectorConnectForm'
import { cadenceLabel } from '@/components/integrations/ConnectorFeedsPanel'

const connector = {
  authType: 'API_KEY',
  configFields: [
    { key: 'baseUrl', label: 'Metrics URL', source: 'USER_INPUT', secret: false, type: 'STRING', required: true },
    { key: 'apiKey', label: 'API key', source: 'USER_INPUT', secret: true, type: 'SECRET', required: true },
  ],
} as unknown as Parameters<typeof buildConnectionPayload>[0]

describe('defaultConnectionLabel', () => {
  it('uses the connector name for the first connection and counts after that', () => {
    expect(defaultConnectionLabel('Product metrics', 0)).toBe('Product metrics')
    expect(defaultConnectionLabel('Product metrics', 1)).toBe('Product metrics 2')
    expect(defaultConnectionLabel('Product metrics', 2)).toBe('Product metrics 3')
  })
})

describe('buildConnectionPayload', () => {
  it('sends the label so a new connection is never unnamed', () => {
    const result = buildConnectionPayload(connector, { baseUrl: 'https://x.test', apiKey: 'k' }, 'Product metrics 2')
    expect(result).toEqual({
      ok: true,
      body: { label: 'Product metrics 2', apiKey: 'k', configJson: { baseUrl: 'https://x.test' } },
    })
  })
})

describe('cadenceLabel', () => {
  it('reads minutes as hours and days when they divide evenly', () => {
    expect(cadenceLabel(360)).toBe('Every 6 hours')
    expect(cadenceLabel(120)).toBe('Every 2 hours')
    expect(cadenceLabel(60)).toBe('Every hour')
    expect(cadenceLabel(2880)).toBe('Every 2 days')
    expect(cadenceLabel(15)).toBe('Every 15 minutes')
    expect(cadenceLabel(1)).toBe('Every 1 minute')
  })
})
