// Heuristic linter for Mermaid code blocks embedded in Conductor markdown docs.
//
// It deliberately does NOT run the full Mermaid parser: Mermaid needs a browser
// DOM and pulls in a large dependency tree, which is the wrong thing to ship in
// a CLI. Instead this flags the specific, recurring grammar landmines that have
// actually broken rendering in practice — caught at authoring/lint time, before
// the content is ever stored. Same "fail loud early" philosophy as project
// resolution. It is a high-precision heuristic, not an exhaustive validator.

export interface MermaidLintIssue {
  level: 'error' | 'warning'
  /** 1-based line number within the source file. */
  line: number
  rule: string
  message: string
}

interface MermaidBlock {
  /** Lowercased first token of the diagram, e.g. 'sequencediagram', 'flowchart'. */
  diagramType: string
  lines: { text: string; line: number }[]
}

// A sequence message: `A->>B: label` / `A-->>B: label` (also -x, -) terminators).
const SEQUENCE_MESSAGE = /^\s*[A-Za-z0-9_]+\s*(?:-{1,2}>{1,2}|-{1,2}[x)])\s*[A-Za-z0-9_]+\s*:\s*(.*)$/
const PARTICIPANT_DECL = /^\s*(?:participant|actor)\s+(.+?)\s*$/

/** Split fenced ```mermaid blocks out of markdown, tracking file line numbers. */
export function extractMermaidBlocks(content: string): MermaidBlock[] {
  const fileLines = content.split(/\r?\n/)
  const blocks: MermaidBlock[] = []
  let i = 0
  while (i < fileLines.length) {
    if (fileLines[i].trim().toLowerCase().startsWith('```mermaid')) {
      const inner: { text: string; line: number }[] = []
      let j = i + 1
      while (j < fileLines.length && fileLines[j].trim() !== '```') {
        inner.push({ text: fileLines[j], line: j + 1 })
        j++
      }
      const firstNonEmpty = inner.find(l => l.text.trim() !== '')
      const diagramType = firstNonEmpty ? firstNonEmpty.text.trim().split(/\s+/)[0].toLowerCase() : ''
      blocks.push({ diagramType, lines: inner })
      i = j + 1
    } else {
      i++
    }
  }
  return blocks
}

/** Flag known Mermaid parse/render landmines in every ```mermaid block. */
export function lintMermaid(content: string): MermaidLintIssue[] {
  const issues: MermaidLintIssue[] = []
  for (const block of extractMermaidBlocks(content)) {
    for (const { text, line } of block.lines) {
      // Literal "\n" renders verbatim in every diagram type — authors mean <br/>.
      if (text.includes('\\n')) {
        issues.push({
          level: 'warning',
          line,
          rule: 'literal-newline',
          message: 'Mermaid renders literal "\\n"; use "<br/>" for line breaks.',
        })
      }

      if (block.diagramType !== 'sequencediagram') continue

      const participant = text.match(PARTICIPANT_DECL)
      if (participant) {
        const decl = participant[1]
        const asSplit = decl.split(/\s+as\s+/)
        if (asSplit.length > 1) {
          const alias = asSplit.slice(1).join(' as ')
          if (/[():]/.test(alias)) {
            issues.push({
              level: 'error',
              line,
              rule: 'participant-alias-chars',
              message: `Mermaid participant alias "${alias}" contains "(", ")" or ":" which break the parser — use plain text.`,
            })
          }
        } else if (/[\s():]/.test(decl)) {
          // No "as": the id must be a single bare token.
          issues.push({
            level: 'error',
            line,
            rule: 'participant-id-chars',
            message: `Mermaid participant "${decl}" has spaces or special characters — use 'participant ID as Label'.`,
          })
        }
        continue
      }

      const message = text.match(SEQUENCE_MESSAGE)
      if (message) {
        const label = message[1]
        if (label.includes(';')) {
          issues.push({
            level: 'error',
            line,
            rule: 'message-semicolon',
            message: '";" is a statement separator in Mermaid and splits this message — replace it with a comma.',
          })
        }
        if (label.includes('#')) {
          issues.push({
            level: 'warning',
            line,
            rule: 'message-hash',
            message: '"#" may be parsed as an HTML entity in Mermaid — consider removing (e.g. "Gate #1" → "Gate 1").',
          })
        }
      }
    }
  }
  return issues
}
