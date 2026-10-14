import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

vi.mock('../lib/slideshow/compose.js', async (orig) => ({ ...(await orig<typeof import('../lib/slideshow/compose.js')>()), composeSlideshow: vi.fn() }))
vi.mock('../lib/image-common.js', async (orig) => ({ ...(await orig<typeof import('../lib/image-common.js')>()), jpegPreview: vi.fn(async () => ({ data: Buffer.from('x'), mimeType: 'image/jpeg' })) }))

import { composeSlideshow } from '../lib/slideshow/compose.js'
import { SlideshowSpecError } from '../lib/slideshow/index.js'
import { composeSlideshowTool } from '../mcp/tools/slideshow.js'

const compose = vi.mocked(composeSlideshow)
let tmp: string
const result = (passed: boolean) => ({
  slidePaths: { '9x16': ['/o/a-9x16-01.png'], '4x5': ['/o/a-4x5-01.png'] },
  checks: passed ? [] : [{ slide: 1, ratio: '9x16' as const, rule: 'safeZone' as const, severity: 'error' as const, message: 'bad' }],
  passed,
  specPath: '/o/spec.json',
  outDir: '/o',
})

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'ss-tool-'))
  compose.mockReset()
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

describe('composeSlideshowTool', () => {
  it('requires exactly one of spec or specPath', async () => {
    expect(await composeSlideshowTool({}, { projectRoot: tmp })).toMatchObject({ ok: false, error: expect.stringMatching(/exactly one/) })
    expect(await composeSlideshowTool({ spec: { id: 'a', slides: [{}] }, specPath: 'x.json' }, { projectRoot: tmp })).toMatchObject({ ok: false })
    expect(compose).not.toHaveBeenCalled()
  })

  it('validates ratios', async () => {
    const res = await composeSlideshowTool({ spec: { id: 'a', slides: [{}] }, ratios: ['1x1'] }, { projectRoot: tmp })
    expect(res).toMatchObject({ ok: false, error: expect.stringMatching(/ratios/) })
  })

  it('returns a fix-first nextStep when checks fail', async () => {
    compose.mockResolvedValue(result(false))
    const res = await composeSlideshowTool({ spec: { id: 'a', slides: [{}] } }, { projectRoot: tmp })
    expect(res).toMatchObject({ ok: true, passed: false, nextStep: expect.stringMatching(/Fix the spec.*BEFORE showing/) })
  })

  it('points a passing run at preview_creative_draft CAROUSEL and returns the first slide', async () => {
    compose.mockResolvedValue(result(true))
    const res = await composeSlideshowTool({ spec: { id: 'My Show!', slides: [{}] } }, { projectRoot: tmp, now: new Date(2026, 9, 14, 9, 5, 3) })
    expect(res).toMatchObject({ ok: true, passed: true, slidePaths: { '9x16': ['/o/a-9x16-01.png'] }, image: { mimeType: 'image/jpeg' } })
    expect((res as { nextStep: string }).nextStep).toMatch(/preview_creative_draft\(\{kind: "CAROUSEL"/)
    expect(compose.mock.calls[0]![1]).toBe(join(tmp, '.conductor', 'slideshows', '20261014-090503-My-Show-'))
  })

  it('reads specPath and resolves images against its folder', async () => {
    compose.mockResolvedValue(result(true))
    writeFileSync(join(tmp, 's.json'), JSON.stringify({ id: 'a', slides: [{}] }))
    await composeSlideshowTool({ specPath: 's.json', draftDir: 'out' }, { projectRoot: tmp })
    expect(compose.mock.calls[0]![1]).toBe(join(tmp, 'out'))
    expect(compose.mock.calls[0]![2]).toMatchObject({ specDir: tmp })
    expect(await composeSlideshowTool({ specPath: 'nope.json' }, { projectRoot: tmp })).toMatchObject({ ok: false, error: expect.stringMatching(/Could not read/) })
  })

  it('turns spec validation errors into a structured error', async () => {
    compose.mockRejectedValue(new SlideshowSpecError(['slides[0] (screen): shot (the screenshot path) is required']))
    const res = await composeSlideshowTool({ spec: { id: 'a', slides: [{ type: 'screen' }] } }, { projectRoot: tmp })
    expect(res).toEqual({ ok: false, error: 'The slideshow spec is invalid.', errors: [expect.stringMatching(/shot/)] })
  })
})
