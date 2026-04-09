import type { Config } from './config.js'

async function request<T>(
  method: string,
  urlPath: string,
  body: unknown | undefined,
  config: Config
): Promise<T> {
  const url = `${config.apiUrl}${urlPath}`
  const headers: Record<string, string> = {
    Authorization: `Bearer ${config.apiKey}`,
    'Content-Type': 'application/json',
  }

  const response = await fetch(url, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(`API error ${response.status}: ${text}`)
  }

  if (method === 'DELETE') {
    return undefined as T
  }

  return response.json() as Promise<T>
}

export async function apiGet<T>(urlPath: string, config: Config): Promise<T> {
  return request<T>('GET', urlPath, undefined, config)
}

export async function apiPost<T>(urlPath: string, body: unknown, config: Config): Promise<T> {
  return request<T>('POST', urlPath, body, config)
}

export async function apiPatch<T>(urlPath: string, body: unknown, config: Config): Promise<T> {
  return request<T>('PATCH', urlPath, body, config)
}

export async function apiPut<T>(urlPath: string, body: unknown, config: Config): Promise<T> {
  return request<T>('PUT', urlPath, body, config)
}

export async function apiDelete(urlPath: string, config: Config): Promise<void> {
  await request<void>('DELETE', urlPath, undefined, config)
}
