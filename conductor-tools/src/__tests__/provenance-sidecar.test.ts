import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { PROVENANCE_FIELD_MAX, clipProvenanceField, fitProvenanceForUpload, readProvenanceSidecar, writeProvenanceSidecar } from '../lib/provenance-sidecar.js'

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

  it('reads an over-long source or licence back in full (clipping happens at upload)', async () => {
    const long = 'Publisher cover image via retailer listing: https://images-na.ssl-images-amazon.com/images/P/0062698192.01.L.jpg (Friendsgiving: Celebrate Your Family of Friends, Alexandra Shytsman, William Morrow 2017, ISBN 9780062698193)'
    await writeProvenanceSidecar(file, { source: long, licence: 'x'.repeat(250), aiGenerated: false })
    const read = await readProvenanceSidecar(file)
    expect(read!.source).toBe(long)
    expect(read!.licence).toBe('x'.repeat(250))
  })

  it('clipProvenanceField cuts at a word and ends with an ellipsis', () => {
    const long = 'Publisher cover image via retailer listing: https://images-na.ssl-images-amazon.com/images/P/0062698192.01.L.jpg (Friendsgiving: Celebrate Your Family of Friends, Alexandra Shytsman, William Morrow 2017, ISBN 9780062698193)'
    const clipped = clipProvenanceField(long)
    expect(clipped.length).toBeLessThanOrEqual(PROVENANCE_FIELD_MAX)
    expect(clipped.endsWith('…')).toBe(true)
    expect(long.startsWith(clipped.slice(0, -1))).toBe(true)
    expect(clipProvenanceField('x'.repeat(250)).length).toBe(PROVENANCE_FIELD_MAX)
  })

  it('fitProvenanceForUpload clips and warns per field, naming the file', () => {
    const { fields, warnings } = fitProvenanceForUpload({ source: 'ok', licence: 'L '.repeat(150) }, 'hero')
    expect(fields.source).toBe('ok')
    expect(fields.licence!.length).toBeLessThanOrEqual(PROVENANCE_FIELD_MAX)
    expect(warnings).toHaveLength(1)
    expect(warnings[0]).toMatch(/^hero: licence was 300 characters; shortened to 200 for Conductor.*sidecar/)
  })

  it('leaves a source at the limit untouched', async () => {
    const exact = 'a'.repeat(PROVENANCE_FIELD_MAX)
    await writeProvenanceSidecar(file, { source: exact, licence: 'L', aiGenerated: false })
    expect((await readProvenanceSidecar(file))!.source).toBe(exact)
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
