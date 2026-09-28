import { describe, it, expect, vi, beforeEach } from 'vitest'
import { EventEmitter } from 'node:events'

class FakeChild extends EventEmitter {
  stderr = new EventEmitter()
  finishOk(): void {
    this.emit('exit', 0)
  }
  finishFail(stderr = 'boom'): void {
    this.stderr.emit('data', stderr)
    this.emit('exit', 1)
  }
}

const spawnMock = vi.fn()
vi.mock('node:child_process', () => ({ spawn: (...args: unknown[]) => spawnMock(...args) }))

const readFileMock = vi.fn()
const unlinkMock = vi.fn()
vi.mock('node:fs/promises', () => ({ readFile: (...args: unknown[]) => readFileMock(...args), unlink: (...args: unknown[]) => unlinkMock(...args) }))

const resolveFfmpegPathMock = vi.fn()
vi.mock('../lib/media-probe.js', () => ({ resolveFfmpegPath: () => resolveFfmpegPathMock() }))

/** composePosterSheet awaits resolveFfmpegPath() (a mocked promise) before calling spawn(), so the
 * spawn() call — and the attaching of its 'exit'/'stderr' listeners — happens a microtask hop after
 * the function is invoked. Poll on a macrotask boundary instead of guessing a fixed number of ticks. */
async function waitForSpawnCalls(n: number, timeoutMs = 1000): Promise<void> {
  const start = Date.now()
  while (spawnMock.mock.calls.length < n) {
    if (Date.now() - start > timeoutMs) throw new Error(`timed out waiting for spawn() call #${n}`)
    await new Promise((r) => setTimeout(r, 0))
  }
}

let posterSheet: typeof import('../lib/poster-sheet.js')

beforeEach(async () => {
  vi.resetModules()
  spawnMock.mockReset()
  readFileMock.mockReset()
  unlinkMock.mockReset()
  unlinkMock.mockResolvedValue(undefined)
  resolveFfmpegPathMock.mockReset()
  resolveFfmpegPathMock.mockResolvedValue('/usr/bin/ffmpeg')
  posterSheet = await import('../lib/poster-sheet.js')
})

describe('buildSheetFilterComplex', () => {
  it('scales a single poster with no stacking', () => {
    expect(posterSheet.buildSheetFilterComplex(1)).toBe('[0:v]scale=-2:360[v]')
  })

  it('scales every input to ~360px high then hstacks them left to right', () => {
    const filter = posterSheet.buildSheetFilterComplex(3)
    expect(filter).toBe('[0:v]scale=-2:360[v0];[1:v]scale=-2:360[v1];[2:v]scale=-2:360[v2];[v0][v1][v2]hstack=inputs=3[v]')
  })
})

describe('composePosterSheet', () => {
  it('spawns ffmpeg with an hstack filter over every poster and returns the composed bytes', async () => {
    const child = new FakeChild()
    spawnMock.mockReturnValueOnce(child)
    readFileMock.mockResolvedValueOnce(Buffer.from('sheet-bytes'))

    const promise = posterSheet.composePosterSheet(['/tmp/9x16.jpg', '/tmp/4x5.jpg'])
    await waitForSpawnCalls(1)
    child.finishOk()

    const result = await promise
    expect(result).toEqual(Buffer.from('sheet-bytes'))

    const [ffmpegPath, args] = spawnMock.mock.calls[0]!
    expect(ffmpegPath).toBe('/usr/bin/ffmpeg')
    expect(args).toEqual(
      expect.arrayContaining(['-i', '/tmp/9x16.jpg', '-i', '/tmp/4x5.jpg', '-filter_complex', expect.stringContaining('hstack=inputs=2'), '-map', '[v]'])
    )
    expect(unlinkMock).toHaveBeenCalled()
  })

  it('rejects with ffmpeg stderr when composition fails, so a caller can fall back to one poster', async () => {
    const child = new FakeChild()
    spawnMock.mockReturnValueOnce(child)

    const promise = posterSheet.composePosterSheet(['/tmp/only.jpg'])
    await waitForSpawnCalls(1)
    child.finishFail('unsupported pixel format')

    await expect(promise).rejects.toThrow(/unsupported pixel format/)
    expect(readFileMock).not.toHaveBeenCalled()
  })

  it('throws synchronously when given no posters, before touching ffmpeg', async () => {
    await expect(posterSheet.composePosterSheet([])).rejects.toThrow(/needs at least one poster/)
    expect(resolveFfmpegPathMock).not.toHaveBeenCalled()
  })
})
