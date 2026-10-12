import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'

vi.mock('../lib/codex-image.js', () => ({ generateImages: vi.fn() }))

import { generateImages } from '../lib/codex-image.js'
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
    preview: { data: Buffer.from('jpg'), mimeType: 'image/jpeg' },
  })
})
afterEach(() => fs.rmSync(tmp, { recursive: true, force: true }))

describe('generateCreativeImage', () => {
  it('validates the params', async () => {
    await expect(generateCreativeImage({ prompt: '  ' }, { outDir: tmp })).rejects.toThrow(/prompt is required/)
    await expect(generateCreativeImage({ prompt: 'a', aspect: 'wide' }, { outDir: tmp })).rejects.toThrow(/aspect must be/)
    await expect(generateCreativeImage({ prompt: 'a', count: 0 }, { outDir: tmp })).rejects.toThrow(/count must be/)
    await expect(generateCreativeImage({ prompt: 'a', count: 4 }, { outDir: tmp })).rejects.toThrow(/count must be/)
    await expect(generateCreativeImage({ prompt: 'a', referenceImagePaths: [path.join(tmp, 'nope.png')] }, { outDir: tmp })).rejects.toThrow(/Reference image not found/)
    expect(gen).not.toHaveBeenCalled()
  })

  it('returns files, provenance, next step and the preview', async () => {
    const ref = path.join(tmp, 'ref.png')
    fs.writeFileSync(ref, 'x')
    const res = await generateCreativeImage({ prompt: 'a kitchen', count: 2, referenceImagePaths: [ref] }, { outDir: path.join(tmp, 'out') })
    expect(gen).toHaveBeenCalledWith(expect.objectContaining({ prompt: 'a kitchen', aspect: 'portrait', count: 2, referenceImagePaths: [ref], outDir: path.join(tmp, 'out') }))
    expect(res.files[0]).toEqual({ path: '/x/image-1.png', width: 1024, height: 1536 })
    expect(res.provenance.aiGenerated).toBe(true)
    expect(res.revisedPrompt).toBe('rp')
    expect(res.nextStep).toContain('photoPath')
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
