import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const { pushSpy } = vi.hoisted(() => ({ pushSpy: vi.fn() }))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: pushSpy }) }))

const { mockUseDraftPosts } = vi.hoisted(() => ({ mockUseDraftPosts: vi.fn() }))
vi.mock('./useDraftPosts', () => ({ useDraftPosts: mockUseDraftPosts }))

vi.mock('./types', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./types')>()),
  attachCreativeRender: vi.fn(),
}))

import { attachCreativeRender } from './types'
import { UseInPostDialog } from './UseInPostDialog'

const POST_WORKFLOWS = [{ slug: 'MARKETING', area: 'marketing', noun: 'Post' }]
const DRAFT_POSTS = [
  { id: 'wi-1', displayId: 'MK-18', title: 'Week 3 launch', workflowSlug: 'MARKETING', area: 'marketing', noun: 'Post' },
  { id: 'wi-2', displayId: 'MK-19', title: 'Macro lane', workflowSlug: 'MARKETING', area: 'marketing', noun: 'Post' },
]

function setup(overrides: Partial<React.ComponentProps<typeof UseInPostDialog>> = {}) {
  const onOpenChange = vi.fn()
  render(
    <UseInPostDialog
      open
      onOpenChange={onOpenChange}
      projectId="proj-1"
      creativeId="cr-1"
      creativeDisplayId="12a"
      renderId="render-1"
      token="tok"
      {...overrides}
    />,
  )
  return { onOpenChange }
}

describe('UseInPostDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseDraftPosts.mockReturnValue({ posts: DRAFT_POSTS, postWorkflows: POST_WORKFLOWS, error: null, reload: vi.fn() })
  })

  it('lists Draft Posts and a New Post option', () => {
    setup()
    expect(screen.getByText('MK-18')).toBeInTheDocument()
    expect(screen.getByText('Week 3 launch')).toBeInTheDocument()
    expect(screen.getByText('MK-19')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /new post from this creative/i })).toBeInTheDocument()
  })

  it('navigates to compose with creativeId and renderId when New Post is picked', async () => {
    setup()
    await userEvent.click(screen.getByRole('button', { name: /new post from this creative/i }))
    expect(pushSpy).toHaveBeenCalledWith('/app/projects/proj-1/marketing/posts/new?creativeId=cr-1&renderId=render-1')
  })

  it('calls attach with renderId + workItemId when an existing Draft Post is picked, and shows updated/skipped targets', async () => {
    ;(attachCreativeRender as Mock).mockResolvedValue({
      assets: [{ assetId: 'a1', frameId: 'f1', placementKey: '9x16', sequenceIndex: null }],
      targetsUpdated: [{ targetId: 't-tiktok', platform: 'tiktok', assetIds: ['a1'] }],
      targetsSkipped: [{ targetId: 't-fb', platform: 'facebook', reason: 'has custom media' }],
    })
    setup()

    await userEvent.click(screen.getByText('Week 3 launch'))

    expect(attachCreativeRender).toHaveBeenCalledWith('proj-1', 'cr-1', { renderId: 'render-1', workItemId: 'wi-1' }, 'tok')

    await waitFor(() => expect(screen.getByTestId('use-in-post-result')).toBeInTheDocument())
    expect(screen.getByText(/TikTok/)).toBeInTheDocument()
    expect(screen.getByText(/1 frame/)).toBeInTheDocument()
    expect(screen.getByText(/Facebook/)).toBeInTheDocument()
    expect(screen.getByText(/has custom media/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'MK-18' })).toHaveAttribute('href', '/app/projects/proj-1/marketing/posts/MK-18')
  })

  it('shows an error and does not crash when attach fails', async () => {
    ;(attachCreativeRender as Mock).mockRejectedValue(new Error('boom'))
    setup()
    await userEvent.click(screen.getByText('Week 3 launch'))
    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
  })

  it('shows a video icon next to a target whose attached asset came from a video frame', async () => {
    ;(attachCreativeRender as Mock).mockResolvedValue({
      assets: [{ assetId: 'a1', frameId: 'f-video', placementKey: '9x16', sequenceIndex: null }],
      targetsUpdated: [{ targetId: 't-tiktok', platform: 'tiktok', assetIds: ['a1'] }],
      targetsSkipped: [],
    })
    setup({
      renderFrames: [
        {
          id: 'f-video',
          placementKey: '9x16',
          platform: 'tiktok',
          sequenceIndex: null,
          url: 'https://storage.example/9x16.mp4',
          width: 1080,
          height: 1920,
          sizeBytes: 2_000_000,
          warnings: [],
          contentType: 'video/mp4',
        },
      ],
    })

    await userEvent.click(screen.getByText('Week 3 launch'))
    await waitFor(() => expect(screen.getByTestId('use-in-post-result')).toBeInTheDocument())

    expect(screen.getByLabelText('Video')).toBeInTheDocument()
  })

  it('says there are no Draft Posts when the project has none', () => {
    mockUseDraftPosts.mockReturnValue({ posts: [], postWorkflows: [], error: null, reload: vi.fn() })
    setup()
    expect(screen.getByText('No Draft Posts in this project yet.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /new post from this creative/i })).not.toBeInTheDocument()
  })
})
