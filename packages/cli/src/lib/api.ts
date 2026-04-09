export class ApiError extends Error {
  constructor(
    message: string,
    public readonly statusCode: number
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function apiGet<T>(
  path: string,
  apiKey: string,
  apiUrl: string
): Promise<T> {
  const url = `${apiUrl}${path}`
  const response = await fetch(url, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
  })

  if (!response.ok) {
    const text = await response.text().catch(() => response.statusText)
    throw new ApiError(
      `GET ${path} failed with status ${response.status}: ${text}`,
      response.status
    )
  }

  return response.json() as Promise<T>
}

export async function apiPost<T>(
  path: string,
  body: unknown,
  apiKey: string,
  apiUrl: string
): Promise<T> {
  const url = `${apiUrl}${path}`
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  })

  if (!response.ok) {
    const text = await response.text().catch(() => response.statusText)
    throw new ApiError(
      `POST ${path} failed with status ${response.status}: ${text}`,
      response.status
    )
  }

  return response.json() as Promise<T>
}

export async function apiPatch<T>(
  path: string,
  body: unknown,
  apiKey: string,
  apiUrl: string
): Promise<T> {
  const url = `${apiUrl}${path}`
  const response = await fetch(url, {
    method: 'PATCH',
    headers: {
      'Authorization': `Bearer ${apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(body),
  })

  if (!response.ok) {
    const text = await response.text().catch(() => response.statusText)
    throw new ApiError(
      `PATCH ${path} failed with status ${response.status}: ${text}`,
      response.status
    )
  }

  return response.json() as Promise<T>
}
