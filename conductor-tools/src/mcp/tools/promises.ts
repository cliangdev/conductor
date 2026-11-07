import { Config } from '../config.js'
import { apiGet, apiPut, apiDelete } from '../api.js'

/**
 * The promise ledger (`/marketing/promises`) and the message tags behind it (`/marketing/message-tags`). A tag says
 * what a target promises (a keyword, a custom product page, a Post, a creative, a paywall headline); the ledger joins
 * each message to installs, intent picks, trial rate and day-7 retention. Compact per `docs/mcp-tool-guidelines.md`:
 * a figure with no data stays an explicit null (never 0), and a rate reads with its 95% range and n, flagged as a
 * small sample under 20.
 */

export const TARGET_KINDS = ['CREATIVE', 'PRODUCT_PAGE', 'KEYWORD', 'PAYWALL_HEADLINE', 'POST'] as const
export const MAX_MESSAGE = 120
export const MAX_REF = 200
export const MAX_LABEL = 200

const DATE = /^\d{4}-\d{2}-\d{2}$/

type Obj = Record<string, unknown>

interface ApiTag {
  id: string
  message: string
  targetKind: string
  targetRef: string
  label?: string | null
}

export interface ApiRate {
  value: number
  n: number
  low: number
  high: number
  smallN: boolean
}

interface ApiPromise {
  message: string
  targets?: Record<string, number>
  installs?: number | null
  installsNote?: string | null
  intentMix?: { pick: string; count: number; share: number }[] | null
  intentN?: number | null
  trialRate?: ApiRate | null
  d7?: ApiRate | null
  smallN?: boolean
  outcomesNote?: string | null
}

interface ApiLedger {
  from: string
  to: string
  promises?: ApiPromise[]
}

const pct = (fraction: number): string => `${Math.round(fraction * 100)}%`

/** "12% (4–31%, n=17, small sample)"; null when there is no rate (never "0%"). */
export function formatRate(rate: ApiRate | null | undefined): string | null {
  if (!rate) return null
  const flag = rate.smallN ? ', small sample' : ''
  return `${pct(rate.value)} (${Math.round(rate.low * 100)}–${pct(rate.high)}, n=${rate.n}${flag})`
}

function checkKind(kind: string | undefined): string {
  const normalised = (kind ?? '').trim().toUpperCase()
  if (!(TARGET_KINDS as readonly string[]).includes(normalised)) {
    throw new Error(`targetKind must be one of ${TARGET_KINDS.join(', ')}`)
  }
  return normalised
}

function checkRef(ref: string | undefined): string {
  const trimmed = typeof ref === 'string' ? ref.trim() : ''
  if (!trimmed || trimmed.length > MAX_REF) throw new Error(`targetRef is required and at most ${MAX_REF} characters`)
  return trimmed
}

function checkDate(name: string, value: string | undefined): void {
  if (value !== undefined && !DATE.test(value)) throw new Error(`${name} must be YYYY-MM-DD`)
}

// ---- tag_message -------------------------------------------------------------------------------------

export interface TagMessageParams {
  message: string
  targetKind: string
  targetRef: string
  label?: string
}

export async function tagMessage(config: Config, p: TagMessageParams): Promise<Record<string, unknown>> {
  const message = typeof p.message === 'string' ? p.message.trim() : ''
  if (!message || message.length > MAX_MESSAGE) throw new Error(`message is required and at most ${MAX_MESSAGE} characters`)
  const targetKind = checkKind(p.targetKind)
  const targetRef = checkRef(p.targetRef)
  if (p.label !== undefined && p.label.length > MAX_LABEL) throw new Error(`label must be at most ${MAX_LABEL} characters`)

  const saved = await apiPut<ApiTag>(
    `/api/v2/projects/${config.projectId}/marketing/message-tags`,
    { message: p.message, targetKind, targetRef, label: p.label },
    config
  )
  return {
    message: saved.message,
    targetKind: saved.targetKind,
    targetRef: saved.targetRef,
    ...(saved.label ? { label: saved.label } : {}),
    nextStep: 'list_promises shows what this message brings (installs, intent mix, trial rate, D7).',
  }
}

// ---- untag_message -----------------------------------------------------------------------------------

export interface UntagMessageParams {
  targetKind: string
  targetRef: string
}

export async function untagMessage(config: Config, p: UntagMessageParams): Promise<Record<string, unknown>> {
  const targetKind = checkKind(p.targetKind)
  const targetRef = checkRef(p.targetRef)
  await apiDelete(
    `/api/v2/projects/${config.projectId}/marketing/message-tags?targetKind=${targetKind}&targetRef=${encodeURIComponent(targetRef)}`,
    config
  )
  return {
    targetKind,
    targetRef,
    note: 'No message is tagged on this target now (it also holds if it had none).',
    nextStep: 'list_promises shows the messages that remain.',
  }
}

// ---- list_promises -----------------------------------------------------------------------------------

export interface ListPromisesParams {
  from?: string
  to?: string
}

function compactPromise(p: ApiPromise): Obj {
  const mix = p.intentMix && p.intentMix.length > 0 ? p.intentMix.map((s) => `${s.pick} ${pct(s.share)}`) : null
  return {
    message: p.message,
    targets: p.targets ?? {},
    // null = no tagged target has a row in the window; a reported 0 stays 0
    installs: p.installs ?? null,
    ...(p.installsNote ? { installsNote: p.installsNote } : {}),
    intentMix: mix,
    ...(mix ? { intentN: p.intentN ?? null } : {}),
    trialRate: formatRate(p.trialRate),
    d7: formatRate(p.d7),
    smallSample: p.smallN === true,
    ...(p.outcomesNote ? { outcomesNote: p.outcomesNote } : {}),
  }
}

export async function listPromises(config: Config, p: ListPromisesParams): Promise<Record<string, unknown>> {
  checkDate('from', p.from)
  checkDate('to', p.to)
  const query = [p.from ? `from=${p.from}` : '', p.to ? `to=${p.to}` : ''].filter(Boolean).join('&')
  const ledger = await apiGet<ApiLedger>(
    `/api/v2/projects/${config.projectId}/marketing/promises${query ? `?${query}` : ''}`,
    config
  )
  const promises = (ledger.promises ?? []).map(compactPromise)
  return {
    from: ledger.from,
    to: ledger.to,
    promises,
    ...(promises.length === 0
      ? { note: 'No message is tagged yet: tag_message links a keyword, product page, Post, creative or paywall headline to one.' }
      : {}),
  }
}
