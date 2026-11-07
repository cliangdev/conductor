import { Config } from '../config.js'
import { apiGet, apiPost, apiPut } from '../api.js'

/**
 * AI-assistant visibility (`/marketing/ai-visibility`): a weekly check of whether AI assistants name the brand for a
 * fixed set of questions. Outputs are compacted per `docs/mcp-tool-guidelines.md`. `named: false` is kept as an
 * explicit false (an assistant answered and did not name the brand); an assistant with no credential, an error or an
 * unchecked week never appears as a reading, so it can never be mistaken for "not named".
 */

export const MAX_EXCERPT = 240

const MAX_WEEKS = 52
const MAX_QUESTIONS = 20
const MAX_QUESTION_LENGTH = 400
const MAX_BRAND_TERMS = 10
const MAX_TERM_LENGTH = 80

type Json = unknown
type Obj = Record<string, Json>

interface Reading {
  weekStart?: string
  named?: boolean
  rankInAnswer?: number | null
  excerpt?: string | null
  model?: string | null
}

interface AssistantPayload {
  assistant: string
  latest?: Reading
  trend?: Array<{ weekStart: string; named: boolean }>
}

interface QuestionPayload {
  id: string
  question: string
  active?: boolean
  assistants?: AssistantPayload[]
}

interface VisibilityPayload {
  brandTerms?: string[]
  questions?: QuestionPayload[]
  skipped?: Array<{ assistant: string; reason: string }>
}

function base(config: Config): string {
  return `/api/v2/projects/${config.projectId}/marketing/ai-visibility`
}

function shorten(text: string | null | undefined): string | undefined {
  if (typeof text !== 'string' || text === '') return undefined
  const flat = text.replace(/\s+/g, ' ').trim()
  return flat.length > MAX_EXCERPT ? `${flat.slice(0, MAX_EXCERPT)}...` : flat
}

function compactAssistant(a: AssistantPayload): Obj {
  const latest = a.latest ?? {}
  const out: Obj = { assistant: a.assistant }
  if (latest.weekStart) out['week'] = latest.weekStart
  if (typeof latest.named === 'boolean') out['named'] = latest.named
  if (typeof latest.rankInAnswer === 'number') out['rank'] = latest.rankInAnswer
  const excerpt = shorten(latest.excerpt)
  if (excerpt) out['excerpt'] = excerpt
  if (latest.model) out['model'] = latest.model
  out['trend'] = (a.trend ?? []).map((t) => `${t.weekStart}:${t.named ? 'named' : 'not-named'}`)
  return out
}

function compactQuestion(q: QuestionPayload): Obj {
  const out: Obj = { id: q.id, question: q.question }
  if (q.active === false) out['active'] = false
  out['assistants'] = (q.assistants ?? []).map(compactAssistant)
  return out
}

export async function getAiVisibility(config: Config, p: { weeks?: number }): Promise<Record<string, unknown>> {
  let path = base(config)
  if (p.weeks !== undefined) {
    if (!Number.isInteger(p.weeks) || p.weeks < 1 || p.weeks > MAX_WEEKS) {
      throw new Error(`weeks must be a whole number from 1 to ${MAX_WEEKS}`)
    }
    path = `${path}?weeks=${p.weeks}`
  }
  const v = await apiGet<VisibilityPayload>(path, config)
  const out: Record<string, unknown> = {
    brandTerms: v.brandTerms ?? [],
    questions: (v.questions ?? []).map(compactQuestion),
  }
  if (v.skipped && v.skipped.length > 0) out['skipped'] = v.skipped
  return out
}

export interface SetQuestionsParams {
  questions: string[]
  brandTerms?: string[]
}

function checkQuestions(questions: unknown): string[] {
  if (!Array.isArray(questions)) throw new Error('questions must be a list of strings (an empty list switches every question off)')
  const cleaned = questions.map((q) => (typeof q === 'string' ? q.trim() : ''))
  if (cleaned.some((q) => q === '')) throw new Error('A question cannot be blank')
  if (cleaned.some((q) => q.length > MAX_QUESTION_LENGTH)) {
    throw new Error(`A question is at most ${MAX_QUESTION_LENGTH} characters`)
  }
  if (cleaned.length > MAX_QUESTIONS) throw new Error(`At most ${MAX_QUESTIONS} questions are checked`)
  return cleaned
}

function checkBrandTerms(terms: unknown): string[] {
  if (!Array.isArray(terms) || terms.some((t) => typeof t !== 'string')) {
    throw new Error('brandTerms must be a list of strings (an empty list goes back to the workspace name)')
  }
  const cleaned = (terms as string[]).map((t) => t.trim()).filter((t) => t !== '')
  if (cleaned.some((t) => t.length > MAX_TERM_LENGTH)) throw new Error(`A brand term is at most ${MAX_TERM_LENGTH} characters`)
  if (cleaned.length > MAX_BRAND_TERMS) throw new Error(`At most ${MAX_BRAND_TERMS} brand terms`)
  return cleaned
}

export async function setAiQuestions(config: Config, p: SetQuestionsParams): Promise<Record<string, unknown>> {
  // Check both inputs before the first call so a bad brandTerms cannot leave the questions half applied.
  const questions = checkQuestions(p.questions)
  const brandTerms = p.brandTerms === undefined ? undefined : checkBrandTerms(p.brandTerms)

  const saved = await apiPut<{ questions?: Array<{ question: string; active: boolean }> }>(
    `${base(config)}/questions`,
    { questions },
    config
  )
  const all = saved.questions ?? []
  const out: Record<string, unknown> = { questions: all.filter((q) => q.active).map((q) => q.question) }
  const inactive = all.filter((q) => !q.active).map((q) => q.question)
  if (inactive.length > 0) out['inactive'] = inactive

  if (brandTerms !== undefined) {
    const terms = await apiPut<{ brandTerms?: string[] }>(`${base(config)}/brand-terms`, { brandTerms }, config)
    out['brandTerms'] = terms.brandTerms ?? []
  }
  return out
}

interface RunPayload {
  weekStart: string
  recorded: number
  alreadyRecorded: number
  skipped?: Array<{ assistant: string; reason: string }>
  errors?: Array<{ assistant: string; question?: string | null; reason: string }>
}

export async function runAiVisibilityCheck(config: Config): Promise<Record<string, unknown>> {
  const r = await apiPost<RunPayload>(`${base(config)}/run`, {}, config)
  const out: Record<string, unknown> = {
    week: r.weekStart,
    recorded: r.recorded,
    alreadyRecorded: r.alreadyRecorded,
  }
  if (r.skipped && r.skipped.length > 0) out['skipped'] = r.skipped
  if (r.errors && r.errors.length > 0) {
    out['errors'] = r.errors.map((e) => {
      const item: Obj = { assistant: e.assistant }
      if (e.question) item['question'] = e.question
      item['reason'] = e.reason
      return item
    })
  }
  return out
}
