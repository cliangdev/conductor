import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

describe('printNextSteps', () => {
  beforeEach(() => {
    vi.spyOn(console, 'log').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('outputs /conductor:prd', async () => {
    const { printNextSteps } = await import('../lib/next-steps.js')
    printNextSteps()

    const output = vi.mocked(console.log).mock.calls.flat().join('\n')
    expect(output).toContain('/conductor:prd')
  })
})
