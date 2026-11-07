import { describe, it, expect } from 'vitest'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { sharedBrowser, composeOverlaySheet, overlayHtml, overlaySheetsFor, renderOverlayFrames } from '../lib/safe-zone/overlay.js'
import { findBrowserFactory, type BrowserFactory } from '../lib/creative-browser.js'

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
// The real-browser test makes its input with ffmpeg, which CI runners may not have.
const hasFfmpeg = spawnSync('ffmpeg', ['-version']).status === 0

const frames = [
  { dataUrl: 'data:image/png;base64,AAAA', label: 'Slide 1' },
  { dataUrl: 'data:image/png;base64,AAAA', label: 'Cut at 4.2 s' },
]

describe('overlayHtml', () => {
  for (const platform of ['instagram', 'tiktok'] as const) {
    it(`${platform}: zones, labels and the disclaimer`, () => {
      const html = overlayHtml(frames, platform)
      expect(html).toContain('(approximate)')
      expect(html).toContain(platform === 'instagram' ? 'Instagram Reels UI (approximate)' : 'TikTok UI (approximate)')
      expect(html).toContain('Slide 1')
      expect(html).toContain('Cut at 4.2 s')
      // top 290/1920, bottom from 1490, right x 918/1080 and y 576..1632
      expect(html).toContain('height:15.104%')
      expect(html).toContain('top:77.604%')
      expect(html).toContain('left:85%')
      expect(html).toContain('top:30%')
      expect(html).toContain('height:55%')
      expect(html.match(/class="zone /g)).toHaveLength(frames.length * 3)
    })
  }

  it('draws each platform own chrome', () => {
    expect(overlayHtml(frames, 'instagram')).toContain('Friends')
    expect(overlayHtml(frames, 'instagram')).toContain('Original audio')
    expect(overlayHtml(frames, 'tiktok')).toContain('For You')
    expect(overlayHtml(frames, 'tiktok')).toContain('original sound')
  })

  it('wraps to a second row after 6 columns', () => {
    const many = Array.from({ length: 8 }, (_, i) => ({ dataUrl: 'data:image/png;base64,AAAA', label: `f${i}` }))
    expect(overlayHtml(many, 'tiktok')).toContain('repeat(6,')
  })
})

describe('one browser per call', () => {
  it('launches once for two platforms and closes it once', async () => {
    let launched = 0
    let closed = 0
    let pages = 0
    const fakeBrowser = {
      newPage: async () => {
        pages++
        return { setContent: async () => {}, screenshot: async () => Buffer.from([1]), close: async () => {} }
      },
      close: async () => {
        closed++
      },
    }
    const factory = (async () => {
      launched++
      return fakeBrowser
    }) as unknown as BrowserFactory
    const dir = mkdtempSync(join(tmpdir(), 'ov-one-'))
    const png = Buffer.alloc(33)
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(png)
    png.writeUInt32BE(13, 8)
    png.write('IHDR', 12)
    png.writeUInt32BE(1080, 16)
    png.writeUInt32BE(1920, 20)
    try {
      const shared = sharedBrowser(factory)
      await overlaySheetsFor([{ png, label: 'a' }], ['instagram', 'tiktok'], dir, 'd', { browser: shared.factory })
      await overlaySheetsFor([{ png, label: 'a' }], ['instagram'], dir, 'e', { browser: shared.factory })
      expect(launched).toBe(1)
      expect(pages).toBe(3)
      expect(closed).toBe(0)
      await shared.closeAll()
      expect(closed).toBe(1)
      await overlaySheetsFor([{ png, label: 'a' }], ['instagram', 'tiktok'], dir, 'f', { browser: factory })
      expect(launched).toBe(2)
      expect(closed).toBe(2)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  })
})

describe('overlay input checks', () => {
  it('rejects a frame that is not 9:16 before launching a browser', async () => {
    const png = Buffer.alloc(32)
    png.writeUInt32BE(0x89504e47, 0)
    png.writeUInt32BE(1920, 16)
    png.writeUInt32BE(1080, 20)
    await expect(renderOverlayFrames([{ png, label: 'x' }], 'instagram')).rejects.toThrow(/9:16/)
  })
})

describe.skipIf(!browserFactory || !hasFfmpeg)('overlay rendering (real browser)', () => {
  it('renders frames and a sheet under 1 MB', async () => {
    const dir = mkdtempSync(join(tmpdir(), 'overlay-'))
    try {
      const img = join(dir, 'in.png')
      execFileSync('ffmpeg', ['-v', 'error', '-f', 'lavfi', '-i', 'testsrc=size=1080x1920:rate=1', '-frames:v', '1', img])
      const input = ['Slide 1', 'Slide 2', 'Slide 3'].map((label) => ({ png: img, label }))
      const jpegs = await renderOverlayFrames(input, 'instagram', { browser: browserFactory! })
      expect(jpegs).toHaveLength(3)
      expect(jpegs[0]!.subarray(0, 2).toString('hex')).toBe('ffd8')
      const sheet = await composeOverlaySheet(input, 'tiktok', { browser: browserFactory! })
      expect(sheet.mimeType).toBe('image/jpeg')
      expect(sheet.data.length).toBeLessThan(1_000_000)
      const probeOut = execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=width,height', '-of', 'csv=p=0', '-'], { input: sheet.data }).toString().trim()
      const [w, h] = probeOut.split(',').map(Number)
      expect(w).toBe(3 * 320 + 4 * 16)
      expect(h).toBeGreaterThan(569)
      const written = await overlaySheetsFor(input, ['instagram', 'tiktok'], join(dir, 'out'), 'draft', { browser: browserFactory! })
      expect(written.map((r) => r.file.replace(/.*\//, ''))).toEqual(['draft-ui-instagram.jpg', 'draft-ui-tiktok.jpg'])
      expect(readFileSync(written[0]!.file).length).toBe(written[0]!.bytes)
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  }, 60000)
})
