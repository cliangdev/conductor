import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'
import { safeNext } from '@/lib/safe-next'

/**
 * Route gating. Only `/app/**` requires a session.
 *
 * The public marketing pages (`/`, `/privacy`, `/terms` and `/data-deletion`) sit outside the
 * matcher on purpose. They have to be reachable with no cookie at all, because platform app
 * reviewers (TikTok, Meta) check them while signed out. The landing page renders for signed-in visitors too; its header
 * links to `/login`, which forwards an existing session on to the app.
 */
export function middleware(request: NextRequest) {
  const token = request.cookies.get('access_token')?.value
  const { pathname, search } = request.nextUrl

  if (pathname.startsWith('/app') && !token) {
    const loginUrl = new URL('/login', request.url)
    // Keep the query string: a deep link such as /marketing/consent?request=<id> must survive login.
    loginUrl.searchParams.set('next', pathname + search)
    return NextResponse.redirect(loginUrl)
  }

  if (pathname === '/login' && token) {
    const target = new URL(safeNext(request.nextUrl.searchParams.get('next')), request.url)
    return NextResponse.redirect(target)
  }

  return NextResponse.next()
}

export const config = {
  matcher: ['/login', '/app/:path*'],
}
