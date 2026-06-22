let onUnauthorized: (() => void) | null = null
export function setOnUnauthorized(cb: () => void) {
  onUnauthorized = cb
}

// ── Integration connections (unified connector framework) ──────────────────
//
// A "connection" is one configured instance of a connector for a project:
// a single API-key/OAuth connection for pull connectors (PostHog, GCP Billing),
// or one row per repository for the GitHub webhook connector.

export type ConnectionStatus = 'ACTIVE' | 'NEEDS_SETUP' | 'ERROR' | 'DISABLED'

export interface ConnectionSummary {
  id: string
  label?: string | null
  status: ConnectionStatus
  healthStatus?: string | null
  fetchedAt?: string | null
}

export interface ConnectionResponse {
  id: string
  connectorId: string
  label?: string | null
  status: ConnectionStatus
  authType: string
  /** Only returned for webhook connectors at creation — the URL to paste into GitHub. */
  webhookUrl?: string | null
  /** Only returned ONCE at creation — never surfaced again. */
  webhookSecret?: string | null
  connectedAt?: string | null
}

export interface WebhookEventSummary {
  id: string
  deliveryId?: string | null
  eventType: string
  status: 'PENDING' | 'PROCESSED' | 'FAILED' | 'DEAD'
  attempts: number
  errorMessage?: string | null
  receivedAt: string
}

export interface ConnectionDataResponse {
  connectionId: string
  connectorId: string
  data: Record<string, unknown> | null
  healthStatus: 'HEALTHY' | 'DEGRADED' | 'SETUP_REQUIRED'
  fetchedAt?: string | null
  isStale?: boolean
  errorMessage?: string | null
}

export function listConnections(
  projectId: string,
  connectorId: string,
  token: string,
): Promise<ConnectionSummary[]> {
  return apiGet<ConnectionSummary[]>(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/connections`,
    token,
  )
}

export function createConnection(
  projectId: string,
  connectorId: string,
  body: { label?: string; apiKey?: string; configJson?: Record<string, unknown> },
  token: string,
): Promise<ConnectionResponse> {
  return apiPost<ConnectionResponse>(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/connections`,
    body,
    token,
  )
}

export function patchConnection(
  projectId: string,
  connectorId: string,
  connectionId: string,
  body: { label?: string; config?: Record<string, unknown> },
  token: string,
): Promise<ConnectionResponse> {
  return apiPatch<ConnectionResponse>(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/connections/${connectionId}`,
    body,
    token,
  ) as Promise<ConnectionResponse>
}

export function deleteConnection(
  projectId: string,
  connectorId: string,
  connectionId: string,
  token: string,
): Promise<void> {
  return apiDelete(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/connections/${connectionId}`,
    token,
  )
}

export function listConnectionWebhookEvents(
  projectId: string,
  connectorId: string,
  connectionId: string,
  token: string,
): Promise<WebhookEventSummary[]> {
  return apiGet<WebhookEventSummary[]>(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/connections/${connectionId}/webhook-events`,
    token,
  )
}

export function fetchConnectionData(
  projectId: string,
  connectorId: string,
  connectionId: string,
  token: string,
  force = false,
): Promise<ConnectionDataResponse> {
  const path = `/api/v1/projects/${projectId}/integrations/${connectorId}/connections/${connectionId}/data`
  return force
    ? apiPost<ConnectionDataResponse>(path, {}, token)
    : apiGet<ConnectionDataResponse>(path, token)
}

async function throwApiError(res: Response): Promise<never> {
  let detail = `Server error (${res.status})`
  try {
    const contentType = res.headers.get('content-type') ?? ''
    if (contentType.includes('json')) {
      const json = await res.json()
      if (typeof json.detail === 'string') detail = json.detail
      else if (typeof json.message === 'string') detail = json.message
      else if (typeof json.error === 'string') detail = json.error
    }
  } catch { /* non-parseable body — keep default */ }
  const err = new Error(detail) as Error & { status: number }
  err.status = res.status
  throw err
}

function networkError(): never {
  throw new Error('Could not reach server — please try again')
}

export async function apiGet<T>(path: string, token: string): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch { networkError() }
  if (!res!.ok) {
    if (res!.status === 401) onUnauthorized?.()
    await throwApiError(res!)
  }
  return res!.json()
}

export async function apiPost<T>(path: string, body: unknown, token?: string): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify(body),
    })
  } catch { networkError() }
  if (!res!.ok) {
    if (res!.status === 401 && token) onUnauthorized?.()
    await throwApiError(res!)
  }
  return res!.json()
}

export async function apiPatch<T>(path: string, body: unknown, token: string): Promise<T | undefined> {
  let res: Response
  try {
    res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(body),
    })
  } catch { networkError() }
  if (!res!.ok) {
    if (res!.status === 401) onUnauthorized?.()
    await throwApiError(res!)
  }
  if (res!.status === 204 || res!.headers.get('content-length') === '0') {
    return undefined as T
  }
  return res!.json() as Promise<T>
}

export async function apiPut<T>(path: string, body: unknown, token: string): Promise<T> {
  let res: Response
  try {
    res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(body),
    })
  } catch { networkError() }
  if (!res!.ok) {
    if (res!.status === 401) onUnauthorized?.()
    await throwApiError(res!)
  }
  return res!.json()
}

export async function apiDelete(path: string, token: string): Promise<void> {
  let res: Response
  try {
    res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    })
  } catch { networkError() }
  if (!res!.ok) {
    if (res!.status === 401) onUnauthorized?.()
    await throwApiError(res!)
  }
}
