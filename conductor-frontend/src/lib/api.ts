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
  authType?: string | null
  tokenExpiresAt?: string | null
  healthStatus?: string | null
  fetchedAt?: string | null
}

export interface ConnectionResponse {
  id: string
  connectorId: string
  label?: string | null
  status: ConnectionStatus
  authType: string
  tokenExpiresAt?: string | null
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
  body: {
    label?: string;
    apiKey?: string;
    /** SERVICE_ACCOUNT connectors (e.g. gcp) — the SA JSON key. Write-only; never returned. */
    serviceAccountKey?: string;
    configJson?: Record<string, unknown>;
  },
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

// ── Connector feeds (Knowledge Center ingestion — see docs/knowledge.md) ───

export type ConnectorFeedStatus = 'ACTIVE' | 'PAUSED' | 'SETUP_REQUIRED' | 'DEAD'

export interface ConnectorFeedDto {
  id: string
  ingestId: string
  label: string
  description?: string | null
  enabled: boolean
  intervalMinutes: number
  status: ConnectorFeedStatus
  lastRunAt?: string | null
  lastSuccessAt?: string | null
  lastError?: string | null
  consecutiveFailures: number
  nextRunAt: string
  isMetricFeed: boolean
}

export function listConnectorFeeds(
  projectId: string,
  connectorId: string,
  token: string,
): Promise<ConnectorFeedDto[]> {
  return apiGet<ConnectorFeedDto[]>(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/feeds`,
    token,
  )
}

/** Partial update — only `enabled`/`intervalMinutes` are settable; cursor/status/nextRunAt are
 *  platform-owned scheduling state. */
export function updateConnectorFeed(
  projectId: string,
  connectorId: string,
  feedId: string,
  body: { enabled?: boolean; intervalMinutes?: number },
  token: string,
): Promise<ConnectorFeedDto> {
  return apiPatch<ConnectorFeedDto>(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/feeds/${feedId}`,
    body,
    token,
  ) as Promise<ConnectorFeedDto>
}

/** "Sync now" — re-dues the feed for the next scheduler tick; never pulls inline. */
export function runConnectorFeedNow(
  projectId: string,
  connectorId: string,
  feedId: string,
  token: string,
): Promise<ConnectorFeedDto> {
  return apiPost<ConnectorFeedDto>(
    `/api/v1/projects/${projectId}/integrations/${connectorId}/feeds/${feedId}/runs`,
    {},
    token,
  )
}

export interface ConnectorConfigField {
  key: string
  label: string
  hint: string | null
  type: 'STRING' | 'SECRET' | 'SELECT' | 'MULTISELECT' | 'BOOLEAN' | 'URL_READONLY' | 'JSON'
  source: 'USER_INPUT' | 'GENERATED'
  required: boolean
  secret: boolean
}

export interface IntegrationListItem {
  connectorId: string
  name: string
  category: string
  authType: 'NONE' | 'API_KEY' | 'BASIC' | 'OAUTH2' | 'WEBHOOK' | 'APP' | 'SERVICE_ACCOUNT'
  capabilities: string[]
  singleInstance: boolean
  description: string
  iconLabel: string
  connected: boolean
  configFields: ConnectorConfigField[]
  connections: ConnectionSummary[]
}

export function listIntegrations(projectId: string, token: string): Promise<IntegrationListItem[]> {
  return apiGet<IntegrationListItem[]>(`/api/v1/projects/${projectId}/integrations`, token)
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

/**
 * Bind (or rotate) the project's single GitHub Personal Access Token connection. Takes precedence
 * over an App-install connection for credential resolution while ACTIVE. Binding again replaces
 * the existing PAT in place.
 */
export function bindGitHubPat(
  projectId: string,
  body: { token: string; label?: string; expiresAt?: string },
  token: string,
): Promise<ConnectionResponse> {
  return apiPost<ConnectionResponse>(
    `/api/v1/projects/${projectId}/integrations/github/pat`,
    body,
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

// ── AI Agents (named personas, BYO provider keys, tool bindings) ────────────
//
// User-managed agents under /projects/{id}/agents. Provider credentials are
// per-(project, provider) and write-only — the API only reports whether a key
// is configured, never the key itself.

export type AgentState = 'DRAFT' | 'ACTIVE'

/** Generation guardrails applied at run time. All fields optional. */
export interface AgentConfig {
  temperature?: number | null
  maxTokens?: number | null
  maxToolTurns?: number | null
  /** True when a human can talk to this agent directly in a conversation (Discord's /ask, the
   *  conversation REST API) by name or slug -- opt-in per agent. Mirrored read-only at
   *  `Agent.addressable` for display. */
  addressable?: boolean | null
}

export interface Agent {
  id: string
  projectId: string
  name: string
  slug: string
  description?: string | null
  provider: string
  /** Null means the provider's default model applies. */
  model?: string | null
  systemPrompt?: string | null
  config?: AgentConfig
  /** Namespaced tool ids (e.g. connector:posthog/web_analytics_summary). */
  toolIds: string[]
  state: AgentState
  /** Always present — the server derives a deterministic default from the slug when unset. */
  avatarEmoji: string
  /** Always present, one of the 8 avatar identity tokens (see AgentAvatar) — server-derived when unset. */
  avatarColor: string
  /** True for agents seeded by Conductor (e.g. the knowledge-librarian) rather than created by a
   *  project member. Deleting one is allowed — it is recreated the next time the owning feature
   *  self-heals. */
  isDefault: boolean
  /** True when a human can talk to this agent directly in a conversation (Discord's /ask, the
   *  conversation REST API) by name or slug -- opt-in per agent via config.addressable. */
  addressable: boolean
  /** Free-text grouping tag (e.g. "engineering"); "default"/"system" are reserved server-side. */
  tag?: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateAgentBody {
  name: string
  slug?: string
  description?: string
  tag?: string | null
  provider: string
  model?: string
  systemPrompt?: string
  config?: AgentConfig
  toolIds?: string[]
  state?: AgentState
  avatarEmoji?: string
  avatarColor?: string
}

/**
 * Partial update — only supplied fields change. For `toolIds`: omit the key to leave bindings
 * unchanged, or send an empty array to clear all bindings (e.g. a state-only toggle must NOT send
 * `toolIds`, or it would be sent as `[]` and wipe the bindings).
 */
export type UpdateAgentBody = Partial<CreateAgentBody>

/** A tool an agent can be bound to, from any source (connector, http, builtin, mcp). */
export interface AvailableAgentTool {
  id: string
  name: string
  description?: string | null
  source: string
}

export interface AgentProviderInfo {
  id: string
  defaultModel?: string | null
  /** True when leaving `model` blank resolves to the newest discovered model at run time (e.g.
   *  OpenAI); false when it resolves to the fixed `defaultModel` (e.g. Claude's pinned constant). */
  defaultModelIsLive: boolean
}

export interface ProviderVerificationSummary {
  status: 'verified' | 'error'
  checkedAt: string
  error?: string | null
}

export interface ProviderCredentialStatus {
  provider: string
  configured: boolean
  verification?: ProviderVerificationSummary | null
}

export interface VerificationCheck {
  name: string
  status: 'pass' | 'fail' | 'warn'
  message: string
}

export interface ProviderVerificationReport {
  provider: string
  status: 'verified' | 'error'
  checkedAt: string
  checks: VerificationCheck[]
}

export function listAgents(projectId: string, token: string): Promise<Agent[]> {
  return apiGet<Agent[]>(`/api/v1/projects/${projectId}/agents`, token)
}

export function getAgent(projectId: string, agentId: string, token: string): Promise<Agent> {
  return apiGet<Agent>(`/api/v1/projects/${projectId}/agents/${agentId}`, token)
}

export function createAgent(projectId: string, body: CreateAgentBody, token: string): Promise<Agent> {
  return apiPost<Agent>(`/api/v1/projects/${projectId}/agents`, body, token)
}

export function updateAgent(
  projectId: string,
  agentId: string,
  body: UpdateAgentBody,
  token: string,
): Promise<Agent> {
  return apiPatch<Agent>(`/api/v1/projects/${projectId}/agents/${agentId}`, body, token) as Promise<Agent>
}

export function deleteAgent(projectId: string, agentId: string, token: string): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/agents/${agentId}`, token)
}

export function listAgentTools(projectId: string, token: string): Promise<AvailableAgentTool[]> {
  return apiGet<AvailableAgentTool[]>(`/api/v1/projects/${projectId}/agents/tools`, token)
}

export function listAgentProviders(projectId: string, token: string): Promise<AgentProviderInfo[]> {
  return apiGet<AgentProviderInfo[]>(`/api/v1/projects/${projectId}/agents/providers`, token)
}

export interface ProviderModelInfo {
  id: string
  latest: boolean
}

/** Models the provider currently supports, for the Model field's combobox suggestions. Empty when
 *  no credential is stored for `provider` or the provider does no discovery — callers should fall
 *  back to free text, not treat an empty list as an error. */
export function listProviderModels(
  projectId: string,
  provider: string,
  token: string,
): Promise<{ models: ProviderModelInfo[] }> {
  return apiGet<{ models: ProviderModelInfo[] }>(
    `/api/v1/projects/${projectId}/agents/providers/${encodeURIComponent(provider)}/models`,
    token,
  )
}

/**
 * Credential status for every provider the backend knows about in one call (today: `claude`,
 * `claude-code`) — the read-model backing the "Connect Claude" surface, so it doesn't have to
 * fan out a per-provider status call.
 */
export function listProviderCredentialStatuses(
  projectId: string,
  token: string,
): Promise<ProviderCredentialStatus[]> {
  return apiGet<ProviderCredentialStatus[]>(
    `/api/v1/projects/${projectId}/agents/providers/credentials`,
    token,
  )
}

export function setProviderCredential(
  projectId: string,
  provider: string,
  apiKey: string,
  token: string,
): Promise<ProviderCredentialStatus> {
  return apiPut<ProviderCredentialStatus>(
    `/api/v1/projects/${projectId}/agents/providers/${provider}/credential`,
    { apiKey },
    token,
  )
}

export function deleteProviderCredential(
  projectId: string,
  provider: string,
  token: string,
): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/agents/providers/${provider}/credential`, token)
}

/** Re-runs preflight verification on demand — the same probe {@link setProviderCredential} triggers
 *  automatically after a save. Runs even with no credential stored (probes `claude-code` builtin
 *  runtime readiness pre-token). */
export function verifyProviderCredential(
  projectId: string,
  provider: string,
  token: string,
): Promise<ProviderVerificationReport> {
  return apiPost<ProviderVerificationReport>(
    `/api/v1/projects/${projectId}/agents/providers/${provider}/credential/verify`,
    {},
    token,
  )
}

/** Which Cloud Run target `runs-on: cloud-run` resolves to for this project. DB-only — cheap enough to
 *  refetch on every page load so env drift (an operator setting GCP_CLOUDRUN_PROJECT_ID) is visible. */
export interface ClaudeRuntimeConfig {
  source: 'project-target' | 'builtin'
  runtimeTargetId?: string | null
  runtimeTarget?: RuntimeTarget | null
  builtinConfigured: boolean
}

export function getClaudeRuntime(projectId: string, token: string): Promise<ClaudeRuntimeConfig> {
  return apiGet<ClaudeRuntimeConfig>(`/api/v1/projects/${projectId}/agents/providers/claude-code/runtime`, token)
}

/** `runtimeTargetId: null` clears the designation, falling back to the operator's builtin target. */
export function setClaudeRuntime(
  projectId: string,
  runtimeTargetId: string | null,
  token: string,
): Promise<ClaudeRuntimeConfig> {
  return apiPut<ClaudeRuntimeConfig>(
    `/api/v1/projects/${projectId}/agents/providers/claude-code/runtime`,
    { runtimeTargetId },
    token,
  )
}

// ── Project-scoped API keys (machine-to-machine: CLI/MCP daemon, claude-code runtime) ──────
//
// Distinct from a user's personal API keys (`/api/v1/api-keys`, managed on the API Keys settings
// page) — these are scoped to the project itself (`ApiKeyAuthenticationToken`, principal = projectId)
// and are what a `claude-code` runtime's knowledge tools authenticate with. Key values are never
// returned after creation.

export interface ProjectApiKey {
  id: string
  name: string
  createdAt: string
  lastUsedAt?: string | null
}

export function listProjectApiKeys(projectId: string, token: string): Promise<ProjectApiKey[]> {
  return apiGet<ProjectApiKey[]>(`/api/v1/projects/${projectId}/api-keys`, token)
}

// ── Runtime targets (BYO GCP Cloud Run for claude-code workflow steps) ─────
//
// A named place jobs run (`runs-on: <name>`), backed by a `gcp` connection. Create/update/
// provision are synchronous on the backend (a couple seconds) — a 2xx response does NOT mean
// the target is ACTIVE; always read `status`/`errorMessage` off the response body.

export type RuntimeTargetProvider = 'gcp-cloud-run'
export type RuntimeTargetStatus = 'PROVISIONING' | 'ACTIVE' | 'ERROR'

export interface RuntimeTarget {
  id: string
  name: string
  provider: RuntimeTargetProvider
  connectionId: string
  gcpProjectId: string
  region: string
  jobName: string
  image: string
  status: RuntimeTargetStatus
  errorMessage?: string | null
  warnings?: string[] | null
  resolvedImage?: string | null
  lastProvisionedAt?: string | null
  createdAt: string
  updatedAt: string
}

export interface CreateRuntimeTargetBody {
  name: string
  provider: RuntimeTargetProvider
  connectionId: string
  gcpProjectId: string
  region: string
  image: string
  jobName?: string
}

/** Only config fields are mutable — name/provider/connectionId are immutable after create. */
export interface UpdateRuntimeTargetBody {
  region?: string
  image?: string
  jobName?: string
}

export function listRuntimeTargets(projectId: string, token: string): Promise<RuntimeTarget[]> {
  return apiGet<RuntimeTarget[]>(`/api/v1/projects/${projectId}/runtime-targets`, token)
}

export function createRuntimeTarget(
  projectId: string,
  body: CreateRuntimeTargetBody,
  token: string,
): Promise<RuntimeTarget> {
  return apiPost<RuntimeTarget>(`/api/v1/projects/${projectId}/runtime-targets`, body, token)
}

export function updateRuntimeTarget(
  projectId: string,
  targetId: string,
  body: UpdateRuntimeTargetBody,
  token: string,
): Promise<RuntimeTarget> {
  return apiPatch<RuntimeTarget>(
    `/api/v1/projects/${projectId}/runtime-targets/${targetId}`,
    body,
    token,
  ) as Promise<RuntimeTarget>
}

export function deleteRuntimeTarget(projectId: string, targetId: string, token: string): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/runtime-targets/${targetId}`, token)
}

/** Idempotent — safe to retry after an ERROR status. */
export function provisionRuntimeTarget(
  projectId: string,
  targetId: string,
  token: string,
): Promise<RuntimeTarget> {
  return apiPost<RuntimeTarget>(
    `/api/v1/projects/${projectId}/runtime-targets/${targetId}/provision`,
    {},
    token,
  )
}

// ── Workflow secrets (config values workflow YAML references by key) ───────
//
// Write-only, like agent provider credentials: the API returns key + timestamps, never the value.

export interface WorkflowSecretKey {
  key: string
  createdAt: string
  updatedAt: string
}

export function listWorkflowSecrets(projectId: string, token: string): Promise<WorkflowSecretKey[]> {
  return apiGet<WorkflowSecretKey[]>(`/api/v1/projects/${projectId}/workflow-secrets`, token)
}

export function createWorkflowSecret(
  projectId: string,
  body: { key: string; value: string },
  token: string,
): Promise<WorkflowSecretKey> {
  return apiPost<WorkflowSecretKey>(`/api/v1/projects/${projectId}/workflow-secrets`, body, token)
}

export function updateWorkflowSecret(
  projectId: string,
  key: string,
  value: string,
  token: string,
): Promise<WorkflowSecretKey> {
  return apiPut<WorkflowSecretKey>(
    `/api/v1/projects/${projectId}/workflow-secrets/${encodeURIComponent(key)}`,
    { value },
    token,
  )
}

export function deleteWorkflowSecret(projectId: string, key: string, token: string): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/workflow-secrets/${encodeURIComponent(key)}`, token)
}
