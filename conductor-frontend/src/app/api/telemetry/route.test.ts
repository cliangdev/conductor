import { afterEach, describe, expect, it, vi } from 'vitest'
import { POST } from './route'
import { MAX_BODY_BYTES, MAX_SAMPLES, MAX_STRING, sanitizeBatch } from '@/lib/telemetry-validate'

function req(body: unknown, headers: Record<string, string> = {}) {
  return new Request('http://localhost/api/telemetry', {
    method: 'POST',
    body: typeof body === 'string' ? body : JSON.stringify(body),
    headers,
  })
}

describe('sanitizeBatch', () => {
  it('drops unknown types and non-objects', () => {
    const out = sanitizeBatch({ samples: [{ type: 'evil' }, 'x', null, [], { type: 'api_call', path: '/a' }] })
    expect(out).toEqual([{ type: 'api_call', path: '/a' }])
  })

  it('truncates long strings, drops nested values and reserved keys', () => {
    const [s] = sanitizeBatch({
      samples: [
        { type: 'page_ready', page: 'x'.repeat(5000), nested: { a: 1 }, severity: 'ERROR', message: 'spoof', ms: 5, 'bad key': 1 },
      ],
    })
    expect(s.page).toHaveLength(MAX_STRING)
    expect(s).not.toHaveProperty('nested')
    expect(s).not.toHaveProperty('severity')
    expect(s).not.toHaveProperty('message')
    expect(s).not.toHaveProperty('bad key')
    expect(s.ms).toBe(5)
  })

  it('keeps via=direct|proxy on api_call samples and drops any other value', () => {
    const out = sanitizeBatch({
      samples: [
        { type: 'api_call', path: '/a', via: 'direct' },
        { type: 'api_call', path: '/b', via: 'proxy' },
        { type: 'api_call', path: '/c', via: 'carrier-pigeon' },
        { type: 'api_call', path: '/d', via: 7 },
      ],
    })
    expect(out.map((s) => s.via)).toEqual(['direct', 'proxy', undefined, undefined])
    expect(out[2]).toEqual({ type: 'api_call', path: '/c' })
  })

  it('caps the number of samples', () => {
    const samples = Array.from({ length: 500 }, () => ({ type: 'web_vital', name: 'LCP', value: 1 }))
    expect(sanitizeBatch({ samples })).toHaveLength(MAX_SAMPLES)
  })

  it('returns nothing for malformed bodies', () => {
    expect(sanitizeBatch(null)).toEqual([])
    expect(sanitizeBatch({ samples: 'x' })).toEqual([])
  })
})

describe('POST /api/telemetry', () => {
  afterEach(() => vi.restoreAllMocks())

  it('logs each valid sample as one JSON line', async () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {})
    const res = await POST(req({ samples: [{ type: 'api_call', path: '/a', clientMs: 3 }, { type: 'nope' }] }))
    expect(res.status).toBe(204)
    expect(log).toHaveBeenCalledTimes(1)
    expect(JSON.parse(log.mock.calls[0][0] as string)).toEqual({
      severity: 'INFO',
      message: 'api_call',
      type: 'api_call',
      path: '/a',
      clientMs: 3,
    })
  })

  it('rejects oversized and invalid bodies', async () => {
    const log = vi.spyOn(console, 'log').mockImplementation(() => {})
    expect((await POST(req('x'.repeat(MAX_BODY_BYTES + 1)))).status).toBe(413)
    expect((await POST(req('not json'))).status).toBe(400)
    expect(log).not.toHaveBeenCalled()
  })
})
