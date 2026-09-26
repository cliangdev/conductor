import { describe, it, expect } from 'vitest'
import { readImageDimensions } from '../lib/image-dimensions.js'

function u32be(n: number): number[] {
  return [(n >>> 24) & 0xff, (n >>> 16) & 0xff, (n >>> 8) & 0xff, n & 0xff]
}
function u16be(n: number): number[] {
  return [(n >>> 8) & 0xff, n & 0xff]
}
function u16le(n: number): number[] {
  return [n & 0xff, (n >>> 8) & 0xff]
}
function u24le(n: number): number[] {
  return [n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff]
}

function makePng(width: number, height: number): Uint8Array {
  return new Uint8Array([
    0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, // signature
    0, 0, 0, 13, // IHDR length
    0x49, 0x48, 0x44, 0x52, // "IHDR"
    ...u32be(width),
    ...u32be(height),
    8, 6, 0, 0, 0, // bit depth, color type, compression, filter, interlace
    0, 0, 0, 0, // CRC (unchecked by our parser)
  ])
}

function makeJpeg(width: number, height: number): Uint8Array {
  // SOI, then SOF0 directly (skips APP0 — our parser doesn't require it), then EOI.
  return new Uint8Array([
    0xff, 0xd8, // SOI
    0xff, 0xc0, // SOF0
    ...u16be(17), // segment length (2 length bytes + 15 payload bytes)
    8, // precision
    ...u16be(height),
    ...u16be(width),
    3, // numComponents
    1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1, // 3 components x 3 bytes each
    0xff, 0xd9, // EOI
  ])
}

function riffHeader(fourCc: string, chunkDataLength: number): number[] {
  const riffSize = 4 + 8 + chunkDataLength // "WEBP" + chunk header + chunk data
  return [
    0x52, 0x49, 0x46, 0x46, // "RIFF"
    ...u32leArr(riffSize),
    0x57, 0x45, 0x42, 0x50, // "WEBP"
    ...[...fourCc].map((c) => c.charCodeAt(0)),
    ...u32leArr(chunkDataLength),
  ]
}
function u32leArr(n: number): number[] {
  return [n & 0xff, (n >>> 8) & 0xff, (n >>> 16) & 0xff, (n >>> 24) & 0xff]
}

function makeWebpVp8x(width: number, height: number): Uint8Array {
  const chunkData = [0, 0, 0, 0, ...u24le(width - 1), ...u24le(height - 1)]
  return new Uint8Array([...riffHeader('VP8X', chunkData.length), ...chunkData])
}

function makeWebpVp8Lossy(width: number, height: number): Uint8Array {
  const widthField = width & 0x3fff
  const heightField = height & 0x3fff
  const chunkData = [0, 0, 0, 0x9d, 0x01, 0x2a, ...u16le(widthField), ...u16le(heightField)]
  return new Uint8Array([...riffHeader('VP8 ', chunkData.length), ...chunkData])
}

function makeWebpVp8Lossless(width: number, height: number): Uint8Array {
  const bits = ((width - 1) & 0x3fff) | (((height - 1) & 0x3fff) << 14)
  const chunkData = [0x2f, bits & 0xff, (bits >>> 8) & 0xff, (bits >>> 16) & 0xff, (bits >>> 24) & 0xff]
  return new Uint8Array([...riffHeader('VP8L', chunkData.length), ...chunkData])
}

describe('readImageDimensions', () => {
  it('reads a PNG IHDR chunk', () => {
    expect(readImageDimensions(makePng(1080, 1920))).toEqual({ contentType: 'image/png', width: 1080, height: 1920 })
  })

  it('reads a JPEG SOF0 segment', () => {
    expect(readImageDimensions(makeJpeg(1200, 630))).toEqual({ contentType: 'image/jpeg', width: 1200, height: 630 })
  })

  it('reads a WebP VP8X (extended) chunk', () => {
    expect(readImageDimensions(makeWebpVp8x(2160, 3840))).toEqual({ contentType: 'image/webp', width: 2160, height: 3840 })
  })

  it('reads a WebP VP8 (simple lossy) chunk', () => {
    expect(readImageDimensions(makeWebpVp8Lossy(640, 480))).toEqual({ contentType: 'image/webp', width: 640, height: 480 })
  })

  it('reads a WebP VP8L (lossless) chunk', () => {
    expect(readImageDimensions(makeWebpVp8Lossless(800, 600))).toEqual({ contentType: 'image/webp', width: 800, height: 600 })
  })

  it('returns null for an unrecognized format', () => {
    expect(readImageDimensions(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8]))).toBeNull()
  })

  it('returns null for a truncated PNG signature', () => {
    expect(readImageDimensions(new Uint8Array([0x89, 0x50, 0x4e, 0x47]))).toBeNull()
  })

  it('returns null for empty bytes', () => {
    expect(readImageDimensions(new Uint8Array([]))).toBeNull()
  })
})
