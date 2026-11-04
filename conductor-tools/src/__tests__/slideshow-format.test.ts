import { describe, it, expect } from 'vitest'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { composeSlideshow } from '../lib/slideshow/index.js'
import { findBrowserFactory, type BrowserFactory } from '../lib/creative-browser.js'

/** One launch with the same fallbacks the composer uses; the factory is reused by the tests. */
async function probe(): Promise<BrowserFactory | null> {
  try {
    const factory = await findBrowserFactory()
    await (await factory()).close()
    return factory
  } catch {
    return null
  }
}
const browserFactory = await probe()

describe.skipIf(!browserFactory)('composeSlideshow output format (real browser)', () => {
  const spec = { id: 'fmt', slides: [{ bgColor: '#336699', texts: [{ t: 'hello there', y: 40 }] }] }
  const first = async (options: Parameters<typeof composeSlideshow>[2]): Promise<string> => {
    const out = mkdtempSync(join(tmpdir(), 'ss-format-'))
    try {
      const result = await composeSlideshow(spec, out, { ratios: ['9x16'], browserFactory: browserFactory!, ...options })
      return result.slidePaths['9x16']![0]!.replace(/.*\./, '') + ':' + readFileSync(result.slidePaths['9x16']![0]!).subarray(0, 4).toString('hex')
    } finally {
      rmSync(out, { recursive: true, force: true })
    }
  }

  it('writes JPEG slides by default: a .jpg starting FFD8FF (Instagram and TikTok photo posts refuse PNG)', async () => {
    expect((await first({})).startsWith('jpg:ffd8ff')).toBe(true)
  }, 120000)

  it('writes PNG only when asked for', async () => {
    expect(await first({ format: 'png' })).toBe('png:89504e47')
  }, 120000)
})
