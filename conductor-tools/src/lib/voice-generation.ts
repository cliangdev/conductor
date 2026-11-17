/**
 * Runs the voice gate in this order: project configured, inputs valid, per-call cap, daily cap, cost
 * confirmation (only above a small threshold). Only then does it call Cloud Text-to-Speech with the user's
 * gcloud token. Spend goes in its own small ledger (`voice-spend.json`, same format as the video one) and is
 * recorded after a successful synthesis, or when the audio was made (billed) but could not be used.
 */
import * as os from 'node:os'
import * as path from 'node:path'
import { readConfig } from './config.js'
import { getVertexToken, resetVertexTokenCache } from './vertex-veo.js'
import { VideoError } from './video-common.js'
import { record, spentToday, type SpendOptions } from './video-spend.js'
import {
  DEFAULT_VOICE_CONFIRM_ABOVE_USD,
  DEFAULT_VOICE_DAILY_CAP_USD,
  DEFAULT_VOICE_PER_CALL_CAP_USD,
  TTS_API,
  VoiceCostCapExceeded,
  VoiceCostConfirmationRequired,
  VoiceDailyCapExceeded,
  VoiceError,
  VoiceNotConfigured,
  buildMarkedSsml,
  estimateVoiceCost,
  normalizeVoiceParams,
  ttsFailure,
  type Timepoint,
  type VoiceParams,
  type VoiceRequest,
} from './voice-common.js'

export const VOICE_SPEND_PATH = path.join(os.homedir(), '.conductor', 'voice-spend.json')
export const TTS_TIMEOUT_MS = 60_000
export const MAX_RETRIES = 2

export interface VoiceDeps {
  fetch?: typeof fetch
  sleep?: (ms: number) => Promise<void>
  getToken?: () => Promise<string>
}

export interface VoiceCaps {
  perCallUsd: number
  dailyUsd: number
  confirmAboveUsd: number
}

export function voiceCaps(): VoiceCaps {
  const cfg = readConfig()
  const ok = (n: unknown, d: number, min: number): number => (typeof n === 'number' && Number.isFinite(n) && n >= min ? n : d)
  return {
    perCallUsd: ok(cfg?.voicePerCallCapUsd, DEFAULT_VOICE_PER_CALL_CAP_USD, 0.0001),
    dailyUsd: ok(cfg?.voiceDailyCapUsd, DEFAULT_VOICE_DAILY_CAP_USD, 0.0001),
    confirmAboveUsd: ok(cfg?.voiceConfirmAboveUsd, DEFAULT_VOICE_CONFIRM_ABOVE_USD, 0),
  }
}

export interface SynthesisResult {
  audio: Buffer
  /** Mark timepoints, present only on the SSML route (non-Chirp voices). */
  timepoints?: Timepoint[]
}

interface TtsResponse {
  audioContent?: string
  timepoints?: Timepoint[]
}

/** Builds the request. Chirp 3: HD takes plain text (it ignores <mark>); other families go through v1beta1 with marks. */
export function buildTtsRequest(req: VoiceRequest): { url: string; body: Record<string, unknown>; marked: boolean } {
  const marked = req.family !== 'Chirp3-HD'
  const base = {
    voice: { languageCode: req.languageCode, name: req.voice },
    audioConfig: { audioEncoding: 'LINEAR16', speakingRate: req.speakingRate },
  }
  return marked
    ? { url: `https://${TTS_API}/v1beta1/text:synthesize`, body: { input: { ssml: buildMarkedSsml(req.words) }, ...base, enableTimePointing: ['SSML_MARK'] }, marked }
    : { url: `https://${TTS_API}/v1/text:synthesize`, body: { input: { text: req.text }, ...base }, marked }
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: { message?: string } }
    return body.error?.message ?? res.statusText ?? 'unknown error'
  } catch {
    return res.statusText || 'unknown error'
  }
}

async function tokenOf(deps: VoiceDeps): Promise<string> {
  try {
    return await (deps.getToken ?? getVertexToken)()
  } catch (err) {
    const why = err instanceof VideoError ? err.message : (err as Error).message
    throw new VoiceError('VOICE_AUTH', `Could not get a Google Cloud access token: ${why} Nothing was billed.`)
  }
}

/** One synthesis with token refresh on a 401 and a short retry on network errors and 5xx. Throws VoiceError. */
export async function synthesize(req: VoiceRequest, project: string, deps: VoiceDeps = {}): Promise<SynthesisResult> {
  const doFetch = deps.fetch ?? fetch
  const sleep = deps.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))
  const { url, body } = buildTtsRequest(req)
  let token = await tokenOf(deps)
  let refreshed = false
  for (let attempt = 0; ; attempt++) {
    let res: Response
    try {
      res = await doFetch(url, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'x-goog-user-project': project, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(TTS_TIMEOUT_MS),
      })
    } catch (err) {
      if (attempt < MAX_RETRIES) {
        await sleep(1000 * 2 ** attempt)
        continue
      }
      throw new VoiceError('VOICE_FAILED', `Could not reach Text-to-Speech: ${(err as Error).message}. Nothing was billed.`)
    }
    if (res.ok) {
      let json: TtsResponse
      try {
        json = (await res.json()) as TtsResponse
      } catch {
        throw billed(new VoiceError('VOICE_FAILED', 'Text-to-Speech answered but the response could not be read. It may have been billed.'))
      }
      if (!json.audioContent) throw billed(new VoiceError('VOICE_FAILED', 'Text-to-Speech answered without audio. It may have been billed.'))
      return { audio: Buffer.from(json.audioContent, 'base64'), ...(json.timepoints ? { timepoints: json.timepoints } : {}) }
    }
    const message = await errorMessage(res)
    if (res.status === 401 && !refreshed) {
      refreshed = true
      resetVertexTokenCache()
      token = await tokenOf(deps)
      continue
    }
    if (res.status >= 500 && attempt < MAX_RETRIES) {
      await sleep(1000 * 2 ** attempt)
      continue
    }
    if (res.status === 401 || res.status === 403) resetVertexTokenCache()
    throw ttsFailure(res.status, message, project, req.voice)
  }
}

/** Marks a failure that happened after Google made (and billed) the audio. */
export function billed<E extends VoiceError>(err: E): E {
  err.details['billed'] = true
  return err
}

export interface GenerateVoiceResult extends SynthesisResult {
  request: VoiceRequest
  marked: boolean
  estimateUsd: number
  characters: number
  breakdown: string
  spentTodayUsd: number
  dailyCapUsd: number
  /** True when the cost was above the confirm threshold and the person agreed. */
  confirmed: boolean
}

export async function generateVoice(params: VoiceParams, options: { spend?: SpendOptions; deps?: VoiceDeps } = {}): Promise<GenerateVoiceResult> {
  const cfg = readConfig()
  const project = cfg?.vertexProject?.trim()
  if (!project) throw new VoiceNotConfigured()
  const request = normalizeVoiceParams(params)
  const { marked } = buildTtsRequest(request)
  const characters = marked ? buildMarkedSsml(request.words).replace(/<mark name="w\d+"\/>/g, '').length : request.text.length
  const est = estimateVoiceCost(characters, request.family, cfg?.voiceRates)
  const caps = voiceCaps()
  const spend: SpendOptions = options.spend ?? { path: VOICE_SPEND_PATH }
  if (est.estimateUsd > caps.perCallUsd) throw new VoiceCostCapExceeded(est.estimateUsd, caps.perCallUsd)
  const before = spentToday(spend)
  if (before + est.estimateUsd > caps.dailyUsd) throw new VoiceDailyCapExceeded(est.estimateUsd, before, caps.dailyUsd)
  const needsConfirm = est.estimateUsd > caps.confirmAboveUsd
  if (needsConfirm && !request.confirmCost) {
    throw new VoiceCostConfirmationRequired({ estimateUsd: est.estimateUsd, breakdown: est.breakdown, spentTodayUsd: before, dailyCapUsd: caps.dailyUsd, voice: request.voice })
  }
  try {
    const out = await synthesize(request, project, options.deps)
    record(est.estimateUsd, spend)
    return { ...out, request, marked, estimateUsd: est.estimateUsd, characters, breakdown: est.breakdown, spentTodayUsd: spentToday(spend), dailyCapUsd: caps.dailyUsd, confirmed: needsConfirm }
  } catch (err) {
    if (err instanceof VoiceError && err.details['billed'] === true) record(est.estimateUsd, spend)
    throw err
  }
}
