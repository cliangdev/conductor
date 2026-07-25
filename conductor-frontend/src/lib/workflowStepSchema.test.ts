import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fetchWorkflowStepSchema } from '@/lib/workflowStepSchema'
import { apiGet } from '@/lib/api'
import type { WorkflowStepSchemaResponse } from '@/types/workflow'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
}))

const SCHEMA: WorkflowStepSchemaResponse = {
  stepTypes: [
    { type: 'http', description: 'Call an HTTP endpoint', fields: [
      { name: 'url', type: 'STRING', required: true, description: 'Request URL' },
    ] },
  ],
  interpolation: { roots: [], functions: [] },
}

describe('fetchWorkflowStepSchema', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('fetches from the step-schema endpoint', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(SCHEMA)

    const result = await fetchWorkflowStepSchema('proj-1', 'tok')

    expect(apiGet).toHaveBeenCalledWith('/api/v1/projects/proj-1/workflows/step-schema', 'tok')
    expect(result).toBe(SCHEMA)
  })

  it('caches per project so a second call does not re-fetch', async () => {
    vi.mocked(apiGet).mockResolvedValueOnce(SCHEMA)

    await fetchWorkflowStepSchema('proj-2', 'tok')
    await fetchWorkflowStepSchema('proj-2', 'tok')

    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('shares one in-flight request across concurrent callers', async () => {
    let resolve!: (v: WorkflowStepSchemaResponse) => void
    vi.mocked(apiGet).mockReturnValueOnce(new Promise((r) => { resolve = r }))

    const p1 = fetchWorkflowStepSchema('proj-3', 'tok')
    const p2 = fetchWorkflowStepSchema('proj-3', 'tok')
    resolve(SCHEMA)

    const [r1, r2] = await Promise.all([p1, p2])
    expect(r1).toBe(SCHEMA)
    expect(r2).toBe(SCHEMA)
    expect(apiGet).toHaveBeenCalledTimes(1)
  })
})
