import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { EventEmitter } from 'node:events'

/**
 * A minimal fake child_process.ChildProcess: an EventEmitter with `.stdout`/`.stderr` sub-emitters.
 * Each test queues one of these per expected spawn() call (systemBinaryAvailable's `-version` probe,
 * then the real ffprobe/ffmpeg invocation), and drives it by calling `.finish()`.
 */
class FakeChild extends EventEmitter {
  stdout = new EventEmitter()
  stderr = new EventEmitter()

  finishOk(stdout = ''): void {
    if (stdout) this.stdout.emit('data', stdout)
    this.emit('exit', 0)
  }
  finishFail(code = 1, stderr = 'boom'): void {
    if (stderr) this.stderr.emit('data', stderr)
    this.emit('exit', code)
  }
  finishEnoent(): void {
    this.emit('error', Object.assign(new Error('spawn ENOENT'), { code: 'ENOENT' }))
  }
}

const spawnMock = vi.fn()
vi.mock('node:child_process', () => ({ spawn: (...args: unknown[]) => spawnMock(...args) }))

const requireMock = vi.fn()
vi.mock('node:module', () => ({ createRequire: () => requireMock }))

const existsSyncMock = vi.fn()
vi.mock('node:fs', () => ({ existsSync: (...args: unknown[]) => existsSyncMock(...args) }))

/** Waits until spawn() has been called `n` times. A chain like probeMedia -> resolveFfprobePath ->
 * systemBinaryAvailable resolves across several microtask hops before the next spawn() call happens
 * and attaches its listeners, so a fixed number of `await Promise.resolve()`s is fragile — this polls
 * on a macrotask boundary instead, which reliably drains any number of pending microtasks first. */
async function waitForSpawnCalls(n: number, timeoutMs = 1000): Promise<void> {
  const start = Date.now()
  while (spawnMock.mock.calls.length < n) {
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for spawn() call #${n}`)
    await new Promise((r) => setTimeout(r, 0))
  }
}

let media: typeof import('../lib/media-probe.js')

beforeEach(async () => {
  vi.resetModules()
  spawnMock.mockReset()
  requireMock.mockReset()
  existsSyncMock.mockReset()
  media = await import('../lib/media-probe.js')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

// --- parseProbeJson: pure parsing, no process spawning ----------------------

describe('parseProbeJson', () => {
  it('classifies an mp4 with a video and audio stream as VIDEO, with duration and hasAudio', () => {
    const json = {
      streams: [
        { codec_type: 'video', codec_name: 'h264', width: 1920, height: 1080, nb_frames: '150' },
        { codec_type: 'audio', codec_name: 'aac' },
      ],
      format: { duration: '5.033000', format_name: 'mov,mp4,m4a,3gp,3g2,mj2' },
    }
    const result = media.parseProbeJson(json, '/tmp/clip.mp4')
    expect(result).toEqual({
      kind: 'VIDEO',
      contentType: 'video/mp4',
      width: 1920,
      height: 1080,
      durationSeconds: 5.033,
      hasAudio: true,
      codec: 'h264',
    })
  })

  it('picks contentType from the extension, e.g. .mov as video/quicktime', () => {
    const json = {
      streams: [{ codec_type: 'video', codec_name: 'h264', width: 1080, height: 1920, nb_frames: '60' }],
      format: { duration: '2.0' },
    }
    const result = media.parseProbeJson(json, '/tmp/clip.mov')
    expect(result.kind).toBe('VIDEO')
    expect(result.contentType).toBe('video/quicktime')
    expect(result.hasAudio).toBe(false)
  })

  it('classifies an mp3 as AUDIO with duration and codec, no width/height', () => {
    const json = {
      streams: [{ codec_type: 'audio', codec_name: 'mp3' }],
      format: { duration: '183.500000', format_name: 'mp3' },
    }
    const result = media.parseProbeJson(json, '/tmp/song.mp3')
    expect(result).toEqual({ kind: 'AUDIO', contentType: 'audio/mpeg', durationSeconds: 183.5, codec: 'mp3' })
  })

  it('does not mistake an mp3 with embedded cover art for a video', () => {
    const json = {
      streams: [
        { codec_type: 'video', codec_name: 'mjpeg', width: 500, height: 500, disposition: { attached_pic: 1 } },
        { codec_type: 'audio', codec_name: 'mp3' },
      ],
      format: { duration: '210.0', format_name: 'mp3' },
    }
    const result = media.parseProbeJson(json, '/tmp/song-with-art.mp3')
    expect(result.kind).toBe('AUDIO')
    expect(result.durationSeconds).toBe(210)
  })

  it('classifies a still image (no duration, at most one frame) as IMAGE', () => {
    const json = {
      streams: [{ codec_type: 'video', codec_name: 'png', width: 800, height: 600 }],
      format: { format_name: 'png_pipe' },
    }
    const result = media.parseProbeJson(json, '/tmp/photo.png')
    expect(result).toEqual({ kind: 'IMAGE', contentType: 'image/png', width: 800, height: 600, codec: 'png' })
  })

  it('throws a clear error when ffprobe found no usable stream at all', () => {
    expect(() => media.parseProbeJson({ streams: [], format: {} }, '/tmp/mystery.bin')).toThrow(
      /no usable video or audio stream/
    )
  })
})

// --- binary discovery order --------------------------------------------------

describe('resolveFfprobePath / resolveFfmpegPath', () => {
  it('prefers the system binary when it launches', async () => {
    const child = new FakeChild()
    spawnMock.mockReturnValueOnce(child)
    const promise = media.resolveFfprobePath()
    child.finishOk()
    await expect(promise).resolves.toBe('ffprobe')
    expect(spawnMock).toHaveBeenCalledWith('ffprobe', ['-version'], { stdio: 'ignore' })
    expect(requireMock).not.toHaveBeenCalled()
  })

  it('falls back to the bundled ffprobe-static path when no system binary is found', async () => {
    const child = new FakeChild()
    spawnMock.mockReturnValueOnce(child)
    requireMock.mockReturnValueOnce({ path: '/bundled/ffprobe' })
    existsSyncMock.mockReturnValueOnce(true)

    const promise = media.resolveFfprobePath()
    child.finishEnoent()
    await expect(promise).resolves.toBe('/bundled/ffprobe')
    expect(requireMock).toHaveBeenCalledWith('ffprobe-static')
  })

  it('throws a clear error when neither a system nor a bundled ffprobe is usable', async () => {
    const child = new FakeChild()
    spawnMock.mockReturnValueOnce(child)
    requireMock.mockImplementationOnce(() => {
      throw new Error('module not found')
    })

    const promise = media.resolveFfprobePath()
    child.finishEnoent()
    await expect(promise).rejects.toThrow(media.NO_FFPROBE_HINT)
  })

  it('caches a resolved ffprobe path across calls (spawns only once)', async () => {
    const child = new FakeChild()
    spawnMock.mockReturnValueOnce(child)
    const promise = media.resolveFfprobePath()
    child.finishOk()
    await promise

    await expect(media.resolveFfprobePath()).resolves.toBe('ffprobe')
    expect(spawnMock).toHaveBeenCalledTimes(1)
  })

  it('resolves ffmpeg the same way: system first, then ffmpeg-static bundled path', async () => {
    const child = new FakeChild()
    spawnMock.mockReturnValueOnce(child)
    requireMock.mockReturnValueOnce('/bundled/ffmpeg')
    existsSyncMock.mockReturnValueOnce(true)

    const promise = media.resolveFfmpegPath()
    child.finishEnoent()
    await expect(promise).resolves.toBe('/bundled/ffmpeg')
    expect(requireMock).toHaveBeenCalledWith('ffmpeg-static')
  })

  it('treats a bundled path that does not exist on disk as unusable', async () => {
    const child = new FakeChild()
    spawnMock.mockReturnValueOnce(child)
    requireMock.mockReturnValueOnce(null) // unsupported platform per ffmpeg-static's own contract
    const promise = media.resolveFfmpegPath()
    child.finishEnoent()
    await expect(promise).rejects.toThrow(media.NO_FFMPEG_HINT)
  })
})

// --- probeMedia: spawns ffprobe and parses its JSON output -------------------

describe('probeMedia', () => {
  it('spawns ffprobe with the expected flags and parses its JSON stdout', async () => {
    const versionChild = new FakeChild()
    const probeChild = new FakeChild()
    spawnMock.mockReturnValueOnce(versionChild).mockReturnValueOnce(probeChild)

    const promise = media.probeMedia('/tmp/clip.mp4')
    versionChild.finishOk()
    await waitForSpawnCalls(2)
    probeChild.finishOk(
      JSON.stringify({ streams: [{ codec_type: 'video', codec_name: 'h264', width: 100, height: 100, nb_frames: '30' }], format: { duration: '1.0' } })
    )

    const result = await promise
    expect(result.kind).toBe('VIDEO')
    expect(spawnMock).toHaveBeenNthCalledWith(2, 'ffprobe', ['-v', 'error', '-print_format', 'json', '-show_streams', '-show_format', '/tmp/clip.mp4'])
  })

  it('rejects with ffprobe stderr when it exits non-zero', async () => {
    const versionChild = new FakeChild()
    const probeChild = new FakeChild()
    spawnMock.mockReturnValueOnce(versionChild).mockReturnValueOnce(probeChild)

    const promise = media.probeMedia('/tmp/bad.mp4')
    versionChild.finishOk()
    await waitForSpawnCalls(2)
    probeChild.finishFail(1, 'Invalid data found when processing input')

    await expect(promise).rejects.toThrow(/Invalid data found/)
  })
})

// --- extractPoster: falls back to 0s when the requested offset fails --------

describe('extractPoster', () => {
  it('extracts at the requested offset when it succeeds', async () => {
    const versionChild = new FakeChild()
    const posterChild = new FakeChild()
    spawnMock.mockReturnValueOnce(versionChild).mockReturnValueOnce(posterChild)
    existsSyncMock.mockReturnValueOnce(true) // outJpg exists after a successful run

    const promise = media.extractPoster('/tmp/clip.mp4', '/tmp/out.jpg', 1)
    versionChild.finishOk()
    await waitForSpawnCalls(2)
    posterChild.finishOk()

    await expect(promise).resolves.toBeUndefined()
    expect(spawnMock).toHaveBeenNthCalledWith(2, 'ffmpeg', ['-y', '-ss', '1', '-i', '/tmp/clip.mp4', '-frames:v', '1', '-q:v', '2', '/tmp/out.jpg'], {
      stdio: ['ignore', 'ignore', 'pipe'],
    })
  })

  it('falls back to 0s when the clip is shorter than the requested offset', async () => {
    const versionChild = new FakeChild()
    const firstAttempt = new FakeChild()
    const secondAttempt = new FakeChild()
    spawnMock.mockReturnValueOnce(versionChild).mockReturnValueOnce(firstAttempt).mockReturnValueOnce(secondAttempt)
    existsSyncMock.mockReturnValueOnce(true) // second attempt's outJpg check

    const promise = media.extractPoster('/tmp/short.mp4', '/tmp/out.jpg', 5)
    versionChild.finishOk()
    await waitForSpawnCalls(2)
    firstAttempt.finishFail(1, 'seek out of range')
    await waitForSpawnCalls(3)
    secondAttempt.finishOk()

    await expect(promise).resolves.toBeUndefined()
    expect(spawnMock).toHaveBeenNthCalledWith(2, 'ffmpeg', expect.arrayContaining(['-ss', '5']), expect.anything())
    expect(spawnMock).toHaveBeenNthCalledWith(3, 'ffmpeg', expect.arrayContaining(['-ss', '0']), expect.anything())
  })

  it('throws when even the 0s fallback fails', async () => {
    const versionChild = new FakeChild()
    const firstAttempt = new FakeChild()
    const secondAttempt = new FakeChild()
    spawnMock.mockReturnValueOnce(versionChild).mockReturnValueOnce(firstAttempt).mockReturnValueOnce(secondAttempt)

    const promise = media.extractPoster('/tmp/broken.mp4', '/tmp/out.jpg', 5)
    versionChild.finishOk()
    await waitForSpawnCalls(2)
    firstAttempt.finishFail(1, 'first failure')
    await waitForSpawnCalls(3)
    secondAttempt.finishFail(1, 'second failure')

    await expect(promise).rejects.toThrow(/second failure/)
  })
})
