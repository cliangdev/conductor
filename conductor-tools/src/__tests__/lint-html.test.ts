import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import * as fs from 'fs'
import { Command } from 'commander'

const mockReadConfig = vi.fn()

vi.mock('../lib/config.js', () => ({
  readConfig: mockReadConfig,
  CONFIG_PATH: '/mock/home/.conductor/config.json',
}))

vi.mock('fs')

const mockFs = vi.mocked(fs)

const ISSUE_ID = 'abc-123'
const LOCAL_PATH = '/mock/repo'
const ISSUE_DIR = `${LOCAL_PATH}/.conductor/issues/${ISSUE_ID}`
const HTML_PATH = `${ISSUE_DIR}/prd.html`
const MD_PATH = `${ISSUE_DIR}/prd.md`
const TASKS_PATH = `${ISSUE_DIR}/tasks.json`

const SECTION_ORDER = [
  'problem',
  'glossary',
  'goals',
  'non-goals',
  'users',
  'reference-patterns',
  'features',
]

const FEATURE_P0 = `<article id="P0-1" data-feature="P0-1">
  <h3>Do the thing</h3>
  <dl>
    <dt>What</dt><dd>Build it.</dd>
    <dt>Not</dt><dd><em>None.</em></dd>
    <dt>Inputs</dt><dd>Stuff in.</dd>
    <dt>Outputs</dt><dd>Stuff out.</dd>
    <dt>Invariants</dt><dd>Always true.</dd>
  </dl>
  <ul data-acs>
    <li data-ac="AC-P0-1.1">First criterion</li>
    <li data-ac="AC-P0-1.2">Second criterion</li>
  </ul>
</article>`

const FEATURE_P1 = `<article id="P1-1" data-feature="P1-1">
  <h3>Nice to have</h3>
  <dl><dt>What</dt><dd>Extra.</dd></dl>
  <ul data-acs><li data-ac="AC-P1-1.1">Extra works</li></ul>
</article>`

const BODIES: Record<string, string> = {
  problem: '<p>Something hurts.</p>',
  glossary: '<dl><dt>Foo</dt><dd>bar</dd></dl>',
  goals: '<ul><li>Goal one</li></ul>',
  'non-goals': '<ul><li>Out of scope</li></ul>',
  users: '<p>Some users.</p>',
  'reference-patterns': '<p><em>None.</em></p>',
  features: `${FEATURE_P0}\n${FEATURE_P1}`,
}

function section(name: string, body: string): string {
  return `<section id="${name}" data-prd-section="${name}"><h2>${name}</h2>${body}</section>`
}

function prd(
  opts: {
    order?: string[]
    bodies?: Record<string, string>
    meta?: string
    extraHead?: string
    drop?: string
  } = {}
): string {
  const meta =
    opts.meta ??
    `<meta name="conductor:doc-type" content="prd">
<meta name="conductor:schema-version" content="2">
<meta name="conductor:issue-id" content="abc-123">`
  const order = (opts.order ?? SECTION_ORDER).filter(n => n !== opts.drop)
  const sections = order.map(n => section(n, opts.bodies?.[n] ?? BODIES[n])).join('\n')
  return `<!doctype html><html><head><meta charset="utf-8">${meta}<title>T</title><style>:root{--a:1}</style>${opts.extraHead ?? ''}</head><body>${sections}</body></html>`
}

async function lib() {
  return await import('../commands/lint.js')
}

describe('validatePrdHtml', () => {
  beforeEach(() => vi.resetModules())

  it('accepts a valid PRD and returns its AC ids', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(prd())
    expect(r.errors).toEqual([])
    expect(r.isV2).toBe(true)
    expect(r.acIds).toEqual(['AC-P0-1.1', 'AC-P0-1.2', 'AC-P1-1.1'])
  })

  it('accepts the literal _None._ placeholder in glossary', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(prd({ bodies: { glossary: '<p>_None._</p>' } }))
    expect(r.errors).toEqual([])
  })

  it('errors on a missing section', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(prd({ drop: 'users' }))
    expect(r.errors).toContain('prd.html: missing required section: Users')
  })

  it('errors on sections out of order', async () => {
    const { validatePrdHtml } = await lib()
    const order = [...SECTION_ORDER]
    ;[order[2], order[3]] = [order[3], order[2]]
    const r = validatePrdHtml(prd({ order }))
    expect(r.errors.some(e => e.startsWith('prd.html: sections out of order'))).toBe(true)
  })

  it('errors on an empty glossary', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(prd({ bodies: { glossary: '' } }))
    expect(r.errors.some(e => e.includes('section Glossary: empty'))).toBe(true)
  })

  it('errors when a section has no h2', async () => {
    const { validatePrdHtml } = await lib()
    const html = prd().replace('<h2>goals</h2>', '')
    expect(validatePrdHtml(html).errors).toContain('prd.html: section Goals: missing <h2> heading')
  })

  it('errors on a missing P0 field', async () => {
    const { validatePrdHtml } = await lib()
    const noInvariants = FEATURE_P0.replace('<dt>Invariants</dt><dd>Always true.</dd>', '')
    const r = validatePrdHtml(prd({ bodies: { features: noInvariants } }))
    expect(r.errors).toContain('prd.html: feature P0-1: missing required field: Invariants')
  })

  it('does not require the P0 fields on a P1 feature', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(prd({ bodies: { features: `${FEATURE_P0}\n${FEATURE_P1}` } }))
    expect(r.errors).toEqual([])
  })

  it('errors when a P0 feature has no acceptance criteria list', async () => {
    const { validatePrdHtml } = await lib()
    const noAcs = FEATURE_P0.replace(/<ul data-acs>[\s\S]*<\/ul>/, '')
    const r = validatePrdHtml(prd({ bodies: { features: noAcs } }))
    expect(r.errors).toContain('prd.html: feature P0-1: missing required field: Acceptance Criteria')
  })

  it('errors on a malformed AC id', async () => {
    const { validatePrdHtml } = await lib()
    const bad = FEATURE_P0.replace('AC-P0-1.2', 'AC-P0-one')
    const r = validatePrdHtml(prd({ bodies: { features: bad } }))
    expect(r.errors).toContain('prd.html: feature P0-1: malformed acceptance criterion id: AC-P0-one')
  })

  it('errors on an li in the AC list with no data-ac', async () => {
    const { validatePrdHtml } = await lib()
    const bad = FEATURE_P0.replace('<li data-ac="AC-P0-1.2">', '<li>')
    const r = validatePrdHtml(prd({ bodies: { features: bad } }))
    expect(r.errors.some(e => e.includes('acceptance criterion missing data-ac'))).toBe(true)
  })

  it('errors on a duplicate AC id', async () => {
    const { validatePrdHtml } = await lib()
    const dup = FEATURE_P0.replace('AC-P0-1.2', 'AC-P0-1.1')
    const r = validatePrdHtml(prd({ bodies: { features: dup } }))
    expect(r.errors).toContain('prd.html: duplicate AC id: AC-P0-1.1')
  })

  it('errors when an AC does not match its feature', async () => {
    const { validatePrdHtml } = await lib()
    const wrong = FEATURE_P0.replace('AC-P0-1.2', 'AC-P0-2.1')
    const r = validatePrdHtml(prd({ bodies: { features: wrong } }))
    expect(r.errors).toContain('prd.html: feature P0-1: AC AC-P0-2.1 does not belong to this feature')
  })

  it('errors when the schema-version meta is missing', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(prd({ meta: '<meta name="conductor:doc-type" content="prd">' }))
    expect(r.isV2).toBe(false)
    expect(r.errors[0]).toContain('conductor:schema-version')
  })

  it('errors when the doc-type meta is missing', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(prd({ meta: '<meta name="conductor:schema-version" content="2">' }))
    expect(r.errors[0]).toContain('conductor:doc-type')
  })

  it('warns on network use, window.claude, window.open and non-allowlisted scripts', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(
      prd({
        extraHead:
          '<script src="https://evil.example.com/x.js"></script><script>fetch("/x"); window.claude.complete(); window.open("https://a.example")</script>',
      })
    )
    expect(r.errors).toEqual([])
    expect(r.warnings.length).toBe(4)
    expect(r.warnings.some(w => w.includes('window.open'))).toBe(true)
  })

  it.each([
    'https://cdnjs.cloudflare.com/ajax/libs/x/1.0.0/x.js',
    'https://cdn.jsdelivr.net/npm/chart.js@4.4.1',
    'https://unpkg.com/lib@1.2.3/dist/lib.js',
    'https://cdn.tailwindcss.com/3.4.1',
    'https://code.jquery.com/jquery-3.7.1.min.js',
  ])('accepts a script from the CSP-allowed host %s', async src => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(prd({ extraHead: `<script src="${src}"></script>` }))
    expect(r.warnings).toEqual([])
  })

  it('warns on a script from a host outside the CSP list and on non-https', async () => {
    const { validatePrdHtml } = await lib()
    const r = validatePrdHtml(
      prd({
        extraHead:
          '<script src="https://cdn.example.com/x.js"></script><script src="http://unpkg.com/x.js"></script>',
      })
    )
    expect(r.warnings.length).toBe(2)
  })
})

const TASKS = JSON.stringify({
  issue_id: ISSUE_ID,
  schema_version: 2,
  epics: [
    {
      id: 'E1',
      tasks: [
        {
          id: 'T1.1',
          files_owned: ['a.ts'],
          covers_acs: ['AC-P0-1.1', 'AC-P0-1.2', 'AC-P1-1.1'],
          contract: { input: 'i', output: 'o', invariant: 'v' },
          test_plan: ['t'],
        },
      ],
    },
  ],
})

const MD_TASKS = JSON.stringify({
  issue_id: ISSUE_ID,
  schema_version: 2,
  epics: [
    {
      id: 'E1',
      tasks: [
        {
          id: 'T1.1',
          files_owned: ['a.ts'],
          covers_acs: ['AC-P0-1.1', 'AC-P0-1.2'],
          contract: { input: 'i', output: 'o', invariant: 'v' },
          test_plan: ['t'],
        },
      ],
    },
  ],
})

const MD_PRD = `---
issueId: abc-123
schemaVersion: 2
---

# T

## Problem
x
## Glossary
x
## Goals
x
## Non-Goals
x
## Users
x
## Reference Patterns
x
## Features

### P0: Must Have

#### P0-1: Foo
- **What**: a
- **Not**: _None._
- **Inputs**: a
- **Outputs**: a
- **Invariants**: a
- **Acceptance Criteria**:
  - [ ] \`AC-P0-1.1\` a
  - [ ] \`AC-P0-1.2\` a
`

function setupFs(files: Record<string, string>): void {
  mockFs.existsSync.mockImplementation(
    (p: fs.PathLike) => String(p) in files || String(p) === ISSUE_DIR
  )
  mockFs.readFileSync.mockImplementation((p: fs.PathOrFileDescriptor) => {
    const s = String(p)
    if (s in files) return files[s]
    throw new Error(`unexpected readFileSync: ${s}`)
  })
}

async function runLint(): Promise<{ output: string; exitCode: number | undefined }> {
  mockReadConfig.mockReturnValue({
    apiKey: 'k',
    projectId: 'p',
    projectName: 'T',
    email: 'u@example.com',
    apiUrl: 'http://localhost:8080',
    localPath: LOCAL_PATH,
  })
  const writeSpy = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
  const logSpy = vi.spyOn(console, 'log').mockImplementation(() => undefined)
  let exitCode: number | undefined
  const exitSpy = vi.spyOn(process, 'exit').mockImplementation(((code?: number) => {
    exitCode = code
    return undefined as never
  }) as never)
  const { registerLint } = await import('../commands/lint.js')
  const program = new Command()
  program.exitOverride()
  registerLint(program)
  try {
    await program.parseAsync(['node', 'conductor', 'lint', ISSUE_ID])
  } catch {
    // exitOverride throws
  }
  const output =
    writeSpy.mock.calls.map(c => String(c[0])).join('') +
    logSpy.mock.calls.map(c => c.map(String).join(' ') + '\n').join('')
  writeSpy.mockRestore()
  logSpy.mockRestore()
  exitSpy.mockRestore()
  return { output, exitCode }
}

describe('lintIssue PRD file selection', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.resetModules()
  })
  afterEach(() => vi.restoreAllMocks())

  it('prefers prd.html when both exist', async () => {
    setupFs({ [HTML_PATH]: prd(), [MD_PATH]: MD_PRD, [TASKS_PATH]: TASKS })
    const { output, exitCode } = await runLint()
    expect(output).toContain('✓ prd.html')
    expect(output).not.toContain('✓ prd.md')
    expect(exitCode).toBe(0)
  })

  it('falls back to prd.md when there is no prd.html', async () => {
    setupFs({ [MD_PATH]: MD_PRD, [TASKS_PATH]: MD_TASKS })
    const { output, exitCode } = await runLint()
    expect(output).toContain('✓ prd.md')
    expect(exitCode).toBe(0)
  })

  it('names prd.html in errors and cross-checks tasks.json against its ACs', async () => {
    setupFs({
      [HTML_PATH]: prd({ drop: 'users' }),
      [TASKS_PATH]: TASKS.replace('"AC-P1-1.1"', '"AC-P0-9.9"'),
    })
    const { output, exitCode } = await runLint()
    expect(output).toContain('prd.html: missing required section: Users')
    expect(output).toContain('covers unknown AC AC-P0-9.9')
    expect(exitCode).toBe(1)
  })

  it('reports HTML ACs that no task covers', async () => {
    setupFs({
      [HTML_PATH]: prd(),
      [TASKS_PATH]: TASKS.replace(',"AC-P1-1.1"', ''),
    })
    const { output, exitCode } = await runLint()
    expect(output).toContain('coverage: AC-P1-1.1 has no covering task')
    expect(exitCode).toBe(1)
  })
})
