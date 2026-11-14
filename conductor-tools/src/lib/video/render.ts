/**
 * compose_video's ffmpeg half: runs the argv builders in conductor-creative/job/media.mjs (the same code the
 * slideshow and CLIP renders use, including the two-pass loudnorm), then reads frames back from the finished
 * file (frame 0, the 0-3 s contact sheet, the opening-motion measurement).
 */
import { execFile } from 'node:child_process'
import { existsSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { runCapture } from '../image-common.js'
import { resolveFfmpegPath } from '../media-probe.js'
import { MOTION_FRAME, MOTION_SAMPLE_SEC, motionScore, type Timeline } from './timeline.js'

/** The slice of conductor-creative/job/media.mjs this module uses. */
export interface MediaCore {
  buildVideoComposeArgs(plan: unknown, inputs: unknown, measured: unknown): string[]
  buildVideoComposeMeasureArgs(plan: unknown, inputs: unknown): string[] | null
  videoComposeHasAudio(plan: unknown, inputs: unknown): boolean
  runFfmpeg(ffmpegPath: string, args: string[]): Promise<string>
  runLoudnormTwoPass(opts: { ffmpegPath: string; measureArgs: string[]; buildEncodeArgs: (measured: unknown) => string[]; log?: (m: string) => void }): Promise<unknown>
}

/**
 * media.mjs is plain ESM shared with the render job. In a build it sits at dist/creative/job (copied by
 * scripts/build-creative.mjs); from source (tests, ts-node) it is read from the sibling conductor-creative.
 */
export async function loadMediaCore(): Promise<MediaCore> {
  const here = dirname(fileURLToPath(import.meta.url))
  const candidates = [join(here, '..', '..', 'creative', 'job', 'media.mjs'), join(here, '..', '..', '..', '..', 'conductor-creative', 'job', 'media.mjs')]
  const found = candidates.find((p) => existsSync(p))
  if (!found) throw new Error(`The video encoder (media.mjs) was not found. In this repo run \`npm run build\` in conductor-tools, or reinstall @cliangdev/conductor. Looked in: ${candidates.join(', ')}`)
  return (await import(pathToFileURL(found).href)) as MediaCore
}

export interface RenderPlanInput {
  timeline: Timeline
  texts: Array<{ fromSec: number; toSec: number }>
  textPngs: string[]
  musicPath?: string
  voicePath?: string
  duck?: boolean
  outPath: string
}

/** The plan and inputs media.mjs takes, from a resolved timeline. Pure (exported for tests). */
export function composePlan(input: RenderPlanInput): { plan: Record<string, unknown>; inputs: Record<string, unknown> } {
  const { timeline } = input
  return {
    plan: {
      segments: timeline.segments.map((s) => ({
        kind: s.kind,
        startSec: s.startSec,
        durationSec: s.durationSec,
        zoom: s.zoom,
        useAudio: s.useAudio,
        transitionIn: s.transitionIn,
      })),
      texts: input.texts,
      totalSec: timeline.totalSec,
      audio: { duck: input.duck !== false },
    },
    inputs: {
      segmentPaths: timeline.segments.map((s) => s.path),
      textPaths: input.textPngs,
      ...(input.musicPath ? { musicPath: input.musicPath } : {}),
      ...(input.voicePath ? { voicePath: input.voicePath } : {}),
      outPath: input.outPath,
    },
  }
}

/** Encodes the video (measure pass first when there is sound). Resolves whether the output has an audio track. */
export async function renderVideo(input: RenderPlanInput, log?: (m: string) => void, core?: MediaCore): Promise<{ hasAudio: boolean }> {
  const media = core ?? (await loadMediaCore())
  const ffmpegPath = await resolveFfmpegPath()
  const { plan, inputs } = composePlan(input)
  const hasAudio = media.videoComposeHasAudio(plan, inputs)
  if (hasAudio) {
    const measureArgs = media.buildVideoComposeMeasureArgs(plan, inputs)!
    await media.runLoudnormTwoPass({ ffmpegPath, measureArgs, buildEncodeArgs: (measured) => media.buildVideoComposeArgs(plan, inputs, measured), log })
  } else {
    await media.runFfmpeg(ffmpegPath, media.buildVideoComposeArgs(plan, inputs, null))
  }
  return { hasAudio }
}

/** One frame at `atSeconds` as 8-bit grey at MOTION_FRAME size. */
function grayFrame(ffmpeg: string, videoPath: string, atSeconds: number): Promise<Buffer> {
  const args = ['-v', 'error', '-ss', atSeconds.toFixed(3), '-i', videoPath, '-frames:v', '1', '-vf', `scale=${MOTION_FRAME.width}:${MOTION_FRAME.height},format=gray`, '-f', 'rawvideo', '-']
  return new Promise((resolve, reject) => {
    execFile(ffmpeg, args, { encoding: 'buffer', maxBuffer: 4 * 1024 * 1024 }, (err, stdout) => {
      if (err) return reject(err)
      if (stdout.length !== MOTION_FRAME.width * MOTION_FRAME.height) return reject(new Error(`ffmpeg returned ${stdout.length} bytes for a grey frame at ${atSeconds}s`))
      resolve(stdout)
    })
  })
}

/** Mean grey difference (0-255) between the first frame and the frame at 0.8 s. */
export async function measureOpeningMotion(videoPath: string): Promise<number> {
  const ffmpeg = await resolveFfmpegPath()
  const [a, b] = await Promise.all([grayFrame(ffmpeg, videoPath, 0), grayFrame(ffmpeg, videoPath, MOTION_SAMPLE_SEC)])
  return motionScore(a, b)
}

/** The first frame as a PNG file. */
export async function extractFrame0(videoPath: string, outPng: string): Promise<void> {
  const ffmpeg = await resolveFfmpegPath()
  const res = await runCapture(ffmpeg, ['-y', '-v', 'error', '-i', videoPath, '-frames:v', '1', outPng])
  if (res.code !== 0 || !existsSync(outPng)) throw new Error(`ffmpeg could not write frame 0: ${res.output.slice(-200)}`)
}

/** Frames at 0, 0.5, 1 ... 3 s in one row (270 px wide each) as a JPEG; the first three seconds are the hook. */
export async function makeHookContactSheet(videoPath: string, outJpg: string): Promise<{ data: Buffer; mimeType: string }> {
  const ffmpeg = await resolveFfmpegPath()
  const res = await runCapture(ffmpeg, ['-y', '-v', 'error', '-i', videoPath, '-vf', 'fps=2,trim=duration=3.2,scale=270:-2,tile=7x1', '-frames:v', '1', '-q:v', '5', outJpg])
  if (res.code !== 0 || !existsSync(outJpg)) throw new Error(`ffmpeg could not write the contact sheet: ${res.output.slice(-200)}`)
  return { data: await readFile(outJpg), mimeType: 'image/jpeg' }
}
