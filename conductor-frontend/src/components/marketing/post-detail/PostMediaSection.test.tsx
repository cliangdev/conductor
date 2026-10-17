import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => true,
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: () => true, refresh: vi.fn() }),
}))

import { apiDelete } from '@/lib/api'
import { MARKETING_VIEW, option, selection } from '@/components/marketing/test-fixtures'
import { buildRows, summarize, type DestinationActions, type DestinationsState } from '@/components/marketing/destinations/model'
import { draftFromTargets } from '@/components/marketing/destinations/selectionState'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { PostMediaSection } from './PostMediaSection'
import { assetDisplayLabel, placementLabel } from './mediaGroups'

const ig = option({ platform: 'instagram', connectionId: 'ig', label: '@rexipe.ig' })
const fb = option({ platform: 'facebook', connectionId: 'fb', label: 'Rexipe' })

function asset(id: string, over: Partial<MediaAsset> = {}): MediaAsset {
  return {
    id,
    issueId: 'wi-1',
    type: 'facebook_post',
    label: `file ${id}`,
    kind: 'file',
    ref: `ref-${id}`,
    done: false,
    createdAt: '',
    updatedAt: '',
    uploadStatus: 'UPLOADED',
    contentType: 'image/jpeg',
    sizeBytes: 2048,
    width: 1080,
    height: 1920,
    previewUrl: `https://storage.example/${id}.jpg`,
    ...over,
  }
}

const ASSETS: MediaAsset[] = [
  asset('s1', { creativeFrameId: 'f1', creativeId: 'cr-1', creativeDisplayId: '18a', placementKey: '9x16', sequenceIndex: 0 }),
  asset('s2', { creativeFrameId: 'f2', creativeId: 'cr-1', creativeDisplayId: '18a', placementKey: '9x16', sequenceIndex: 1 }),
  asset('f4', { creativeFrameId: 'f3', creativeId: 'cr-1', creativeDisplayId: '18a', placementKey: '4x5', sequenceIndex: 0, width: 1080, height: 1350 }),
  asset('lib', { mediaId: 'm-1', label: 'library pic' }),
  asset('up', { label: 'my upload' }),
]

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

function destinations(): DestinationsState {
  const igTarget = { ...selection(ig, 'wi-1'), format: 'reel' as const, assetIds: ['s2', 's1'] }
  const fbTarget = { ...selection(fb, 'wi-1'), assetIds: null }
  const selected = [igTarget, fbTarget]
  const rows = buildRows({
    options: [ig, fb],
    selected,
    draft: draftFromTargets(selected),
    preflight: null,
    metrics: null,
    consent: null,
    approvedOrLater: false,
    mode: 'pick',
    assets: ASSETS,
  })
  return {
    mode: 'pick',
    rows,
    summary: summarize(rows, 'pick'),
    loading: false,
    loadError: null,
    saving: false,
    retrying: false,
    locked: false,
    revertsOnEdit: false,
    editing: false,
    postLevel: { blockers: [], warnings: [] },
    totals: null,
    observedAt: null,
    unreportedNote: null,
    consentError: null,
    consentSaving: false,
    actions: actions(),
  }
}

function setup(over: Partial<React.ComponentProps<typeof PostMediaSection>> = {}) {
  const onAssetsChanged = vi.fn()
  render(
    <PostMediaSection
      projectId="proj-1"
      workItemId="wi-1"
      token="tok"
      status="DRAFT"
      workflowView={MARKETING_VIEW}
      caption="cap"
      assets={ASSETS}
      canEdit
      destinations={destinations()}
      onAssetsChanged={onAssetsChanged}
      {...over}
    />,
  )
  return { onAssetsChanged }
}

describe('PostMediaSection', () => {
  beforeEach(() => vi.clearAllMocks())

  it('groups by destination in effectiveAssetIds order and notes inheritance', () => {
    setup()
    const groups = screen.getAllByTestId('media-destination-group')
    expect(groups).toHaveLength(2)
    const igGroup = groups.find((g) => within(g).queryByText(/@rexipe\.ig/))!
    const names = within(igGroup)
      .getAllByRole('button', { name: /full size/i })
      .map((b) => b.getAttribute('aria-label'))
    expect(names).toEqual(['Open file s2 full size', 'Open file s1 full size'])
    expect(within(igGroup).getByText(/Reel/)).toBeInTheDocument()
    expect(within(igGroup).queryByText('Uses all Post media')).not.toBeInTheDocument()

    const fbGroup = groups.find((g) => within(g).queryByText(/Rexipe$/))!
    expect(within(fbGroup).getByText('Uses all Post media')).toBeInTheDocument()
    expect(within(fbGroup).getAllByRole('button', { name: /full size/i })).toHaveLength(5)
  })

  it('shows the Creative origin line with a link to the Creative', () => {
    setup()
    const line = screen.getByTestId('media-origin-line')
    expect(line).toHaveTextContent('From Creative 18a')
    expect(within(line).getByRole('link', { name: 'Open Creative' })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/marketing/creatives/cr-1',
    )
  })

  it('shows the Creative name next to its display id', async () => {
    const named = ASSETS.map((a) => (a.creativeId ? { ...a, creativeName: 'Weeknight chicken bowls' } : a))
    setup({ assets: named })
    expect(screen.getByTestId('media-origin-line')).toHaveTextContent('From Creative 18a · Weeknight chicken bowls')
    expect(screen.getByTitle('Creative 18a · Weeknight chicken bowls')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('radio', { name: /all files 5/i }))
    expect(screen.getAllByTestId('media-origin-group')[0]).toHaveTextContent('Creative 18a · Weeknight chicken bowls')
  })

  it('falls back to the display id alone when the Creative has no name', () => {
    setup()
    expect(screen.getByTestId('media-origin-line')).toHaveTextContent('From Creative 18a')
    expect(screen.getByTitle('Creative 18a')).toBeInTheDocument()
  })

  it('names the Creative in the full-size viewer source line', async () => {
    const named = ASSETS.map((a) => (a.creativeId ? { ...a, creativeName: 'Weeknight chicken bowls' } : a))
    setup({ assets: named })
    const igGroup = screen.getAllByTestId('media-destination-group').find((g) => within(g).queryByText(/@rexipe\.ig/))!
    await userEvent.click(within(igGroup).getByRole('button', { name: 'Open file s2 full size' }))
    const aside = await screen.findByTestId('viewer-aside')
    expect(within(aside).getByText(/Creative 18a · Weeknight chicken bowls · 9:16 · slide 2/)).toBeInTheDocument()
  })

  it('All files groups by Creative placement, library and uploads, and flags unused files', async () => {
    setup()
    await userEvent.click(screen.getByRole('radio', { name: /all files 5/i }))
    const groups = screen.getAllByTestId('media-origin-group')
    expect(groups.map((g) => g.getAttribute('aria-label'))).toEqual(['Creative 18a', 'From library', 'Uploaded'])
    const creative = groups[0]!
    expect(within(creative).getByText('4:5')).toBeInTheDocument()
    expect(within(creative).getByText('9:16')).toBeInTheDocument()
    expect(within(creative).getByText(/Carousel · 3 files/)).toBeInTheDocument()
    // Facebook inherits everything, so nothing is unused here.
    expect(screen.queryByText('Not used by any destination')).not.toBeInTheDocument()
  })

  it('flags a file no destination uses when every destination picks its own', async () => {
    const igOnly = { ...selection(ig, 'wi-1'), assetIds: ['s1'] }
    const rows = buildRows({
      options: [ig],
      selected: [igOnly],
      draft: draftFromTargets([igOnly]),
      preflight: null,
      metrics: null,
      consent: null,
      approvedOrLater: false,
      mode: 'pick',
      assets: ASSETS,
    })
    const base = destinations()
    setup({ destinations: { ...base, rows, summary: summarize(rows, 'pick') } })
    await userEvent.click(screen.getByRole('radio', { name: /all files/i }))
    expect(screen.getAllByText('Not used by any destination')).toHaveLength(4)
    expect(screen.getByRole('button', { name: 'Remove my upload' })).toBeInTheDocument()
  })

  it('removes an unused file after confirming', async () => {
    const igOnly = { ...selection(ig, 'wi-1'), assetIds: ['s1'] }
    const rows = buildRows({
      options: [ig],
      selected: [igOnly],
      draft: draftFromTargets([igOnly]),
      preflight: null,
      metrics: null,
      consent: null,
      approvedOrLater: false,
      mode: 'pick',
      assets: ASSETS,
    })
    const { onAssetsChanged } = setup({ destinations: { ...destinations(), rows } })
    await userEvent.click(screen.getByRole('radio', { name: /all files/i }))
    await userEvent.click(screen.getByRole('button', { name: 'Remove my upload' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Remove' }))
    expect(apiDelete).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/wi-1/assets/up', 'tok')
    await vi.waitFor(() => expect(onAssetsChanged).toHaveBeenCalled())
  })

  it('opens the viewer on a tile with where it goes and where it came from, and walks the group', async () => {
    setup()
    const igGroup = screen.getAllByTestId('media-destination-group').find((g) => within(g).queryByText(/@rexipe\.ig/))!
    await userEvent.click(within(igGroup).getByRole('button', { name: 'Open file s2 full size' }))
    const aside = await screen.findByTestId('viewer-aside')
    expect(within(aside).getByText('Slide 1 of 2')).toBeInTheDocument()
    expect(within(aside).getByText('9:16 · 1080 × 1920 px · 2.0 KB')).toBeInTheDocument()
    expect(within(within(aside).getByRole('list')).getByText(/Instagram · @rexipe\.ig/)).toBeInTheDocument()
    expect(within(aside).getByText(/Reel · position 1/)).toBeInTheDocument()
    expect(within(aside).getByText(/Creative 18a · 9:16 · slide 2/)).toBeInTheDocument()
    expect(within(aside).getByRole('link', { name: /open creative/i })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(within(screen.getByTestId('viewer-aside')).getByText('Slide 2 of 2')).toBeInTheDocument()
    expect(within(screen.getByTestId('viewer-aside')).getByText(/slide 1$/)).toBeInTheDocument()
  })

  it('hides Add, Customize and Remove when the viewer cannot edit', () => {
    setup({ canEdit: false })
    expect(screen.queryByRole('button', { name: /from media/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /from a creative/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Customize' })).not.toBeInTheDocument()
  })

  it('offers Customize and opens the destination editor', async () => {
    setup()
    await userEvent.click(screen.getAllByRole('button', { name: 'Customize' })[0]!)
    expect(await screen.findByText(/Customize Instagram|Customize Facebook/)).toBeInTheDocument()
  })

  it('without destinations, shows all files with a hint', () => {
    setup({ destinations: undefined })
    expect(screen.getByText(/add destinations/i)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /full size/i })).toHaveLength(5)
  })
})

describe('placementLabel / assetDisplayLabel', () => {
  it('writes every placement key in colon form', () => {
    expect(placementLabel('4×5')).toBe('4:5')
    expect(placementLabel('1x1')).toBe('1:1')
    expect(placementLabel('9:16')).toBe('9:16')
    expect(placementLabel('16x9')).toBe('16:9')
    expect(placementLabel('story')).toBe('Story')
    expect(placementLabel(null)).toBe('')
  })

  it('names creative frames by slide and leaves other files alone', () => {
    const frame = asset('x', { creativeFrameId: 'f', placementKey: '4×5', sequenceIndex: 1, label: 'Creative 3a 4×5' })
    expect(assetDisplayLabel(frame)).toBe('Slide 2')
    expect(assetDisplayLabel(frame, { withRatio: true })).toBe('4:5 · slide 2')
    expect(assetDisplayLabel(asset('u', { label: 'my upload' }))).toBe('my upload')
  })
})
