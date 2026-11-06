/** Video frame sampling with ffmpeg: a frame every half second plus the first frame after each cut. */
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { runCapture } from '../image-common.js'
import { resolveFfmpegPath } from '../media-probe.js'

export interface SampledFrame {
  atSeconds: number
  png: Buffer
  isCut: boolean
}

export interface SampleOptions {
  everySeconds?: number
  maxFrames?: number
  sceneThreshold?: number
}

const DEDUPE_SECONDS = 0.2
const EARLY_SECONDS = 3

/** Reads the cut times (pts_time) out of ffmpeg's showinfo log. */
export function parseCutTimes(log: string): number[] {
  return [...log.matchAll(/pts_time:\s*([0-9.]+)/g)].map((m) => Number(m[1])).filter((n) => Number.isFinite(n))
}

/** Reads the clip duration from ffmpeg's "Duration: hh:mm:ss.xx" line (0 when absent). */
export function parseDuration(log: string): number {
  const m = log.match(/Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/)
  return m ? Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) : 0
}

/**
 * The times to look at: a regular grid plus each cut, merged within 0.2 s and capped. A long clip
 * widens the grid to fit the cap but always keeps 0.5 s steps before t=3s. Cuts are kept first.
 */
export function planSampleTimes(
  duration: number,
  cuts: number[],
  { everySeconds = 0.5, maxFrames = 60 }: SampleOptions = {},
): Array<{ at: number; isCut: boolean }> {
  const end = Math.max(0, duration - 0.05)
  const early: number[] = []
  for (let t = 0; t < Math.min(EARLY_SECONDS, end + 0.001); t += 0.5) early.push(t)
  const cutTimes = cuts.filter((c) => c <= end)
  const budget = Math.max(1, maxFrames - early.length - cutTimes.length)
  const lateSpan = Math.max(0, end - EARLY_SECONDS)
  const step = Math.max(everySeconds, lateSpan / budget)
  const grid = [...early]
  for (let t = EARLY_SECONDS; t <= end; t += step) grid.push(t)
  const all = [...grid.map((at) => ({ at, isCut: false })), ...cutTimes.map((at) => ({ at, isCut: true }))].sort((a, b) => a.at - b.at)
  const out: Array<{ at: number; isCut: boolean }> = []
  for (const f of all) {
    const last = out[out.length - 1]
    if (last && f.at - last.at < DEDUPE_SECONDS) {
      if (f.isCut) out[out.length - 1] = f
      continue
    }
    out.push(f)
  }
  return out.slice(0, maxFrames)
}

/** One frame at a time offset as a PNG. */
export async function extractFrameAt(videoPath: string, seconds: number): Promise<Buffer> {
  const ffmpeg = await resolveFfmpegPath()
  const dir = await mkdtemp(join(tmpdir(), 'conductor-frame-'))
  try {
    const out = join(dir, 'f.png')
    const res = await runCapture(ffmpeg, ['-y', '-v', 'error', '-ss', String(seconds), '-i', videoPath, '-frames:v', '1', out])
    if (res.code !== 0) throw new Error(`ffmpeg could not read a frame at ${seconds}s: ${res.output.slice(-200)}`)
    return await readFile(out)
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}

/** Frames every `everySeconds` plus the first frame after each scene cut. */
export async function sampleVideoFrames(videoPath: string, options: SampleOptions = {}): Promise<SampledFrame[]> {
  const { sceneThreshold = 0.3 } = options
  const ffmpeg = await resolveFfmpegPath()
  const probe = await runCapture(ffmpeg, ['-hide_banner', '-i', videoPath, '-f', 'null', '-'])
  const duration = parseDuration(probe.output)
  if (!(duration > 0)) throw new Error('could not read the clip duration')
  const scene = await runCapture(ffmpeg, ['-hide_banner', '-i', videoPath, '-vf', `select='gt(scene,${sceneThreshold})',showinfo`, '-an', '-f', 'null', '-'])
  const plan = planSampleTimes(duration, parseCutTimes(scene.output), options)
  const dir = await mkdtemp(join(tmpdir(), 'conductor-frames-'))
  try {
    const frames: SampledFrame[] = []
    for (let i = 0; i < plan.length; i++) {
      const out = join(dir, `f${i}.png`)
      const res = await runCapture(ffmpeg, ['-y', '-v', 'error', '-ss', plan[i]!.at.toFixed(3), '-i', videoPath, '-frames:v', '1', out])
      if (res.code !== 0) continue
      frames.push({ atSeconds: Number(plan[i]!.at.toFixed(3)), png: await readFile(out), isCut: plan[i]!.isCut })
    }
    return frames
  } finally {
    await rm(dir, { recursive: true, force: true })
  }
}
