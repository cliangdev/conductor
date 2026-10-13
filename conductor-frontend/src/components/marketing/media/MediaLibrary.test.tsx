import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
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

import { apiDelete, apiGet, apiPatch, apiPost } from '@/lib/api'
import { MediaLibrary } from './MediaLibrary'
import { photo, slides } from './testFixtures'
import type { CreativePhoto } from '@/components/marketing/creatives/types'

function load(rows: CreativePhoto[]) {
  ;(apiGet as Mock).mockImplementation(async (path: string) => {
    const prefix = new URL(`http://x${path}`).searchParams.get('labelPrefix')
    return prefix ? rows.filter((r) => r.label?.startsWith(prefix)) : rows
  })
}

const tileIds = () =>
  within(screen.getByTestId('media-grid'))
    .getAllByRole('listitem')
    .map((li) => li.getAttribute('data-testid')!.replace('media-tile-', ''))

beforeEach(() => {
  vi.clearAllMocks()
  ;(apiPatch as Mock).mockImplementation(async (path: string, body: object) => {
    const id = path.split('/').pop()
    return { ...(all.find((p) => p.id === id) as CreativePhoto), ...body }
  })
})
let all: CreativePhoto[] = []
function setup(rows: CreativePhoto[]) {
  all = rows
  load(rows)
  return render(<MediaLibrary projectId="proj-1" />)
}

describe('MediaLibrary grid', () => {
  it('shows a skeleton, then tiles in natural label order', async () => {
    setup([photo('a', 'slide 10'), photo('b', 'slide 2'), photo('c', 'slide 1')])
    expect(screen.getByTestId('media-grid-skeleton')).toBeInTheDocument()
    await screen.findByTestId('media-grid')
    expect(tileIds()).toEqual(['c', 'b', 'a'])
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
    await waitFor(() => expect(tileIds()).toEqual(['4x5-3', 'cover']))
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
    expect((apiPatch as Mock).mock.calls.map((c) => c[0].split('/').pop())).toEqual(['4x5-2', '4x5-3', '4x5-4'])
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
