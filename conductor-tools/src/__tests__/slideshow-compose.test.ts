import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { mkdtempSync, readFileSync, rmSync, writeFileSync, existsSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateSync } from 'node:zlib'
import { composeSlideshow, type SlideshowSpec } from '../lib/slideshow/index.js'

function crc32(buf: Buffer): number {
  let c = ~0
  for (const b of buf) {
    c ^= b
    for (let k = 0; k < 8; k++) c = (c >>> 1) ^ (0xedb88320 & -(c & 1))
  }
  return ~c >>> 0
}
function chunk(type: string, data: Buffer): Buffer {
  const len = Buffer.alloc(4)
  len.writeUInt32BE(data.length)
  const td = Buffer.concat([Buffer.from(type), data])
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(td))
  return Buffer.concat([len, td, crc])
}
/** A solid-colour PNG. */
function solidPng(w: number, h: number, rgb: [number, number, number]): Buffer {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8
  ihdr[9] = 2
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: w }, () => rgb).flat())])
  const raw = Buffer.concat(Array.from({ length: h }, () => row))
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
}
const dims = (file: string) => {
  const b = readFileSync(file)
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20) }
}

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

describe.skipIf(!hasBrowser)('composeSlideshow (real browser)', () => {
  let tmp: string
  beforeAll(() => {
    tmp = mkdtempSync(join(tmpdir(), 'slideshow-'))
    writeFileSync(join(tmp, 'photo.png'), solidPng(120, 200, [200, 120, 60]))
    writeFileSync(join(tmp, 'shot.png'), solidPng(200, 400, [240, 240, 250]))
  })
  afterAll(() => rmSync(tmp, { recursive: true, force: true }))

  const spec = (extra: SlideshowSpec['slides'] = []): SlideshowSpec => ({
    id: 'demo',
    slides: [
      { bg: 'photo.png', dim: true, texts: [{ t: 'three things\nI stopped buying', y: 30 }] },
      { type: 'messages', title: 'Mom', bubbles: [{ from: 'them', t: 'dinner?' }, { from: 'me', t: 'on it' }] },
      { type: 'screen', bg: 'photo.png', blur: true, shot: 'shot.png', callouts: [{ x: 50, y: 50, label: 'tap here' }] },
      ...extra,
    ],
  })

  it('renders 1080x1920 and 1080x1350 PNGs, writes the resolved spec and cleans up', async () => {
    const out = join(tmp, 'out1')
    const res = await composeSlideshow(spec(), out, { specDir: tmp })
    expect(res.checks.filter((c) => c.severity === 'error')).toEqual([])
    expect(res.passed).toBe(true)
    expect(res.slidePaths['9x16']).toHaveLength(3)
    expect(res.slidePaths['4x5']).toHaveLength(3)
    expect(dims(res.slidePaths['9x16']![0]!)).toEqual({ w: 1080, h: 1920 })
    expect(dims(res.slidePaths['4x5']![2]!)).toEqual({ w: 1080, h: 1350 })
    expect(res.slidePaths['9x16']![0]).toBe(join(out, 'demo-9x16-01.png'))
    const resolved = JSON.parse(readFileSync(res.specPath, 'utf8'))
    expect(resolved.slides[0].bg).toBe(join(tmp, 'photo.png'))
    expect(readdirSync(out).filter((f) => f.endsWith('.html'))).toEqual([])
  }, 60000)

  it('fails safeZone for a text box at y=95 (9:16 only)', async () => {
    const res = await composeSlideshow(spec([{ bg: 'photo.png', texts: [{ t: 'too low', y: 95 }] }]), join(tmp, 'out2'), { specDir: tmp })
    expect(res.passed).toBe(false)
    const hits = res.checks.filter((c) => c.rule === 'safeZone' && c.slide === 4)
    expect(hits.length).toBeGreaterThan(0)
    expect(hits.every((c) => c.ratio === '9x16' && c.severity === 'error')).toBe(true)
  }, 60000)

  it('reports a missing image and small text, and refuses web URLs', async () => {
    const res = await composeSlideshow(
      { id: 'bad', slides: [{ bg: 'missing.png', texts: [{ t: 'tiny', y: 40, fs: 30 }, { t: 'one two three four five six seven eight nine', y: 50 }] }] },
      join(tmp, 'out3'),
      { specDir: tmp, ratios: ['9x16'] }
    )
    expect(res.checks.map((c) => c.rule)).toEqual(expect.arrayContaining(['image', 'textSize', 'wordsPerLine']))
    expect(res.checks.find((c) => c.rule === 'wordsPerLine')?.severity).toBe('warning')
    await expect(composeSlideshow({ id: 'w', slides: [{ bg: 'https://example.com/a.png' }] }, join(tmp, 'out4'), { specDir: tmp })).rejects.toThrow(/local files only/)
    expect(existsSync(join(tmp, 'out4', 'w-9x16-01.png'))).toBe(false)
  }, 60000)
})
