import { describe, it, expect } from 'vitest'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { composeSlideshow } from '../lib/slideshow/index.js'

async function browserAvailable(): Promise<boolean> {
  try {
    const { chromium } = await import('playwright-core')
    for (const options of [{ channel: 'chrome' }, { channel: 'msedge' }, {}]) {
      try {
        const browser = await chromium.launch(options)
        await browser.close()
        return true
      } catch {
        continue
      }
    }
  } catch {
    /* no playwright */
  }
  return false
}
const hasBrowser = await browserAvailable()

describe.skipIf(!hasBrowser)('composeSlideshow output format (real browser)', () => {
  it('writes JPEG slides (.jpg, FFD8FF) when asked, PNG otherwise', async () => {
    const out = mkdtempSync(join(tmpdir(), 'ss-format-'))
    try {
      const spec = { id: 'fmt', slides: [{ bgColor: '#336699', texts: [{ t: 'hello there', y: 40 }] }] }
      const jpeg = await composeSlideshow(spec, join(out, 'jpeg'), { ratios: ['9x16'], format: 'jpeg', jpegQuality: 92 })
      const jpegFile = jpeg.slidePaths['9x16']![0]!
      expect(jpegFile).toMatch(/\.jpg$/)
      expect([...readFileSync(jpegFile).subarray(0, 3)]).toEqual([0xff, 0xd8, 0xff])

      const png = await composeSlideshow(spec, join(out, 'png'), { ratios: ['9x16'] })
      const pngFile = png.slidePaths['9x16']![0]!
      expect(pngFile).toMatch(/\.png$/)
      expect([...readFileSync(pngFile).subarray(0, 4)]).toEqual([0x89, 0x50, 0x4e, 0x47])
    } finally {
      rmSync(out, { recursive: true, force: true })
    }
  }, 120000)
})
