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

export async function apiGet<T>(path: string, token: string): Promise<T> {
  const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok) {
    const err = new Error(`API error: ${res.status}`) as Error & { status: number }
    err.status = res.status
    throw err
  }
  return res.json()
}

export async function apiPost<T>(path: string, body: unknown, token?: string): Promise<T> {
  const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const err = new Error(`API error: ${res.status}`) as Error & { status: number }
    err.status = res.status
    throw err
  }
  return res.json()
}

export async function apiPatch<T>(path: string, body: unknown, token: string): Promise<T> {
  const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const err = new Error(`API error: ${res.status}`) as Error & { status: number }
    err.status = res.status
    throw err
  }
  return res.json()
}

export async function apiPut<T>(path: string, body: unknown, token: string): Promise<T> {
  const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  })
  if (!res.ok) {
    const err = new Error(`API error: ${res.status}`) as Error & { status: number }
    err.status = res.status
    throw err
  }
  return res.json()
}

export async function apiDelete(path: string, token: string): Promise<void> {
  const res = await fetch(`${process.env.NEXT_PUBLIC_API_URL}${path}`, {
    method: 'DELETE',
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!res.ok) {
    const err = new Error(`API error: ${res.status}`) as Error & { status: number }
    err.status = res.status
    throw err
  }
}
