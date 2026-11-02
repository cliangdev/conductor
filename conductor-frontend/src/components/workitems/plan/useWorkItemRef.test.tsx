import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'

const { apiGet, fetchWorkflowView } = vi.hoisted(() => ({ apiGet: vi.fn(), fetchWorkflowView: vi.fn() }))
vi.mock('@/lib/api', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/lib/api')>()), apiGet }))
vi.mock('@/lib/workflows', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/lib/workflows')>()), fetchWorkflowView }))

import { useWorkItemRef } from './useWorkItemRef'

describe('useWorkItemRef', () => {
  beforeEach(() => vi.clearAllMocks())

  it('is ready with no ref when there is no id', () => {
    const { result } = renderHook(() => useWorkItemRef('p1', null, 'tok'))
    expect(result.current).toEqual({ status: 'ready', ref: null })
  })

  it('goes loading to ready with the readable path', async () => {
    apiGet.mockResolvedValue({ id: 'wi', displayId: 'COND-1', title: 'T', workflow: 'ENGINEERING' })
    fetchWorkflowView.mockResolvedValue({ area: 'ENGINEERING', noun: 'PRD' })
    const { result } = renderHook(() => useWorkItemRef('p1', 'wi', 'tok'))
    expect(result.current.status).toBe('loading')
    await waitFor(() => expect(result.current.ref?.href).toBe('/app/projects/p1/engineering/prds/COND-1'))
    expect(result.current.status).toBe('ready')
  })

  it('reports error, distinct from loading, when the fetch fails', async () => {
    apiGet.mockRejectedValue(new Error('boom'))
    const { result } = renderHook(() => useWorkItemRef('p1', 'wi', 'tok'))
    expect(result.current.status).toBe('loading')
    await waitFor(() => expect(result.current.status).toBe('error'))
    expect(result.current.ref).toBeNull()
  })

  it('does not show a previous id result for a new id', async () => {
    apiGet.mockResolvedValueOnce({ id: 'a', displayId: 'A-1', title: 'A' })
    const { result, rerender } = renderHook(({ id }) => useWorkItemRef('p1', id, 'tok'), { initialProps: { id: 'a' } })
    await waitFor(() => expect(result.current.status).toBe('ready'))
    apiGet.mockReturnValue(new Promise(() => {}))
    rerender({ id: 'b' })
    expect(result.current.status).toBe('loading')
  })
})
