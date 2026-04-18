import { NextResponse } from 'next/server'
import type { NextRequest } from 'next/server'

export function middleware(request: NextRequest) {
  const token = request.cookies.get('access_token')?.value
  const { pathname } = request.nextUrl
  const isAppRoute = pathname.startsWith('/app')
  const isRoot = pathname === '/'
  const isLogin = pathname === '/login'

  if (isAppRoute && !token) {
    const loginUrl = new URL('/login', request.url)
    loginUrl.searchParams.set('next', pathname)
    return NextResponse.redirect(loginUrl)
  }

  if ((isRoot || isLogin) && token) {
    const next = request.nextUrl.searchParams.get('next')
    const target = new URL(next && next.startsWith('/') ? next : '/app/projects', request.url)
    return NextResponse.redirect(target)
  }

  if (isRoot && !token) {
    return NextResponse.redirect(new URL('/login', request.url))
  }

  return NextResponse.next()
}

export const config = {
  matcher: ['/', '/login', '/app/:path*'],
}
