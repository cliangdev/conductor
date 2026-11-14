/**
 * compose_video: the timeline spec, its validation and the pure timeline math (segment offsets, trims, total
 * length, text windows) and spec-level checks. No ffmpeg, no browser: render.ts and text-beats.ts do that.
 *
 * Spec:
 *   segments  in order; { clip, startSec?, endSec?, mute? } or { image, durationSec, zoom? }; either may carry
 *             transition: "fade" (the boundary INTO that segment cross-fades 0.5 s; the default is a hard cut)
 *   texts     timed beats { fromSec, toSec, t, y, fs?, style? } (the compose_slideshow text renderer and styles)
 *   audio     { clipAudio: keep | mute, track?, voicePath?, duck? }
 * Times are seconds on the OUTPUT timeline.
 */

import type { DraftCheck } from '../creative-render.js'
import { TEXT_STYLES, type TextStyle } from '../slideshow/spec.js'

export const VIDEO_LIMITS = {
  minTotalSec: 3,
  maxTotalSec: 90,
  maxSegments: 20,
  maxTexts: 40,
  imageMinSec: 1,
  imageMaxSec: 10,
  /** A clip segment is at least this long (shorter is a flash, not a shot). */
  clipMinSec: 0.5,
  /** The cross-fade length (conductor-creative/job/media.mjs CROSSFADE_SEC). */
  fadeSec: 0.5,
  /** A segment that touches a fade has to outlast the fade. */
  fadeSegmentMinSec: 1,
  maxTextChars: 200,
  /** No cut and no text change for longer than this is a warning. */
  staticStretchSec: 5,
} as const

export interface ClipSegmentSpec {
  clip: string
  startSec?: number
  endSec?: number
  mute?: boolean
  transition?: 'cut' | 'fade'
}
export interface ImageSegmentSpec {
  image: string
  durationSec: number
  zoom?: boolean
  transition?: 'cut' | 'fade'
}
export type SegmentSpec = ClipSegmentSpec | ImageSegmentSpec

export interface TextBeatSpec {
  fromSec: number
  toSec: number
  t: string
  /** % of the 9:16 frame height (the compose_slideshow convention). */
  y: number
  fs?: number
  style?: TextStyle
}

export interface VideoAudioSpec {
  clipAudio?: 'keep' | 'mute'
  /** A local audio file used as the music track. */
  track?: string
  /** A local audio file played over the music (and the clips' sound). */
  voicePath?: string
  /** Duck the music under clip sound and voice (default true). */
  duck?: boolean
}

export interface VideoSpec {
  id?: string
  layout?: number
  segments: SegmentSpec[]
  texts?: TextBeatSpec[]
  audio?: VideoAudioSpec
}

export const isClipSegment = (s: SegmentSpec): s is ClipSegmentSpec => typeof (s as ClipSegmentSpec).clip === 'string'

type Obj = Record<string, unknown>
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v)
const isNum = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v)
const isStr = (v: unknown): v is string => typeof v === 'string'
const r3 = (n: number): number => Math.round(n * 1000) / 1000

/**
 * Structural validation. Every message starts with the offending path (`segments[1].endSec: ...`); nothing is
 * read from disk here. An empty list means the spec has the right shape (sources and total length come next).
 */
export function validateVideoSpec(spec: unknown): string[] {
  const errors: string[] = []
  if (!isObj(spec)) return ['spec: must be an object with { segments, texts?, audio? }']
  const segs = spec['segments']
  if (!Array.isArray(segs) || segs.length === 0) {
    errors.push('segments: a non-empty array is required')
  } else {
    if (segs.length > VIDEO_LIMITS.maxSegments) errors.push(`segments: ${segs.length} segments; the maximum is ${VIDEO_LIMITS.maxSegments}`)
    segs.forEach((raw, i) => {
      const at = `segments[${i}]`
      if (!isObj(raw)) return void errors.push(`${at}: must be an object with clip or image`)
      const hasClip = raw['clip'] !== undefined
      const hasImage = raw['image'] !== undefined
      if (hasClip === hasImage) return void errors.push(`${at}: give exactly one of clip (a video file) or image (a still)`)
      if (raw['transition'] !== undefined && raw['transition'] !== 'cut' && raw['transition'] !== 'fade') {
        errors.push(`${at}.transition: must be "cut" or "fade", got ${JSON.stringify(raw['transition'])}`)
      } else if (i === 0 && raw['transition'] === 'fade') {
        errors.push(`${at}.transition: the first segment has nothing to fade from`)
      }
      if (hasClip) {
        if (!isStr(raw['clip']) || raw['clip'].trim() === '') errors.push(`${at}.clip: must be a local file path`)
        for (const k of ['startSec', 'endSec'] as const) {
          if (raw[k] !== undefined && !(isNum(raw[k]) && (raw[k] as number) >= 0)) errors.push(`${at}.${k}: must be a number of seconds from 0`)
        }
        if (isNum(raw['startSec']) && isNum(raw['endSec']) && raw['endSec'] <= raw['startSec']) {
          errors.push(`${at}.endSec: ${raw['endSec']} must be after startSec ${raw['startSec']}`)
        }
        if (raw['mute'] !== undefined && typeof raw['mute'] !== 'boolean') errors.push(`${at}.mute: must be true or false`)
        for (const k of ['durationSec', 'zoom']) if (raw[k] !== undefined) errors.push(`${at}.${k}: only for an image segment; trim a clip with startSec and endSec`)
      } else {
        if (!isStr(raw['image']) || raw['image'].trim() === '') errors.push(`${at}.image: must be a local file path`)
        const d = raw['durationSec']
        if (!isNum(d) || d < VIDEO_LIMITS.imageMinSec || d > VIDEO_LIMITS.imageMaxSec) {
          errors.push(`${at}.durationSec: required, a number from ${VIDEO_LIMITS.imageMinSec} to ${VIDEO_LIMITS.imageMaxSec} seconds`)
        }
        if (raw['zoom'] !== undefined && typeof raw['zoom'] !== 'boolean') errors.push(`${at}.zoom: must be true or false`)
        for (const k of ['startSec', 'endSec', 'mute']) if (raw[k] !== undefined) errors.push(`${at}.${k}: only for a clip segment`)
      }
    })
  }

  const texts = spec['texts']
  if (texts !== undefined) {
    if (!Array.isArray(texts)) {
      errors.push('texts: must be an array of { fromSec, toSec, t, y, fs?, style? }')
    } else {
      if (texts.length > VIDEO_LIMITS.maxTexts) errors.push(`texts: ${texts.length} beats; the maximum is ${VIDEO_LIMITS.maxTexts}`)
      texts.forEach((raw, i) => {
        const at = `texts[${i}]`
        if (!isObj(raw)) return void errors.push(`${at}: must be an object`)
        if (!isStr(raw['t']) || raw['t'].trim() === '') errors.push(`${at}.t: the text is required`)
        else if (raw['t'].length > VIDEO_LIMITS.maxTextChars) errors.push(`${at}.t: ${raw['t'].length} characters; the maximum is ${VIDEO_LIMITS.maxTextChars}`)
        if (!isNum(raw['fromSec']) || raw['fromSec'] < 0) errors.push(`${at}.fromSec: required, seconds from 0`)
        if (!isNum(raw['toSec'])) errors.push(`${at}.toSec: required, seconds`)
        else if (isNum(raw['fromSec']) && raw['toSec'] <= raw['fromSec']) errors.push(`${at}.toSec: ${raw['toSec']} must be after fromSec ${raw['fromSec']}`)
        if (!isNum(raw['y']) || raw['y'] < 0 || raw['y'] > 100) errors.push(`${at}.y: required, a number from 0 to 100 (percent of the 9:16 frame height)`)
        if (raw['fs'] !== undefined && !(isNum(raw['fs']) && raw['fs'] > 0 && raw['fs'] <= 300)) errors.push(`${at}.fs: must be a font size in pixels, up to 300`)
        if (raw['style'] !== undefined && !(TEXT_STYLES as readonly unknown[]).includes(raw['style'])) {
          errors.push(`${at}.style: "${String(raw['style'])}" is unknown; use ${TEXT_STYLES.join(', ')}`)
        }
      })
    }
  }

  const audio = spec['audio']
  if (audio !== undefined) {
    if (!isObj(audio)) {
      errors.push('audio: must be an object { clipAudio?, track?, voicePath?, duck? }')
    } else {
      if (audio['clipAudio'] !== undefined && audio['clipAudio'] !== 'keep' && audio['clipAudio'] !== 'mute') {
        errors.push(`audio.clipAudio: must be "keep" or "mute", got ${JSON.stringify(audio['clipAudio'])}`)
      }
      for (const k of ['track', 'voicePath']) {
        if (audio[k] !== undefined && (!isStr(audio[k]) || (audio[k] as string).trim() === '')) errors.push(`audio.${k}: must be a local audio file path`)
      }
      if (audio['duck'] !== undefined && typeof audio['duck'] !== 'boolean') errors.push('audio.duck: must be true or false')
    }
  }
  return errors
}

/** What the tool learned about each clip segment's source file (ffprobe). */
export interface ClipSource {
  durationSec: number
  hasAudio: boolean
  width?: number
  height?: number
}

export interface TimelineSegment {
  index: number
  kind: 'clip' | 'image'
  path: string
  /** Trim start in the source clip (0 for an image). */
  startSec: number
  durationSec: number
  zoom: boolean
  /** This segment's own sound is used (a clip with sound, not muted, with clipAudio keep). */
  useAudio: boolean
  transitionIn: 'cut' | 'fade'
  /** On the output timeline. */
  outStart: number
  outEnd: number
}

export interface Timeline {
  segments: TimelineSegment[]
  totalSec: number
  /** The times the picture changes by a cut or fade: every segment start but the first. */
  cutTimes: number[]
}

/**
 * Trims, durations and output offsets. `sources` has an entry per segment index (clips only; undefined for
 * images). A segment starts where the previous one ends, minus 0.5 s when the boundary is a fade.
 * Returns errors (each starting with the offending path) instead of a timeline when something does not fit.
 */
export function resolveTimeline(spec: VideoSpec, sources: Array<ClipSource | undefined>): { errors: string[]; timeline?: Timeline } {
  const errors: string[] = []
  const keep = spec.audio?.clipAudio !== 'mute'
  const segments: TimelineSegment[] = []
  let cursor = 0
  spec.segments.forEach((seg, i) => {
    const at = `segments[${i}]`
    const transitionIn = i > 0 && seg.transition === 'fade' ? 'fade' : 'cut'
    let startSec = 0
    let durationSec: number
    let path: string
    let useAudio = false
    if (isClipSegment(seg)) {
      path = seg.clip
      const src = sources[i]
      if (!src) {
        errors.push(`${at}.clip: the file was not inspected`)
        return
      }
      startSec = seg.startSec ?? 0
      if (startSec >= src.durationSec - 0.05) {
        errors.push(`${at}.startSec: ${startSec} is at or past the end of the clip (${r3(src.durationSec)} s long)`)
        return
      }
      const end = seg.endSec ?? src.durationSec
      if (end > src.durationSec + 0.05) {
        errors.push(`${at}.endSec: ${end} is past the end of the clip (${r3(src.durationSec)} s long)`)
        return
      }
      durationSec = r3(Math.min(end, src.durationSec) - startSec)
      if (durationSec < VIDEO_LIMITS.clipMinSec) {
        errors.push(`${at}: the trim is ${durationSec} s; a clip segment must be at least ${VIDEO_LIMITS.clipMinSec} s`)
        return
      }
      useAudio = keep && src.hasAudio && seg.mute !== true
    } else {
      path = seg.image
      durationSec = seg.durationSec
    }
    const outStart = r3(i === 0 ? 0 : cursor - (transitionIn === 'fade' ? VIDEO_LIMITS.fadeSec : 0))
    const outEnd = r3(outStart + durationSec)
    cursor = outEnd
    segments.push({
      index: i,
      kind: isClipSegment(seg) ? 'clip' : 'image',
      path,
      startSec,
      durationSec,
      zoom: !isClipSegment(seg) && seg.zoom === true,
      useAudio,
      transitionIn,
      outStart,
      outEnd,
    })
  })
  if (errors.length > 0) return { errors }

  // a segment that touches a fade has to outlast it
  segments.forEach((s, k) => {
    const next = segments[k + 1]
    const touchesFade = s.transitionIn === 'fade' || next?.transitionIn === 'fade'
    if (touchesFade && s.durationSec < VIDEO_LIMITS.fadeSegmentMinSec) {
      const where = s.transitionIn === 'fade' ? k : k + 1
      errors.push(`segments[${where}].transition: segment ${k} is ${s.durationSec} s, too short for a ${VIDEO_LIMITS.fadeSec} s fade (at least ${VIDEO_LIMITS.fadeSegmentMinSec} s); use a hard cut`)
    }
  })
  const totalSec = r3(segments[segments.length - 1]!.outEnd)
  if (totalSec < VIDEO_LIMITS.minTotalSec || totalSec > VIDEO_LIMITS.maxTotalSec) {
    errors.push(`segments: the video is ${totalSec} s long; it must be ${VIDEO_LIMITS.minTotalSec} to ${VIDEO_LIMITS.maxTotalSec} s`)
  }
  ;(spec.texts ?? []).forEach((b, i) => {
    if (b.fromSec >= totalSec) errors.push(`texts[${i}].fromSec: ${b.fromSec} is after the end of the video (${totalSec} s)`)
    else if (b.toSec > totalSec + 0.001) errors.push(`texts[${i}].toSec: ${b.toSec} is after the end of the video (${totalSec} s); end it at ${totalSec} or earlier`)
  })
  if (errors.length > 0) return { errors }
  return { errors, timeline: { segments, totalSec, cutTimes: segments.slice(1).map((s) => s.outStart) } }
}

// --- spec-level checks (known from the spec, no rendering) -------------------------------------

const check = (rule: string, severity: 'error' | 'warning', message: string, extra: Partial<DraftCheck> = {}): DraftCheck => ({
  placementKey: '9x16',
  rule,
  severity,
  message,
  ...extra,
})

/** Text is on screen from the first frame and stays through the first second. */
export function firstSecondTextCheck(texts: readonly TextBeatSpec[]): DraftCheck[] {
  const covered = texts.some((b) => b.fromSec <= 0.1 && b.toSec >= 1)
  return covered
    ? []
    : [check('firstSecondText', 'error', 'the first second has no on-screen text: add a text beat with fromSec 0 and toSec of at least 1 (a text-free opening is a scroll-past)', { atSeconds: 0 })]
}

export interface BeatBox {
  fromSec: number
  toSec: number
  /** Rendered vertical extent in frame pixels; null when it could not be measured. */
  top: number | null
  bottom: number | null
}

/** Beats that are on screen together must not share rows of the frame. */
export function overlappingBeatChecks(beats: readonly BeatBox[]): DraftCheck[] {
  const out: DraftCheck[] = []
  for (let i = 0; i < beats.length; i++) {
    for (let j = i + 1; j < beats.length; j++) {
      const a = beats[i]!
      const b = beats[j]!
      const together = Math.min(a.toSec, b.toSec) - Math.max(a.fromSec, b.fromSec)
      if (together <= 0.001 || a.top === null || a.bottom === null || b.top === null || b.bottom === null) continue
      if (Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top) > 0) {
        const from = Math.max(a.fromSec, b.fromSec)
        out.push(check('textOverlap', 'error', `texts[${i}] and texts[${j}] are on screen together from ${r3(from)} s and cover the same part of the frame (y ${Math.round(a.top)}-${Math.round(a.bottom)} and ${Math.round(b.top)}-${Math.round(b.bottom)} px); move one with y or end one before the other starts`, { atSeconds: from }))
      }
    }
  }
  return out
}

/** Stretches longer than the limit with no cut and no text change (a beat appearing or leaving), as [from, to]. */
export function staticStretches(totalSec: number, cutTimes: readonly number[], texts: readonly TextBeatSpec[], limit: number = VIDEO_LIMITS.staticStretchSec): Array<[number, number]> {
  const events = new Set<number>([0, r3(totalSec)])
  for (const c of cutTimes) events.add(r3(c))
  for (const b of texts) {
    events.add(r3(Math.min(b.fromSec, totalSec)))
    events.add(r3(Math.min(b.toSec, totalSec)))
  }
  const sorted = [...events].sort((a, b) => a - b)
  const out: Array<[number, number]> = []
  for (let i = 1; i < sorted.length; i++) {
    if (sorted[i]! - sorted[i - 1]! > limit + 0.0005) out.push([sorted[i - 1]!, sorted[i]!])
  }
  return out
}

export function staticStretchChecks(totalSec: number, cutTimes: readonly number[], texts: readonly TextBeatSpec[]): DraftCheck[] {
  const stretches = staticStretches(totalSec, cutTimes, texts)
  if (stretches.length === 0) return []
  const list = stretches.map(([a, b]) => `${a}-${b} s`).join(', ')
  return [check('staticStretch', 'warning', `nothing changes (no cut, no text change) for more than ${VIDEO_LIMITS.staticStretchSec} s at ${list}: add a cut or a new text beat there`, { atSeconds: stretches[0]![0] })]
}

// --- motion in the opening --------------------------------------------------------------------

/** Size of the grey frames compared for motion (9:16, small enough that compression noise averages out). */
export const MOTION_FRAME = { width: 108, height: 192 } as const
/** Seconds into the video the second frame is taken (the first is 0 s). */
export const MOTION_SAMPLE_SEC = 0.8
/**
 * Mean absolute grey difference (0-255) between frame 0 and frame 0.8 s below which the opening counts as
 * completely static. Measured on renders of this pipeline (frames at 0 s and 0.8 s, 108x192 grey) and on
 * ffmpeg lavfi sources:
 *   still + text, no zoom          0.01-0.02   (encoder noise only)
 *   still + text, 3 s slow zoom    0.39-0.47   (weak motion; a 10 s still zooms 3x slower and can fall under)
 *   testsrc2 test pattern          1.35-1.58
 *   gradients drifting slowly      12.9
 *   mandelbrot zooming             19.6
 *   locked-off grey + heavy noise  0.82        (noise counts as change: a false pass, never a false fail)
 * 0.25 sits in the gap between the still and the zoomed still, so only a picture that does not change fails.
 */
export const STATIC_MOTION_MAX = 0.25

/** Mean absolute difference of two equally sized 8-bit grey frames (0-255). */
export function motionScore(a: Uint8Array, b: Uint8Array): number {
  if (a.length === 0 || a.length !== b.length) throw new Error('motionScore: the frames must be the same non-zero size')
  let sum = 0
  for (let i = 0; i < a.length; i++) sum += Math.abs(a[i]! - b[i]!)
  return sum / a.length
}

/** The opening's motion verdict. A moving clip passes; a still opening is a warning; nothing moving is an error. */
export function openingMotionChecks(firstKind: 'clip' | 'image', score: number): DraftCheck[] {
  const s = Math.round(score * 100) / 100
  if (score < STATIC_MOTION_MAX) {
    return [check('firstSecondMotion', 'error', `the opening is completely static (nothing changes between 0 s and ${MOTION_SAMPLE_SEC} s, difference ${s} of 255): open on a moving clip, or on a still with zoom: true as a last resort`, { atSeconds: 0 })]
  }
  if (firstKind === 'image') {
    return [check('firstSecondMotion', 'warning', `the opening is a still image (only the slow zoom moves, difference ${s} of 255): a moving clip hooks better in the first second`, { atSeconds: 0 })]
  }
  return []
}
