import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { option, selection, tiktokOption } from '@/components/marketing/test-fixtures'
import type { PublishPreflight } from '@/components/marketing/publishReadiness'
import { DestinationsPanel } from './DestinationsPanel'
import { buildRows, summarize, type DestinationActions, type DestinationsState } from './model'
import { EMPTY_DRAFT } from './selectionState'
import { targetKey } from './publishState'
import type { SelectedPublishTarget } from './types'

const WORK_ITEM = 'post-1'
const facebook = option({ platform: 'facebook', connectionId: 'fb', label: 'Rexipe' })
const instagram = option({ platform: 'instagram', connectionId: 'ig', label: '@rexipe.ig' })
const youtube = option({ platform: 'youtube', connectionId: 'yt', label: 'Rexipe Kitchen' })
const tiktok = tiktokOption('rexipeio')

function actions(): DestinationActions {
  return {
    toggle: vi.fn(),
    setFormat: vi.fn(),
    setTikTokOptions: vi.fn(),
    setInstagramOptions: vi.fn(),
    setYouTubeOptions: vi.fn(),
    setContent: vi.fn(),
    retry: vi.fn(async () => {}),
    completeManual: vi.fn(async () => {}),
    setConsent: vi.fn(async () => {}),
    editDestinations: vi.fn(),
  }
}

function state(
  selected: SelectedPublishTarget[],
  overrides: Partial<DestinationsState> & { preflight?: PublishPreflight | null; mode?: 'pick' | 'outcome'; approvedOrLater?: boolean } = {}
): DestinationsState {
  const mode = overrides.mode ?? 'outcome'
  const approvedOrLater = overrides.approvedOrLater ?? mode === 'outcome'
  const rows = buildRows({
    options: [facebook, instagram, youtube, tiktok],
    selected,
    draft: { ...EMPTY_DRAFT, keys: new Set(selected.map((t) => targetKey(t.platform, t.connectionId))) },
    preflight: overrides.preflight ?? null,
    metrics: null,
    consent: null,
    approvedOrLater,
    mode,
    assets: [],
  })
  return {
    mode,
    rows,
    summary: summarize(rows, mode),
    loading: false,
    loadError: null,
    saving: false,
    retrying: false,
    locked: false,
    revertsOnEdit: approvedOrLater,
    editing: false,
    postLevel: {
      blockers: (overrides.preflight?.blockers ?? []).filter((f) => !f.targetId),
      warnings: (overrides.preflight?.warnings ?? []).filter((f) => !f.targetId),
    },
    totals: null,
    observedAt: null,
    unreportedNote: null,
    consentError: null,
    consentSaving: false,
    actions: actions(),
    ...overrides,
  }
}

function renderPanel(s: DestinationsState, extra: Partial<React.ComponentProps<typeof DestinationsPanel>> = {}) {
  return render(
    <DestinationsPanel state={s} assets={[]} caption="Hello" canEdit noun="Post" statusLabel="Published" {...extra} />
  )
}

describe('DestinationsPanel — outcomes', () => {
  const mixed = [
    { ...selection(facebook, WORK_ITEM), state: 'PUBLISHED', permalink: 'https://facebook.com/rexipe/posts/1' },
    { ...selection(instagram, WORK_ITEM), state: 'PUBLISHED' },
    { ...selection(youtube, WORK_ITEM), state: 'FAILED', errorMessage: 'Daily upload limit reached.', errorDetail: '{"code":403}' },
    { ...selection(tiktok, WORK_ITEM), state: 'AWAITING_MANUAL', effectiveCaption: 'Post this', effectiveAssetIds: ['a'] },
  ]

  it('lists what needs you first and says so in the summary', () => {
    renderPanel(state(mixed))
    const rows = screen.getAllByRole('listitem')
    expect(within(rows[0]).getByText('Rexipe Kitchen')).toBeInTheDocument()
    expect(within(rows[1]).getByText('@rexipeio')).toBeInTheDocument() // the TikTok row
    expect(screen.getByTestId('destinations-summary')).toHaveTextContent('2 of 4 published · 2 need you')
  })

  it('offers Retry on the failed row and Mark published on the by-hand one, and opens the form under it', async () => {
    const s = state(mixed)
    renderPanel(s)
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(s.actions.retry).toHaveBeenCalledTimes(1)

    await userEvent.click(screen.getByRole('button', { name: 'Mark published' }))
    expect(screen.getByLabelText('Link to the published post')).toBeInTheDocument()
    expect(screen.getByText('Post this')).toBeInTheDocument()
  })

  it('shows the platform’s own words on the failed row with a Show details disclosure', async () => {
    renderPanel(state(mixed))
    const ytRow = screen.getAllByRole('listitem')[0]
    await userEvent.click(within(ytRow).getByRole('button', { name: 'Show details' }))
    expect(within(ytRow).getByText('Daily upload limit reached.')).toBeInTheDocument()
    await userEvent.click(within(ytRow).getByRole('button', { name: 'Show details' }))
    expect(within(ytRow).getByText('{"code":403}')).toBeInTheDocument()
  })

  it('links a published row to the platform', () => {
    renderPanel(state(mixed))
    const link = screen.getByRole('link', { name: /Open on facebook.com/ })
    expect(link).toHaveAttribute('href', 'https://facebook.com/rexipe/posts/1')
  })

  it('opens one row’s details at a time', async () => {
    renderPanel(state(mixed))
    const [ytRow, ttRow] = screen.getAllByRole('listitem')
    await userEvent.click(within(ytRow).getByRole('button', { name: 'Show details' }))
    expect(within(ytRow).getByRole('button', { name: 'Hide details' })).toBeInTheDocument()
    await userEvent.click(within(ttRow).getByRole('button', { name: 'Show details' }))
    expect(within(ytRow).getByRole('button', { name: 'Show details' })).toBeInTheDocument()
    expect(within(ttRow).getByRole('button', { name: 'Hide details' })).toBeInTheDocument()
  })

  it('offers Retry all only when more than one destination failed', () => {
    renderPanel(state(mixed))
    expect(screen.queryByRole('button', { name: 'Retry all failed' })).not.toBeInTheDocument()
    const twoFailed = mixed.map((t) => (t.platform === 'instagram' ? { ...t, state: 'FAILED' } : t))
    renderPanel(state(twoFailed))
    expect(screen.getByRole('button', { name: 'Retry all failed' })).toBeInTheDocument()
  })

  it('shows the totals and the footnote under the rows', () => {
    renderPanel(
      state(mixed, {
        totals: { observedAt: '2026-09-12T10:00:00Z', views: 3400, likes: 294, comments: 31 },
        observedAt: '2026-09-12T10:00:00Z',
        unreportedNote: "Facebook doesn't report views.",
      })
    )
    const footer = screen.getByTestId('post-performance')
    expect(footer).toHaveTextContent('All destinations')
    expect(footer).toHaveTextContent('3.4K views')
    expect(footer).toHaveTextContent("Facebook doesn't report views.")
  })
})

describe('DestinationsPanel — picking', () => {
  it('renders checkboxes, keeps platform order, and toggles through the actions', async () => {
    const fb = selection(facebook, WORK_ITEM)
    const s = state([fb], { mode: 'pick', approvedOrLater: false })
    renderPanel(s)
    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(4)
    expect(boxes[0]).toBeChecked()
    expect(screen.getByTestId('destinations-summary')).toHaveTextContent('1 selected')
    await userEvent.click(screen.getByRole('checkbox', { name: /@rexipe\.ig/ }))
    expect(s.actions.toggle).toHaveBeenCalledWith(expect.objectContaining({ key: targetKey('instagram', 'ig') }))
  })

  it('shows a row’s own blocker under it and the Post-level one in the header', () => {
    const ig = selection(instagram, WORK_ITEM, 'ig-target')
    const preflight = {
      publishing: true,
      ready: false,
      blockers: [
        { code: 'TARGET_MEDIA_MISSING', message: 'Instagram (@rexipeio) needs at least one image', targetId: 'ig-target' },
        { code: 'NO_MEDIA', message: 'Add at least one image or video', targetId: null },
      ],
      warnings: [],
      consent: { required: false, verdict: 'NOT_REQUIRED' },
      review: { gated: false, assignedReviewers: 0, satisfied: false },
    } as PublishPreflight
    renderPanel(state([ig], { mode: 'pick', approvedOrLater: false, preflight }))
    expect(screen.getByTestId('publish-readiness')).toHaveTextContent('Add at least one image or video')
    const igRow = screen.getByTestId('destination-row-instagram-ig')
    expect(within(igRow).getByText('Instagram (@rexipeio) needs at least one image')).toBeInTheDocument()
  })

  it('offers Review & consent on a TikTok row until the creator has consented', async () => {
    const tt = selection(tiktok, WORK_ITEM)
    const rows = buildRows({
      options: [tiktok],
      selected: [tt],
      draft: { ...EMPTY_DRAFT, keys: new Set([targetKey('tiktok', 'rexipeio')]) },
      preflight: null,
      metrics: null,
      consent: { state: null, given: false },
      approvedOrLater: false,
      mode: 'pick',
      assets: [],
    })
    const s = state([tt], { mode: 'pick', approvedOrLater: false, rows, summary: summarize(rows, 'pick') })
    renderPanel(s)
    await userEvent.click(screen.getByRole('button', { name: 'Review & consent' }))
    expect(screen.getByText(/You are posting to/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('checkbox', { name: /I consent to publishing this post to TikTok/ }))
    expect(s.actions.setConsent).toHaveBeenCalledWith(true)
  })
})

describe('DestinationsPanel — notices', () => {
  it('says it is locked while in review, and offers Edit destinations once approved', async () => {
    const fb = selection(facebook, WORK_ITEM)
    renderPanel(state([fb], { locked: true }), { statusLabel: 'In review' })
    expect(screen.getByText(/Locked while this post is in review/)).toBeInTheDocument()

    const s = state([fb], { revertsOnEdit: true })
    renderPanel(s)
    await userEvent.click(screen.getByRole('button', { name: 'Edit destinations' }))
    expect(s.actions.editDestinations).toHaveBeenCalled()
  })

  it('renders the header slot, the loading skeleton and the load error', () => {
    renderPanel(state([]), { headerSlot: <span>Goes out Friday</span> })
    expect(screen.getByText('Goes out Friday')).toBeInTheDocument()
    renderPanel(state([], { loadError: 'Could not load publishing accounts' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Could not load publishing accounts')
    const { container } = renderPanel(state([], { loading: true }))
    expect(container.querySelector('[data-testid="destinations-summary"]')).toBeNull()
  })
})
