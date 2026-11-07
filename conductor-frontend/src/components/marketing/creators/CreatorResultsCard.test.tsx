import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1' }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
  usePathname: () => '/app/projects/proj-1/marketing/creators/RE-12',
  useSearchParams: () => new URLSearchParams(),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

let postsHref: string | null = '/app/projects/proj-1/marketing/posts'
vi.mock('@/components/marketing/shared/usePostsHref', () => ({
  usePostsHref: () => postsHref,
}))

import { apiGet } from '@/lib/api'
import { CreatorResultsCard, type CreatorResults } from './CreatorResultsCard'

const RESULTS_URL = '/api/v2/projects/proj-1/marketing/creators/wi-1/results'

function results(overrides: Partial<CreatorResults> = {}): CreatorResults {
  return {
    window: { from: '2026-09-29', to: '2026-10-26' },
    creator: { workItemId: 'wi-1', displayId: 'RE-12', handle: 'jane', status: 'POSTED' },
    postCount: 2,
    posts: [
      { workItemId: 'wi-2', displayId: 'RE-20', title: 'Jane unboxing', status: 'PUBLISHED', views: 1200 },
      { workItemId: 'wi-3', displayId: 'RE-21', title: 'Jane story', status: 'IN_REVIEW', views: 10 },
    ],
    linkDownloads: { tokens: ['jane-a', 'jane-b'], downloads: 13, belowThreshold: false, note: null },
    offerRedemptions: { code: 'JANE10', value: 6, note: null },
    ...overrides,
  }
}

function renderCard(props: Partial<React.ComponentProps<typeof CreatorResultsCard>> = {}) {
  return render(
    <CreatorResultsCard projectId="proj-1" workItemId="wi-1" token="tok" status="POSTED" fields={{ handle: 'jane' }} {...props} />
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  postsHref = '/app/projects/proj-1/marketing/posts'
})

describe('CreatorResultsCard', () => {
  it('reads the creator results for this Work Item', async () => {
    ;(apiGet as Mock).mockResolvedValue(results())

    renderCard()

    await waitFor(() => expect(apiGet).toHaveBeenCalledWith(RESULTS_URL, 'tok'))
  })

  it('shows the posts with their views, the link downloads and the offer code redemptions', async () => {
    ;(apiGet as Mock).mockResolvedValue(results())

    renderCard()

    const posts = await screen.findByTestId('creator-posts')
    expect(within(posts).getByText('Jane unboxing')).toBeInTheDocument()
    expect(within(posts).getByText('RE-20')).toBeInTheDocument()
    expect(within(posts).getByText('1,200')).toBeInTheDocument()
    expect(within(posts).getByText('Jane story')).toBeInTheDocument()
    expect(within(posts).getByText('In Review')).toBeInTheDocument()

    const downloads = screen.getByTestId('creator-downloads')
    expect(within(downloads).getByText('13')).toBeInTheDocument()
    expect(within(downloads).getByText('jane-a')).toBeInTheDocument()
    expect(within(downloads).getByText('jane-b')).toBeInTheDocument()

    const redemptions = screen.getByTestId('creator-redemptions')
    expect(within(redemptions).getByText('JANE10')).toBeInTheDocument()
    expect(within(redemptions).getByText('6')).toBeInTheDocument()
  })

  it('names the window the downloads and redemptions cover', async () => {
    ;(apiGet as Mock).mockResolvedValue(results())

    renderCard()

    expect(await screen.findByText(/Sep 29, 2026/)).toBeInTheDocument()
    expect(screen.getByText(/Oct 26, 2026/)).toBeInTheDocument()
  })

  it('links a post to its page when the Posts list is known and shows plain text when it is not', async () => {
    ;(apiGet as Mock).mockResolvedValue(results())
    const { unmount } = renderCard()

    const link = await screen.findByRole('link', { name: 'Jane unboxing' })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/marketing/posts/RE-20')
    unmount()

    postsHref = null
    renderCard()
    expect(await screen.findByText('Jane unboxing')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Jane unboxing' })).toBeNull()
  })

  it('shows a dash for a post that has not reported views, never 0', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      results({
        postCount: 1,
        posts: [{ workItemId: 'wi-2', displayId: 'RE-20', title: 'Draft post', status: 'DRAFT', views: null }],
      })
    )

    renderCard()

    const posts = await screen.findByTestId('creator-posts')
    const row = within(posts).getByText('Draft post').closest('tr')!
    expect(within(row).getByText('—')).toBeInTheDocument()
    expect(within(row).queryByText('0')).toBeNull()
  })

  it('shows a reported zero as 0', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      results({
        posts: [{ workItemId: 'wi-2', displayId: 'RE-20', title: 'Flop', status: 'PUBLISHED', views: 0 }],
        offerRedemptions: { code: 'JANE10', value: 0, note: null },
      })
    )

    renderCard()

    const posts = await screen.findByTestId('creator-posts')
    expect(within(posts).getByText('0')).toBeInTheDocument()
    expect(within(screen.getByTestId('creator-redemptions')).getByText('0')).toBeInTheDocument()
  })

  it("explains downloads below Apple's threshold in words instead of showing 0", async () => {
    ;(apiGet as Mock).mockResolvedValue(
      results({
        linkDownloads: {
          tokens: ['jane-a'],
          downloads: null,
          belowThreshold: true,
          note: "Below Apple's reporting threshold: Apple sends no row for these links, which is not 0.",
        },
      })
    )

    renderCard()

    const downloads = await screen.findByTestId('creator-downloads')
    expect(within(downloads).getByText(/Below Apple's reporting threshold/)).toBeInTheDocument()
    expect(within(downloads).getByText('—')).toBeInTheDocument()
    expect(within(downloads).queryByText('0')).toBeNull()
  })

  it('says why redemptions are unknown when the product backend does not send them', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      results({
        offerRedemptions: {
          code: 'JANE10',
          value: null,
          note: 'Your product backend does not send offer_redemptions yet',
        },
      })
    )

    renderCard()

    const redemptions = await screen.findByTestId('creator-redemptions')
    expect(within(redemptions).getByText('Your product backend does not send offer_redemptions yet')).toBeInTheDocument()
    expect(within(redemptions).getByText('—')).toBeInTheDocument()
  })

  it('says a creator without a link or an offer code has none yet', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      results({
        creator: { workItemId: 'wi-1', displayId: 'RE-12', handle: null, status: 'PROSPECT' },
        linkDownloads: { tokens: [], downloads: null, belowThreshold: false, note: 'This creator has no campaign link yet.' },
        offerRedemptions: { code: null, value: null, note: 'This creator has no offer code yet. Add one in its Offer code field.' },
      })
    )

    renderCard()

    expect(await screen.findByText('This creator has no campaign link yet.')).toBeInTheDocument()
    expect(screen.getByText(/has no offer code yet/)).toBeInTheDocument()
  })

  it('tells how a post gets onto a creator when there are none', async () => {
    ;(apiGet as Mock).mockResolvedValue(results({ postCount: 0, posts: [] }))

    renderCard()

    const posts = await screen.findByTestId('creator-posts')
    expect(within(posts).getByText(/No posts yet/)).toBeInTheDocument()
    expect(within(posts).getByText(/creator:jane/)).toBeInTheDocument()
  })

  it('says how many more posts there are than listed', async () => {
    ;(apiGet as Mock).mockResolvedValue(results({ postCount: 53 }))

    renderCard()

    expect(await screen.findByText(/Showing 2 of 53 posts/)).toBeInTheDocument()
  })

  it('shows the reason and no numbers when the results cannot be read', async () => {
    ;(apiGet as Mock).mockRejectedValue(new Error('boom'))

    renderCard()

    expect(await screen.findByText('Could not read the creator results')).toBeInTheDocument()
    expect(screen.queryByTestId('creator-posts')).toBeNull()
  })

  it('reads again when the creator moves on or its offer code changes', async () => {
    ;(apiGet as Mock).mockResolvedValue(results())
    const { rerender } = renderCard()
    await screen.findByTestId('creator-posts')
    expect(apiGet).toHaveBeenCalledTimes(1)

    rerender(
      <CreatorResultsCard projectId="proj-1" workItemId="wi-1" token="tok" status="POSTED" fields={{ handle: 'jane' }} />
    )
    expect(apiGet).toHaveBeenCalledTimes(1)

    rerender(
      <CreatorResultsCard
        projectId="proj-1"
        workItemId="wi-1"
        token="tok"
        status="POSTED"
        fields={{ handle: 'jane', offerCode: 'JANE10' }}
      />
    )
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(2))

    rerender(
      <CreatorResultsCard
        projectId="proj-1"
        workItemId="wi-1"
        token="tok"
        status="PAID_OUT"
        fields={{ handle: 'jane', offerCode: 'JANE10' }}
      />
    )
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(3))
  })
})
