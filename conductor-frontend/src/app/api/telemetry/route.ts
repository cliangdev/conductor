import { NextResponse } from 'next/server'
import { MAX_BODY_BYTES, sanitizeBatch } from '@/lib/telemetry-validate'

/**
 * Receives batches of client telemetry (see src/lib/telemetry.ts) and writes each sample as one JSON
 * line to stdout, which Cloud Run ingests as jsonPayload. Unauthenticated by design (sendBeacon cannot
 * carry the bearer token), so it validates and bounds everything (see telemetry-validate.ts) and
 * writes nothing but log lines.
 */

export const dynamic = 'force-dynamic'

export async function POST(request: Request) {
  try {
    const declared = Number(request.headers.get('content-length') ?? 0)
    if (declared > MAX_BODY_BYTES) return new NextResponse(null, { status: 413 })

    const text = await request.text()
    if (text.length > MAX_BODY_BYTES) return new NextResponse(null, { status: 413 })

    for (const sample of sanitizeBatch(JSON.parse(text))) {
      console.log(JSON.stringify({ severity: 'INFO', message: sample.type, ...sample }))
    }
  } catch {
    return new NextResponse(null, { status: 400 })
  }
  return new NextResponse(null, { status: 204 })
}
