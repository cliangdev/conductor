import { createHash } from 'node:crypto'
import { mkdir, writeFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { probeMedia } from '../../lib/media-probe.js'
import { writeProvenanceSidecar } from '../../lib/provenance-sidecar.js'
import { captionBeats, estimateWordTimes, wordsFromTimepoints, type CaptionBeat, type VoiceParams, type Word } from '../../lib/voice-common.js'
import { generateVoice } from '../../lib/voice-generation.js'
import { prepareGeneratedOutDir } from './creative-images.js'

/**
 * generate_voice_line: turns one spoken line into a voice file for compose_video's `audio.voicePath`, with
 * word times and ready-to-paste caption beats, on this machine's Google credentials (billed to the person).
 * Files land in <project>/.conductor/generated/ with a provenance sidecar; nothing is sent to Conductor.
 */

export interface GenerateVoiceLineOptions {
  log?: (message: string) => void
  cwd?: string
  outDir?: string
  /** Overrides the generator (tests). */
  generate?: typeof generateVoice
  /** Overrides the duration probe (tests). */
  measure?: (path: string) => Promise<number | null>
}

export interface GenerateVoiceLineResult {
  audioPath: string
  durationSec: number
  words: Word[]
  timing: 'provider' | 'estimated'
  captionBeats: CaptionBeat[]
  voice: string
  estimatedCostUsd: number
  spentTodayUsd: number
  dailyCapUsd: number
  warnings: string[]
  outDir: string
  provenance: { source: string; licence: string; aiGenerated: true }
  disclosure: string
  nextStep: string
}

const DISCLOSURE =
  'This is a synthetic voice: in realistic content it falls under the platform AI-label rules in the skill\'s compliance checklist (references/compliance-checklist.md); apply them when you publish.'

const NEXT_STEP =
  'Pass audioPath as spec.audio.voicePath with duck: true, and paste captionBeats into spec.texts (add your own hook beats at other y values; beats on screen together must not share rows). The first word should land inside 0.5 s: if the spoken line starts later in the video, shift it with offsetSec and call again. Check the captions against the audio before approving: with timing "estimated" each word time is a guess to within a fraction of a second.'

/** Seconds of a 16-bit PCM WAV from its header, for when ffprobe is missing. */
export function wavSeconds(wav: Buffer): number | null {
  if (wav.length < 44 || wav.toString('ascii', 0, 4) !== 'RIFF') return null
  const byteRate = wav.readUInt32LE(28)
  const data = wav.indexOf('data', 12, 'ascii')
  if (!byteRate || data < 0) return null
  const size = Math.min(wav.readUInt32LE(data + 4), wav.length - data - 8)
  return size / byteRate
}

export async function generateVoiceLine(params: VoiceParams, options: GenerateVoiceLineOptions = {}): Promise<GenerateVoiceLineResult> {
  const run = options.generate ?? generateVoice
  // The gate (project, validation, caps, confirmation) runs inside and throws before anything is created on disk.
  const gen = await run(params)
  const warnings: string[] = []

  const outDir = resolve(options.outDir ?? params.outDir ?? (await prepareGeneratedOutDir(`voice ${gen.request.text.slice(0, 40)}`, options.cwd)))
  await mkdir(outDir, { recursive: true })
  const audioPath = join(outDir, 'voice.wav')
  await writeFile(audioPath, gen.audio)

  let durationSec = await (options.measure ?? (async (p: string) => (await probeMedia(p)).durationSeconds ?? null))(audioPath).catch(() => null)
  if (durationSec === null) {
    durationSec = wavSeconds(gen.audio)
    warnings.push('ffprobe could not measure the audio; the duration was read from the WAV header.')
  }
  if (durationSec === null || durationSec <= 0) throw new Error(`The audio was saved (${audioPath}) but its length could not be measured, so word times cannot be made.`)
  durationSec = Math.round(durationSec * 1000) / 1000

  const fromProvider = gen.marked ? wordsFromTimepoints(gen.request.words, gen.timepoints, durationSec) : null
  if (gen.marked && !fromProvider) warnings.push('The provider returned no usable word marks; word times are estimated.')
  const words = fromProvider ?? estimateWordTimes(gen.request.words, durationSec)
  const timing = fromProvider ? 'provider' : 'estimated'
  const beats = captionBeats(words, gen.request.offsetSec)
  if (gen.request.offsetSec === 0 && words[0] && words[0].fromSec > 0.5 && timing === 'provider') warnings.push(`The first word starts at ${words[0].fromSec} s; the hook voice should start inside 0.5 s.`)
  if (timing === 'estimated' && gen.request.offsetSec === 0) warnings.push('Estimated word times assume speech starts at 0 s; check the first caption against the audio.')

  const date = new Date().toISOString().slice(0, 10)
  const provenance = { source: `Synthesised with Google Cloud Text-to-Speech (${gen.request.voice}) on ${date}`, licence: 'Generated, house use', aiGenerated: true as const }
  await writeProvenanceSidecar(audioPath, {
    ...provenance,
    model: gen.request.voice,
    route: 'google-cloud-tts',
    voice: gen.request.voice,
    textSha256: createHash('sha256').update(gen.request.text).digest('hex'),
    characters: gen.characters,
    timing,
    estimatedCostUsd: gen.estimateUsd,
    generatedAt: new Date().toISOString(),
    durationSeconds: durationSec,
  })

  return {
    audioPath,
    durationSec,
    words,
    timing,
    captionBeats: beats,
    voice: gen.request.voice,
    estimatedCostUsd: gen.estimateUsd,
    spentTodayUsd: gen.spentTodayUsd,
    dailyCapUsd: gen.dailyCapUsd,
    warnings,
    outDir,
    provenance,
    disclosure: DISCLOSURE,
    nextStep: NEXT_STEP,
  }
}
