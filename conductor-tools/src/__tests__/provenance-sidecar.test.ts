import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { readProvenanceSidecar, writeProvenanceSidecar } from '../lib/provenance-sidecar.js'

let dir: string
let file: string

beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'cond-sidecar-'))
  file = path.join(dir, 'gen.png')
})
afterEach(() => fs.rmSync(dir, { recursive: true, force: true }))

describe('provenance sidecar', () => {
  it('returns null when there is no sidecar', async () => {
    expect(await readProvenanceSidecar(file)).toBeNull()
  })

  it('round-trips, returning only the provenance fields', async () => {
    await writeProvenanceSidecar(file, { source: 'S', licence: 'L', aiGenerated: true, prompt: 'p', revisedPrompt: 'rp' })
    expect(JSON.parse(fs.readFileSync(`${file}.provenance.json`, 'utf8')).prompt).toBe('p')
    expect(await readProvenanceSidecar(file)).toEqual({ source: 'S', licence: 'L', aiGenerated: true })
  })

  it('throws a clear error on malformed JSON', async () => {
    fs.writeFileSync(`${file}.provenance.json`, '{oops')
    await expect(readProvenanceSidecar(file)).rejects.toThrow(/not valid JSON/)
  })

  it.each([
    [{ source: 1, licence: 'L', aiGenerated: true }, /"source" must be a string/],
    [{ source: 'S', licence: null, aiGenerated: true }, /"licence" must be a string/],
    [{ source: 'S', licence: 'L', aiGenerated: 'yes' }, /"aiGenerated" must be a boolean/],
    [[1], /must be a JSON object/],
  ])('throws on wrong field types %#', async (body, re) => {
    fs.writeFileSync(`${file}.provenance.json`, JSON.stringify(body))
    await expect(readProvenanceSidecar(file)).rejects.toThrow(re)
  })
})
