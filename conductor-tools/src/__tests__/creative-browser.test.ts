import { describe, it, expect, beforeEach, vi } from 'vitest'

// A plain stub rather than vi.fn: each test sets what the next launches do and reads the calls back.
const calls: unknown[] = []
let outcomes: Array<() => Promise<unknown>> = []
vi.mock('playwright-core', () => ({
  chromium: {
    launch: (options: unknown) => {
      calls.push(options)
      const next = outcomes.shift()
      return next ? next() : Promise.resolve({ close: async () => {} })
    },
  },
}))

import { findBrowserFactory } from '../lib/creative-browser.js'

const fail = (message: string) => () => Promise.reject(new Error(message))

describe('findBrowserFactory', () => {
  beforeEach(() => {
    calls.length = 0
    outcomes = []
  })

  it('launches nothing until the render asks for a browser', async () => {
    const factory = await findBrowserFactory()
    // A spec fetch that fails before this point must not leave a browser running.
    expect(calls).toHaveLength(0)
    await factory()
    expect(calls).toEqual([{ channel: 'chrome' }])
  })

  it('falls through to the next browser when one cannot launch', async () => {
    const browser = { close: async () => {} }
    outcomes = [fail('no chrome'), () => Promise.resolve(browser)]
    const factory = await findBrowserFactory()
    await expect(factory()).resolves.toBe(browser)
    expect(calls).toEqual([{ channel: 'chrome' }, { channel: 'msedge' }])
  })

  it('throws the install hint, with each attempt, when nothing launches', async () => {
    outcomes = [fail('nope'), fail('nope'), fail('nope')]
    const factory = await findBrowserFactory()
    const err = await factory().then(() => null, (e: Error) => e)
    expect(err?.message).toContain('No installed browser could be launched')
    expect(err?.message).toContain('system Edge (channel: msedge): nope')
  })
})
