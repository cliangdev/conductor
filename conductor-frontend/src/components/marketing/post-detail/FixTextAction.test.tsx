import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

vi.mock('@/lib/api', async (orig) => ({
  ...(await orig<typeof import('@/lib/api')>()),
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiGetPage: vi.fn(),
}))

import { apiGet } from '@/lib/api'
import { FixTextAction } from './FixTextAction'
import { creative } from '@/components/marketing/creatives/slideTextFixtures'

const get = vi.mocked(apiGet)
const open = () => render(<FixTextAction projectId="p" token="t" creativeId="cr-1" postId="w2" onChanged={vi.fn()} />)

beforeEach(() => vi.clearAllMocks())

describe('FixTextAction', () => {
  it('offers Fix text for a slideshow with a stored spec', async () => {
    get.mockResolvedValue(creative())
    open()
    expect(await screen.findByTestId('fix-text')).toBeInTheDocument()
    expect(screen.queryByTestId('fix-text-unavailable')).not.toBeInTheDocument()
  })

  it('offers nothing but a note when the Creative has 1x1 slides', async () => {
    get.mockResolvedValue(creative({ slides: { '9x16': ['s1'], '4x5': ['t1'], '1x1': ['q1'] } }))
    open()
    expect(await screen.findByTestId('fix-text-unavailable')).toHaveTextContent(
      "Text editing isn't available for Creatives with 1x1 slides yet.",
    )
    expect(screen.queryByTestId('fix-text')).not.toBeInTheDocument()
  })

  it('renders nothing without a stored spec', async () => {
    get.mockResolvedValue(creative({ slideshowSpec: null }))
    const { container } = open()
    await vi.waitFor(() => expect(get).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })
})
