import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPut: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => (err as { detail?: string })?.detail ?? fallback,
}))
vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1', name: 'Bryan' }, accessToken: 'tok', loading: false }),
}))

import { apiGet, apiPut } from '@/lib/api'
import { ConsentPageView } from './ConsentPageView'
import { PendingConsentNotice } from './PendingConsentNotice'
import type { ConsentPageCreator, ConsentPagePost, TikTokConsentPage } from './api'

const CREATOR: ConsentPageCreator = {
  connectionId: 'conn-1',
  nickname: 'rexipe',
  avatarUrl: null,
  privacyLevelOptions: ['PUBLIC_TO_EVERYONE', 'SELF_ONLY'],
  commentDisabled: false,
  duetDisabled: true,
  stitchDisabled: false,
  canPost: true,
  reconnectRequired: false,
}

function post(n: number, overrides: Partial<ConsentPagePost> = {}): ConsentPagePost {
  return {
    workItemId: `wi-${n}`,
    displayId: `RE-${n}`,
    title: `Post ${n}`,
    caption: `Caption ${n}`,
    verdict: 'NEVER_GIVEN',
    etag: `"etag-${n}"`,
    targets: [{ targetId: `t-${n}`, connectionId: 'conn-1', effectiveAssetIds: ['a-1'] }],
    ...overrides,
  }
}

const VIDEO_ASSET = { id: 'a-1', label: 'clip', type: 'tiktok_post', contentType: 'video/mp4', previewUrl: 'https://cdn.test/clip.mp4' }

let pageData: TikTokConsentPage
let pageError: unknown = null

beforeEach(() => {
  vi.clearAllMocks()
  pageError = null
  pageData = { requestId: 'req-1', posts: [post(1), post(2)], creators: [CREATOR] }
  ;(apiGet as Mock).mockImplementation(async (path: string) => {
    if (path.includes('/assets')) return [VIDEO_ASSET]
    if (pageError) throw pageError
    return pageData
  })
  ;(apiPut as Mock).mockResolvedValue({
    workItemId: 'wi-1',
    required: true,
    valid: true,
    verdict: 'VALID',
    consentedAt: '2026-10-19T10:00:00Z',
    consentedByName: 'Bryan',
  })
})

const card = (displayId: string) => screen.getByTestId(`consent-card-${displayId}`)

describe('ConsentPageView', () => {
  it('reads the request-scoped page once and states progress', async () => {
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    expect(await screen.findByText('0 of 2 consented')).toBeInTheDocument()
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/p1/tiktok-consent?request=req-1', 'tok')
    // The account is named once, as TikTok shows it — a nickname is not a handle, so no "@" prefix.
    expect(within(card('RE-1')).getAllByText(/posting to/i)).toHaveLength(1)
    expect(card('RE-1')).toHaveTextContent('rexipe')
    expect(card('RE-1')).not.toHaveTextContent('@rexipe')
  })

  it('disables Confirm when commercial content is disclosed without Your Brand or Branded Content', async () => {
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    await screen.findByText('0 of 2 consented')
    const c = within(card('RE-1'))
    await userEvent.selectOptions(c.getByLabelText(/who can view this/i), 'PUBLIC_TO_EVERYONE')
    expect(c.getByRole('button', { name: /confirm/i })).toBeEnabled()
    await userEvent.click(c.getByRole('switch', { name: /disclose commercial content/i }))
    expect(c.getByRole('button', { name: /confirm/i })).toBeDisabled()
    expect(c.getAllByText('Choose Your Brand or Branded Content')).toHaveLength(1)
    await userEvent.click(c.getByRole('switch', { name: 'Your Brand' }))
    expect(c.getByRole('button', { name: /confirm/i })).toBeEnabled()
  })

  it('shows the branded-content-but-private error once', async () => {
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    await screen.findByText('0 of 2 consented')
    const c = within(card('RE-1'))
    await userEvent.selectOptions(c.getByLabelText(/who can view this/i), 'SELF_ONLY')
    await userEvent.click(c.getByRole('switch', { name: /disclose commercial content/i }))
    await userEvent.click(c.getByRole('switch', { name: 'Branded Content' }))
    expect(c.getAllByText(/can.t be posted privately/i)).toHaveLength(1)
    expect(c.getByRole('button', { name: /confirm/i })).toBeDisabled()
  })

  it('lists allowed interactions as well as the ones that are off', async () => {
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    await screen.findByText('0 of 2 consented')
    const c = within(card('RE-1'))
    await waitFor(() => expect(c.getByRole('switch', { name: 'Duet' })).toBeInTheDocument())
    await userEvent.click(c.getByRole('switch', { name: 'Comment' }))
    expect(c.getByText(/Comments on · Duet off · Stitch off/)).toBeInTheDocument()
  })

  it('previews several photos as a compact strip', async () => {
    ;(apiGet as Mock).mockImplementation(async (path: string) => {
      if (path.includes('/assets'))
        return [1, 2, 3].map((n) => ({ id: `a-${n}`, label: `p${n}`, type: 'tiktok_post', contentType: 'image/png', previewUrl: `https://cdn.test/${n}.png` }))
      return { ...pageData, posts: [post(1, { targets: [{ targetId: 't', connectionId: 'conn-1', effectiveAssetIds: ['a-1', 'a-2', 'a-3'] }] })] }
    })
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    expect(await within(await screen.findByTestId('consent-card-RE-1')).findByRole('list', { name: 'Post images' })).toBeInTheDocument()
    expect(within(card('RE-1')).getByLabelText('Who can view this post')).toBeInTheDocument()
  })

  it('keeps Confirm disabled until a privacy level is picked, with every switch off', async () => {
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    await screen.findByText('0 of 2 consented')
    const c = within(card('RE-1'))
    const confirm = c.getByRole('button', { name: /confirm/i })
    expect(confirm).toBeDisabled()
    expect(c.getByLabelText(/who can view this/i)).toHaveValue('')
    for (const name of ['Comment', 'Stitch']) {
      expect(c.getByRole('switch', { name })).not.toBeChecked()
    }
    // The creator disabled Duet in TikTok: greyed and off.
    await waitFor(() => expect(c.getByRole('switch', { name: 'Duet' })).toBeDisabled())
    expect(c.getByRole('switch', { name: 'Duet' })).not.toBeChecked()

    await userEvent.selectOptions(c.getByLabelText(/who can view this video/i), 'PUBLIC_TO_EVERYONE')
    expect(confirm).toBeEnabled()
  })

  it('links TikTok’s declaration and switches to the branded wording when branded content is on', async () => {
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    await screen.findByText('0 of 2 consented')
    const c = within(card('RE-1'))
    expect(c.getByRole('link', { name: 'Music Usage Confirmation' })).toHaveAttribute(
      'href',
      'https://www.tiktok.com/legal/page/global/music-usage-confirmation/en'
    )
    expect(c.queryByRole('link', { name: 'Branded Content Policy' })).not.toBeInTheDocument()

    await userEvent.click(c.getByRole('switch', { name: /disclose commercial content/i }))
    await userEvent.click(c.getByRole('switch', { name: 'Branded Content' }))
    const link = c.getByRole('link', { name: 'Branded Content Policy' })
    expect(link).toHaveAttribute('href', 'https://www.tiktok.com/legal/page/global/bc-policy/en')
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'))
  })

  it('confirms each card on its own with the six choices, If-Match and the bulk channel', async () => {
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    await screen.findByText('0 of 2 consented')
    const c = within(card('RE-1'))
    await userEvent.selectOptions(c.getByLabelText(/who can view this video/i), 'SELF_ONLY')
    await userEvent.click(c.getByRole('switch', { name: 'Comment' }))
    await userEvent.click(c.getByRole('button', { name: /confirm/i }))

    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1))
    const [path, body, token, headers] = (apiPut as Mock).mock.calls[0]
    expect(path).toBe('/api/v2/projects/p1/work-items/wi-1/tiktok-consent')
    expect(token).toBe('tok')
    expect(headers).toEqual({ 'If-Match': '"etag-1"' })
    expect(body).toEqual({
      targets: [
        {
          connectionId: 'conn-1',
          privacyLevel: 'SELF_ONLY',
          disableComment: false,
          disableDuet: true,
          disableStitch: true,
          brandContentToggle: false,
          brandOrganicToggle: false,
        },
      ],
      channel: 'BULK_PAGE',
      requestId: 'req-1',
      declarationVersion: 'tiktok-csg-2026-10',
    })
    expect(await screen.findByText('1 of 2 consented')).toBeInTheDocument()
    expect(card('RE-1')).toHaveTextContent(/Consented by you/)
    // The other card is untouched and still needs its own choice.
    expect(within(card('RE-2')).getByRole('button', { name: /confirm/i })).toBeDisabled()
  })

  it('reloads just the card on a 412 and says the post changed', async () => {
    ;(apiPut as Mock).mockRejectedValueOnce({ status: 412, detail: 'This post changed — review it again' })
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    await screen.findByText('0 of 2 consented')
    const c = within(card('RE-1'))
    await userEvent.selectOptions(c.getByLabelText(/who can view this video/i), 'PUBLIC_TO_EVERYONE')
    pageData = { ...pageData, posts: [post(1, { etag: '"etag-1b"' }), post(2)] }
    await userEvent.click(c.getByRole('button', { name: /confirm/i }))

    expect(await within(card('RE-1')).findByText(/your choices were cleared/i)).toBeInTheDocument()
    // Reloaded: the choice was reset, the new etag is used on the next Confirm.
    const again = within(card('RE-1'))
    expect(again.getByLabelText(/who can view this video/i)).toHaveValue('')
    await userEvent.selectOptions(again.getByLabelText(/who can view this video/i), 'PUBLIC_TO_EVERYONE')
    await userEvent.click(again.getByRole('button', { name: /confirm/i }))
    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(2))
    expect((apiPut as Mock).mock.calls[1][3]).toEqual({ 'If-Match': '"etag-1b"' })
  })

  it('shows a reason instead of Confirm when the creator cannot post', async () => {
    pageData = {
      ...pageData,
      creators: [{ ...CREATOR, canPost: false, cannotPostReason: 'TikTok has paused posting for this account' }],
    }
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    await screen.findByText('0 of 2 consented')
    expect(within(card('RE-1')).queryByRole('button', { name: /confirm/i })).not.toBeInTheDocument()
    expect(within(card('RE-1')).getByText(/paused posting/)).toBeInTheDocument()
  })

  it('collapses a post that already has valid consent', async () => {
    pageData = {
      ...pageData,
      posts: [post(1, { verdict: 'VALID', consentedAt: '2026-10-19T10:00:00Z', consentedByUserId: 'user-2', consentedByName: 'Rita' }), post(2)],
    }
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    expect(await screen.findByText('1 of 2 consented')).toBeInTheDocument()
    expect(card('RE-1')).toHaveTextContent('Consented by Rita')
    expect(within(card('RE-1')).queryByRole('button', { name: /confirm/i })).not.toBeInTheDocument()
  })

  it('says so when nothing is pending', async () => {
    pageData = { posts: [], creators: [] }
    render(<ConsentPageView projectId="p1" />)
    expect(await screen.findByText(/no posts are waiting for TikTok consent/i)).toBeInTheDocument()
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/p1/tiktok-consent', 'tok')
  })

  it('names who the link was created for on a 403', async () => {
    pageError = { status: 403, detail: 'This link was created for Rita' }
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    expect(await screen.findByText('This link was created for Rita')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /every post waiting/i })).toHaveAttribute(
      'href',
      '/app/projects/p1/marketing/consent'
    )
  })

  it('tells the person to ask for a new link on a 410', async () => {
    pageError = { status: 410, detail: 'expired' }
    render(<ConsentPageView projectId="p1" requestId="req-1" />)
    expect(await screen.findByText('This link expired — ask the agent for a new one.')).toBeInTheDocument()
  })
})

describe('PendingConsentNotice', () => {
  it('links to the consent page with the count of posts not yet consented', async () => {
    ;(apiGet as Mock).mockImplementation(async (path: string) => {
      expect(path).toBe('/api/v2/projects/p1/tiktok-consent/summary')
      return { pendingCount: 2 }
    })
    render(<PendingConsentNotice projectId="p1" token="tok" />)
    const link = await screen.findByRole('link', { name: '2 posts need TikTok consent' })
    expect(link).toHaveAttribute('href', '/app/projects/p1/marketing/consent')
  })

  it('renders nothing when the read fails', async () => {
    pageError = { status: 403 }
    const { container } = render(<PendingConsentNotice projectId="p1" token="tok" />)
    await waitFor(() => expect(apiGet).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })
})
