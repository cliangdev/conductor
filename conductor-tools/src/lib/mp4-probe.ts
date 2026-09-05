import { open } from 'node:fs/promises'

/**
 * Width, height and duration of an MP4/MOV file, read from its `moov` atom.
 *
 * The backend deliberately ships no ffprobe or container parser (see `MediaMetadata` there) and asks the
 * client for a video's measurements at mint — the browser measures with an `HTMLVideoElement`. This is
 * the MCP server's equivalent: an ISO base-media-file box walk that reads two atoms and nothing else.
 * It handles the two layouts that matter (`moov` before or after `mdat`), 32- and 64-bit box sizes,
 * version 0 and 1 headers, and the rotation matrix a phone writes when it records portrait video in a
 * landscape track. Anything it cannot read returns `null` fields rather than a guess: a wrong number
 * would pass the approval gate and fail at the platform.
 */
export interface VideoProbe {
  width: number | null
  height: number | null
  durationSeconds: number | null
}

const EMPTY: VideoProbe = { width: null, height: null, durationSeconds: null }

/** How much of a `moov` box this is willing to read into memory. Real ones are kilobytes to a few MB. */
const MAX_MOOV_BYTES = 64 * 1024 * 1024

export async function probeVideo(filePath: string): Promise<VideoProbe> {
  const handle = await open(filePath, 'r')
  try {
    const { size: fileSize } = await handle.stat()
    let offset = 0
    while (offset + 8 <= fileSize) {
      const header = Buffer.alloc(16)
      const { bytesRead } = await handle.read(header, 0, 16, offset)
      if (bytesRead < 8) break
      let boxSize = header.readUInt32BE(0)
      const type = header.toString('latin1', 4, 8)
      let headerSize = 8
      if (boxSize === 1) {
        if (bytesRead < 16) break
        boxSize = Number(header.readBigUInt64BE(8))
        headerSize = 16
      } else if (boxSize === 0) {
        boxSize = fileSize - offset
      }
      if (boxSize < headerSize) break
      if (type === 'moov') {
        const bodySize = Math.min(boxSize - headerSize, MAX_MOOV_BYTES)
        const body = Buffer.alloc(bodySize)
        await handle.read(body, 0, bodySize, offset + headerSize)
        return parseMoov(body)
      }
      offset += boxSize
    }
    return EMPTY
  } finally {
    await handle.close()
  }
}

/** Probe an in-memory file — the same walk, for callers that already hold the bytes (and for tests). */
export function probeVideoBuffer(bytes: Buffer): VideoProbe {
  for (const box of boxes(bytes, 0, bytes.length)) {
    if (box.type === 'moov') return parseMoov(bytes.subarray(box.bodyStart, box.end))
  }
  return EMPTY
}

interface Box {
  type: string
  bodyStart: number
  end: number
}

function* boxes(buf: Buffer, start: number, end: number): Generator<Box> {
  let offset = start
  while (offset + 8 <= end) {
    let size = buf.readUInt32BE(offset)
    const type = buf.toString('latin1', offset + 4, offset + 8)
    let headerSize = 8
    if (size === 1) {
      if (offset + 16 > end) return
      size = Number(buf.readBigUInt64BE(offset + 8))
      headerSize = 16
    } else if (size === 0) {
      size = end - offset
    }
    if (size < headerSize || offset + size > end) return
    yield { type, bodyStart: offset + headerSize, end: offset + size }
    offset += size
  }
}

function parseMoov(moov: Buffer): VideoProbe {
  let durationSeconds: number | null = null
  let width: number | null = null
  let height: number | null = null
  for (const box of boxes(moov, 0, moov.length)) {
    if (box.type === 'mvhd' && durationSeconds === null) {
      durationSeconds = parseMvhd(moov.subarray(box.bodyStart, box.end))
    } else if (box.type === 'trak' && width === null) {
      const dims = parseTrak(moov.subarray(box.bodyStart, box.end))
      if (dims) {
        width = dims.width
        height = dims.height
      }
    }
  }
  return { width, height, durationSeconds }
}

function parseMvhd(body: Buffer): number | null {
  if (body.length < 4) return null
  const version = body.readUInt8(0)
  try {
    if (version === 1) {
      const timescale = body.readUInt32BE(20)
      const duration = Number(body.readBigUInt64BE(24))
      return timescale > 0 ? round(duration / timescale) : null
    }
    const timescale = body.readUInt32BE(12)
    const duration = body.readUInt32BE(16)
    return timescale > 0 ? round(duration / timescale) : null
  } catch {
    return null
  }
}

/** The first track that carries a picture, with its rotation matrix applied. */
function parseTrak(trak: Buffer): { width: number; height: number } | null {
  let dims: { width: number; height: number; rotated: boolean } | null = null
  let isVideo = false
  for (const box of boxes(trak, 0, trak.length)) {
    if (box.type === 'tkhd') {
      dims = parseTkhd(trak.subarray(box.bodyStart, box.end))
    } else if (box.type === 'mdia') {
      for (const inner of boxes(trak, box.bodyStart, box.end)) {
        if (inner.type === 'hdlr' && inner.end - inner.bodyStart >= 12) {
          isVideo = trak.toString('latin1', inner.bodyStart + 8, inner.bodyStart + 12) === 'vide'
        }
      }
    }
  }
  if (!dims || dims.width === 0 || dims.height === 0) return null
  if (!isVideo && dims.width === 0) return null
  return dims.rotated ? { width: dims.height, height: dims.width } : { width: dims.width, height: dims.height }
}

function parseTkhd(body: Buffer): { width: number; height: number; rotated: boolean } | null {
  if (body.length < 4) return null
  const version = body.readUInt8(0)
  // After version/flags: creation, modification, track id, reserved, duration — 20 bytes in v0, 32 in v1.
  const afterDuration = version === 1 ? 4 + 32 : 4 + 20
  // Then 8 reserved, 2 layer, 2 alternate group, 2 volume, 2 reserved = 16, then the 36-byte matrix.
  const matrixStart = afterDuration + 16
  const widthStart = matrixStart + 36
  if (body.length < widthStart + 8) return null
  try {
    const a = fixed16(body.readInt32BE(matrixStart))
    const b = fixed16(body.readInt32BE(matrixStart + 4))
    const c = fixed16(body.readInt32BE(matrixStart + 12))
    const d = fixed16(body.readInt32BE(matrixStart + 16))
    const width = Math.round(body.readUInt32BE(widthStart) / 65536)
    const height = Math.round(body.readUInt32BE(widthStart + 4) / 65536)
    // A quarter turn leaves the diagonal at zero and the off-diagonal at ±1: the stored width is then
    // the picture's height, which is what a platform will see.
    const rotated = Math.abs(a) < 0.001 && Math.abs(d) < 0.001 && Math.abs(b) > 0.5 && Math.abs(c) > 0.5
    return { width, height, rotated }
  } catch {
    return null
  }
}

function fixed16(raw: number): number {
  return raw / 65536
}

function round(seconds: number): number {
  return Math.round(seconds * 1000) / 1000
}
