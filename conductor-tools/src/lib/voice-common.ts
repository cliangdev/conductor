/**
 * Pure pieces of `generate_voice_line`: limits, voice validation, the rate table and cost estimate, word
 * timing (provider marks or an honest estimate), caption chunking and the errors. No network or disk here.
 *
 * Provider: Google Cloud Text-to-Speech (texttospeech.googleapis.com), billed to the configured Google Cloud
 * project, default voice family Chirp 3: HD. Chirp 3: HD ignores SSML <mark> (per its docs page), so its word
 * times are estimated; the older families (Neural2, WaveNet, Standard) return real mark timepoints.
 */
export const MAX_VOICE_CHARS = 400
export const MAX_VOICE_WORDS = 60
export const DEFAULT_VOICE = 'en-US-Chirp3-HD-Aoede'
/** A short curated list for the tool description; any voice matching the pattern is accepted. */
export const CURATED_VOICES = ['Aoede', 'Kore', 'Puck', 'Charon', 'Leda', 'Fenrir'] as const
export const MIN_RATE = 0.25
export const MAX_RATE = 2
export const DEFAULT_VOICE_PER_CALL_CAP_USD = 0.25
export const DEFAULT_VOICE_DAILY_CAP_USD = 2
/** Estimates at or under this need no confirmation; the cost is reported in the result. */
export const DEFAULT_VOICE_CONFIRM_ABOVE_USD = 0.05

/** Caption chunks: at most this many words and this long on screen. */
export const CAPTION_MAX_WORDS = 4
export const CAPTION_MAX_SEC = 1.2
/** % of the 9:16 frame height: clear of the top 15% and the bottom 22% platform UI. */
export const CAPTION_Y = 62
export const CAPTION_STYLE = 'sub'

/** USD per 1 million characters (cloud.google.com/text-to-speech/pricing, read 2026-11-14). The free tier is ignored: estimates are high-side. */
export const DEFAULT_VOICE_RATES: Record<string, number> = { 'Chirp3-HD': 30, Neural2: 16, Wavenet: 4, Standard: 4 }

export type VoiceFamily = 'Chirp3-HD' | 'Neural2' | 'Wavenet' | 'Standard'

/** locale-family-voice, e.g. en-US-Chirp3-HD-Aoede or en-US-Neural2-F. */
const VOICE_PATTERN = /^([a-z]{2,3}-[A-Z]{2})-(Chirp3-HD|Neural2|Wavenet|Standard)-([A-Za-z]+)$/

export function parseVoice(voice: string): { languageCode: string; family: VoiceFamily; name: string } | null {
  const m = VOICE_PATTERN.exec(voice)
  return m ? { languageCode: m[1]!, family: m[2] as VoiceFamily, name: voice } : null
}

export interface VoiceParams {
  text: string
  voice?: string
  speakingRate?: number
  offsetSec?: number
  outDir?: string
  confirmCost?: boolean
}

export interface VoiceRequest {
  text: string
  words: string[]
  voice: string
  languageCode: string
  family: VoiceFamily
  speakingRate: number
  offsetSec: number
  confirmCost: boolean
}

export const wordsOf = (text: string): string[] => text.split(/\s+/).filter(Boolean)

/** Applies defaults and checks everything that does not touch the network. Throws a plain Error on the first problem. */
export function normalizeVoiceParams(p: VoiceParams): VoiceRequest {
  const text = typeof p.text === 'string' ? p.text.replace(/\s+/g, ' ').trim() : ''
  if (!text) throw new Error('text is required: one spoken line or a short narration.')
  const words = wordsOf(text)
  if (text.length > MAX_VOICE_CHARS || words.length > MAX_VOICE_WORDS) {
    throw new Error(`text is too long (${words.length} words, ${text.length} characters): the limit is ${MAX_VOICE_WORDS} words and ${MAX_VOICE_CHARS} characters. This tool is for a hook line or a short narration; a hook line is about 8 words per 3 s.`)
  }
  const voice = p.voice === undefined ? DEFAULT_VOICE : p.voice
  const parsed = typeof voice === 'string' ? parseVoice(voice) : null
  if (!parsed) throw new Error(`voice "${String(voice)}" is not a usable voice name. Use <locale>-<family>-<voice> with family Chirp3-HD, Neural2, Wavenet or Standard, e.g. ${DEFAULT_VOICE}.`)
  const speakingRate = p.speakingRate === undefined ? 1 : p.speakingRate
  if (typeof speakingRate !== 'number' || !Number.isFinite(speakingRate) || speakingRate < MIN_RATE || speakingRate > MAX_RATE) {
    throw new Error(`speakingRate must be a number from ${MIN_RATE} to ${MAX_RATE} (1 is normal pace).`)
  }
  const offsetSec = p.offsetSec === undefined ? 0 : p.offsetSec
  if (typeof offsetSec !== 'number' || !Number.isFinite(offsetSec) || offsetSec < 0 || offsetSec > 60) throw new Error('offsetSec must be a number from 0 to 60.')
  if (p.confirmCost !== undefined && typeof p.confirmCost !== 'boolean') throw new Error('confirmCost must be true or false.')
  return { text, words, voice, languageCode: parsed.languageCode, family: parsed.family, speakingRate, offsetSec, confirmCost: p.confirmCost === true }
}

const money = (n: number): string => `$${Number(n.toFixed(4))}`
const round4 = (n: number): number => Math.round(n * 10000) / 10000

export interface VoiceCostEstimate {
  estimateUsd: number
  characters: number
  usdPerMillionChars: number
  breakdown: string
}

export function estimateVoiceCost(characters: number, family: VoiceFamily, overrides?: Record<string, number>): VoiceCostEstimate {
  const o = overrides?.[family]
  const usdPerMillionChars = typeof o === 'number' && Number.isFinite(o) && o >= 0 ? o : DEFAULT_VOICE_RATES[family]!
  const estimateUsd = round4((characters * usdPerMillionChars) / 1_000_000)
  return { estimateUsd, characters, usdPerMillionChars, breakdown: `${characters} characters x $${usdPerMillionChars}/1M (${family}) = ${money(estimateUsd)}, an estimate that ignores the free monthly allowance` }
}

export interface Word {
  w: string
  fromSec: number
  toSec: number
}

const r3 = (n: number): number => Math.round(n * 1000) / 1000

/**
 * No provider timing: spread the measured duration over the words by character count, with a pause
 * weight after commas and sentence ends (a spoken line breathes there). Times are approximate.
 */
export function estimateWordTimes(words: readonly string[], durationSec: number): Word[] {
  const weight = (w: string): number => {
    const letters = w.replace(/[^\p{L}\p{N}]/gu, '').length
    const pause = /[.!?…]["')\]]*$/.test(w) ? 4 : /[,;:—–-]["')\]]*$/.test(w) ? 2 : 0
    return Math.max(1, letters + pause)
  }
  const total = words.reduce((s, w) => s + weight(w), 0)
  let at = 0
  return words.map((w) => {
    const span = (weight(w) / total) * durationSec
    const out = { w, fromSec: r3(at), toSec: r3(at + span) }
    at += span
    return out
  })
}

export interface Timepoint {
  markName?: string
  timeSeconds?: number
}

/** SSML for the mark route: one <mark name="w{i}"/> before every word. Characters are escaped. */
export function buildMarkedSsml(words: readonly string[]): string {
  const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return `<speak>${words.map((w, i) => `<mark name="w${i}"/>${esc(w)}`).join(' ')}</speak>`
}

/** Word times from mark timepoints; null when a mark is missing (the caller then estimates). A word ends where the next begins, the last at the audio end. */
export function wordsFromTimepoints(words: readonly string[], points: readonly Timepoint[] | undefined, durationSec: number): Word[] | null {
  if (!points || points.length < words.length) return null
  const at = new Map<number, number>()
  for (const p of points) {
    const m = /^w(\d+)$/.exec(p.markName ?? '')
    if (m && typeof p.timeSeconds === 'number' && Number.isFinite(p.timeSeconds)) at.set(Number(m[1]), p.timeSeconds)
  }
  if (words.some((_, i) => !at.has(i))) return null
  return words.map((w, i) => ({ w, fromSec: r3(at.get(i)!), toSec: r3(i + 1 < words.length ? at.get(i + 1)! : Math.max(durationSec, at.get(i)!)) }))
}

export interface CaptionBeat {
  fromSec: number
  toSec: number
  t: string
  y: number
  style: string
}

/**
 * Chunks of at most 4 words and about 1.2 s, broken after a clause end; offset by `offsetSec`. The first
 * beat starts at the offset (a caption just ahead of the voice) and lasts past 1 s so compose_video's
 * first-second text check passes; a beat never starts before the previous one ends, so none overlap.
 */
export function captionBeats(words: readonly Word[], offsetSec = 0): CaptionBeat[] {
  const chunks: Word[][] = []
  let cur: Word[] = []
  for (const w of words) {
    if (cur.length && (cur.length >= CAPTION_MAX_WORDS || w.toSec - cur[0]!.fromSec > CAPTION_MAX_SEC)) {
      chunks.push(cur)
      cur = []
    }
    cur.push(w)
    if (/[.!?,;:…]["')\]]*$/.test(w.w)) {
      chunks.push(cur)
      cur = []
    }
  }
  if (cur.length) chunks.push(cur)
  const beats: CaptionBeat[] = []
  for (const [i, c] of chunks.entries()) {
    const next = chunks[i + 1]
    const last = c[c.length - 1]!
    const prevTo = beats[i - 1]?.toSec ?? 0
    const from = i === 0 ? offsetSec : Math.max(r3(offsetSec + c[0]!.fromSec), prevTo)
    let to = r3(offsetSec + (next ? Math.min(next[0]!.fromSec, last.toSec + 0.25) : last.toSec + 0.25))
    if (i === 0) to = Math.max(to, r3(offsetSec + 1.1))
    beats.push({ fromSec: r3(from), toSec: Math.max(to, r3(from + 0.3)), t: c.map((w) => w.w).join(' '), y: CAPTION_Y, style: CAPTION_STYLE })
  }
  return beats
}

/** Typed failure with a stable `code` and extra fields the MCP layer returns as a structured error. */
export class VoiceError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> = {}
  ) {
    super(message)
    this.name = 'VoiceError'
  }
}

export const TTS_API = 'texttospeech.googleapis.com'
export const SETUP_VOICE = '`conductor config set-vertex-project <id>` and `gcloud auth login`'

export class VoiceNotConfigured extends VoiceError {
  constructor() {
    super('VOICE_NOT_CONFIGURED', `No Google Cloud project is set for billing. Run ${SETUP_VOICE}. Nothing was generated or billed.`)
  }
}

export class VoiceCostCapExceeded extends VoiceError {
  constructor(estimateUsd: number, capUsd: number) {
    super('VOICE_COST_CAP_EXCEEDED', `Estimated ${money(estimateUsd)} is over the per-call cap of ${money(capUsd)}. Shorten the text or raise voicePerCallCapUsd in ~/.conductor/config.json.`, { estimateUsd, perCallCapUsd: capUsd })
  }
}

export class VoiceDailyCapExceeded extends VoiceError {
  constructor(estimateUsd: number, spentTodayUsd: number, capUsd: number) {
    super('VOICE_DAILY_CAP_EXCEEDED', `Estimated ${money(estimateUsd)} plus ${money(spentTodayUsd)} already spent today passes the daily cap of ${money(capUsd)}. Wait until tomorrow or raise voiceDailyCapUsd in ~/.conductor/config.json.`, { estimateUsd, spentTodayUsd, dailyCapUsd: capUsd })
  }
}

export class VoiceCostConfirmationRequired extends VoiceError {
  constructor(info: { estimateUsd: number; breakdown: string; spentTodayUsd: number; dailyCapUsd: number; voice: string }) {
    super('VOICE_COST_CONFIRMATION_REQUIRED', `This will cost about ${money(info.estimateUsd)} (${info.breakdown}). Tell the person, and call again with confirmCost: true only after they agree.`, info)
  }
}

/** Maps a Text-to-Speech HTTP failure to a plain, actionable error. Never includes request headers. */
export function ttsFailure(status: number, apiMessage: string, project: string, voice: string): VoiceError {
  const enable = `gcloud services enable ${TTS_API} --project ${project}`
  if (/SERVICE_DISABLED|has not been used in project|is disabled|API has not been enabled/i.test(apiMessage)) {
    return new VoiceError('VOICE_API_DISABLED', `The Cloud Text-to-Speech API (${TTS_API}) is not enabled on project ${project}. Enable it with \`${enable}\` (or in the Cloud console under APIs & Services), wait a minute, then call again. Nothing was billed.`, { api: TTS_API, enableCommand: enable })
  }
  if (status === 401 || status === 403) {
    return new VoiceError('VOICE_AUTH', `Google rejected the credentials (${status}). Run \`gcloud auth login\` and check that the account can use project ${project} (role roles/serviceusage.serviceUsageConsumer or Editor) and that billing is on. It says: ${apiMessage}`, { status })
  }
  if (status === 429) return new VoiceError('VOICE_QUOTA', `Text-to-Speech is out of quota or rate limited (429). Wait a few minutes or raise the quota. It says: ${apiMessage}`, { status })
  if (status === 400 && /voice|language/i.test(apiMessage)) {
    return new VoiceError('VOICE_NOT_FOUND', `Text-to-Speech does not accept the voice ${voice}. Try ${DEFAULT_VOICE} or another from the Cloud TTS voice list. It says: ${apiMessage}`, { status })
  }
  return new VoiceError('VOICE_FAILED', `Text-to-Speech failed (${status}): ${apiMessage}`, { status })
}
