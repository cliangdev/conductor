import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

vi.mock('../lib/slideshow/compose.js', async (orig) => ({ ...(await orig<typeof import('../lib/slideshow/compose.js')>()), composeSlideshow: vi.fn() }))
vi.mock('../lib/image-common.js', async (orig) => ({ ...(await orig<typeof import('../lib/image-common.js')>()), jpegPreview: vi.fn(async () => ({ data: Buffer.from('x'), mimeType: 'image/jpeg' })) }))

import { composeSlideshow } from '../lib/slideshow/compose.js'
import { NATIVE_STYLE, SlideshowSpecError } from '../lib/slideshow/index.js'
import { composeSlideshowTool } from '../mcp/tools/slideshow.js'

const compose = vi.mocked(composeSlideshow)
let tmp: string
const result = (passed: boolean) => ({
  slidePaths: { '9x16': ['/o/a-9x16-01.png'], '4x5': ['/o/a-4x5-01.png'] },
  checks: passed ? [] : [{ slide: 1, ratio: '9x16' as const, rule: 'safeZone' as const, severity: 'error' as const, message: 'bad' }],
  passed,
  provenance: { recorded: 1, needed: passed ? [] : [2], aiGenerated: !passed },
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

  it('passes the provenance summary through and extends nextStep', async () => {
    compose.mockResolvedValue(result(false))
    const res = (await composeSlideshowTool({ spec: { id: 'a', slides: [{}] } }, { projectRoot: tmp })) as { provenance: unknown }
    expect(res.provenance).toEqual({ recorded: 1, needed: [2], aiGenerated: true })
    compose.mockResolvedValue({ ...result(true), provenance: { recorded: 1, needed: [2], aiGenerated: true } })
    const ok = (await composeSlideshowTool({ spec: { id: 'a', slides: [{}] } }, { projectRoot: tmp })) as { nextStep: string }
    expect(ok.nextStep).toMatch(/Slides 2 have no provenance/)
    expect(ok.nextStep).toMatch(/AI-generated/)
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

  describe('style', () => {
    const spec = { id: 'a', slides: [{}] }
    const kit = (slideshowStyle: unknown) => vi.fn(async () => ({ id: 'k1', name: 'Warm', slideshowStyle }))
    const styleArg = (n = 0) => (compose.mock.calls[n]![2] as { resolvedStyle: Record<string, unknown> }).resolvedStyle

    it('composes the native look when neither brandKitId nor style is given, and returns the style', async () => {
      compose.mockResolvedValue({ ...result(true), style: NATIVE_STYLE })
      const res = (await composeSlideshowTool({ spec }, { projectRoot: tmp })) as { style: unknown; nextStep: string }
      expect(styleArg()).toEqual(NATIVE_STYLE)
      expect(res.style).toEqual(NATIVE_STYLE)
      expect(res.nextStep).toMatch(/slideStyle: style/)
    })

    it("uses the kit's slideshowStyle, with inline style on top", async () => {
      compose.mockResolvedValue(result(true))
      const loadKit = kit({ fontFamily: 'Lora', fontUrl: 'https://f.example/l.css', boxBg: '#fff8e7' })
      await composeSlideshowTool({ spec, brandKitId: 'k1', style: { boxBg: '#123456', textSize: 70 } }, { projectRoot: tmp, loadKit })
      expect(loadKit).toHaveBeenCalledWith('k1')
      expect(styleArg()).toMatchObject({ fontFamily: 'Lora', fontUrl: 'https://f.example/l.css', boxBg: '#123456', textSize: 70, subSize: 44 })
    })

    it('applies an inline style alone over the native look', async () => {
      compose.mockResolvedValue(result(true))
      await composeSlideshowTool({ spec, style: { accent: '#00FF88' } }, { projectRoot: tmp })
      expect(styleArg()).toEqual({ ...NATIVE_STYLE, accent: '#00ff88' })
    })

    it('layers native < kit < the spec\'s own style < inline style, and leaves the spec\'s style as given', async () => {
      compose.mockResolvedValue(result(true))
      const loadKit = kit({ accent: '#111111', boxBg: '#222222', textSize: 50, fontFamily: 'Lora' })
      const own = { id: 'a', slides: [{}], style: { boxBg: '#333333', textSize: 55 } }
      await composeSlideshowTool({ spec: own, brandKitId: 'k1', style: { textSize: 66 } }, { projectRoot: tmp, loadKit })
      expect(styleArg()).toMatchObject({ accent: '#111111', boxBg: '#333333', textSize: 66, fontFamily: 'Lora' })
      expect((compose.mock.calls[0]![0] as { style: unknown }).style).toEqual({ boxBg: '#333333', textSize: 55 })
    })

    it('ignores resolvedStyle from an earlier output when re-composing', async () => {
      compose.mockResolvedValue(result(true))
      const old = { ...NATIVE_STYLE, accent: '#abcdef', fontFamily: 'Old Kit Font' }
      writeFileSync(join(tmp, 'spec.json'), JSON.stringify({ id: 'a', slides: [{}], resolvedStyle: old }))
      await composeSlideshowTool({ specPath: 'spec.json' }, { projectRoot: tmp })
      expect(styleArg()).toEqual(NATIVE_STYLE)
      await composeSlideshowTool({ specPath: 'spec.json', brandKitId: 'k2' }, { projectRoot: tmp, loadKit: kit({ accent: '#00ff88' }) })
      expect(styleArg(1)).toEqual({ ...NATIVE_STYLE, accent: '#00ff88' })
    })

    it('inline null resets to native over a kit; a kit style: null in the spec is no layer', async () => {
      compose.mockResolvedValue(result(true))
      const loadKit = kit({ fontFamily: 'Lora', fontUrl: 'https://f.example/l.css', accent: '#111111' })
      await composeSlideshowTool({ spec, brandKitId: 'k1', style: { fontFamily: null } }, { projectRoot: tmp, loadKit })
      expect(styleArg()).toEqual({ ...NATIVE_STYLE, accent: '#111111' })
      await composeSlideshowTool({ spec: { id: 'a', slides: [{}], style: null }, brandKitId: 'k1' }, { projectRoot: tmp, loadKit })
      expect(styleArg(1)).toMatchObject({ fontFamily: 'Lora', accent: '#111111' })
      // nulls inside a kit mean "not set by the kit"
      await composeSlideshowTool({ spec, brandKitId: 'k1' }, { projectRoot: tmp, loadKit: kit({ fontFamily: null, accent: '#222222' }) })
      expect(styleArg(2)).toEqual({ ...NATIVE_STYLE, accent: '#222222' })
    })

    it('names the Brand Kit when its style is invalid', async () => {
      const res = await composeSlideshowTool({ spec, brandKitId: 'k1' }, { projectRoot: tmp, loadKit: kit({ fontFamily: ',', fontUrl: 'ftp://x' }) })
      expect(res).toMatchObject({
        ok: false,
        error: expect.stringMatching(/Brand Kit "Warm".*update_brand_kit.*inline style/s),
        errors: [expect.stringMatching(/^Brand Kit "Warm" slideshowStyle\.fontFamily/), expect.stringMatching(/^Brand Kit "Warm" slideshowStyle\.fontUrl/)],
      })
    })

    it('ignores unknown fields in a kit style but rejects them in an inline style', async () => {
      compose.mockResolvedValue(result(true))
      await composeSlideshowTool({ spec, brandKitId: 'k1' }, { projectRoot: tmp, loadKit: kit({ accent: '#00ff88', futureField: 1 }) })
      expect(styleArg()).toEqual({ ...NATIVE_STYLE, accent: '#00ff88' })
      const bad = await composeSlideshowTool({ spec, style: { acent: '#00ff88' } as never }, { projectRoot: tmp })
      expect(bad).toMatchObject({ ok: false, errors: [expect.stringMatching(/acent/)] })
    })

    it('errors clearly when the kit has no slideshow style', async () => {
      for (const ss of [null, undefined, {}]) {
        const res = await composeSlideshowTool({ spec, brandKitId: 'k1' }, { projectRoot: tmp, loadKit: kit(ss) })
        expect(res).toMatchObject({ ok: false, error: expect.stringMatching(/"Warm" has no slideshow style.*native look.*update_brand_kit/s) })
      }
      expect(compose).not.toHaveBeenCalled()
    })

    it('reports an unreadable kit and an invalid inline style without composing', async () => {
      const failing = vi.fn(async () => { throw new Error('404') })
      expect(await composeSlideshowTool({ spec, brandKitId: 'nope' }, { projectRoot: tmp, loadKit: failing })).toMatchObject({ ok: false, error: expect.stringMatching(/Could not read Brand Kit nope: 404/) })
      const bad = await composeSlideshowTool({ spec, style: { boxBg: 'red', textSize: 5 } }, { projectRoot: tmp })
      expect(bad).toMatchObject({ ok: false, error: 'The style is invalid.', errors: [expect.stringMatching(/boxBg/), expect.stringMatching(/textSize/)] })
      expect(compose).not.toHaveBeenCalled()
    })

    it('rejects an invalid style that arrives from a kit', async () => {
      const res = await composeSlideshowTool({ spec, brandKitId: 'k1' }, { projectRoot: tmp, loadKit: kit({ accent: 'zzz' }) })
      expect(res).toMatchObject({ ok: false, errors: [expect.stringMatching(/accent/)] })
    })
  })
})
