import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

vi.mock('../lib/image-generation.js', () => ({ generateImages: vi.fn() }))

import { generateImages } from '../lib/image-generation.js'
import { generateCreativeImage, pruneGenerated } from '../mcp/tools/creative-images.js'

const gen = vi.mocked(generateImages)
let tmp: string

beforeEach(() => {
  gen.mockReset()
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'creative-img-'))
  gen.mockResolvedValue({
    files: [{ path: '/x/image-1.png', width: 1024, height: 1536 }],
    provenance: { source: 'Generated with Codex image_gen on 2026-10-12', licence: 'Generated, house use', aiGenerated: true },
    revisedPrompt: 'rp',
    outDir: '/x',
    generator: { route: 'openai-api', model: 'gpt-image-2.5-sunburst' },
    size: '1024x1536',
    sizeNote: 'fell back',
    placementFit: { '9x16': { scale: 1.25, videoScale: 1.4, ok: false, videoOk: false } },
    quality: 'high',
    model: 'gpt-image-2.5-sunburst',
    preview: { data: Buffer.from('jpg'), mimeType: 'image/jpeg' },
  })
})
afterEach(() => fs.rmSync(tmp, { recursive: true, force: true }))

describe('generateCreativeImage', () => {
  it('validates the params', async () => {
    await expect(generateCreativeImage({ prompt: '  ' }, { outDir: tmp })).rejects.toThrow(/prompt is required/)
    await expect(generateCreativeImage({ prompt: 'a', aspect: 'wide' }, { outDir: tmp })).rejects.toThrow(/aspect must be/)
    await expect(generateCreativeImage({ prompt: 'a', size: '1000x1600' }, { outDir: tmp })).rejects.toThrow(/multiples of 16/)
    await expect(generateCreativeImage({ prompt: 'a', count: 0 }, { outDir: tmp })).rejects.toThrow(/count must be/)
    await expect(generateCreativeImage({ prompt: 'a', count: 4 }, { outDir: tmp })).rejects.toThrow(/count must be/)
    await expect(generateCreativeImage({ prompt: 'a', referenceImagePaths: [path.join(tmp, 'nope.png')] }, { outDir: tmp })).rejects.toThrow(/Reference image not found/)
    await expect(generateCreativeImage({ prompt: 'a', useCodex: 'yes' as unknown as boolean }, { outDir: tmp })).rejects.toThrow(/useCodex must be/)
    await expect(generateCreativeImage({ prompt: 'a', quality: 'ultra' }, { outDir: tmp })).rejects.toThrow('quality must be one of low, medium, high.')
    expect(gen).not.toHaveBeenCalled()
  })

  it('passes quality through and returns the model and quality used', async () => {
    const res = await generateCreativeImage({ prompt: 'a', quality: 'low' }, { outDir: tmp })
    expect(gen).toHaveBeenCalledWith(expect.objectContaining({ quality: 'low' }))
    expect(res).toMatchObject({ quality: 'high', model: 'gpt-image-2.5-sunburst' })
    await generateCreativeImage({ prompt: 'a' }, { outDir: path.join(tmp, 'n') })
    expect(gen.mock.calls[1]![0]).not.toHaveProperty('quality')
  })

  it('accepts aliases and passes size through', async () => {
    await generateCreativeImage({ prompt: 'a', aspect: 'landscape', size: '1280x1920' }, { outDir: tmp })
    expect(gen).toHaveBeenCalledWith(expect.objectContaining({ aspect: '3:2', size: '1280x1920' }))
  })

  it('passes useCodex through only when true', async () => {
    await generateCreativeImage({ prompt: 'a' }, { outDir: path.join(tmp, 'a') })
    expect(gen.mock.calls[0]![0]).not.toHaveProperty('useCodex')
    await generateCreativeImage({ prompt: 'a', useCodex: true }, { outDir: path.join(tmp, 'b') })
    expect(gen.mock.calls[1]![0]).toMatchObject({ useCodex: true })
  })

  it('returns files, provenance, next step and the preview', async () => {
    const ref = path.join(tmp, 'ref.png')
    fs.writeFileSync(ref, 'x')
    const res = await generateCreativeImage({ prompt: 'a kitchen', count: 2, referenceImagePaths: [ref] }, { outDir: path.join(tmp, 'out') })
    expect(gen).toHaveBeenCalledWith(expect.objectContaining({ prompt: 'a kitchen', aspect: '9:16', count: 2, referenceImagePaths: [ref], outDir: path.join(tmp, 'out') }))
    expect(res.files[0]).toEqual({ path: '/x/image-1.png', width: 1024, height: 1536 })
    expect(res.provenance.aiGenerated).toBe(true)
    expect(res.revisedPrompt).toBe('rp')
    expect(res.generator).toEqual({ route: 'openai-api', model: 'gpt-image-2.5-sunburst' })
    expect(res.nextStep).toContain('photoPath')
    expect(res.nextStep).toContain('realism check')
    expect(res.nextStep).toContain('100%')
    expect(res.nextStep).toContain('9x16 (1.25x)')
    expect(res.nextStep).toMatch(/not use this photo full-bleed/i)
    expect(res).toMatchObject({ size: '1024x1536', sizeNote: 'fell back', placementFit: { '9x16': { scale: 1.25 } } })
    expect(res.image?.mimeType).toBe('image/jpeg')
  })

  it('defaults the output folder under .conductor/generated with a .gitignore', async () => {
    await generateCreativeImage({ prompt: 'A Kitchen!' }, { cwd: tmp })
    const outDir = gen.mock.calls[0]![0].outDir
    expect(outDir).toMatch(/\.conductor[\\/]generated[\\/]\d{8}-\d{6}-a-kitchen$/)
    expect(fs.readFileSync(path.join(tmp, '.conductor', 'generated', '.gitignore'), 'utf8')).toBe('*\n')
  })
})

describe('pruneGenerated', () => {
  it('removes folders older than 14 days only', async () => {
    const root = path.join(tmp, '.conductor', 'generated')
    fs.mkdirSync(path.join(root, 'old'), { recursive: true })
    fs.mkdirSync(path.join(root, 'new'), { recursive: true })
    const old = new Date(Date.now() - 20 * 86400_000)
    fs.utimesSync(path.join(root, 'old'), old, old)
    await pruneGenerated(tmp)
    expect(fs.existsSync(path.join(root, 'old'))).toBe(false)
    expect(fs.existsSync(path.join(root, 'new'))).toBe(true)
  })
})
