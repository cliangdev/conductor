/**
 * Reads an image's format and pixel dimensions straight from its file header — no decode, no
 * external image library. Used by `upload_creative_photo`, which needs width/height and content
 * type before minting an upload (the backend's `CreateCreativePhotoRequest` requires both, since
 * Conductor has no server-side image pipeline and WebP in particular cannot be probed there).
 *
 * Covers exactly the three formats the Creative photo API accepts (image/png, image/jpeg,
 * image/webp). Returns `null` for anything else or a header that doesn't parse — callers should
 * treat that as "not a usable photo" rather than guessing.
 */

export type CreativePhotoContentType = 'image/png' | 'image/jpeg' | 'image/webp'

export interface ImageInfo {
  contentType: CreativePhotoContentType
  width: number
  height: number
}

function readUInt32BE(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset]! << 24) | (bytes[offset + 1]! << 16) | (bytes[offset + 2]! << 8) | bytes[offset + 3]!) >>> 0
}

function readUInt16BE(bytes: Uint8Array, offset: number): number {
  return ((bytes[offset]! << 8) | bytes[offset + 1]!) >>> 0
}

function readUInt24LE(bytes: Uint8Array, offset: number): number {
  return (bytes[offset]! | (bytes[offset + 1]! << 8) | (bytes[offset + 2]! << 16)) >>> 0
}

const PNG_SIGNATURE = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]

function readPng(bytes: Uint8Array): ImageInfo | null {
  if (bytes.length < 24) return null
  if (!PNG_SIGNATURE.every((b, i) => bytes[i] === b)) return null
  // IHDR is always the first chunk: 4-byte length, 4-byte type "IHDR", then width/height (4 bytes
  // each, big-endian) at offsets 16 and 20.
  return { contentType: 'image/png', width: readUInt32BE(bytes, 16), height: readUInt32BE(bytes, 20) }
}

// SOFn (start-of-frame) markers that carry dimensions. Excludes 0xC4 (DHT), 0xC8 (JPG extension,
// unused in practice) and 0xCC (DAC) — those share the 0xC0-0xCF range but aren't SOF markers.
const JPEG_SOF_MARKERS = new Set([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf])
// Standalone markers with no length-prefixed payload following them.
const JPEG_NO_PAYLOAD_MARKERS = new Set([0x01, 0xd8, 0xd9])

function readJpeg(bytes: Uint8Array): ImageInfo | null {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null
  let offset = 2
  while (offset + 4 <= bytes.length) {
    if (bytes[offset] !== 0xff) {
      offset += 1 // resync on a stray fill byte (0xFF00 stuffing, etc.)
      continue
    }
    const marker = bytes[offset + 1]!
    if (marker === 0xd9) break // EOI, no dimensions found
    if (JPEG_NO_PAYLOAD_MARKERS.has(marker) || (marker >= 0xd0 && marker <= 0xd7)) {
      offset += 2
      continue
    }
    const segmentLength = readUInt16BE(bytes, offset + 2)
    if (JPEG_SOF_MARKERS.has(marker)) {
      const payloadStart = offset + 4
      if (payloadStart + 5 > bytes.length) return null
      const height = readUInt16BE(bytes, payloadStart + 1)
      const width = readUInt16BE(bytes, payloadStart + 3)
      return { contentType: 'image/jpeg', width, height }
    }
    if (segmentLength < 2) return null // malformed — refuse to loop forever
    offset += 2 + segmentLength
  }
  return null
}

function isRiffWebp(bytes: Uint8Array): boolean {
  return (
    bytes.length >= 21 && // enough to read the fourCC at 12-15 and reach the chunk data at 20
    bytes[0] === 0x52 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x46 && // "RIFF"
    bytes[8] === 0x57 &&
    bytes[9] === 0x45 &&
    bytes[10] === 0x42 &&
    bytes[11] === 0x50 // "WEBP"
  )
}

function readWebp(bytes: Uint8Array): ImageInfo | null {
  if (!isRiffWebp(bytes)) return null
  const fourCc = String.fromCharCode(bytes[12]!, bytes[13]!, bytes[14]!, bytes[15]!)
  const chunkStart = 20 // after the 8-byte RIFF header, "WEBP", fourCC and 4-byte chunk size

  if (fourCc === 'VP8X') {
    // flags (1 byte) + reserved (3 bytes), then 3-byte little-endian (width-1) and (height-1).
    if (bytes.length < chunkStart + 10) return null
    return {
      contentType: 'image/webp',
      width: readUInt24LE(bytes, chunkStart + 4) + 1,
      height: readUInt24LE(bytes, chunkStart + 7) + 1,
    }
  }
  if (fourCc === 'VP8 ') {
    // 3-byte frame tag + 3-byte start code (0x9d 0x01 0x2a), then 14-bit width/height, little-endian.
    if (bytes.length < chunkStart + 10) return null
    const widthRaw = bytes[chunkStart + 6]! | (bytes[chunkStart + 7]! << 8)
    const heightRaw = bytes[chunkStart + 8]! | (bytes[chunkStart + 9]! << 8)
    return { contentType: 'image/webp', width: widthRaw & 0x3fff, height: heightRaw & 0x3fff }
  }
  if (fourCc === 'VP8L') {
    // 1-byte signature (0x2f), then a little-endian 32-bit field: 14-bit (width-1), 14-bit (height-1).
    if (bytes.length < chunkStart + 5) return null
    const bits =
      (bytes[chunkStart + 1]! | (bytes[chunkStart + 2]! << 8) | (bytes[chunkStart + 3]! << 16) | (bytes[chunkStart + 4]! << 24)) >>> 0
    return { contentType: 'image/webp', width: (bits & 0x3fff) + 1, height: ((bits >>> 14) & 0x3fff) + 1 }
  }
  return null
}

/** Sniffs the format from magic bytes and parses width/height from the header. `null` when the
 * bytes aren't a recognizable PNG, JPEG or WebP, or the header is truncated/malformed. */
export function readImageDimensions(bytes: Uint8Array): ImageInfo | null {
  return readPng(bytes) ?? readJpeg(bytes) ?? readWebp(bytes)
}
