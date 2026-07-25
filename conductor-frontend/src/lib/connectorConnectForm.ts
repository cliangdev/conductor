import { parseServiceAccountKey } from './serviceAccountKey'
import type { ConnectorConfigField } from './api'

/**
 * Turns the generic connect form's `formValues` into the body `createConnection` expects, shared
 * by every connect flow (the integrations hub's modal and any connector's detail-page form) so a
 * fix to this mapping — e.g. the JSON-secret validation — only has to happen once.
 */
export function buildConnectionPayload(
  connector: { configFields: ConnectorConfigField[]; authType: string },
  formValues: Record<string, string>,
): { ok: true; body: { apiKey?: string; serviceAccountKey?: string; configJson: Record<string, string> } } | { ok: false; error: string } {
  const inputFields = connector.configFields.filter((f) => f.source === 'USER_INPUT')
  const secretField = inputFields.find((f) => f.secret)
  const secretValue = formValues[secretField?.key || 'apiKey'] || formValues['apiKey']
  const configJson: Record<string, string> = {}
  inputFields
    .filter((f) => !f.secret)
    .forEach((f) => {
      if (formValues[f.key]) configJson[f.key] = formValues[f.key]
    })

  if (secretField?.type === 'JSON') {
    const parsed = parseServiceAccountKey(secretValue || '')
    if (!parsed.valid) return { ok: false, error: parsed.error ?? 'Invalid key' }
  }

  return {
    ok: true,
    body: connector.authType === 'SERVICE_ACCOUNT'
      ? { serviceAccountKey: secretValue, configJson }
      : { apiKey: secretValue, configJson },
  }
}
