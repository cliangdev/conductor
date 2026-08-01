import { Blob } from 'node:buffer'
import type { Config } from './config.js'

async function requestWithStatus<T>(
  method: string,
  urlPath: string,
  body: unknown | undefined,
  config: Config
): Promise<{ data: T; status: number }> {
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
    return { data: undefined as T, status: response.status }
  }

  return { data: (await response.json()) as T, status: response.status }
}

async function request<T>(
  method: string,
  urlPath: string,
  body: unknown | undefined,
  config: Config
): Promise<T> {
  return (await requestWithStatus<T>(method, urlPath, body, config)).data
}

/**
 * True for a permanent client error (HTTP 4xx) surfaced by {@link request}. These are not transient — a
 * validation/permission/not-found failure will never succeed on retry — so callers must NOT queue them for
 * offline replay; surface the message instead.
 */
export function isClientError(err: unknown): boolean {
  return err instanceof Error && /API error 4\d\d\b/.test(err.message)
}

export async function apiGet<T>(urlPath: string, config: Config): Promise<T> {
  return request<T>('GET', urlPath, undefined, config)
}

export async function apiPost<T>(urlPath: string, body: unknown, config: Config): Promise<T> {
  return request<T>('POST', urlPath, body, config)
}

/** Like {@link apiPost}, but also returns the response status -- for the rare caller that needs to
 *  distinguish e.g. 201 (created) from 200 (claim-or-return hit an existing row) in the response body. */
export async function apiPostWithStatus<T>(
  urlPath: string,
  body: unknown,
  config: Config
): Promise<{ data: T; status: number }> {
  return requestWithStatus<T>('POST', urlPath, body, config)
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

/**
 * POSTs a single file as multipart/form-data. Deliberately does not set Content-Type: fetch has to
 * generate it itself so the multipart boundary matches the body it builds.
 */
export async function apiPostFile<T>(
  urlPath: string,
  file: { fieldName: string; filename: string; contentType: string; bytes: Uint8Array },
  config: Config
): Promise<T> {
  // Copy into a plain ArrayBuffer: the incoming view may be backed by a pooled Node Buffer, which is
  // both a type mismatch for Blob and a slice of a larger allocation.
  const buffer = new ArrayBuffer(file.bytes.byteLength)
  new Uint8Array(buffer).set(file.bytes)

  const form = new FormData()
  form.append(
    file.fieldName,
    new Blob([buffer], { type: file.contentType }) as unknown as globalThis.Blob,
    file.filename
  )

  const response = await fetch(`${config.apiUrl}${urlPath}`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${config.apiKey}` },
    body: form,
  })

  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(`API error ${response.status}: ${text}`)
  }

  return (await response.json()) as T
}
