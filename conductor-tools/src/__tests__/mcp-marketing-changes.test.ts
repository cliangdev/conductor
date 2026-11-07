import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiPost: vi.fn(),
}))

import { apiPost } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import {
  importMarketingChanges,
  parseChangeLog,
  MAX_LISTED_NOTES,
  MAX_RECORDS_PER_REQUEST,
} from '../mcp/tools/marketing-changes.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const ENDPOINT = '/api/v2/projects/proj-1/marketing/changes/import'

let dir: string
beforeEach(async () => {
  vi.clearAllMocks()
  dir = await mkdtemp(join(tmpdir(), 'conductor-changes-'))
})
afterEach(async () => {
  await rm(dir, { recursive: true, force: true })
})

async function logFile(content: string): Promise<string> {
  const filePath = join(dir, 'changes.jsonl')
  await writeFile(filePath, content, 'utf8')
  return filePath
}

const line = (o: Record<string, unknown>) => JSON.stringify(o)
const empty = { created: 0, alreadyImported: 0, skipped: [], refused: [], items: [] }

describe('parseChangeLog', () => {
  it('reads one object per line, ignoring blank lines and CRLF', () => {
    const records = parseChangeLog(`${line({ id: 'a' })}\r\n\r\n   \n${line({ id: 'b' })}\n`)
    expect(records).toEqual([{ id: 'a' }, { id: 'b' }])
  })

  it('names every bad line by its number in the original file, blank lines counted', () => {
    const text = [line({ id: 'a' }), '', '{"id": "b",', line({ id: 'c' }), '[1, 2]', 'null'].join('\n')
    expect(() => parseChangeLog(text)).toThrow(/line 3 is not valid JSON/)
    expect(() => parseChangeLog(text)).toThrow(/line 5 is not a JSON object/)
    expect(() => parseChangeLog(text)).toThrow(/line 6 is not a JSON object/)
    expect(() => parseChangeLog(text)).toThrow(/3 bad lines/)
    expect(() => parseChangeLog(text)).toThrow(/Nothing was sent/)
  })

  it('lists at most ten bad lines and counts the rest', () => {
    const text = Array.from({ length: 12 }, () => 'nope').join('\n')
    expect(() => parseChangeLog(text)).toThrow(/12 bad lines.*and 2 more/)
  })
})

describe('importMarketingChanges', () => {
  it('parses the file, posts the records and returns a compact summary with the display ids', async () => {
    const filePath = await logFile(
      [line({ id: 'a', status: 'live' }), '', line({ id: 'b', status: 'reviewed', verdict: 'hurt' }), line({ id: 'c', status: 'reference' })].join('\n')
    )
    mocked(apiPost).mockResolvedValue({
      created: 2,
      alreadyImported: 0,
      skipped: [{ id: 'c', reason: 'reference record, not a change' }],
      refused: [],
      items: [
        { id: 'w1', displayId: 'RE-10', recordId: 'a', status: 'LIVE' },
        { id: 'w2', displayId: 'RE-11', recordId: 'b', status: 'REVIEWED' },
      ],
    })

    const out = await importMarketingChanges(config, { filePath })

    expect(apiPost).toHaveBeenCalledTimes(1)
    expect(apiPost).toHaveBeenCalledWith(
      ENDPOINT,
      { records: [{ id: 'a', status: 'live' }, { id: 'b', status: 'reviewed', verdict: 'hurt' }, { id: 'c', status: 'reference' }] },
      config
    )
    expect(out).toEqual({
      records: 3,
      created: 2,
      alreadyImported: 0,
      skipped: [{ id: 'c', reason: 'reference record, not a change' }],
      refused: [],
      createdByStatus: { LIVE: 1, REVIEWED: 1 },
      displayIds: ['RE-10', 'RE-11'],
    })
  })

  it('sends the source only when one is given', async () => {
    const filePath = await logFile(line({ id: 'a', status: 'live' }))
    mocked(apiPost).mockResolvedValue(empty)

    await importMarketingChanges(config, { filePath })
    expect(mocked(apiPost).mock.calls[0]![1]).not.toHaveProperty('source')

    await importMarketingChanges(config, { filePath, source: ' other-log ' })
    expect(mocked(apiPost).mock.calls[1]![1]).toMatchObject({ source: 'other-log' })
  })

  it('reports the bad line number and sends nothing', async () => {
    const filePath = await logFile([line({ id: 'a' }), line({ id: 'b' }), '{broken'].join('\n'))

    await expect(importMarketingChanges(config, { filePath })).rejects.toThrow(/line 3 is not valid JSON.*Nothing was sent/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('refuses a file with no records and one it cannot read, without sending', async () => {
    const blank = await logFile('\n  \n')
    await expect(importMarketingChanges(config, { filePath: blank })).rejects.toThrow(/has no records/)
    await expect(importMarketingChanges(config, { filePath: join(dir, 'missing.jsonl') })).rejects.toThrow(/Cannot read/)
    await expect(importMarketingChanges(config, { filePath: ' ' })).rejects.toThrow(/filePath is required/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('names a refused record and its reason, and leaves out the empty parts of a re-run', async () => {
    const filePath = await logFile([line({ id: 'a' }), line({ id: 'b', verdict: 'maybe' })].join('\n'))
    mocked(apiPost).mockResolvedValue({
      created: 0,
      alreadyImported: 1,
      skipped: [],
      refused: [{ id: 'b', reason: "unknown verdict 'maybe' (expected worked, hurt, no effect, expected or inconclusive)" }],
      items: [],
    })

    const out = await importMarketingChanges(config, { filePath })

    expect(out['alreadyImported']).toBe(1)
    expect(out['refused']).toEqual([{ id: 'b', reason: expect.stringContaining("'maybe'") }])
    expect(out).not.toHaveProperty('displayIds')
    expect(out).not.toHaveProperty('createdByStatus')
  })

  it('sends a file longer than the cap in consecutive requests and adds the answers up', async () => {
    const total = MAX_RECORDS_PER_REQUEST + 1
    const filePath = await logFile(Array.from({ length: total }, (_, i) => line({ id: `r${i}`, status: 'live' })).join('\n'))
    mocked(apiPost)
      .mockResolvedValueOnce({
        ...empty,
        created: 500,
        items: Array.from({ length: 500 }, (_, i) => ({ id: `w${i}`, displayId: `RE-${i + 1}`, recordId: `r${i}`, status: 'LIVE' })),
      })
      .mockResolvedValueOnce({ ...empty, created: 1, items: [{ id: 'w500', displayId: 'RE-501', recordId: 'r500', status: 'LIVE' }] })

    const out = await importMarketingChanges(config, { filePath })

    expect(apiPost).toHaveBeenCalledTimes(2)
    expect((mocked(apiPost).mock.calls[0]![1] as { records: unknown[] }).records).toHaveLength(500)
    expect((mocked(apiPost).mock.calls[1]![1] as { records: unknown[] }).records).toHaveLength(1)
    expect(out['records']).toBe(501)
    expect(out['created']).toBe(501)
    expect(out['createdByStatus']).toEqual({ LIVE: 501 })
    expect((out['displayIds'] as string[]).length).toBe(501)
  })

  it('says what was already created when a later request fails, and that a re-run is safe', async () => {
    const filePath = await logFile(Array.from({ length: 501 }, (_, i) => line({ id: `r${i}` })).join('\n'))
    mocked(apiPost)
      .mockResolvedValueOnce({ ...empty, created: 500 })
      .mockRejectedValueOnce(new Error('server unavailable'))

    await expect(importMarketingChanges(config, { filePath })).rejects.toThrow(
      /server unavailable \(500 changes were already created.*importing the file again is safe/
    )
  })

  it('lets a first-request failure through unchanged', async () => {
    const filePath = await logFile(line({ id: 'a' }))
    mocked(apiPost).mockRejectedValueOnce(new Error('Requires the Editor role (or Admin)'))

    await expect(importMarketingChanges(config, { filePath })).rejects.toThrow('Requires the Editor role (or Admin)')
  })

  it('lists at most 50 refused records and still gives the total', async () => {
    const filePath = await logFile(line({ id: 'a' }))
    const refused = Array.from({ length: MAX_LISTED_NOTES + 5 }, (_, i) => ({ id: `r${i}`, reason: 'unknown status' }))
    mocked(apiPost).mockResolvedValue({ ...empty, refused })

    const out = await importMarketingChanges(config, { filePath })

    expect((out['refused'] as unknown[]).length).toBe(MAX_LISTED_NOTES)
    expect(out['refusedTotal']).toBe(MAX_LISTED_NOTES + 5)
    expect(out).not.toHaveProperty('skippedTotal')
  })
})

describe('import_marketing_changes tool definition', () => {
  const tool = TOOLS.find((t) => t.name === 'import_marketing_changes')!

  it('is registered, requires the file path and names its read-back companion', () => {
    expect(tool).toBeDefined()
    expect(tool.inputSchema.required).toEqual(['filePath'])
    expect(Object.keys(tool.inputSchema.properties)).toEqual(['filePath', 'source'])
    expect(tool.description).toContain('get_marketing_report')
    expect(tool.description).toContain('Safe to run again')
  })

  it('keeps its description lean', () => {
    expect(tool.description.length).toBeLessThan(900)
  })
})
