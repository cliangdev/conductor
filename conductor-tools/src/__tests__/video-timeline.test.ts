import { describe, it, expect } from 'vitest'
import {
  STATIC_MOTION_MAX,
  firstSecondTextCheck,
  motionScore,
  openingMotionChecks,
  overlappingBeatChecks,
  resolveTimeline,
  staticStretchChecks,
  staticStretches,
  validateVideoSpec,
  type ClipSource,
  type VideoSpec,
} from '../lib/video/timeline.js'

const clipSrc = (durationSec: number, hasAudio = true): ClipSource => ({ durationSec, hasAudio, width: 1080, height: 1920 })
const spec = (over: Partial<VideoSpec> = {}): VideoSpec => ({
  segments: [{ clip: 'a.mp4', startSec: 0.5, endSec: 3.9 }, { image: 'b.png', durationSec: 3 }, { image: 'c.png', durationSec: 4, zoom: true }],
  texts: [{ fromSec: 0, toSec: 3.4, t: 'Hook', y: 30 }],
  ...over,
})

describe('validateVideoSpec', () => {
  it('accepts a good spec', () => {
    expect(validateVideoSpec(spec())).toEqual([])
  })

  it('names the offending path for every kind of mistake', () => {
    const errs = validateVideoSpec({
      segments: [
        { clip: 'a.mp4', startSec: 3, endSec: 2 },
        { image: 'b.png', durationSec: 11 },
        { image: 'c.png' },
        { clip: 'd.mp4', image: 'd.png' },
        { clip: 'e.mp4', durationSec: 3 },
        { image: 'f.png', durationSec: 3, transition: 'wipe' },
      ],
      texts: [
        { fromSec: 2, toSec: 1, t: 'x', y: 10 },
        { fromSec: 0, toSec: 1, t: '', y: 10 },
        { fromSec: 0, toSec: 1, t: 'x', y: 120 },
        { fromSec: 0, toSec: 1, t: 'x', y: 10, style: 'neon' },
        { fromSec: 0, toSec: 1, t: 'x', y: 10, fs: 0 },
      ],
      audio: { clipAudio: 'loud', duck: 'yes', track: '' },
    })
    const text = errs.join('\n')
    for (const path of [
      'segments[0].endSec',
      'segments[1].durationSec',
      'segments[2].durationSec',
      'segments[3]',
      'segments[4].durationSec',
      'segments[5].transition',
      'texts[0].toSec',
      'texts[1].t',
      'texts[2].y',
      'texts[3].style',
      'texts[4].fs',
      'audio.clipAudio',
      'audio.duck',
      'audio.track',
    ]) {
      expect(errs.some((e) => e.startsWith(path)), `${path} in:\n${text}`).toBe(true)
    }
  })

  it('rejects a fade on the first segment, a missing list, too many segments and too many beats', () => {
    expect(validateVideoSpec({ segments: [{ image: 'a.png', durationSec: 3, transition: 'fade' }] })[0]).toMatch(/^segments\[0\]\.transition/)
    expect(validateVideoSpec({})[0]).toMatch(/^segments:/)
    expect(validateVideoSpec('x')[0]).toMatch(/^spec:/)
    const many = Array.from({ length: 21 }, () => ({ image: 'a.png', durationSec: 1 }))
    expect(validateVideoSpec({ segments: many })[0]).toMatch(/^segments: 21 segments/)
    const beats = Array.from({ length: 41 }, () => ({ fromSec: 0, toSec: 1, t: 'x', y: 30 }))
    expect(validateVideoSpec({ segments: many.slice(0, 3), texts: beats })[0]).toMatch(/^texts: 41 beats/)
  })
})

describe('resolveTimeline', () => {
  it('computes trims, offsets, total and cut times for hard cuts', () => {
    const { errors, timeline } = resolveTimeline(spec(), [clipSrc(5), undefined, undefined])
    expect(errors).toEqual([])
    expect(timeline!.segments.map((s) => [s.startSec, s.durationSec, s.outStart, s.outEnd, s.transitionIn])).toEqual([
      [0.5, 3.4, 0, 3.4, 'cut'],
      [0, 3, 3.4, 6.4, 'cut'],
      [0, 4, 6.4, 10.4, 'cut'],
    ])
    expect(timeline!.totalSec).toBe(10.4)
    expect(timeline!.cutTimes).toEqual([3.4, 6.4])
    expect(timeline!.segments[2]!.zoom).toBe(true)
  })

  it('a fade overlaps the previous segment by 0.5 s and shortens the total', () => {
    const s = spec({ segments: [{ clip: 'a.mp4', endSec: 3 }, { image: 'b.png', durationSec: 3, transition: 'fade' }, { image: 'c.png', durationSec: 3 }] })
    const { timeline } = resolveTimeline(s, [clipSrc(4), undefined, undefined])
    expect(timeline!.segments.map((x) => [x.outStart, x.outEnd])).toEqual([[0, 3], [2.5, 5.5], [5.5, 8.5]])
    expect(timeline!.totalSec).toBe(8.5)
    expect(timeline!.cutTimes).toEqual([2.5, 5.5])
  })

  it('defaults a clip to its whole length and uses its sound unless muted', () => {
    const s: VideoSpec = { segments: [{ clip: 'a.mp4' }, { clip: 'b.mp4', mute: true }] }
    const { timeline } = resolveTimeline(s, [clipSrc(2), clipSrc(2.5)])
    expect(timeline!.segments.map((x) => [x.durationSec, x.useAudio])).toEqual([[2, true], [2.5, false]])
    expect(resolveTimeline({ ...s, audio: { clipAudio: 'mute' } }, [clipSrc(2), clipSrc(2.5)]).timeline!.segments.map((x) => x.useAudio)).toEqual([false, false])
    expect(resolveTimeline(s, [clipSrc(2, false), clipSrc(2.5)]).timeline!.segments[0]!.useAudio).toBe(false)
  })

  it('reports trims that do not fit the clip, naming the path', () => {
    const past = resolveTimeline({ segments: [{ clip: 'a.mp4', startSec: 1, endSec: 9 }, { image: 'b.png', durationSec: 3 }] }, [clipSrc(5), undefined])
    expect(past.errors[0]).toMatch(/^segments\[0\]\.endSec: 9 is past the end of the clip \(5 s long\)/)
    const start = resolveTimeline({ segments: [{ clip: 'a.mp4', startSec: 5 }] }, [clipSrc(5)])
    expect(start.errors[0]).toMatch(/^segments\[0\]\.startSec/)
    const tiny = resolveTimeline({ segments: [{ clip: 'a.mp4', startSec: 0, endSec: 0.2 }] }, [clipSrc(5)])
    expect(tiny.errors[0]).toMatch(/^segments\[0\]: the trim is 0.2 s/)
  })

  it('enforces the 3 to 90 s total and keeps beats inside the video', () => {
    expect(resolveTimeline({ segments: [{ image: 'a.png', durationSec: 2 }] }, [undefined]).errors[0]).toMatch(/^segments: the video is 2 s long; it must be 3 to 90 s/)
    const long = Array.from({ length: 10 }, () => ({ image: 'a.png', durationSec: 10 }))
    expect(resolveTimeline({ segments: long }, long.map(() => undefined)).errors[0]).toMatch(/is 100 s long/)
    const late = spec({ texts: [{ fromSec: 0, toSec: 99, t: 'x', y: 30 }, { fromSec: 20, toSec: 21, t: 'y', y: 40 }] })
    const res = resolveTimeline(late, [clipSrc(5), undefined, undefined])
    expect(res.errors.map((e) => e.split(':')[0])).toEqual(['texts[0].toSec', 'texts[1].fromSec'])
  })

  it('refuses a fade next to a segment too short to hold it', () => {
    const res = resolveTimeline({ segments: [{ image: 'a.png', durationSec: 3 }, { clip: 'b.mp4', startSec: 0, endSec: 0.8, transition: 'fade' }, { image: 'c.png', durationSec: 3 }] }, [undefined, clipSrc(2), undefined])
    expect(res.errors[0]).toMatch(/^segments\[1\]\.transition: segment 1 is 0.8 s, too short/)
  })
})

describe('spec-level checks', () => {
  it('errors when no beat covers the first second', () => {
    expect(firstSecondTextCheck([{ fromSec: 0, toSec: 3, t: 'x', y: 30 }])).toEqual([])
    expect(firstSecondTextCheck([{ fromSec: 0, toSec: 1, t: 'x', y: 30 }])).toEqual([])
    const late = firstSecondTextCheck([{ fromSec: 0.5, toSec: 3, t: 'x', y: 30 }])
    expect(late[0]).toMatchObject({ rule: 'firstSecondText', severity: 'error', message: expect.stringMatching(/first second has no on-screen text/) })
    expect(firstSecondTextCheck([{ fromSec: 0, toSec: 0.6, t: 'x', y: 30 }])).toHaveLength(1)
    expect(firstSecondTextCheck([])).toHaveLength(1)
  })

  it('only beats on screen together that share rows of the frame collide', () => {
    const a = { fromSec: 0, toSec: 3, top: 500, bottom: 700 }
    expect(overlappingBeatChecks([a, { fromSec: 1, toSec: 4, top: 650, bottom: 800 }])[0]).toMatchObject({ rule: 'textOverlap', severity: 'error', atSeconds: 1, message: expect.stringMatching(/texts\[0\] and texts\[1\]/) })
    expect(overlappingBeatChecks([a, { fromSec: 1, toSec: 4, top: 700, bottom: 800 }])).toEqual([]) // stacked, not overlapping
    expect(overlappingBeatChecks([a, { fromSec: 3, toSec: 4, top: 500, bottom: 700 }])).toEqual([]) // same place, one after the other
    expect(overlappingBeatChecks([a, { fromSec: 1, toSec: 4, top: null, bottom: null }])).toEqual([])
  })

  it('finds stretches over 5 s with no cut and no text change', () => {
    expect(staticStretches(12, [], [])).toEqual([[0, 12]])
    expect(staticStretches(10.4, [3.4, 6.4], [{ fromSec: 0, toSec: 3.4, t: 'x', y: 30 }])).toEqual([])
    expect(staticStretches(10, [], [{ fromSec: 0, toSec: 5, t: 'x', y: 30 }])).toEqual([])
    expect(staticStretches(12, [], [{ fromSec: 0, toSec: 5.1, t: 'x', y: 30 }])).toEqual([[0, 5.1], [5.1, 12]])
    expect(staticStretches(12, [6], [{ fromSec: 0, toSec: 12, t: 'x', y: 30 }])).toEqual([[0, 6], [6, 12]])
    expect(staticStretches(11, [5.5], [])).toEqual([[0, 5.5], [5.5, 11]])
    expect(staticStretches(10, [5], [])).toEqual([])
  })

  it('lists the stretch ranges in one warning', () => {
    const [w] = staticStretchChecks(12, [6], [{ fromSec: 0, toSec: 12, t: 'x', y: 30 }])
    expect(w).toMatchObject({ rule: 'staticStretch', severity: 'warning', message: expect.stringContaining('0-6 s, 6-12 s') })
    expect(staticStretchChecks(10, [3, 6], [])).toEqual([])
  })
})

describe('opening motion', () => {
  it('scores the mean absolute grey difference', () => {
    const a = new Uint8Array([0, 10, 20, 30])
    expect(motionScore(a, a)).toBe(0)
    expect(motionScore(a, new Uint8Array([10, 10, 30, 30]))).toBe(5)
    expect(() => motionScore(a, new Uint8Array([1]))).toThrow()
    expect(() => motionScore(new Uint8Array(0), new Uint8Array(0))).toThrow()
  })

  it('static is an error, a moving still is a warning, a moving clip passes', () => {
    expect(openingMotionChecks('clip', 0.02)[0]).toMatchObject({ rule: 'firstSecondMotion', severity: 'error', message: expect.stringMatching(/completely static/) })
    expect(openingMotionChecks('image', 0.02)[0]!.severity).toBe('error')
    expect(openingMotionChecks('image', 0.4)[0]).toMatchObject({ severity: 'warning', message: expect.stringMatching(/moving clip hooks better/) })
    expect(openingMotionChecks('clip', 1.5)).toEqual([])
    expect(openingMotionChecks('clip', STATIC_MOTION_MAX)).toEqual([])
    expect(openingMotionChecks('clip', STATIC_MOTION_MAX - 0.001)).toHaveLength(1)
  })
})
