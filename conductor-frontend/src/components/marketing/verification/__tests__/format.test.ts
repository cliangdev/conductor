import { describe, expect, it } from 'vitest'
import { dayCount, dayRange, formatLedgerValue } from '../format'
import { MAX_READ_DAYS, readToRequest, validateRead, type ReadFormValues } from '../readValidation'

describe('formatLedgerValue', () => {
  it('writes exact counts, never compacted, so a small gap stays visible', () => {
    expect(formatLedgerValue(123456, 'COUNT')).toBe('123,456')
    expect(formatLedgerValue(100040, 'COUNT')).toBe('100,040')
  })

  it('writes money with cents and an unknown value as a dash, never 0', () => {
    expect(formatLedgerValue(162.86, 'CURRENCY')).toBe('$162.86')
    expect(formatLedgerValue(null, 'COUNT')).toBe('—')
    expect(formatLedgerValue(undefined, 'CURRENCY')).toBe('—')
    expect(formatLedgerValue(0, 'COUNT')).toBe('0')
  })

  it('writes percent, rating and position', () => {
    expect(formatLedgerValue(0.0059, 'PERCENT')).toBe('0.59%')
    expect(formatLedgerValue(4.66, 'RATING')).toBe('4.66')
    expect(formatLedgerValue(8.2, 'POSITION')).toBe('8.20')
  })
})

describe('dayRange and dayCount', () => {
  it('counts both ends and writes a single day once', () => {
    expect(dayCount('2026-09-01', '2026-09-28')).toBe(28)
    expect(dayCount('2026-09-03', '2026-09-03')).toBe(1)
    expect(dayRange('2026-09-01', '2026-09-28')).toBe('Sep 1 – Sep 28')
    expect(dayRange('2026-09-03', '2026-09-03')).toBe('Sep 3')
  })

  it('has no range when a day is missing', () => {
    expect(dayRange(null, '2026-09-03')).toBeNull()
    expect(dayCount(undefined, null)).toBeNull()
  })
})

describe('validateRead', () => {
  const now = new Date('2026-10-10T08:00:00Z')
  const ok: ReadFormValues = {
    channel: 'ADS',
    metric: 'installs',
    from: '2026-09-01',
    to: '2026-09-28',
    value: '23',
    sourceName: 'Apple Ads console',
    note: '',
  }

  it('accepts a complete read ending yesterday', () => {
    expect(validateRead(ok, now)).toBeNull()
    expect(validateRead({ ...ok, to: '2026-10-09', from: '2026-10-09' }, now)).toBeNull()
  })

  it('needs a metric and valid days', () => {
    expect(validateRead({ ...ok, metric: '' }, now)).toMatch(/metric/i)
    expect(validateRead({ ...ok, from: '' }, now)).toMatch(/first and last day/)
    expect(validateRead({ ...ok, to: '2026-02-30' }, now)).toMatch(/first and last day/)
  })

  it('refuses a range that starts after it ends', () => {
    expect(validateRead({ ...ok, from: '2026-09-28', to: '2026-09-01' }, now)).toMatch(/on or before/)
  })

  it('refuses a last day of today or later because today is still partial', () => {
    expect(validateRead({ ...ok, to: '2026-10-10' }, now)).toMatch(/yesterday or earlier/)
    expect(validateRead({ ...ok, to: '2026-10-11' }, now)).toMatch(/yesterday or earlier/)
  })

  it('allows at most 92 days, counting both ends', () => {
    expect(MAX_READ_DAYS).toBe(92)
    expect(validateRead({ ...ok, from: '2026-07-02', to: '2026-10-01' }, now)).toBeNull()
    expect(validateRead({ ...ok, from: '2026-07-01', to: '2026-10-01' }, now)).toMatch(/at most 92 days/)
  })

  it('needs a value of zero or more, and a source', () => {
    expect(validateRead({ ...ok, value: '' }, now)).toMatch(/number of zero or more/)
    expect(validateRead({ ...ok, value: 'abc' }, now)).toMatch(/number of zero or more/)
    expect(validateRead({ ...ok, value: '-1' }, now)).toMatch(/number of zero or more/)
    expect(validateRead({ ...ok, value: '0' }, now)).toBeNull()
    expect(validateRead({ ...ok, sourceName: '   ' }, now)).toMatch(/where/i)
    expect(validateRead({ ...ok, sourceName: 'x'.repeat(121) }, now)).toMatch(/120/)
    expect(validateRead({ ...ok, note: 'x'.repeat(501) }, now)).toMatch(/500/)
  })
})

describe('readToRequest', () => {
  const values: ReadFormValues = {
    channel: 'ADS',
    metric: 'installs',
    from: '2026-09-01',
    to: '2026-09-28',
    value: ' 1,204 ',
    sourceName: ' Apple Ads console ',
    note: '  ',
  }

  it('trims, reads a thousands-separated value and leaves a blank note out', () => {
    expect(readToRequest(values, 'COUNT')).toEqual({
      channel: 'ADS',
      metric: 'installs',
      from: '2026-09-01',
      to: '2026-09-28',
      value: 1204,
      sourceName: 'Apple Ads console',
    })
  })

  it('sends a typed percent as a fraction and keeps a note', () => {
    expect(readToRequest({ ...values, value: '4', note: ' checked twice ' }, 'PERCENT')).toMatchObject({
      value: 0.04,
      note: 'checked twice',
    })
  })
})
