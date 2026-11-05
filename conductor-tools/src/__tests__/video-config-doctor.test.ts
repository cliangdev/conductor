import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { Command } from 'commander'

const readConfigMock = vi.hoisted(() => vi.fn())
const writeConfigMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/config.js', () => ({ readConfig: readConfigMock, writeConfig: writeConfigMock, CONFIG_PATH: '/x/config.json' }))
const tokenMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/vertex-veo.js', () => ({ getVertexToken: tokenMock }))
const spentMock = vi.hoisted(() => vi.fn())
vi.mock('../lib/video-spend.js', () => ({ spentToday: spentMock }))

const base = { apiKey: 'cond1234abcd5678', projectId: 'p', projectName: 'P', email: 'e@x.io', apiUrl: 'https://x' }

async function run(args: string[]) {
  const { registerConfig } = await import('../commands/config.js')
  const program = new Command()
  program.exitOverride()
  registerConfig(program)
  await program.parseAsync(['node', 'conductor', 'config', ...args])
}

describe('video config commands', () => {
  let log: ReturnType<typeof vi.spyOn>
  let err: ReturnType<typeof vi.spyOn>
  let exit: ReturnType<typeof vi.spyOn>
  beforeEach(() => {
    readConfigMock.mockReset().mockReturnValue({ ...base })
    writeConfigMock.mockReset()
    log = vi.spyOn(console, 'log').mockImplementation(() => undefined)
    err = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    exit = vi.spyOn(process, 'exit').mockImplementation(() => undefined as never)
  })
  afterEach(() => vi.restoreAllMocks())

  it('sets the vertex project and region', async () => {
    await run(['set-vertex-project', 'my-proj'])
    expect(writeConfigMock).toHaveBeenLastCalledWith(expect.objectContaining({ vertexProject: 'my-proj' }))
    await run(['set-vertex-region', 'us-central1'])
    expect(writeConfigMock).toHaveBeenLastCalledWith(expect.objectContaining({ vertexRegion: 'us-central1' }))
  })

  it('stores and removes the Gemini key without echoing it', async () => {
    await run(['set-gemini-api-key', 'AIzaSyABCDEFGH123456'])
    expect(writeConfigMock).toHaveBeenLastCalledWith(expect.objectContaining({ geminiApiKey: 'AIzaSyABCDEFGH123456' }))
    expect(log.mock.calls.flat().join('\n')).not.toContain('AIzaSyABCDEFGH123456')
    readConfigMock.mockReturnValue({ ...base, geminiApiKey: 'AIzaSyABCDEFGH123456' })
    await run(['unset-gemini-api-key'])
    expect((writeConfigMock.mock.calls.at(-1)![0] as Record<string, unknown>)['geminiApiKey']).toBeUndefined()
  })

  it('sets and validates the route', async () => {
    await run(['set-video-route', 'gemini'])
    expect(writeConfigMock).toHaveBeenLastCalledWith(expect.objectContaining({ videoRoute: 'gemini' }))
    writeConfigMock.mockClear()
    await run(['set-video-route', 'openai'])
    expect(err).toHaveBeenCalledWith(expect.stringContaining('Invalid route'))
    expect(exit).toHaveBeenCalledWith(2)
    expect(writeConfigMock).not.toHaveBeenCalled()
  })

  it('sets caps and rejects non-positive numbers', async () => {
    await run(['set-video-caps', '--per-call', '1.5', '--daily', '8'])
    expect(writeConfigMock).toHaveBeenLastCalledWith(expect.objectContaining({ videoPerCallCapUsd: 1.5, videoDailyCapUsd: 8 }))
    writeConfigMock.mockClear()
    await run(['set-video-caps', '--daily', '-3'])
    await run(['set-video-caps', '--per-call', 'abc'])
    await run(['set-video-caps'])
    expect(writeConfigMock).not.toHaveBeenCalled()
    expect(exit).toHaveBeenCalledWith(2)
  })

  it('show lists the video settings with the key redacted (text and json)', async () => {
    readConfigMock.mockReturnValue({ ...base, vertexProject: 'my-proj', geminiApiKey: 'AIzaSyABCDEFGH123456', videoDailyCapUsd: 8 })
    await run(['show'])
    const text = log.mock.calls.flat().join('\n')
    expect(text).toContain('vertexProject: my-proj')
    expect(text).toContain('videoDailyCapUsd: 8')
    expect(text).toContain('geminiApiKey: AIza...3456')
    expect(text).not.toContain('AIzaSyABCDEFGH123456')
    const write = vi.spyOn(process.stdout, 'write').mockImplementation(() => true)
    await run(['show', '--json'])
    const json = String(write.mock.calls.at(-1)![0])
    expect(json).toContain('"vertexProject": "my-proj"')
    expect(json).not.toContain('AIzaSyABCDEFGH123456')
  })
})

describe('doctor video check', () => {
  const ENV = ['CONDUCTOR_GEMINI_API_KEY', 'GEMINI_API_KEY']
  beforeEach(() => {
    vi.resetModules()
    for (const k of ENV) delete process.env[k]
    readConfigMock.mockReset()
    tokenMock.mockReset().mockResolvedValue('ya29.SECRET-TOKEN')
    spentMock.mockReset().mockReturnValue(1.25)
  })

  it('warns with the setup commands when nothing is set', async () => {
    readConfigMock.mockReturnValue({ ...base })
    const { checkVideoGeneration } = await import('../commands/doctor.js')
    const r = await checkVideoGeneration()
    expect(r.status).toBe('warn')
    expect(r.message).toContain('conductor config set-vertex-project <id>')
    expect(r.message).toContain('gcloud auth login')
    expect(r.message).toContain('conductor config set-gemini-api-key <key>')
  })

  it('passes on Vertex with a token, shows spend, and never prints the token', async () => {
    readConfigMock.mockReturnValue({ ...base, vertexProject: 'my-proj' })
    const { checkVideoGeneration } = await import('../commands/doctor.js')
    const r = await checkVideoGeneration()
    expect(r.status).toBe('pass')
    expect(r.message).toContain('my-proj')
    expect(r.message).toContain('spent today $1.25 of $5.00 cap')
    expect(r.message).not.toContain('SECRET-TOKEN')
  })

  it('warns on Vertex when no token can be obtained', async () => {
    readConfigMock.mockReturnValue({ ...base, vertexProject: 'my-proj' })
    tokenMock.mockRejectedValue(new Error('no gcloud'))
    const { checkVideoGeneration } = await import('../commands/doctor.js')
    const r = await checkVideoGeneration()
    expect(r.status).toBe('warn')
    expect(r.message).toContain('gcloud auth login')
  })

  it('passes on Gemini naming only the key source, never the key', async () => {
    readConfigMock.mockReturnValue({ ...base, geminiApiKey: 'AIzaSyTOPSECRET' })
    const { checkVideoGeneration } = await import('../commands/doctor.js')
    const r = await checkVideoGeneration()
    expect(r.status).toBe('pass')
    expect(r.message).toContain('from config')
    expect(r.message).not.toContain('AIzaSyTOPSECRET')
  })
})
