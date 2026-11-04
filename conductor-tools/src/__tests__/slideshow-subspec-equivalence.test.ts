import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { deflateSync } from 'node:zlib'
import { composeSlideshow, type SlideshowSpec } from '../lib/slideshow/index.js'

/**
 * render-slides composes only the changed slides, as a spec holding just those. That is only the same picture as the
 * full composition if nothing in a slide's HTML depends on its position or on the other slides. This pins it: slide
 * k composed alone is byte-for-byte slide k of the full composition.
 */
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


describe.skipIf(!hasBrowser)('composing a sub-spec of changed slides', () => {
  let tmp: string
  beforeAll(() => {
    tmp = mkdtempSync(join(tmpdir(), 'slideshow-sub-'))
    writeFileSync(join(tmp, 'photo.png'), solidPng(120, 200, [200, 120, 60]))
    writeFileSync(join(tmp, 'shot.png'), solidPng(200, 400, [240, 240, 250]))
  })
  afterAll(() => rmSync(tmp, { recursive: true, force: true }))

  const full: SlideshowSpec = {
    id: 'demo',
    slides: [
      { bg: 'photo.png', dim: true, texts: [{ t: 'three things\nI stopped buying', y: 30 }] },
      { type: 'messages', title: 'Mom', bubbles: [{ from: 'them', t: 'dinner?' }, { from: 'me', t: 'on it' }] },
      { type: 'screen', bg: 'photo.png', blur: true, shot: 'shot.png', callouts: [{ x: 50, y: 50, label: 'tap here' }] },
      { type: 'notes', title: 'List', items: ['eggs', 'milk'] },
      { bg: 'photo.png', texts: [{ t: 'the end', y: 40 }] },
    ],
  }
  const style = { boxBg: '#ffeeaa', accent: '#118833' }
  const png = (file: string): Buffer => readFileSync(file)

  it('gives the same PNG for slide k alone as for slide k of the full composition, at both ratios', async () => {
    const all = await composeSlideshow(full, join(tmp, 'full'), { specDir: tmp, style })
    for (const k of [1, 2, 3, 4, 5]) {
      const alone = await composeSlideshow({ ...full, slides: [full.slides[k - 1]!] }, join(tmp, `alone-${k}`), { specDir: tmp, style })
      for (const ratio of ['9x16', '4x5'] as const) {
        expect(png(alone.slidePaths[ratio]![0]!).equals(png(all.slidePaths[ratio]![k - 1]!)), `slide ${k} ${ratio}`).toBe(true)
      }
    }
  }, 180000)

  it('gives the same PNGs for several changed slides composed together, in order', async () => {
    const all = await composeSlideshow(full, join(tmp, 'full2'), { specDir: tmp, style, ratios: ['9x16'] })
    const pick = [1, 3, 5]
    const sub = await composeSlideshow({ ...full, slides: pick.map((k) => full.slides[k - 1]!) }, join(tmp, 'sub'), { specDir: tmp, style, ratios: ['9x16'] })
    pick.forEach((k, j) => expect(png(sub.slidePaths['9x16']![j]!).equals(png(all.slidePaths['9x16']![k - 1]!)), `slide ${k}`).toBe(true))
  }, 180000)

  it('reports checks under the sub-spec numbering (1..n), which render-slides maps back', async () => {
    const sub = await composeSlideshow({ ...full, slides: [full.slides[0]!, { bg: 'photo.png', texts: [{ t: 'too low', y: 95 }] }] }, join(tmp, 'chk'), { specDir: tmp, ratios: ['9x16'] })
    expect(sub.checks.filter((c) => c.rule === 'safeZone').map((c) => c.slide)).toEqual([2])
  }, 60000)
})
