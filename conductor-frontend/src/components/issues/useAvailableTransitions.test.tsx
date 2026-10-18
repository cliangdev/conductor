import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'

const apiGet = vi.fn()
vi.mock('@/lib/api', () => ({ apiGet: (...a: unknown[]) => apiGet(...a) }))

import { useAvailableTransitions } from './useAvailableTransitions'

function Consumer({ id }: { id: string }) {
  const t = useAvailableTransitions('p1', 'wi-1', 'DRAFT', 'ADMIN', 'tok')
  return <span data-testid={id}>{t.map((x) => x.toStatus).join(',')}</span>
}

describe('useAvailableTransitions', () => {
  beforeEach(() => {
    apiGet.mockReset()
    apiGet.mockResolvedValue({ workflow: 'W', transitions: [{ toStatus: 'DONE', label: 'Done' }] })
  })

  it('asks the server once when two controls on a page need the same item', async () => {
    render(
      <>
        <Consumer id="a" />
        <Consumer id="b" />
      </>
    )
    await waitFor(() => expect(screen.getByTestId('a')).toHaveTextContent('DONE'))
    expect(screen.getByTestId('b')).toHaveTextContent('DONE')
    expect(apiGet).toHaveBeenCalledTimes(1)
  })
})
