import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

/**
 * Route gating. Only `/app/**` requires a session.
 *
 * The public marketing pages — `/` (the landing page), `/privacy` and `/terms` — are deliberately
 * outside the matcher: they must be reachable with no cookie at all, because platform app reviewers
 * (TikTok, Meta) verify them while signed out. The landing page renders for signed-in visitors too;
 * its header links to `/login`, which forwards an existing session on to the app.
 */
export function middleware(request: NextRequest) {
  const token = request.cookies.get('access_token')?.value
  const { pathname } = request.nextUrl

  if (pathname.startsWith('/app') && !token) {
    const loginUrl = new URL('/login', request.url)
    loginUrl.searchParams.set('next', pathname)
    return NextResponse.redirect(loginUrl)
  }

  if (pathname === '/login' && token) {
    const next = request.nextUrl.searchParams.get('next')
    const target = new URL(next && next.startsWith('/') ? next : '/app/projects', request.url)
    return NextResponse.redirect(target)
  }

  return NextResponse.next()
}

export const config = {
  matcher: ['/login', '/app/:path*'],
}
