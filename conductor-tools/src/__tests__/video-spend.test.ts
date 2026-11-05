import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { localDate, record, spentToday } from '../lib/video-spend.js'

let dir: string
let file: string
beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), 'video-spend-'))
  file = path.join(dir, 'video-spend.json')
})
afterEach(() => fs.rmSync(dir, { recursive: true, force: true }))

describe('video spend ledger', () => {
  it('starts at zero and adds up within a day', () => {
    const now = new Date(2026, 10, 4, 9)
    expect(spentToday({ path: file, now })).toBe(0)
    record(0.6, { path: file, now })
    record(0.4, { path: file, now: new Date(2026, 10, 4, 22) })
    expect(spentToday({ path: file, now })).toBe(1)
  })

  it('rolls over to a fresh total on a new local date and keeps only recent days', () => {
    record(3, { path: file, now: new Date(2026, 10, 1, 12) })
    record(1, { path: file, now: new Date(2026, 10, 4, 12) })
    expect(spentToday({ path: file, now: new Date(2026, 10, 4, 13) })).toBe(1)
    expect(spentToday({ path: file, now: new Date(2026, 10, 5, 0, 5) })).toBe(0)
    record(0.5, { path: file, now: new Date(2026, 10, 20, 12) })
    expect(Object.keys(JSON.parse(fs.readFileSync(file, 'utf8')))).toEqual([localDate(new Date(2026, 10, 20))])
  })

  it('treats a corrupt file as empty', () => {
    fs.writeFileSync(file, 'not json')
    expect(spentToday({ path: file })).toBe(0)
    expect(record(1, { path: file })).toBe(1)
  })
})
