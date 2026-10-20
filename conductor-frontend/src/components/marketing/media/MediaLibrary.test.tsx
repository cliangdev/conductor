import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiGetPage: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => (err as { detail?: string })?.detail ?? fallback,
}))
vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, accessToken: 'tok', loading: false }),
}))
const { pushSpy } = vi.hoisted(() => ({ pushSpy: vi.fn() }))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: pushSpy }) }))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => true,
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: () => true, refresh: vi.fn() }),
}))

const { markPageReady } = vi.hoisted(() => ({ markPageReady: vi.fn() }))
vi.mock('@/lib/perf', () => ({ markPageReady }))
import { apiDelete, apiGet, apiGetPage, apiPatch, apiPost } from '@/lib/api'
vi.mock('@/lib/workflows', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/workflows')>()),
  useSidebarWorkNav: () => ({
    entries: [{ slug: 'MARKETING', label: 'Posts', noun: 'Post', area: 'MARKETING', createdAt: '' }],
    loading: false,
  }),
}))

import { MediaLibrary } from './MediaLibrary'
import { photo, slides } from './testFixtures'
import type { CreativePhoto } from '@/components/marketing/creatives/types'

/** A fake of the paged list endpoint: server-side filters, newest first (createdAt, id desc), keyset cursor. */
function load(rows: CreativePhoto[]) {
  ;(apiGet as Mock).mockImplementation(async (path: string) => {
    if (path.endsWith('/usage')) return usage
    return rows
  })
  ;(apiGetPage as Mock).mockImplementation(async (path: string) => {
    const q = new URL(`http://x${path}`).searchParams
    const kinds = q.get('mediaKind')?.split(',')
    const needle = q.get('q')?.toLowerCase()
    let list = rows
      .filter((r) => q.get('includeBlocked') === 'true' || !r.blocked)
      .filter((r) => !kinds || kinds.includes(r.mediaKind ?? 'IMAGE'))
      .filter((r) => !needle || (r.label ?? '').toLowerCase().includes(needle))
      .filter((r) => q.get('unchecked') !== 'true' || !r.checked)
      .filter((r) => q.get('aiGenerated') !== 'true' || r.aiGenerated)
      .filter((r) => !q.get('labelPrefix') || r.label?.startsWith(q.get('labelPrefix')!))
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id))
    const cursor = q.get('cursor')
    if (cursor) list = list.slice(list.findIndex((r) => r.id === cursor.replace('after:', '')) + 1)
    const limit = q.get('limit') ? Number(q.get('limit')) : null
    const page = limit == null ? list : list.slice(0, limit)
    const next = limit != null && list.length > limit ? `after:${page[page.length - 1].id}` : null
    return { data: page, nextCursor: next }
  })
}

const listPaths = () => (apiGetPage as Mock).mock.calls.map((c) => c[0] as string)
const lastListPath = () => listPaths()[listPaths().length - 1]

const tileIds = () =>
  within(screen.getByTestId('media-grid'))
    .getAllByRole('listitem')
    .map((li) => li.getAttribute('data-testid')!.replace('media-tile-', ''))

beforeEach(() => {
  vi.clearAllMocks()
  usage = { creatives: [], posts: [] }
  ;(apiPatch as Mock).mockImplementation(async (path: string, body: object) => {
    const id = path.split('/').pop()
    return { ...(all.find((p) => p.id === id) as CreativePhoto), ...body }
  })
})
let all: CreativePhoto[] = []
let usage: unknown = { creatives: [], posts: [] }
function setup(rows: CreativePhoto[]) {
  all = rows
  load(rows)
  return render(<MediaLibrary projectId="proj-1" />)
}

describe('MediaLibrary grid', () => {
  it('shows a skeleton, then tiles newest first', async () => {
    setup([
      photo('a', 'slide 10', 1, 1, { createdAt: '2026-01-01T00:00:00Z' }),
      photo('b', 'slide 2', 1, 1, { createdAt: '2026-01-03T00:00:00Z' }),
      photo('c', 'slide 1', 1, 1, { createdAt: '2026-01-02T00:00:00Z' }),
    ])
    expect(screen.getByTestId('media-grid-skeleton')).toBeInTheDocument()
    await screen.findByTestId('media-grid')
    expect(tileIds()).toEqual(['b', 'c', 'a'])
  })

  it('sorts by name naturally on request, loading the whole library unpaged', async () => {
    const user = userEvent.setup()
    setup([photo('a', 'slide 10'), photo('b', 'slide 2'), photo('c', 'slide 1')])
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('button', { name: 'Name' }))
    await waitFor(() => expect(tileIds()).toEqual(['c', 'b', 'a']))
    expect(lastListPath()).not.toContain('limit=')
  })

  it('filters by kind tab and shows the empty state', async () => {
    const user = userEvent.setup()
    setup([photo('i', 'pic'), photo('v', 'clip', 1080, 1920, { mediaKind: 'VIDEO', durationSeconds: 8 })])
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('tab', { name: 'Video' }))
    expect(tileIds()).toEqual(['v'])
    await user.click(screen.getByRole('tab', { name: 'Audio' }))
    expect(screen.getByText('Nothing matches')).toBeInTheDocument()
  })

  it('searches by substring, including labels that also start with the query', async () => {
    const user = userEvent.setup()
    // "slide 03 cover" starts with the query, so a server prefix search returns it alone; the
    // mid-label match "01-import 4x5 slide 03" must still show.
    setup([...slides('4x5', 4), photo('cover', 'slide 03 cover')])
    await screen.findByTestId('media-grid')
    await user.type(screen.getByLabelText('Search by label'), 'slide 03')
    await new Promise((r) => setTimeout(r, 400))
    await waitFor(() => expect(tileIds().sort()).toEqual(['4x5-3', 'cover']))
  })

  it('filters Unchecked and AI-generated', async () => {
    const user = userEvent.setup()
    setup([photo('a', 'a', 1, 1, { checked: true }), photo('b', 'b', 1, 1, { aiGenerated: true })])
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('button', { name: 'Unchecked' }))
    expect(tileIds()).toEqual(['b'])
    await user.click(screen.getByRole('button', { name: 'AI-generated' }))
    expect(tileIds()).toEqual(['b'])
  })
})

describe('detail panel', () => {
  it('saves an edited label via patch', async () => {
    const user = userEvent.setup()
    setup([photo('a', 'old name')])
    await user.click(await screen.findByRole('button', { name: 'Open old name' }))
    const input = await screen.findByLabelText('Label')
    expect(screen.getByText('8/200')).toBeInTheDocument()
    await user.clear(input)
    await user.type(input, 'new name')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(expect.stringContaining('/photos/a'), { label: 'new name', source: '', licence: '' }, 'tok'),
    )
  })

  it('shows a 409 delete error verbatim', async () => {
    const user = userEvent.setup()
    ;(apiDelete as Mock).mockRejectedValue({ status: 409, detail: 'Used by Creative 12a' })
    setup([photo('a', 'in use')])
    await user.click(await screen.findByRole('button', { name: 'Open in use' }))
    await user.click(await screen.findByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog').catch(() => screen.getByRole('dialog', { name: /Delete this media/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('Used by Creative 12a')).toBeInTheDocument()
  })

  it('blocks with a reason', async () => {
    const user = userEvent.setup()
    setup([photo('a', 'pic')])
    await user.click(await screen.findByRole('button', { name: 'Open pic' }))
    const block = await screen.findByRole('button', { name: 'Block' })
    expect(block).toBeDisabled()
    await user.type(screen.getByLabelText('Block reason'), 'wrong brand')
    await user.click(block)
    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(expect.stringContaining('/photos/a'), { blocked: true, blockedReason: 'wrong brand' }, 'tok'),
    )
  })
})

describe('selection and bulk actions', () => {
  it('selects a shift range and marks each checked', async () => {
    const user = userEvent.setup()
    setup(slides('4x5', 5))
    await screen.findByTestId('media-grid')
    await user.click(screen.getByLabelText('Select 01-import 4x5 slide 02'))
    await user.keyboard('{Shift>}')
    await user.click(screen.getByLabelText('Select 01-import 4x5 slide 04'))
    await user.keyboard('{/Shift}')
    expect(screen.getByText('3 selected')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Mark checked' }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(3))
    expect((apiPatch as Mock).mock.calls.map((c) => c[0].split('/').pop()).sort()).toEqual(['4x5-2', '4x5-3', '4x5-4'])
    expect((apiPatch as Mock).mock.calls[0][1]).toEqual({ checked: true })
  })

  it('shift-range uses the current view after a filter change', async () => {
    const user = userEvent.setup()
    const rows = slides('4x5', 6).map((r, i) => (i < 2 ? { ...r, checked: true } : r))
    setup(rows)
    await screen.findByTestId('media-grid')
    await user.click(screen.getByLabelText('Select 01-import 4x5 slide 03'))
    await user.click(screen.getByRole('button', { name: 'Unchecked' }))
    await user.keyboard('{Shift>}')
    await user.click(screen.getByLabelText('Select 01-import 4x5 slide 06'))
    await user.keyboard('{/Shift}')
    expect(screen.getByText('4 selected')).toBeInTheDocument()
    expect(screen.getByLabelText('Select 01-import 4x5 slide 04')).toBeChecked()
  })

  it('acts only on the selected items the filters show, and says how many are hidden', async () => {
    const user = userEvent.setup()
    setup(slides('4x5', 3))
    await screen.findByTestId('media-grid')
    await user.click(screen.getByLabelText('Select 01-import 4x5 slide 01'))
    await user.keyboard('{Shift>}')
    await user.click(screen.getByLabelText('Select 01-import 4x5 slide 03'))
    await user.keyboard('{/Shift}')
    await user.type(screen.getByLabelText('Search by label'), 'slide 01')
    await waitFor(() => expect(tileIds()).toEqual(['4x5-1']))
    expect(screen.getByText('1 selected')).toBeInTheDocument()
    expect(screen.getByText('2 hidden by filters')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Mark checked' }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect((apiPatch as Mock).mock.calls[0][0]).toContain('/4x5-1')
  })

  it('Select set picks both aspects of a set', async () => {
    const user = userEvent.setup()
    setup([...slides('4x5', 3), ...slides('9x16', 3), photo('o', 'other thing')])
    await screen.findByTestId('media-grid')
    await user.click(screen.getByLabelText('Select 01-import 4x5 slide 01'))
    await user.click(screen.getByRole('button', { name: 'Select set' }))
    expect(screen.getByText('6 selected')).toBeInTheDocument()
  })
})

describe('Make carousel', () => {
  async function selectAll(user: ReturnType<typeof userEvent.setup>) {
    await screen.findByTestId('media-grid')
    const boxes = screen.getAllByRole('checkbox')
    await user.click(boxes[0])
    await user.keyboard('{Shift>}')
    await user.click(boxes[boxes.length - 1])
    await user.keyboard('{/Shift}')
  }

  it('groups 7 x 4x5 and 7 x 9x16 and creates the carousel', async () => {
    const user = userEvent.setup()
    ;(apiPost as Mock).mockResolvedValue({ id: 'cr-9' })
    setup([...slides('9x16', 7), ...slides('4x5', 7)])
    await selectAll(user)
    await user.click(screen.getByRole('button', { name: 'Make carousel' }))
    const g45 = await screen.findByTestId('carousel-group-4x5')
    expect(within(g45).getByText(/4:5 · 7 slides/)).toBeInTheDocument()
    expect(screen.getByTestId('carousel-group-9x16')).toBeInTheDocument()
    expect(screen.getByLabelText('Name')).toHaveValue('01-import')
    await user.click(screen.getByRole('button', { name: 'Create carousel' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [, body] = (apiPost as Mock).mock.calls[0]
    expect(body).toEqual({
      kind: 'CAROUSEL',
      name: '01-import',
      slides: {
        '4x5': slides('4x5', 7).map((s) => s.id),
        '9x16': slides('9x16', 7).map((s) => s.id),
      },
      placements: ['4x5', '9x16'],
    })
    expect(pushSpy).toHaveBeenCalledWith('/app/projects/proj-1/marketing/creatives/cr-9')
  })

  it('shows server violations from a 422', async () => {
    const user = userEvent.setup()
    ;(apiPost as Mock).mockRejectedValue({ status: 422, detail: 'Validation failed', violations: [{ field: 'slides', ruleId: 'mediaBlocked', message: 'slide 2 is blocked' }] })
    setup(slides('4x5', 2))
    await selectAll(user)
    await user.click(screen.getByRole('button', { name: 'Make carousel' }))
    await user.click(await screen.findByRole('button', { name: 'Create carousel' }))
    expect(await screen.findByText('slide 2 is blocked')).toBeInTheDocument()
  })

  it('is disabled when a video is selected', async () => {
    const user = userEvent.setup()
    setup([photo('i', 'a pic'), photo('v', 'clip', 1080, 1920, { mediaKind: 'VIDEO', durationSeconds: 5 })])
    await selectAll(user)
    expect(screen.getByRole('button', { name: 'Make carousel' })).toBeDisabled()
    expect(screen.getByText(/images only/)).toBeInTheDocument()
  })

  it('refuses an off-aspect image', async () => {
    const user = userEvent.setup()
    setup([photo('w', 'landscape', 1920, 1080)])
    await selectAll(user)
    await user.click(screen.getByRole('button', { name: 'Make carousel' }))
    expect(await screen.findByText(/landscape \(1920×1080\)/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create carousel' })).toBeDisabled()
  })

  it('refuses 11 x 4x5', async () => {
    const user = userEvent.setup()
    setup(slides('4x5', 11))
    await selectAll(user)
    await user.click(screen.getByRole('button', { name: 'Make carousel' }))
    expect(await screen.findByText(/4:5 has 11 slides — the limit is 10/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create carousel' })).toBeDisabled()
  })
})

describe('MediaLibrary usage', () => {
  const rows = () => [
    photo('posted', 'on a post', 1080, 1350, { postCount: 2, creativeCount: 0 }),
    photo('made', 'in a creative', 1080, 1350, { postCount: 0, creativeCount: 1 }),
    photo('free', 'unused one', 1080, 1350, { postCount: 0, creativeCount: 0 }),
    photo('legacy', 'no counts'),
  ]

  it('shows "N posts" on a tile that is on posts, and nothing otherwise', async () => {
    setup([photo('one', 'single', 1080, 1350, { postCount: 1 }), ...rows()])
    await screen.findByTestId('media-grid')
    expect(within(screen.getByTestId('media-tile-posted')).getByText('2 posts')).toBeInTheDocument()
    expect(within(screen.getByTestId('media-tile-one')).getByText('1 post')).toBeInTheDocument()
    expect(within(screen.getByTestId('media-tile-free')).queryByText(/post/)).not.toBeInTheDocument()
  })

  it('"On posts" keeps only media with posts', async () => {
    const user = userEvent.setup()
    setup(rows())
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('button', { name: 'On posts' }))
    expect(tileIds()).toEqual(['posted'])
  })

  it('"Unused" keeps only media with no posts and no creatives', async () => {
    const user = userEvent.setup()
    setup(rows())
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('button', { name: 'Unused' }))
    expect(tileIds().sort()).toEqual(['free', 'legacy'])
  })

  it('lists where media is used, linking Creatives and Posts', async () => {
    const user = userEvent.setup()
    usage = {
      creatives: [{ id: 'cr-9', displayId: '12a', name: 'Paste a link', state: 'READY' }],
      posts: [{ workItemId: 'wi-1', displayId: 'MK-4', title: 'Save recipes', status: 'DRAFT', workflow: 'MARKETING' }],
    }
    setup(rows())
    await user.click(await screen.findByRole('button', { name: 'Open on a post' }))
    expect(await screen.findByRole('heading', { name: 'Used in' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /12a Paste a link/ })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/marketing/creatives/cr-9',
    )
    expect(screen.getByRole('link', { name: /MK-4 Save recipes/ })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/marketing/posts/MK-4',
    )
  })

  it('shows the server message naming the Posts when a delete is refused', async () => {
    const user = userEvent.setup()
    const detail = 'In use on MK-4 "Save recipes" — remove it from those posts first.'
    ;(apiDelete as Mock).mockRejectedValue({ status: 409, detail })
    setup(rows())
    await user.click(await screen.findByRole('button', { name: 'Open on a post' }))
    await user.click(await screen.findByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog').catch(() => screen.getByRole('dialog', { name: /Delete this media/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText(detail)).toBeInTheDocument()
  })
})

describe('MediaLibrary paging', () => {
  const many = (n: number) =>
    Array.from({ length: n }, (_, i) =>
      photo(`m${String(i).padStart(3, '0')}`, `item ${i}`, 1, 1, {
        createdAt: new Date(Date.UTC(2026, 0, 1, 0, 0, n - i)).toISOString(),
      }),
    )

  it('renders the first page, marks the page ready, then loads the next page with the cursor', async () => {
    const user = userEvent.setup()
    setup(many(130))
    await screen.findByTestId('media-grid')
    expect(markPageReady).toHaveBeenCalledWith('media-library')
    expect(tileIds()).toHaveLength(60)
    expect(listPaths()).toHaveLength(1)
    expect(listPaths()[0]).toContain('limit=60')
    expect(listPaths()[0]).not.toContain('cursor=')

    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(tileIds()).toHaveLength(120))
    expect(listPaths()[1]).toContain('cursor=after%3Am059')
    expect(new Set(tileIds()).size).toBe(120)

    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(tileIds()).toHaveLength(130))
    // Last page: no more control and no further fetch.
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
    expect(listPaths()).toHaveLength(3)
  })

  it('loads the next page when the sentinel scrolls into view', async () => {
    const observers: Array<(entries: Array<{ isIntersecting: boolean }>) => void> = []
    vi.stubGlobal(
      'IntersectionObserver',
      class {
        constructor(cb: (entries: Array<{ isIntersecting: boolean }>) => void) {
          observers.push(cb)
        }
        observe() {}
        disconnect() {}
      },
    )
    try {
      setup(many(100))
      await screen.findByTestId('media-grid')
      expect(listPaths()).toHaveLength(1)
      act(() => observers[observers.length - 1]([{ isIntersecting: true }]))
      await waitFor(() => expect(tileIds()).toHaveLength(100))
      expect(listPaths()).toHaveLength(2)
    } finally {
      vi.unstubAllGlobals()
    }
  })

  it('a filter change resets to page 1 with the filter sent to the server', async () => {
    const user = userEvent.setup()
    setup(many(130))
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(tileIds()).toHaveLength(120))
    await user.click(screen.getByRole('button', { name: 'Unchecked' }))
    await waitFor(() => expect(lastListPath()).toContain('unchecked=true'))
    expect(lastListPath()).toContain('limit=60')
    expect(lastListPath()).not.toContain('cursor=')
    await waitFor(() => expect(tileIds()).toHaveLength(60))
  })

  it('search resets to page 1 with q sent to the server', async () => {
    const user = userEvent.setup()
    setup(many(130))
    await screen.findByTestId('media-grid')
    await user.type(screen.getByLabelText('Search by label'), 'item 12')
    await waitFor(() => expect(lastListPath()).toContain('q=item+12'))
    expect(lastListPath()).not.toContain('cursor=')
  })

  it('does not fetch again once the list fits one page', async () => {
    setup(many(10))
    await screen.findByTestId('media-grid')
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
    expect(listPaths()).toHaveLength(1)
  })

  it('Select set pulls in the rest of the library before selecting', async () => {
    const user = userEvent.setup()
    const newer = many(70).map((r, i) => ({ ...r, label: `01-import 9x16 slide ${String(i + 1).padStart(2, '0')}` }))
    const older = slides('4x5', 3).map((r) => ({ ...r, createdAt: '2025-01-01T00:00:00Z' }))
    setup([...newer, ...older])
    await screen.findByTestId('media-grid')
    expect(tileIds()).toHaveLength(60)
    await user.click(screen.getByLabelText('Select 01-import 9x16 slide 01'))
    await user.click(screen.getByRole('button', { name: 'Select set' }))
    await waitFor(() => expect(screen.getByText('73 selected')).toBeInTheDocument())
    expect(tileIds()).toHaveLength(73)
  })
  it('"On posts" and "Unused" load the whole library unpaged, and leaving them pages again', async () => {
    const user = userEvent.setup()
    setup(many(130).map((r, i) => ({ ...r, postCount: i === 100 ? 1 : 0, creativeCount: 0 })))
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('button', { name: 'Unused' }))
    await waitFor(() => expect(tileIds()).toHaveLength(129))
    expect(lastListPath()).not.toContain('limit=')
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Unused' }))
    await user.click(screen.getByRole('button', { name: 'On posts' }))
    await waitFor(() => expect(tileIds()).toEqual(['m100']))
    expect(lastListPath()).not.toContain('limit=')
    await user.click(screen.getByRole('button', { name: 'On posts' }))
    await waitFor(() => expect(tileIds()).toHaveLength(60))
    expect(lastListPath()).toContain('limit=60')
    expect(screen.getByRole('button', { name: 'Load more' })).toBeInTheDocument()
  })

  it('discards a slow first-page response that arrives after a filter change', async () => {
    const user = userEvent.setup()
    const rows = many(130).map((r, i) => (i < 30 ? { ...r, checked: true } : r))
    all = rows
    load(rows)
    const fast = (apiGetPage as Mock).getMockImplementation()!
    let releaseSlow!: () => void
    ;(apiGetPage as Mock).mockImplementation(async (path: string, ...rest: unknown[]) => {
      const res = await fast(path, ...rest)
      if (path.includes('limit=60') && !path.includes('unchecked') && !path.includes('cursor') && !releaseSlow) {
        await new Promise<void>((r) => { releaseSlow = r })
      }
      return res
    })
    render(<MediaLibrary projectId="proj-1" />)
    await waitFor(() => expect(releaseSlow).toBeDefined())
    // Filter while the unfiltered first page is still in flight.
    await user.click(screen.getByRole('button', { name: 'Unchecked' }))
    await waitFor(() => expect(lastListPath()).toContain('unchecked=true'))
    await waitFor(() => expect(tileIds()).toHaveLength(60))
    const before = tileIds()
    expect(before).not.toContain('m000')
    // Now the stale response lands; it must not replace the filtered list or its cursor.
    act(() => releaseSlow())
    await new Promise((r) => setTimeout(r, 20))
    expect(tileIds()).toEqual(before)
    expect(listPaths().filter((p) => p.includes('limit=60'))).toHaveLength(2)
  })

  it('discards a slow "Load more" page that arrives after a filter change', async () => {
    const user = userEvent.setup()
    const rows = many(130).map((r, i) => (i % 3 !== 2 ? { ...r, aiGenerated: true } : r))
    all = rows
    load(rows)
    const fast = (apiGetPage as Mock).getMockImplementation()!
    let release!: () => void
    ;(apiGetPage as Mock).mockImplementation(async (path: string, ...rest: unknown[]) => {
      const res = await fast(path, ...rest)
      if (path.includes('cursor=') && !release) await new Promise<void>((r) => { release = r })
      return res
    })
    render(<MediaLibrary projectId="proj-1" />)
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(release).toBeDefined())
    await user.click(screen.getByRole('button', { name: 'AI-generated' }))
    await waitFor(() => expect(lastListPath()).toContain('aiGenerated=true'))
    await waitFor(() => expect(tileIds()).toHaveLength(60))
    const before = tileIds()
    act(() => release())
    await new Promise((r) => setTimeout(r, 20))
    expect(tileIds()).toEqual(before)
    expect(tileIds().every((id) => rows.find((r) => r.id === id)!.aiGenerated)).toBe(true)
    // The cursor still belongs to the filtered list: the next page completes it.
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(tileIds()).toHaveLength(87))
  })

  it('shows an error and keeps the loaded tiles when loading the next page fails', async () => {
    const user = userEvent.setup()
    setup(many(100))
    await screen.findByTestId('media-grid')
    const ok = (apiGetPage as Mock).getMockImplementation()!
    ;(apiGetPage as Mock).mockRejectedValueOnce({ status: 500, detail: 'boom' })
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    expect(await screen.findByText('boom')).toBeInTheDocument()
    expect(tileIds()).toHaveLength(60)
    ;(apiGetPage as Mock).mockImplementation(ok)
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(tileIds()).toHaveLength(100))
  })

  it('deleting an item removes its tile and keeps paging from the same cursor', async () => {
    const user = userEvent.setup()
    ;(apiDelete as Mock).mockResolvedValue(undefined)
    setup(many(100))
    await screen.findByTestId('media-grid')
    await user.click(screen.getByRole('button', { name: 'Open item 0' }))
    await user.click(await screen.findByRole('button', { name: 'Delete' }))
    const dialog = await screen.findByRole('alertdialog').catch(() => screen.getByRole('dialog', { name: /Delete this media/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(tileIds()).toHaveLength(59))
    expect(tileIds()).not.toContain('m000')
    await user.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(tileIds()).toHaveLength(99))
    expect(new Set(tileIds()).size).toBe(99)
  })
})
