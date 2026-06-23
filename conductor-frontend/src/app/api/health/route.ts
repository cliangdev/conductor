import { NextResponse } from 'next/server'

// Never statically cached — a deploy smoke-test must hit the live server.
export const dynamic = 'force-dynamic'

export function GET() {
  return NextResponse.json({ status: 'ok' })
}
