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

export interface ConnectionHealthResponse {
  oauthConnected: boolean
  configured: boolean
  siteUrl?: string | null
  propertyAccessible?: boolean | null
  status: string
  errorMessage?: string | null
}

export function getConnectionHealth(
  projectId: string,
  connectorId: string,
  connectionId: string,
  token: string,
): Promise<ConnectionHealthResponse> {
  return apiGet<ConnectionHealthResponse>(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/connections/${connectionId}/health`,
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

// ── GitHub App (install via GitHub, repos selected on GitHub) ───────────────

export interface GitHubInstallResponse {
  /** github.com URL to redirect the browser to so the user installs the app + picks repos. */
  installUrl: string
}

export interface GitHubRepository {
  fullName: string
  private: boolean
}

export interface GitHubRepositoriesResponse {
  repositories: GitHubRepository[]
  /** GitHub installation-settings URL — deep-link for adding/removing repositories. */
  installationHtmlUrl: string
  accountLogin: string
  repositorySelection: string
}

/** Begin a GitHub App installation for this project; returns the URL to redirect to. */
export function installGitHubApp(projectId: string, token: string): Promise<GitHubInstallResponse> {
  return apiPost<GitHubInstallResponse>(
    `/api/v1/projects/${projectId}/integrations/github/installations`,
    {},
    token,
  )
}

// ── Integration tool metadata (agent-facing) ──────────────────────────────

export interface IntegrationToolOperation {
  id: string
  description: string
  params: Record<string, string>
  outputShape: Record<string, unknown>
  outputKeys: string[]
}

export interface IntegrationToolItem {
  connectionId: string
  connectorId: string
  displayLabel: string
  capabilities: string[]
  toolMetadata: {
    description: string
    operations: IntegrationToolOperation[]
    [key: string]: unknown
  }
}

export function listIntegrationTools(projectId: string, token: string): Promise<IntegrationToolItem[]> {
  return apiGet<IntegrationToolItem[]>(
    `/api/v1/projects/${projectId}/integrations/tools`,
    token,
  )
}

/** List the repositories an installation can access (read live from GitHub). */
export function listGitHubRepositories(
  projectId: string,
  connectionId: string,
  token: string,
): Promise<GitHubRepositoriesResponse> {
  return apiGet<GitHubRepositoriesResponse>(
    `/api/v1/projects/${projectId}/integrations/github/connections/${connectionId}/repositories`,
    token,
  )
}

/**
 * A failed API call. `detail` is the backend's RFC 7807 ProblemDetail message (present only when the
 * server sent a meaningful one); `message` mirrors it, falling back to an opaque "Server error (n)".
 * `code`/`fieldErrors` are captured forward-compatibly (the backend doesn't mint codes yet).
 */
export interface ApiError extends Error {
  status: number
  detail?: string
  code?: string
  fieldErrors?: { field: string; message: string }[]
}

const NETWORK_ERROR_MESSAGE = 'Could not reach server — please try again'

async function throwApiError(res: Response): Promise<never> {
  let detail: string | undefined
  let code: string | undefined
  let fieldErrors: { field: string; message: string }[] | undefined
  try {
    const contentType = res.headers.get('content-type') ?? ''
    if (contentType.includes('json')) {
      const json = await res.json()
      if (typeof json.detail === 'string') detail = json.detail
      else if (typeof json.message === 'string') detail = json.message
      else if (typeof json.error === 'string') detail = json.error
      if (typeof json.code === 'string') code = json.code
      if (Array.isArray(json.fieldErrors)) fieldErrors = json.fieldErrors
    }
  } catch { /* non-parseable body — keep defaults */ }
  const err = new Error(detail ?? `Server error (${res.status})`) as ApiError
  err.status = res.status
  err.detail = detail
  if (code) err.code = code
  if (fieldErrors) err.fieldErrors = fieldErrors
  throw err
}

function networkError(): never {
  throw new Error(NETWORK_ERROR_MESSAGE)
}

/**
 * Pick a user-facing message: the backend's ProblemDetail `detail` when present and meaningful,
 * otherwise the caller's `fallback` (used for opaque "Server error (n)" and network failures).
 * This is the universal way to surface backend errors — components should call this instead of
 * branching on `status` with hardcoded strings.
 */
export function apiErrorMessage(err: unknown, fallback: string): string {
  if (err && typeof err === 'object') {
    const detail = (err as Partial<ApiError>).detail
    if (typeof detail === 'string' && detail.trim()) return detail
  }
  return fallback
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
