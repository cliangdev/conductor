import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
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
function solidPng(w: number, h: number): Buffer {
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0)
  ihdr.writeUInt32BE(h, 4)
  ihdr[8] = 8
  ihdr[9] = 2
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: w }, () => [90, 90, 110]).flat())])
  const raw = Buffer.concat(Array.from({ length: h }, () => row))
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))])
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

describe.skipIf(!hasBrowser)('safe zones: built-in slide defaults (real browser)', () => {
  let tmp: string
  beforeAll(() => {
    tmp = mkdtempSync(join(tmpdir(), 'slideshow-sz-'))
    writeFileSync(join(tmp, 'photo.png'), solidPng(120, 200))
    writeFileSync(join(tmp, 'shot.png'), solidPng(200, 400))
  })
  afterAll(() => rmSync(tmp, { recursive: true, force: true }))

  const safeZone = (checks: Array<{ rule: string }>) => checks.filter((c) => c.rule === 'safeZone')

  // Every type with its default position (no top / y / phoneY) and realistic, fairly long content.
  const slides: SlideshowSpec['slides'] = [
    { bg: 'photo.png', texts: [{ t: 'The three things I stopped buying', y: 30 }] },
    {
      type: 'tier',
      title: 'Ranking every weeknight dinner I tried this month',
      rows: [
        { k: 'S', c: '#ff7f7f', items: ['Sheet-pan salmon', 'Chicken tacos', 'Lentil soup'] },
        { k: 'A', c: '#ffbf7f', items: ['Pasta e ceci', 'Stir-fry', 'Burrito bowls'] },
        { k: 'B', c: '#ffff7f', items: ['Frittata', 'Fried rice'] },
        { k: 'C', c: '#7fff7f', items: ['Soup from a can'] },
        { k: 'D', c: '#7fbfff', items: ['Cereal'] },
      ],
    },
    {
      type: 'week',
      title: 'Meal plan for the week ahead',
      days: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].map((d) => ({ d, t: 'Tacos with slaw', icon: '+' })),
    },
    {
      type: 'receipt',
      head: 'GROCERY RUN 11/06',
      sections: [
        { h: 'PRODUCE', items: ['Spinach 2 bags', 'Lemons x4', 'Garlic'] },
        { h: 'PANTRY', items: ['Chickpeas x3', 'Olive oil', 'Rice 2 kg'] },
      ],
      count: '9',
    },
    { type: 'placard', title: 'Untitled (Leftovers)', ex: 'Exhibit 12', lines: [{ k: 'Medium:', v: 'Tupperware on a Tuesday night' }, { k: 'Year:', v: '2026' }] },
    { type: 'messages', title: 'Mom', bubbles: [{ from: 'them', t: 'what are you making for dinner tonight' }, { from: 'me', t: 'the lentil soup again, it is so good' }] },
    { type: 'notes', title: 'Groceries', items: ['Spinach', 'Lemons', { t: 'Milk', done: true }] },
    { type: 'stack', photos: [{ src: 'photo.png', label: 'Before' }, { src: 'photo.png', label: 'After' }] },
    { type: 'screen', bg: 'photo.png', shot: 'shot.png', callouts: [{ x: 50, y: 50, label: 'tap here' }] },
    { type: 'grid', cols: 3, photos: [{ src: 'photo.png' }, { src: 'photo.png' }, { src: 'photo.png' }] },
  ]

  it('composes every built-in type at its default position with no safeZone error on 9x16 (story) or 4x5', async () => {
    const res = await composeSlideshow({ id: 'defaults', slides }, join(tmp, 'defaults'), { specDir: tmp })
    expect(res.checks.filter((c) => c.severity === 'error')).toEqual([])
    expect(safeZone(res.checks)).toEqual([])
    expect(res.slidePaths['9x16']).toHaveLength(slides.length)
    expect(res.slidePaths['4x5']).toHaveLength(slides.length)
  }, 120000)

  it('keeps a long centred headline wrapped to the full text container inside the right band', async () => {
    const long = 'Everything I wish somebody had told me before I started meal prepping every single Sunday afternoon'
    const res = await composeSlideshow(
      { id: 'wide', slides: [{ bg: 'photo.png', texts: [{ t: long, y: 30 }, { t: long, y: 42, style: 'dark' }, { t: long, y: 54, style: 'stroke' }] }] },
      join(tmp, 'wide'),
      { specDir: tmp, ratios: ['9x16'] }
    )
    expect(safeZone(res.checks)).toEqual([])
    expect(res.checks.filter((c) => c.severity === 'error')).toEqual([])
  }, 60000)

  describe('re-render leniency for slideshows saved before the bands grew', () => {
    // top 240 (old limit 220) and a line ending at x=935 (old limit 940) passed before; both break the new bands
    const legacy: SlideshowSpec = {
      id: 'legacy',
      slides: [
        { type: 'tier', top: 240, rows: [{ k: 'S', c: '#ff7f7f', items: ['one'] }] },
        { bg: 'photo.png', texts: [{ t: 'x', y: 30 }] },
      ],
    }

    it('new composes keep top-band intrusions as errors', async () => {
      const res = await composeSlideshow(legacy, join(tmp, 'strict'), { specDir: tmp, ratios: ['9x16'] })
      const hits = safeZone(res.checks)
      expect(hits.length).toBeGreaterThan(0)
      expect(hits.every((c) => (c as { severity: string }).severity === 'error')).toBe(true)
      expect(res.passed).toBe(false)
    }, 60000)

    it('lenientSafeZone downgrades top/right intrusions to warnings and passes', async () => {
      const res = await composeSlideshow(legacy, join(tmp, 'lenient'), { specDir: tmp, ratios: ['9x16'], lenientSafeZone: true })
      const hits = safeZone(res.checks) as Array<{ severity: string; message: string }>
      expect(hits.length).toBeGreaterThan(0)
      expect(hits.every((c) => c.severity === 'warning')).toBe(true)
      expect(res.passed).toBe(true)
    }, 60000)

    it('lenientSafeZone still fails the bottom band', async () => {
      const res = await composeSlideshow({ id: 'low', slides: [{ bg: 'photo.png', texts: [{ t: 'too low', y: 95 }] }] }, join(tmp, 'lenient-low'), {
        specDir: tmp,
        ratios: ['9x16'],
        lenientSafeZone: true,
      })
      expect(res.passed).toBe(false)
      expect(res.checks.some((c) => c.rule === 'safeZone' && c.severity === 'error' && /bottom/.test(c.message))).toBe(true)
    }, 60000)
  })
})
