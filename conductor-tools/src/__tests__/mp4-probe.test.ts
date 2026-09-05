import { describe, it, expect } from 'vitest'
import { probeVideoBuffer } from '../lib/mp4-probe.js'

/** Builds an ISO BMFF box: 4-byte size, 4-byte type, body. */
function box(type: string, ...parts: Buffer[]): Buffer {
  const body = Buffer.concat(parts)
  const header = Buffer.alloc(8)
  header.writeUInt32BE(8 + body.length, 0)
  header.write(type, 4, 'latin1')
  return Buffer.concat([header, body])
}

function u32(n: number): Buffer {
  const b = Buffer.alloc(4)
  b.writeUInt32BE(n, 0)
  return b
}

function fixed(n: number): Buffer {
  return u32(Math.round(n * 65536) >>> 0)
}

function mvhd(timescale: number, duration: number): Buffer {
  return box('mvhd', u32(0), u32(0), u32(0), u32(timescale), u32(duration), Buffer.alloc(80))
}

function tkhd(width: number, height: number, rotate90 = false): Buffer {
  const matrix = rotate90
    ? Buffer.concat([fixed(0), fixed(1), u32(0), fixed(-1), fixed(0), u32(0), u32(0), u32(0), u32(0x40000000)])
    : Buffer.concat([fixed(1), fixed(0), u32(0), fixed(0), fixed(1), u32(0), u32(0), u32(0), u32(0x40000000)])
  return box('tkhd', u32(0), u32(0), u32(0), u32(1), u32(0), u32(0), Buffer.alloc(16), matrix, fixed(width), fixed(height))
}

function hdlr(handler: string): Buffer {
  return box('hdlr', u32(0), u32(0), Buffer.from(handler, 'latin1'), Buffer.alloc(12))
}

function trak(width: number, height: number, handler = 'vide', rotate90 = false): Buffer {
  return box('trak', tkhd(width, height, rotate90), box('mdia', hdlr(handler)))
}

function mp4(moovChildren: Buffer[], moovFirst = true): Buffer {
  const ftyp = box('ftyp', Buffer.from('isom', 'latin1'), u32(0))
  const mdat = box('mdat', Buffer.alloc(32))
  const moov = box('moov', ...moovChildren)
  return Buffer.concat(moovFirst ? [ftyp, moov, mdat] : [ftyp, mdat, moov])
}

describe('probeVideoBuffer', () => {
  it('reads duration from mvhd and dimensions from the video track', () => {
    const probe = probeVideoBuffer(mp4([mvhd(1000, 5250), trak(1080, 1920)]))
    expect(probe).toEqual({ width: 1080, height: 1920, durationSeconds: 5.25 })
  })

  it('finds moov after mdat too (a file written without faststart)', () => {
    const probe = probeVideoBuffer(mp4([mvhd(600, 1800), trak(1920, 1080)], false))
    expect(probe).toEqual({ width: 1920, height: 1080, durationSeconds: 3 })
  })

  it('applies a quarter-turn rotation matrix so portrait phone video reports portrait', () => {
    const probe = probeVideoBuffer(mp4([mvhd(1000, 1000), trak(1920, 1080, 'vide', true)]))
    expect(probe.width).toBe(1080)
    expect(probe.height).toBe(1920)
  })

  it('skips an audio track with no picture', () => {
    const probe = probeVideoBuffer(mp4([mvhd(1000, 2000), trak(0, 0, 'soun'), trak(720, 1280)]))
    expect(probe.width).toBe(720)
    expect(probe.height).toBe(1280)
  })

  it('returns nulls rather than guesses for something that is not a video container', () => {
    expect(probeVideoBuffer(Buffer.from('definitely not an mp4 file at all', 'latin1'))).toEqual({
      width: null,
      height: null,
      durationSeconds: null,
    })
    expect(probeVideoBuffer(mp4([trak(640, 480)]))).toEqual({ width: 640, height: 480, durationSeconds: null })
  })
})
