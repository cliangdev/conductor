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
const PRD_PATH = `${ISSUE_DIR}/prd.md`
const TASKS_PATH = `${ISSUE_DIR}/tasks.json`

const CLEAN_V2_PRD = `---
issueId: abc-123
schemaVersion: 2
---

# Title

## Problem

Some problem.

## Glossary

- **Foo**: bar.

## Goals

- Goal one.

## Non-Goals

- Out of scope.

## Users

Some users.

## Reference Patterns

- Pattern one.

## Features

### P0: Must Have

#### P0-1: Foo

- **What**: Build foo.
- **Not**: _None._
- **Inputs**: Stuff in.
- **Outputs**: Stuff out.
- **Invariants**: Always true.
- **Acceptance Criteria**:
  - [ ] \`AC-P0-1.1\` First criterion
  - [ ] \`AC-P0-1.2\` Second criterion
`

const CLEAN_V2_TASKS = JSON.stringify({
  issue_id: ISSUE_ID,
  schema_version: 2,
  epics: [
    {
      id: 'E1',
      title: 'Epic',
      tasks: [
        {
          id: 'T1.1',
          title: 'Do thing',
          files_owned: ['src/foo.ts'],
          covers_acs: ['AC-P0-1.1', 'AC-P0-1.2'],
          contract: {
            input: 'in',
            output: 'out',
            invariant: 'inv',
          },
          test_plan: ['Test foo'],
        },
      ],
    },
  ],
})

function setupFsForIssue(prd: string, tasks: string): void {
  mockFs.existsSync.mockImplementation((p: fs.PathLike) => {
    const s = String(p)
    return s === PRD_PATH || s === TASKS_PATH || s === ISSUE_DIR
  })
  mockFs.readFileSync.mockImplementation((p: fs.PathOrFileDescriptor) => {
    const s = String(p)
    if (s === PRD_PATH) return prd
    if (s === TASKS_PATH) return tasks
    throw new Error(`unexpected readFileSync: ${s}`)
  })
}

function setupConfig(): void {
  mockReadConfig.mockReturnValue({
    apiKey: 'test-key',
    projectId: 'proj_123',
    projectName: 'Test',
    email: 'user@example.com',
    apiUrl: 'http://localhost:8080',
    localPath: LOCAL_PATH,
  })
}

async function runLint(args: string[]): Promise<{
  output: string
  exitCode: number | undefined
}> {
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
    await program.parseAsync(['node', 'conductor', 'lint', ...args])
  } catch {
    // commander.exitOverride throws; ignore
  }

  const output =
    writeSpy.mock.calls.map(c => String(c[0])).join('') +
    logSpy.mock.calls.map(c => c.map(String).join(' ') + '\n').join('')

  writeSpy.mockRestore()
  logSpy.mockRestore()
  exitSpy.mockRestore()
  return { output, exitCode }
}

describe('conductor lint command', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.resetModules()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('AC-P0-4.1: clean v2 PRD + tasks.json → exit 0 with ✓ lines per file', async () => {
    setupConfig()
    setupFsForIssue(CLEAN_V2_PRD, CLEAN_V2_TASKS)

    const { output, exitCode } = await runLint([ISSUE_ID])

    expect(output).toContain('✓ prd.md')
    expect(output).toContain('✓ tasks.json')
    expect(exitCode).toBe(0)
  })

  it('AC-P0-4.2: PRD missing Glossary → exit 1 with missing required section error', async () => {
    setupConfig()
    const prdNoGlossary = CLEAN_V2_PRD.replace(/## Glossary[\s\S]*?(?=## Goals)/, '')
    setupFsForIssue(prdNoGlossary, CLEAN_V2_TASKS)

    const { output, exitCode } = await runLint([ISSUE_ID])

    expect(output).toContain('prd.md: missing required section: Glossary')
    expect(exitCode).toBe(1)
  })

  it('AC-P0-4.3: tasks.json with covers_acs referencing AC-P0-9.9 → exit 1', async () => {
    setupConfig()
    const badTasks = JSON.stringify({
      issue_id: ISSUE_ID,
      schema_version: 2,
      epics: [
        {
          id: 'E1',
          tasks: [
            {
              id: 'T1.1',
              title: 'Do thing',
              files_owned: ['src/foo.ts'],
              covers_acs: ['AC-P0-1.1', 'AC-P0-1.2', 'AC-P0-9.9'],
              contract: { input: 'in', output: 'out', invariant: 'inv' },
              test_plan: ['Test foo'],
            },
          ],
        },
      ],
    })
    setupFsForIssue(CLEAN_V2_PRD, badTasks)

    const { output, exitCode } = await runLint([ISSUE_ID])

    expect(output).toContain('T1.1: covers unknown AC AC-P0-9.9')
    expect(output).not.toContain('TT1.1:')
    expect(exitCode).toBe(1)
  })

  it('AC-P0-4.4: PRD AC AC-P0-1.5 with no covering task → exit 1 with coverage error', async () => {
    setupConfig()
    const prdWithExtraAc = CLEAN_V2_PRD.replace(
      '  - [ ] `AC-P0-1.2` Second criterion',
      '  - [ ] `AC-P0-1.2` Second criterion\n  - [ ] `AC-P0-1.5` Uncovered criterion'
    )
    setupFsForIssue(prdWithExtraAc, CLEAN_V2_TASKS)

    const { output, exitCode } = await runLint([ISSUE_ID])

    expect(output).toContain('coverage: AC-P0-1.5 has no covering task')
    expect(exitCode).toBe(1)
  })

  it('AC-P0-4.5: --json on a clean issue → valid JSON of expected shape', async () => {
    setupConfig()
    setupFsForIssue(CLEAN_V2_PRD, CLEAN_V2_TASKS)

    const { output, exitCode } = await runLint([ISSUE_ID, '--json'])

    expect(exitCode).toBe(0)
    const parsed = JSON.parse(output) as {
      issues: Array<{
        issueId: string
        prd: { ok: boolean; errors: string[] }
        tasks: { ok: boolean; errors: string[] }
      }>
    }
    expect(Array.isArray(parsed.issues)).toBe(true)
    expect(parsed.issues).toHaveLength(1)
    const issue = parsed.issues[0]
    expect(issue.issueId).toBe(ISSUE_ID)
    expect(issue.prd.ok).toBe(true)
    expect(Array.isArray(issue.prd.errors)).toBe(true)
    expect(issue.prd.errors).toEqual([])
    expect(issue.tasks.ok).toBe(true)
    expect(Array.isArray(issue.tasks.errors)).toBe(true)
    expect(issue.tasks.errors).toEqual([])
  })

  it('AC-P0-4.6: missing ~/.conductor/config.json → exit 78', async () => {
    mockReadConfig.mockReturnValue(null)

    const { exitCode } = await runLint([ISSUE_ID])

    expect(exitCode).toBe(78)
  })

  it('AC-P0-4.7: v1 PRD (no schemaVersion) → exit 0 with upgrade-recommended warning', async () => {
    setupConfig()
    const v1Prd = `---
issueId: abc-123
title: Some title
---

# Title

Free-form content.
`
    const v1Tasks = JSON.stringify({
      issue_id: ISSUE_ID,
      epics: [],
    })
    setupFsForIssue(v1Prd, v1Tasks)

    const { output, exitCode } = await runLint([ISSUE_ID])

    expect(output).toContain('prd.md: schemaVersion: 1 — upgrade recommended')
    expect(exitCode).toBe(0)
  })

  it('AC-P0-2.3: tasks.json task missing files_owned → exit 1', async () => {
    setupConfig()
    const tasksMissingFilesOwned = JSON.stringify({
      issue_id: ISSUE_ID,
      schema_version: 2,
      epics: [
        {
          id: 'E1',
          tasks: [
            {
              id: 'T1.1',
              title: 'Do thing',
              covers_acs: ['AC-P0-1.1', 'AC-P0-1.2'],
              contract: { input: 'in', output: 'out', invariant: 'inv' },
              test_plan: ['Test foo'],
            },
          ],
        },
      ],
    })
    setupFsForIssue(CLEAN_V2_PRD, tasksMissingFilesOwned)

    const { output, exitCode } = await runLint([ISSUE_ID])

    expect(output).toContain('T1.1: missing files_owned')
    expect(output).not.toContain('TT1.1:')
    expect(exitCode).toBe(1)
  })
})
