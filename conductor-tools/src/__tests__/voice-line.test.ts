import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mkdtempSync, rmSync, readFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

vi.mock('../lib/config.js', () => ({ readConfig: vi.fn() }))

import { readConfig } from '../lib/config.js'
import {
  CAPTION_Y,
  buildMarkedSsml,
  captionBeats,
  estimateVoiceCost,
  estimateWordTimes,
  normalizeVoiceParams,
  ttsFailure,
  wordsFromTimepoints,
} from '../lib/voice-common.js'
import { buildTtsRequest, generateVoice, synthesize, voiceCaps } from '../lib/voice-generation.js'
import { generateVoiceLine, wavSeconds } from '../mcp/tools/creative-voice.js'
import { record, spentToday } from '../lib/video-spend.js'

const cfg = vi.mocked(readConfig)
let tmp: string
const spend = () => ({ path: join(tmp, 'voice-spend.json') })

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), 'voice-line-'))
  cfg.mockReturnValue({ vertexProject: 'proj-1' } as never)
})
afterEach(() => rmSync(tmp, { recursive: true, force: true }))

/** A 16-bit mono 24 kHz WAV of `seconds` of silence. */
function wav(seconds: number): Buffer {
  const rate = 24000
  const data = Buffer.alloc(Math.round(seconds * rate) * 2)
  const h = Buffer.alloc(44)
  h.write('RIFF', 0)
  h.writeUInt32LE(36 + data.length, 4)
  h.write('WAVEfmt ', 8)
  h.writeUInt32LE(16, 16)
  h.writeUInt16LE(1, 20)
  h.writeUInt16LE(1, 22)
  h.writeUInt32LE(rate, 24)
  h.writeUInt32LE(rate * 2, 28)
  h.writeUInt16LE(2, 32)
  h.writeUInt16LE(16, 34)
  h.write('data', 36)
  h.writeUInt32LE(data.length, 40)
  return Buffer.concat([h, data])
}
const ok = (body: unknown) => ({ ok: true, status: 200, json: async () => body }) as Response
const fail = (status: number, message: string) => ({ ok: false, status, statusText: 'x', json: async () => ({ error: { message } }) }) as Response

describe('input validation', () => {
  it('rejects empty and too long text', () => {
    expect(() => normalizeVoiceParams({ text: '  ' })).toThrow(/required/)
    expect(() => normalizeVoiceParams({ text: 'a'.repeat(401) })).toThrow(/too long/)
    expect(() => normalizeVoiceParams({ text: Array(61).fill('hi').join(' ') })).toThrow(/60 words/)
    expect(normalizeVoiceParams({ text: Array(60).fill('hi').join(' ') }).words).toHaveLength(60)
  })
  it('validates voice, rate and offset', () => {
    expect(normalizeVoiceParams({ text: 'Hi there' })).toMatchObject({ voice: 'en-US-Chirp3-HD-Aoede', family: 'Chirp3-HD', languageCode: 'en-US', speakingRate: 1, offsetSec: 0 })
    expect(normalizeVoiceParams({ text: 'Hi', voice: 'en-US-Neural2-F' }).family).toBe('Neural2')
    expect(() => normalizeVoiceParams({ text: 'Hi', voice: 'robot' })).toThrow(/voice/)
    expect(() => normalizeVoiceParams({ text: 'Hi', voice: 'en-US-Journey-D' })).toThrow(/voice/)
    expect(() => normalizeVoiceParams({ text: 'Hi', speakingRate: 0.1 })).toThrow(/0.25 to 2/)
    expect(() => normalizeVoiceParams({ text: 'Hi', speakingRate: 2.5 })).toThrow(/0.25 to 2/)
    expect(() => normalizeVoiceParams({ text: 'Hi', offsetSec: -1 })).toThrow(/offsetSec/)
  })
})

describe('cost', () => {
  it('estimates from characters and the rate table, with overrides', () => {
    expect(estimateVoiceCost(100, 'Chirp3-HD').estimateUsd).toBe(0.003)
    expect(estimateVoiceCost(100, 'Neural2').estimateUsd).toBe(0.0016)
    expect(estimateVoiceCost(100, 'Chirp3-HD', { 'Chirp3-HD': 60 }).estimateUsd).toBe(0.006)
  })
  it('reads caps from config with defaults', () => {
    expect(voiceCaps()).toEqual({ perCallUsd: 0.25, dailyUsd: 2, confirmAboveUsd: 0.05 })
    cfg.mockReturnValue({ voicePerCallCapUsd: 1, voiceDailyCapUsd: 3, voiceConfirmAboveUsd: 0 } as never)
    expect(voiceCaps()).toEqual({ perCallUsd: 1, dailyUsd: 3, confirmAboveUsd: 0 })
  })
})

describe('word timing', () => {
  it('estimates by character count over the measured duration', () => {
    const w = estimateWordTimes(['Hi', 'there.'], 2)
    expect(w[0]!.fromSec).toBe(0)
    expect(w[1]!.toSec).toBe(2)
    expect(w[0]!.toSec).toBe(w[1]!.fromSec)
    // "there." (5 letters + sentence pause) outweighs "Hi" (2)
    expect(w[1]!.toSec - w[1]!.fromSec).toBeGreaterThan(w[0]!.toSec - w[0]!.fromSec)
  })
  it('builds marked SSML and parses timepoints', () => {
    expect(buildMarkedSsml(['a&b', '<x>'])).toBe('<speak><mark name="w0"/>a&amp;b <mark name="w1"/>&lt;x&gt;</speak>')
    const words = wordsFromTimepoints(['one', 'two'], [{ markName: 'w1', timeSeconds: 0.6 }, { markName: 'w0', timeSeconds: 0.1 }], 1.2)
    expect(words).toEqual([
      { w: 'one', fromSec: 0.1, toSec: 0.6 },
      { w: 'two', fromSec: 0.6, toSec: 1.2 },
    ])
    expect(wordsFromTimepoints(['one', 'two'], [{ markName: 'w0', timeSeconds: 0.1 }], 1)).toBeNull()
    expect(wordsFromTimepoints(['one'], undefined, 1)).toBeNull()
  })
})

describe('caption chunking', () => {
  const at = (ws: string[], step: number) => ws.map((w, i) => ({ w, fromSec: i * step, toSec: (i + 1) * step }))
  it('keeps chunks to 4 words and 1.2 s, in the safe zone', () => {
    const beats = captionBeats(at('one two three four five six seven eight nine'.split(' '), 0.3))
    for (const b of beats) {
      expect(b.t.split(' ').length).toBeLessThanOrEqual(4)
      expect(b.y).toBe(CAPTION_Y)
      expect(b.y).toBeGreaterThan(15)
      expect(b.y).toBeLessThan(77)
    }
    expect(beats.map((b) => b.t)).toEqual(['one two three four', 'five six seven eight', 'nine'])
  })
  it('splits slow speech at 1.2 s and after clause ends', () => {
    expect(captionBeats(at(['a', 'b', 'c', 'd'], 0.6)).map((b) => b.t)).toEqual(['a b', 'c d'])
    expect(captionBeats(at(['Stop,', 'now', 'please'], 0.2)).map((b) => b.t)).toEqual(['Stop,', 'now please'])
  })
  it('starts at the offset, lasts past 1 s on the first beat, and never overlaps', () => {
    const beats = captionBeats(at(['Stop', 'scrolling.', 'Watch', 'this'], 0.2), 0)
    expect(beats[0]!.fromSec).toBe(0)
    expect(beats[0]!.toSec).toBeGreaterThan(1)
    for (let i = 1; i < beats.length; i++) expect(beats[i]!.fromSec).toBeGreaterThanOrEqual(beats[i - 1]!.toSec)
    const shifted = captionBeats(at(['a', 'b'], 0.5), 2)
    expect(shifted[0]!.fromSec).toBe(2)
  })
})

describe('request shape and auth', () => {
  it('sends plain text to v1 for Chirp 3: HD and SSML marks to v1beta1 otherwise', () => {
    const chirp = buildTtsRequest(normalizeVoiceParams({ text: 'Hello world', speakingRate: 1.1 }))
    expect(chirp.url).toBe('https://texttospeech.googleapis.com/v1/text:synthesize')
    expect(chirp.body).toEqual({ input: { text: 'Hello world' }, voice: { languageCode: 'en-US', name: 'en-US-Chirp3-HD-Aoede' }, audioConfig: { audioEncoding: 'LINEAR16', speakingRate: 1.1 } })
    const neural = buildTtsRequest(normalizeVoiceParams({ text: 'Hello world', voice: 'en-US-Neural2-F' }))
    expect(neural.url).toContain('/v1beta1/')
    expect(neural.body).toMatchObject({ enableTimePointing: ['SSML_MARK'], input: { ssml: expect.stringContaining('<mark name="w1"/>world') } })
  })
  it('uses the token helper and the billing project header', async () => {
    const f = vi.fn(async () => ok({ audioContent: wav(0.1).toString('base64') }))
    const out = await synthesize(normalizeVoiceParams({ text: 'Hi' }), 'proj-1', { fetch: f as never, getToken: async () => 'tok-abc' })
    expect(out.audio.length).toBeGreaterThan(44)
    const [, init] = f.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.headers).toMatchObject({ Authorization: 'Bearer tok-abc', 'x-goog-user-project': 'proj-1' })
  })
  it('retries a 5xx, then succeeds', async () => {
    const f = vi.fn().mockResolvedValueOnce(fail(503, 'busy')).mockResolvedValueOnce(ok({ audioContent: wav(0.1).toString('base64') }))
    const sleep = vi.fn(async () => {})
    await synthesize(normalizeVoiceParams({ text: 'Hi' }), 'p', { fetch: f as never, sleep, getToken: async () => 't' })
    expect(f).toHaveBeenCalledTimes(2)
    expect(sleep).toHaveBeenCalledTimes(1)
  })
  it('refreshes the token once on a 401', async () => {
    const tokens = ['old', 'new']
    const f = vi.fn().mockResolvedValueOnce(fail(401, 'expired')).mockResolvedValueOnce(ok({ audioContent: wav(0.1).toString('base64') }))
    await synthesize(normalizeVoiceParams({ text: 'Hi' }), 'p', { fetch: f as never, getToken: async () => tokens.shift()! })
    expect((f.mock.calls[1] as unknown as [string, RequestInit])[1].headers).toMatchObject({ Authorization: 'Bearer new' })
  })
})

describe('error mapping', () => {
  const run = (status: number, msg: string) => ttsFailure(status, msg, 'proj-1', 'en-US-Chirp3-HD-Aoede')
  it('API disabled names the API and the gcloud command', () => {
    const e = run(403, 'Cloud Text-to-Speech API has not been used in project 123 before or it is disabled. Enable it by visiting ...')
    expect(e.code).toBe('VOICE_API_DISABLED')
    expect(e.message).toContain('gcloud services enable texttospeech.googleapis.com --project proj-1')
    expect(e.details['enableCommand']).toBe('gcloud services enable texttospeech.googleapis.com --project proj-1')
  })
  it('maps auth, quota, bad voice and other failures', () => {
    expect(run(401, 'bad token').code).toBe('VOICE_AUTH')
    expect(run(403, 'The caller does not have permission').code).toBe('VOICE_AUTH')
    expect(run(429, 'Quota exceeded').code).toBe('VOICE_QUOTA')
    expect(run(400, 'Voice en-US-X does not exist').code).toBe('VOICE_NOT_FOUND')
    expect(run(500, 'oops').code).toBe('VOICE_FAILED')
  })
  it('a 403 through synthesize is a VoiceError, not a stack trace', async () => {
    const f = vi.fn(async () => fail(403, 'Text-to-Speech API has not been used in project 1 before or it is disabled.'))
    await expect(synthesize(normalizeVoiceParams({ text: 'Hi' }), 'p', { fetch: f as never, getToken: async () => 't' })).rejects.toMatchObject({ code: 'VOICE_API_DISABLED' })
  })
  it('a missing token is a structured auth error', async () => {
    await expect(synthesize(normalizeVoiceParams({ text: 'Hi' }), 'p', { fetch: vi.fn() as never, getToken: async () => { throw new Error('no gcloud') } })).rejects.toMatchObject({ code: 'VOICE_AUTH' })
  })
})

describe('gate', () => {
  const deps = (f: unknown) => ({ fetch: f as never, getToken: async () => 't' })
  const good = () => vi.fn(async () => ok({ audioContent: wav(0.5).toString('base64') }))

  it('needs a project', async () => {
    cfg.mockReturnValue({} as never)
    await expect(generateVoice({ text: 'Hi' }, { spend: spend(), deps: deps(good()) })).rejects.toMatchObject({ code: 'VOICE_NOT_CONFIGURED' })
  })
  it('does not ask for confirmation under the threshold, records the spend and reports it', async () => {
    const f = good()
    const r = await generateVoice({ text: 'Stop scrolling, this saves dinner.' }, { spend: spend(), deps: deps(f) })
    expect(r.confirmed).toBe(false)
    expect(r.estimateUsd).toBeLessThan(0.05)
    expect(spentToday(spend())).toBe(r.estimateUsd)
    expect(f).toHaveBeenCalledOnce()
  })
  it('asks for confirmation above the threshold, before any request', async () => {
    const f = good()
    cfg.mockReturnValue({ vertexProject: 'p', voiceConfirmAboveUsd: 0.005 } as never)
    const text = Array(60).fill('narration').join(' ').slice(0, 400)
    await expect(generateVoice({ text }, { spend: spend(), deps: deps(f) })).rejects.toMatchObject({ code: 'VOICE_COST_CONFIRMATION_REQUIRED' })
    expect(f).not.toHaveBeenCalled()
    const r = await generateVoice({ text, confirmCost: true }, { spend: spend(), deps: deps(f) })
    expect(r.confirmed).toBe(true)
  })
  it('enforces the per-call and daily caps', async () => {
    const f = good()
    cfg.mockReturnValue({ vertexProject: 'p', voicePerCallCapUsd: 0.0005 } as never)
    await expect(generateVoice({ text: 'A fairly long line of words here.' }, { spend: spend(), deps: deps(f) })).rejects.toMatchObject({ code: 'VOICE_COST_CAP_EXCEEDED' })
    cfg.mockReturnValue({ vertexProject: 'p', voiceDailyCapUsd: 0.01 } as never)
    record(0.0095, spend())
    await expect(generateVoice({ text: 'A fairly long line of words here.' }, { spend: spend(), deps: deps(f) })).rejects.toMatchObject({ code: 'VOICE_DAILY_CAP_EXCEEDED' })
    expect(f).not.toHaveBeenCalled()
  })
  it('records spend when audio was made but unusable, not when the API refused', async () => {
    await expect(generateVoice({ text: 'Hi' }, { spend: spend(), deps: deps(vi.fn(async () => ok({})) ) })).rejects.toMatchObject({ details: { billed: true } })
    expect(spentToday(spend())).toBeGreaterThan(0)
    const before = spentToday(spend())
    await expect(generateVoice({ text: 'Hi' }, { spend: spend(), deps: deps(vi.fn(async () => fail(403, 'Text-to-Speech API has not been used in project 1 before or it is disabled'))) })).rejects.toMatchObject({ code: 'VOICE_API_DISABLED' })
    expect(spentToday(spend())).toBe(before)
  })
})

describe('generate_voice_line tool', () => {
  it('writes the file, sidecar, words, estimated timing and caption beats', async () => {
    const audio = wav(2)
    const f = vi.fn(async () => ok({ audioContent: audio.toString('base64') }))
    const r = await generateVoiceLine(
      { text: 'Stop scrolling, this saves dinner.', offsetSec: 0 },
      {
        outDir: join(tmp, 'out'),
        generate: (p) => generateVoice(p, { spend: spend(), deps: { fetch: f as never, getToken: async () => 't' } }),
        measure: async () => null, // as if ffprobe were missing: the WAV header is used
      }
    )
    expect(readFileSync(r.audioPath).equals(audio)).toBe(true)
    expect(r.durationSec).toBe(2)
    expect(r.timing).toBe('estimated')
    expect(r.words.map((w) => w.w)).toEqual(['Stop', 'scrolling,', 'this', 'saves', 'dinner.'])
    expect(r.words[4]!.toSec).toBe(2)
    expect(r.captionBeats[0]).toMatchObject({ fromSec: 0, y: CAPTION_Y })
    expect(r.captionBeats[0]!.toSec).toBeGreaterThan(1)
    expect(r.warnings.join(' ')).toMatch(/WAV header/)
    expect(r.disclosure).toMatch(/compliance-checklist/)
    expect(r.nextStep).toMatch(/voicePath/)
    const side = JSON.parse(readFileSync(`${r.audioPath}.provenance.json`, 'utf8'))
    expect(side).toMatchObject({ aiGenerated: true, voice: 'en-US-Chirp3-HD-Aoede', timing: 'estimated', characters: 34 })
    expect(side.textSha256).toMatch(/^[0-9a-f]{64}$/)
    expect(JSON.stringify(side)).not.toContain('saves dinner')
  })
  it('uses provider marks on the SSML route and labels the timing provider', async () => {
    const f = vi.fn(async () =>
      ok({ audioContent: wav(1.5).toString('base64'), timepoints: [{ markName: 'w0', timeSeconds: 0.05 }, { markName: 'w1', timeSeconds: 0.6 }] })
    )
    const r = await generateVoiceLine(
      { text: 'Hello world', voice: 'en-US-Neural2-F' },
      { outDir: join(tmp, 'o2'), generate: (p) => generateVoice(p, { spend: spend(), deps: { fetch: f as never, getToken: async () => 't' } }), measure: async () => 1.5 }
    )
    expect(r.timing).toBe('provider')
    expect(r.words).toEqual([
      { w: 'Hello', fromSec: 0.05, toSec: 0.6 },
      { w: 'world', fromSec: 0.6, toSec: 1.5 },
    ])
  })
  it('creates nothing on disk when the gate refuses', async () => {
    await expect(generateVoiceLine({ text: '' }, { outDir: join(tmp, 'nope'), generate: (p) => generateVoice(p, { spend: spend() }) })).rejects.toThrow(/required/)
    expect(existsSync(join(tmp, 'nope'))).toBe(false)
  })
  it('reads a WAV duration', () => {
    expect(wavSeconds(wav(1.25))).toBeCloseTo(1.25, 3)
    expect(wavSeconds(Buffer.from('nope'))).toBeNull()
  })
})
