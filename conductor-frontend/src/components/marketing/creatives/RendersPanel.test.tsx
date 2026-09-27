import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

Object.assign(navigator, {
  clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
})

import { apiGet } from '@/lib/api'
import { RendersPanel } from './RendersPanel'
import type { CreativeRender } from './types'

function render_(id: string, overrides: Partial<CreativeRender> = {}): CreativeRender {
  return {
    id,
    state: 'SUCCEEDED',
    previewOnly: false,
    renderer: 'mcp',
    workflowRunId: null,
    creativeVersion: 3,
    requestedAt: '2026-09-26T10:00:00Z',
    finishedAt: '2026-09-26T10:01:00Z',
    error: null,
    frames: [
      {
        id: 'frame-1',
        placementKey: '9x16',
        platform: 'tiktok',
        sequenceIndex: null,
        url: 'https://storage.example/9x16.png',
        width: 1080,
        height: 1920,
        sizeBytes: 204800,
        warnings: [],
      },
      {
        id: 'frame-2',
        placementKey: '4x5',
        platform: 'instagram',
        sequenceIndex: null,
        url: 'https://storage.example/4x5.png',
        width: 1080,
        height: 1350,
        sizeBytes: 190000,
        warnings: ['Headline overflowed and was clipped'],
      },
    ],
    ...overrides,
  }
}

function setup(props: Partial<React.ComponentProps<typeof RendersPanel>> = {}) {
  return render(
    <RendersPanel
      projectId="proj-1"
      creativeId="cr-1"
      creativeDisplayId="12a"
      creativeVersion={3}
      token="tok"
      registry={{
        layouts: {},
        placements: [
          { key: '9x16', label: '9:16', platform: 'tiktok', width: 1080, height: 1920, default: true },
          { key: '4x5', label: '4:5', platform: 'instagram', width: 1080, height: 1350, default: true },
        ],
      }}
      {...props}
    />,
  )
}

describe('RendersPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows the empty state with a copyable prompt and CLI command when there are no renders', async () => {
    ;(apiGet as Mock).mockResolvedValue([])
    setup()
    await waitFor(() => expect(screen.getByText('No renders yet')).toBeInTheDocument())
    expect(screen.getByText('Render creative 12a with render_creative')).toBeInTheDocument()
    expect(screen.getByText('conductor creative render 12a')).toBeInTheDocument()
  })

  it('renders the latest SUCCEEDED render frames with placement, platform, size and warnings', async () => {
    ;(apiGet as Mock).mockResolvedValue([render_('r-1')])
    setup()
    await waitFor(() => expect(screen.getByTestId('renders-panel')).toBeInTheDocument())

    expect(screen.getByText('9:16')).toBeInTheDocument()
    expect(screen.getByText(/tiktok · 1080×1920/)).toBeInTheDocument()
    expect(screen.getByText('4:5')).toBeInTheDocument()
    expect(screen.getByText(/instagram · 1080×1350/)).toBeInTheDocument()
    expect(screen.getByText('Headline overflowed and was clipped')).toBeInTheDocument()
    expect(screen.getAllByText('Download')).toHaveLength(2)
  })

  it('does not show the sheet frame in the grid', async () => {
    ;(apiGet as Mock).mockResolvedValue([
      render_('r-1', {
        frames: [
          {
            id: 'frame-sheet',
            placementKey: 'sheet',
            platform: null,
            sequenceIndex: null,
            url: 'https://storage.example/sheet.png',
            width: 800,
            height: 600,
            sizeBytes: 1000,
            warnings: [],
          },
        ],
      }),
    ])
    setup()
    await waitFor(() => expect(screen.getByTestId('renders-panel')).toBeInTheDocument())
    expect(screen.queryByText('Download')).not.toBeInTheDocument()
  })

  it('shows the latest full render, skipping a newer preview that holds only a contact sheet', async () => {
    const sheetOnly = render_('r-preview', {
      previewOnly: true,
      frames: [
        {
          id: 'frame-sheet',
          placementKey: 'sheet',
          platform: null,
          sequenceIndex: null,
          url: 'https://storage.example/sheet.jpg',
          width: 1200,
          height: 700,
          sizeBytes: 90000,
          warnings: [],
        },
      ],
    })
    ;(apiGet as Mock).mockResolvedValue([sheetOnly, render_('r-full')])
    const onLatestSucceededChange = vi.fn()
    setup({ onLatestSucceededChange })
    await waitFor(() => expect(screen.getAllByText('Download')).toHaveLength(2))
    expect(onLatestSucceededChange).toHaveBeenLastCalledWith(expect.objectContaining({ id: 'r-full' }))
  })

  it('shows a stale badge when the latest render predates the current Creative version', async () => {
    ;(apiGet as Mock).mockResolvedValue([render_('r-1', { creativeVersion: 2 })])
    setup({ creativeVersion: 5 })
    await waitFor(() => expect(screen.getByTestId('render-stale-badge')).toBeInTheDocument())
    expect(screen.getByTestId('render-stale-badge')).toHaveTextContent('v2')
    expect(screen.getByTestId('render-stale-badge')).toHaveTextContent('v5')
  })

  it('does not show a stale badge when the latest render matches the current version', async () => {
    ;(apiGet as Mock).mockResolvedValue([render_('r-1', { creativeVersion: 3 })])
    setup({ creativeVersion: 3 })
    await waitFor(() => expect(screen.getByTestId('renders-panel')).toBeInTheDocument())
    expect(screen.queryByTestId('render-stale-badge')).not.toBeInTheDocument()
  })

  it('polls every 5s while a render is RUNNING, and stops once none are', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    ;(apiGet as Mock)
      .mockResolvedValueOnce([render_('r-1', { state: 'RUNNING', frames: [] })])
      .mockResolvedValueOnce([render_('r-1', { state: 'SUCCEEDED' })])

    setup()
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1))

    await vi.advanceTimersByTimeAsync(5000)
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(2))

    // The render is now SUCCEEDED — no more RUNNING renders, so polling should stop.
    await vi.advanceTimersByTimeAsync(5000)
    await vi.advanceTimersByTimeAsync(5000)
    expect(apiGet).toHaveBeenCalledTimes(2)
  })

  it('does not poll at all when nothing is RUNNING', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    ;(apiGet as Mock).mockResolvedValue([render_('r-1', { state: 'SUCCEEDED' })])
    setup()
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(1))
    await vi.advanceTimersByTimeAsync(15000)
    expect(apiGet).toHaveBeenCalledTimes(1)
  })

  it('lets the manual Refresh button re-fetch', async () => {
    ;(apiGet as Mock).mockResolvedValue([render_('r-1')])
    setup()
    // Wait for the loaded panel (not just the first call) so the click lands on the rendered button.
    const refresh = await screen.findByRole('button', { name: /refresh/i })
    await waitFor(() => expect(refresh).toBeEnabled())
    expect(apiGet).toHaveBeenCalledTimes(1)
    await userEvent.click(refresh)
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(2))
  })
})
