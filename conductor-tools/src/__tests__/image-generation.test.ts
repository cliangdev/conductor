import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const mockReadConfig = vi.fn()
const mockCodex = vi.fn()
const mockOpenAi = vi.fn()

vi.mock('../lib/config.js', () => ({ readConfig: mockReadConfig }))
vi.mock('../lib/codex-image.js', () => ({ generateImagesWithCodex: mockCodex }))
vi.mock('../lib/openai-image.js', () => ({ generateImagesWithOpenAi: mockOpenAi }))

const ENV = ['CONDUCTOR_OPENAI_API_KEY', 'OPENAI_API_KEY', 'CONDUCTOR_IMAGE_MODEL', 'CONDUCTOR_IMAGE_QUALITY']
const saved: Record<string, string | undefined> = {}
const result = { files: [], provenance: { source: 's', licence: 'l', aiGenerated: true }, revisedPrompt: '', outDir: '/x' }

beforeEach(() => {
  for (const k of ENV) {
    saved[k] = process.env[k]
    delete process.env[k]
  }
  mockReadConfig.mockReset().mockReturnValue(null)
  mockCodex.mockReset().mockResolvedValue(result)
  mockOpenAi.mockReset().mockResolvedValue(result)
})
afterEach(() => {
  for (const k of ENV) {
    if (saved[k] === undefined) delete process.env[k]
    else process.env[k] = saved[k]
  }
})

describe('resolveOpenAiKey', () => {
  it('prefers CONDUCTOR_OPENAI_API_KEY, then OPENAI_API_KEY, then config', async () => {
    const { resolveOpenAiKey } = await import('../lib/image-generation.js')
    mockReadConfig.mockReturnValue({ openaiApiKey: 'cfg' })
    expect(resolveOpenAiKey()).toEqual({ key: 'cfg', source: 'config' })
    process.env['OPENAI_API_KEY'] = ' env '
    expect(resolveOpenAiKey()).toEqual({ key: 'env', source: 'OPENAI_API_KEY' })
    process.env['CONDUCTOR_OPENAI_API_KEY'] = 'cond'
    expect(resolveOpenAiKey()).toEqual({ key: 'cond', source: 'CONDUCTOR_OPENAI_API_KEY' })
  })

  it('treats blank values as absent', async () => {
    const { resolveOpenAiKey } = await import('../lib/image-generation.js')
    process.env['OPENAI_API_KEY'] = '   '
    mockReadConfig.mockReturnValue({ openaiApiKey: '  ' })
    expect(resolveOpenAiKey()).toBeNull()
  })
})

describe('resolveImageSettings', () => {
  it('defaults and honours env overrides', async () => {
    const { resolveImageSettings } = await import('../lib/image-generation.js')
    expect(resolveImageSettings()).toEqual({ model: 'gpt-image-2.5-sunburst', quality: 'high' })
    process.env['CONDUCTOR_IMAGE_MODEL'] = 'm'
    process.env['CONDUCTOR_IMAGE_QUALITY'] = 'medium'
    expect(resolveImageSettings()).toEqual({ model: 'm', quality: 'medium' })
  })
})

describe('generateImages', () => {
  it('throws CodexConfirmationRequired without a key or useCodex, and never calls Codex', async () => {
    const { generateImages, CodexConfirmationRequired } = await import('../lib/image-generation.js')
    const err = await generateImages({ prompt: 'p', outDir: '/x' }).catch((e) => e)
    expect(err).toBeInstanceOf(CodexConfirmationRequired)
    expect(err.code).toBe('CODEX_CONFIRMATION_REQUIRED')
    expect(mockCodex).not.toHaveBeenCalled()
    expect(mockOpenAi).not.toHaveBeenCalled()
  })

  it('uses OpenAI when a key is set', async () => {
    const { generateImages } = await import('../lib/image-generation.js')
    process.env['OPENAI_API_KEY'] = 'sk-1'
    const res = await generateImages({ prompt: 'p', outDir: '/x' })
    expect(mockOpenAi).toHaveBeenCalledWith(expect.objectContaining({ prompt: 'p' }), { apiKey: 'sk-1', model: 'gpt-image-2.5-sunburst', quality: 'high', keySource: 'OPENAI_API_KEY' })
    expect(mockCodex).not.toHaveBeenCalled()
    expect(res.generator).toEqual({ route: 'openai-api', model: 'gpt-image-2.5-sunburst' })
  })

  it('uses Codex when useCodex is true, even with a key', async () => {
    const { generateImages } = await import('../lib/image-generation.js')
    process.env['OPENAI_API_KEY'] = 'sk-1'
    const res = await generateImages({ prompt: 'p', outDir: '/x', useCodex: true })
    expect(mockCodex).toHaveBeenCalledTimes(1)
    expect(mockCodex.mock.calls[0]![0]).not.toHaveProperty('useCodex')
    expect(mockOpenAi).not.toHaveBeenCalled()
    expect(res.generator).toEqual({ route: 'codex', model: 'gpt-image-2 (Codex image_gen)' })
  })
})
