let onUnauthorized: (() => void) | null = null
export function setOnUnauthorized(cb: () => void) {
  onUnauthorized = cb
}

export interface ProjectRepository {
  id: string
  label: string
  repoUrl: string
  repoFullName: string
  webhookSecretConfigured: boolean
  connectedAt: string
}

export function listProjectRepositories(projectId: string, token: string): Promise<ProjectRepository[]> {
  return apiGet<ProjectRepository[]>(`/api/v1/projects/${projectId}/repositories`, token)
}

export function addProjectRepository(
  projectId: string,
  body: { label: string; repoUrl: string; webhookSecret: string },
  token: string,
): Promise<ProjectRepository> {
  return apiPost<ProjectRepository>(`/api/v1/projects/${projectId}/repositories`, body, token)
}

export function updateProjectRepository(
  projectId: string,
  repositoryId: string,
  body: { label?: string; webhookSecret?: string },
  token: string,
): Promise<ProjectRepository> {
  return apiPatch<ProjectRepository>(
    `/api/v1/projects/${projectId}/repositories/${repositoryId}`,
    body,
    token,
  )
}

export function deleteProjectRepository(
  projectId: string,
  repositoryId: string,
  token: string,
): Promise<void> {
  return apiDelete(`/api/v1/projects/${projectId}/repositories/${repositoryId}`, token)
}

export interface WebhookEventSummary {
  id: string
  deliveryId?: string
  eventType: string
  status: 'PENDING' | 'PROCESSED' | 'FAILED' | 'DEAD'
  attempts: number
  errorMessage?: string
  createdAt: string
}

export function listWebhookEvents(projectId: string, token: string): Promise<WebhookEventSummary[]> {
  return apiGet<WebhookEventSummary[]>(`/api/v1/projects/${projectId}/github/webhook-events`, token)
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
