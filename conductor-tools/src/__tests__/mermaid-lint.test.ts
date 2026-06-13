import { describe, it, expect } from 'vitest'
import { lintMermaid, extractMermaidBlocks } from '../lib/mermaid-lint.js'

function block(...lines: string[]): string {
  return ['# Doc', '', '```mermaid', ...lines, '```', ''].join('\n')
}

describe('extractMermaidBlocks', () => {
  it('ignores non-mermaid code fences', () => {
    const md = '```ts\nconst x = 1;\n```\n'
    expect(extractMermaidBlocks(md)).toHaveLength(0)
  })

  it('detects diagram type from the first non-empty line', () => {
    const blocks = extractMermaidBlocks(block('', 'sequenceDiagram', 'A->>B: hi'))
    expect(blocks).toHaveLength(1)
    expect(blocks[0].diagramType).toBe('sequencediagram')
  })

  it('reports file line numbers, not block-relative ones', () => {
    // '```mermaid' is line 3; 'sequenceDiagram' is line 4; the message is line 5.
    const blocks = extractMermaidBlocks(block('sequenceDiagram', 'A->>B: x'))
    expect(blocks[0].lines[1].line).toBe(5)
  })
})

describe('lintMermaid — sequence diagram landmines', () => {
  it('flags a semicolon in a message label as an error (statement separator)', () => {
    const issues = lintMermaid(block('sequenceDiagram', 'Web->>API: attach links; Gate 2 approve'))
    expect(issues).toContainEqual(
      expect.objectContaining({ level: 'error', rule: 'message-semicolon' })
    )
  })

  it('warns on a bare # in a message label (HTML entity escape)', () => {
    const issues = lintMermaid(block('sequenceDiagram', 'Web->>API: Gate #1 approve 3-4'))
    expect(issues).toContainEqual(
      expect.objectContaining({ level: 'warning', rule: 'message-hash' })
    )
  })

  it('flags parentheses in a participant alias as an error', () => {
    const issues = lintMermaid(block('sequenceDiagram', 'participant Web as Web app (Founder)'))
    expect(issues).toContainEqual(
      expect.objectContaining({ level: 'error', rule: 'participant-alias-chars' })
    )
  })

  it('flags a colon in a participant alias as an error', () => {
    const issues = lintMermaid(block('sequenceDiagram', 'participant Skill as conductor:video-batch'))
    expect(issues).toContainEqual(
      expect.objectContaining({ level: 'error', rule: 'participant-alias-chars' })
    )
  })

  it('passes a clean, ASCII-safe sequence diagram with no issues', () => {
    const issues = lintMermaid(
      block(
        'sequenceDiagram',
        '    participant Web as Founder',
        '    participant API as API',
        '    Web->>API: Gate 1, approve 3-4 scripts',
        '    API-->>Web: Measured, winning hooks feed next batch'
      )
    )
    expect(issues).toHaveLength(0)
  })
})

describe('lintMermaid — cross-type rules', () => {
  it('warns on a literal \\n inside a flowchart node label', () => {
    const issues = lintMermaid(block('flowchart TD', '    A["Service\\nstatus"] --> B'))
    expect(issues).toContainEqual(
      expect.objectContaining({ level: 'warning', rule: 'literal-newline' })
    )
  })

  it('accepts <br/> line breaks in a flowchart node label', () => {
    const issues = lintMermaid(block('flowchart TD', '    A["Service<br/>status"] --> B'))
    expect(issues).toHaveLength(0)
  })

  it('does not apply sequence rules to a flowchart (semicolons are valid there)', () => {
    const issues = lintMermaid(block('flowchart TD', '    A-->B; B-->C'))
    expect(issues).toHaveLength(0)
  })
})
