import { describe, it, expect } from 'vitest'
import { parseServiceAccountKey } from './serviceAccountKey'

const VALID_KEY = JSON.stringify({
  type: 'service_account',
  project_id: 'my-gcp-project',
  client_email: 'runner@my-gcp-project.iam.gserviceaccount.com',
  private_key: '-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----\n',
})

describe('parseServiceAccountKey', () => {
  it('accepts a well-formed service account key and extracts projectId/clientEmail', () => {
    const result = parseServiceAccountKey(VALID_KEY)
    expect(result.valid).toBe(true)
    expect(result.projectId).toBe('my-gcp-project')
    expect(result.clientEmail).toBe('runner@my-gcp-project.iam.gserviceaccount.com')
    expect(result.error).toBeUndefined()
  })

  it('rejects empty input', () => {
    const result = parseServiceAccountKey('')
    expect(result.valid).toBe(false)
    expect(result.error).toBeTruthy()
  })

  it('rejects invalid JSON', () => {
    const result = parseServiceAccountKey('{not json')
    expect(result.valid).toBe(false)
    expect(result.error).toMatch(/valid json/i)
  })

  it('rejects JSON that is not a service account key (wrong type)', () => {
    const result = parseServiceAccountKey(JSON.stringify({ type: 'authorized_user', project_id: 'p' }))
    expect(result.valid).toBe(false)
    expect(result.error).toMatch(/service account/i)
  })

  it('rejects a service account key missing project_id', () => {
    const result = parseServiceAccountKey(JSON.stringify({ type: 'service_account' }))
    expect(result.valid).toBe(false)
    expect(result.error).toMatch(/project_id/i)
  })

  it('rejects a JSON array (not an object)', () => {
    const result = parseServiceAccountKey('[1,2,3]')
    expect(result.valid).toBe(false)
  })
})
