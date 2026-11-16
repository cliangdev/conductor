import type { ReactNode } from 'react'
import type { Comment, CommentMention } from './types'

/** A mention token in a body: `@[Name](user:<id>)` or `@[Name](agent:<id>)`. */
const TOKEN_SOURCE = String.raw`@\[([^\]]*)\]\((user|agent):([^)\s]+)\)`

export interface MentionToken {
  name: string
  kind: 'user' | 'agent'
  id: string
  /** The token as it is stored in a body. */
  token: string
}

/** Names the textarea shows (`@Ada`) mapped to the tokens they stand for. */
export type MentionMap = Record<string, MentionToken>

function tokenRe() {
  return new RegExp(TOKEN_SOURCE, 'g')
}

export function makeToken(name: string, kind: 'user' | 'agent', id: string): MentionToken {
  const clean = name.replace(/[\][()]/g, '').trim() || 'Unknown'
  return { name: clean, kind, id, token: `@[${clean}](${kind}:${id})` }
}

/** True when the body asks an agent to act (it carries an `agent:` token). */
export function hasAgentMention(content: string): boolean {
  return /@\[[^\]]*\]\(agent:[^)\s]+\)/.test(content)
}

/** Turns stored tokens into readable `@Name` text plus the map needed to turn it back. */
export function tokensToDisplay(content: string): { text: string; map: MentionMap } {
  const map: MentionMap = {}
  const text = content.replace(tokenRe(), (_m, name: string, kind: 'user' | 'agent', id: string) => {
    const t = makeToken(name, kind, id)
    map[`@${t.name}`] = t
    return `@${t.name}`
  })
  return { text, map }
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** Turns readable `@Name` text back into tokens, for every name in the map that is still in the text. */
export function displayToTokens(text: string, map: MentionMap): string {
  const keys = Object.keys(map).sort((a, b) => b.length - a.length)
  if (keys.length === 0) return text
  const re = new RegExp(`(?<![\\w@])(${keys.map(escapeRe).join('|')})(?![\\w])`, 'g')
  return text.replace(re, (m) => map[m]?.token ?? m)
}

/** The text with each mention token replaced by its name, for places that show a line of plain text. */
export function plainCommentText(content: string): string {
  return content.replace(tokenRe(), (_m, name: string) => `@${name}`)
}

/** A body as nodes: plain text (the caller sets `whitespace-pre-wrap` to keep line breaks) with each mention as a chip. */
export function renderCommentBody(content: string, mentions: CommentMention[] = []): ReactNode[] {
  const out: ReactNode[] = []
  const re = tokenRe()
  let last = 0
  let i = 0
  let m: RegExpExecArray | null = re.exec(content)
  while (m) {
    if (m.index > last) out.push(content.slice(last, m.index))
    const kind = m[2] === 'agent' ? 'AGENT' : 'USER'
    const id = m[3]
    const known = mentions.find((x) => x.id === id && x.kind === kind)
    out.push(
      <span
        key={`m${i++}`}
        data-mention={kind.toLowerCase()}
        className="rounded bg-accent-soft px-1 font-medium text-primary"
      >
        @{known?.name ?? m[1]}
      </span>
    )
    last = m.index + m[0].length
    m = re.exec(content)
  }
  if (last < content.length) out.push(content.slice(last))
  return out
}

/** The "Mine" filter: threads I wrote, or where I am mentioned in the thread or any reply. */
export function involvesUser(c: Comment, userId: string): boolean {
  if (!userId) return false
  const named = (x: { mentions?: CommentMention[]; content: string }) =>
    !!x.mentions?.some((mm) => mm.kind === 'USER' && mm.id === userId) || x.content.includes(`(user:${userId})`)
  if (c.authorKind !== 'AGENT' && c.authorId === userId) return true
  return named(c) || c.replies.some(named)
}
