import { describe, it, expect } from 'vitest'
import { middleware } from './middleware'
import { NextRequest } from 'next/server'

function makeRequest(pathname: string, cookies: Record<string, string> = {}): NextRequest {
  const url = `http://localhost${pathname}`
  const req = new NextRequest(url)
  Object.entries(cookies).forEach(([name, value]) => {
    req.cookies.set(name, value)
  })
  return req
}

describe('middleware', () => {
  it('redirects unauthenticated requests to /app/* to /login with next param', () => {
    const req = makeRequest('/app/projects')
    const response = middleware(req)

    expect(response.status).toBe(307)
    const location = response.headers.get('location')
    expect(location).toContain('/login')
    expect(location).toContain('next=%2Fapp%2Fprojects')
  })

  it('redirects unauthenticated /app/ to /login?next=/app/', () => {
    const req = makeRequest('/app/')
    const response = middleware(req)

    expect(response.status).toBe(307)
    const location = response.headers.get('location')
    expect(location).toContain('/login')
    expect(location).toContain('next=')
  })

  it('allows requests with access_token cookie to pass through', () => {
    const req = makeRequest('/app/projects', { access_token: 'valid-token' })
    const response = middleware(req)

    expect(response.status).toBe(200)
  })

  it('allows unauthenticated requests to non-app routes', () => {
    const req = makeRequest('/login')
    const response = middleware(req)

    expect(response.status).toBe(200)
  })

  it('includes the original path as the next param for redirect', () => {
    const req = makeRequest('/app/projects/123/settings')
    const response = middleware(req)

    const location = response.headers.get('location')
    expect(location).toContain('next=%2Fapp%2Fprojects%2F123%2Fsettings')
  })
})
