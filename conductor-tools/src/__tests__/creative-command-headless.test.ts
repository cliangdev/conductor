import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'fs'
import { Command } from 'commander'

vi.mock('fs')
const renderCreativeMock = vi.fn()
vi.mock('../lib/creative-render.js', () => ({ renderCreative: (...args: unknown[]) => renderCreativeMock(...args) }))
const renderSlidesMock = vi.fn()
vi.mock('../mcp/tools/slideshow-render.js', () => ({ renderSlides: (...args: unknown[]) => renderSlidesMock(...args) }))

import { registerCreative, describeConfigFailure } from '../commands/creative.js'

const UUID = '0b0b0b0b-1111-2222-3333-444444444444'
const ENV_KEYS = ['CONDUCTOR_API_URL', 'CONDUCTOR_API_KEY', 'CONDUCTOR_PROJECT_ID']

async function runCli(...args: string[]): Promise<string> {
  const errors: string[] = []
  const spy = vi.spyOn(console, 'error').mockImplementation((...a: unknown[]) => void errors.push(a.join(' ')))
  vi.spyOn(console, 'log').mockImplementation(() => {})
  const program = new Command()
  registerCreative(program)
  await program.parseAsync(['node', 'conductor', 'creative', 'render', ...args])
  spy.mockRestore()
  return errors.join('\n')
}

beforeEach(() => {
  vi.clearAllMocks()
  process.exitCode = undefined
  for (const k of ENV_KEYS) delete process.env[k]
  // No ~/.conductor/config.json, as in a workflow container.
  vi.mocked(fs.readFileSync).mockImplementation(() => {
    throw new Error('ENOENT')
  })
  renderCreativeMock.mockResolvedValue({ ok: true, renderId: 'r1', state: 'SUCCEEDED', frames: [] })
})

afterEach(() => {
  for (const k of ENV_KEYS) delete process.env[k]
  process.exitCode = undefined
})

describe('conductor creative render, headless (env only)', () => {
  it('works with no config file when CONDUCTOR_API_URL/KEY/PROJECT_ID are set, and sends --workflow-run-id', async () => {
    process.env['CONDUCTOR_API_URL'] = 'https://api.test'
    process.env['CONDUCTOR_API_KEY'] = 'run-key'
    process.env['CONDUCTOR_PROJECT_ID'] = 'proj-9'

    await runCli(UUID, '--renderer', 'workflow', '--workflow-run-id', 'run-77')

    expect(process.exitCode).toBeUndefined()
    expect(renderCreativeMock).toHaveBeenCalledTimes(1)
    const [params, config] = renderCreativeMock.mock.calls[0]
    expect(params).toMatchObject({ creativeId: UUID, renderer: 'workflow', workflowRunId: 'run-77', previewOnly: false })
    expect(config).toMatchObject({ apiUrl: 'https://api.test', apiKey: 'run-key', projectId: 'proj-9' })
  })

  it('names the missing variables when env is not set and there is no config file', async () => {
    const err = await runCli(UUID)

    expect(process.exitCode).toBe(1)
    expect(renderCreativeMock).not.toHaveBeenCalled()
    expect(err).toContain('CONDUCTOR_API_URL')
    expect(err).toContain('CONDUCTOR_API_KEY')
  })

  it('names only the variable that is missing', async () => {
    process.env['CONDUCTOR_API_URL'] = 'https://api.test'
    const err = await runCli(UUID)

    expect(process.exitCode).toBe(1)
    expect(err).toContain('CONDUCTOR_API_KEY')
    expect(err).not.toContain('CONDUCTOR_API_URL and')
  })

  it('names CONDUCTOR_PROJECT_ID when the key and url are set but no project is', async () => {
    process.env['CONDUCTOR_API_URL'] = 'https://api.test'
    process.env['CONDUCTOR_API_KEY'] = 'run-key'

    const err = await runCli(UUID)

    expect(process.exitCode).toBe(1)
    expect(renderCreativeMock).not.toHaveBeenCalled()
    expect(err).toContain('CONDUCTOR_PROJECT_ID')
  })

  it('leaves other config errors untouched', () => {
    expect(describeConfigFailure(new Error('Invalid config: missing required fields'), {})).toBe(
      'Invalid config: missing required fields'
    )
  })
})

describe('conductor creative render-slides', () => {
  const REV = '11111111-1111-1111-1111-111111111111'
  async function runSlides(...args: string[]): Promise<{ errors: string; logs: string }> {
    const errors: string[] = []
    const logs: string[] = []
    const e = vi.spyOn(console, 'error').mockImplementation((...a: unknown[]) => void errors.push(a.join(' ')))
    const l = vi.spyOn(console, 'log').mockImplementation((...a: unknown[]) => void logs.push(a.join(' ')))
    const program = new Command()
    registerCreative(program)
    await program.parseAsync(['node', 'conductor', 'creative', 'render-slides', ...args])
    e.mockRestore()
    l.mockRestore()
    return { errors: errors.join('\n'), logs: logs.join('\n') }
  }
  const env = () => {
    process.env['CONDUCTOR_API_URL'] = 'https://api.test'
    process.env['CONDUCTOR_API_KEY'] = 'run-key'
    process.env['CONDUCTOR_PROJECT_ID'] = 'proj-9'
  }

  it('runs with the run-scoped env and no config file, exit 0 on a completed revision', async () => {
    env()
    renderSlidesMock.mockResolvedValue({ ok: true, outcome: 'completed', revisionId: REV, slides: [1, 3], ratios: ['9x16', '4x5'], uploaded: 4, warnings: 0 })
    const { logs } = await runSlides(REV, '--creative', UUID, '--workflow-run-id', 'run-77')
    expect(process.exitCode).toBeUndefined()
    const [params, config] = renderSlidesMock.mock.calls[0]!
    expect(params).toMatchObject({ revisionId: REV, creativeId: UUID, workflowRunId: 'run-77' })
    expect(config).toMatchObject({ apiUrl: 'https://api.test', apiKey: 'run-key', projectId: 'proj-9' })
    expect(logs).toMatch(/slides 1, 3 re-rendered at 9x16, 4x5 \(4 images uploaded/)
  })

  it('exits 1 and lists the failing checks when the layout checks failed', async () => {
    env()
    renderSlidesMock.mockResolvedValue({
      ok: false,
      outcome: 'checks-failed',
      revisionId: REV,
      error: '1 layout check failed; nothing was changed.',
      checks: [{ slide: 3, ratio: '9x16', rule: 'safeZone', severity: 'error', message: 'too low' }],
    })
    const { errors } = await runSlides(REV, '--creative', UUID)
    expect(process.exitCode).toBe(1)
    expect(errors).toMatch(/slide 3 9x16 safeZone: too low/)
  })

  it('exits 1 with the message when the job crashes', async () => {
    env()
    renderSlidesMock.mockRejectedValue(new Error('browser exploded'))
    const { errors } = await runSlides(REV, '--creative', UUID)
    expect(process.exitCode).toBe(1)
    expect(errors).toMatch(/browser exploded/)
  })

  it('names the missing variables when there is no login and no env', async () => {
    const { errors } = await runSlides(REV, '--creative', UUID)
    expect(process.exitCode).toBe(1)
    expect(errors).toMatch(/CONDUCTOR_API_URL and CONDUCTOR_API_KEY/)
    expect(renderSlidesMock).not.toHaveBeenCalled()
  })
})
