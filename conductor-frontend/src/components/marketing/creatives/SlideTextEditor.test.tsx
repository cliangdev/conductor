import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', async (orig) => ({
  ...(await orig<typeof import('@/lib/api')>()),
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiGetPage: vi.fn(),
}))

import { apiGet, apiPost } from '@/lib/api'
import { SlideTextEditor } from './SlideTextEditor'
import { MEDIA, creative } from './slideTextFixtures'

const get = vi.mocked(apiGet)
const post = vi.mocked(apiPost)

const AFFECTED = [
  { workItemId: 'w1', displayId: 'RE-101', title: 'Thanksgiving hook', status: 'APPROVED', statusLabel: 'Approved', outcome: 'back-to-review' },
  { workItemId: 'w2', displayId: 'RE-102', title: 'Second cut', status: 'IN_REVIEW', statusLabel: 'In Review', outcome: 'new-round' },
  { workItemId: 'w3', displayId: 'RE-103', title: 'Draft cut', status: 'DRAFT', statusLabel: 'Draft', outcome: 'updated' },
  { workItemId: 'w4', displayId: 'RE-104', title: 'Already out', status: 'PUBLISHED', statusLabel: 'Published', outcome: 'unchanged' },
]

type Routes = {
  creative?: () => unknown
  affected?: unknown[]
  availability?: unknown
}
function routes(r: Routes = {}) {
  get.mockImplementation(async (path: string) => {
    if (path.includes('/affected-posts')) return { posts: r.affected ?? AFFECTED }
    if (path.includes('/render-availability')) return r.availability ?? { runtime: 'target', targetName: 'byo-test', daemonOnline: null }
    if (path.endsWith('/creatives/cr-1')) return r.creative ? r.creative() : creative()
    throw new Error(`unexpected GET ${path}`)
  })
}

function setup(props: Partial<React.ComponentProps<typeof SlideTextEditor>> = {}) {
  const onApplied = vi.fn(async () => {})
  const onClose = vi.fn()
  render(
    <SlideTextEditor
      projectId="p"
      token="t"
      creative={creative()}
      mediaById={MEDIA}
      onApplied={onApplied}
      onClose={onClose}
      pollMs={15}
      {...props}
    />,
  )
  return { onApplied, onClose }
}

const frame = (n = 1, ratio = '9:16') => screen.getByTitle(`Preview of slide ${n} at ${ratio}`) as HTMLIFrameElement

beforeEach(() => {
  vi.clearAllMocks()
  routes()
})

describe('SlideTextEditor fields', () => {
  it('lists the slide’s fields under plain labels, each a labelled textarea that keeps line breaks', () => {
    setup()
    expect(screen.getByRole('heading', { hidden: true, name: /Edit text · 158a/ })).toBeInTheDocument()
    expect(screen.getByTestId('slide-position')).toHaveTextContent('Slide 1 of 3')
    expect(screen.getByLabelText('Text 1')).toHaveValue('Hook line\nsecond line')
    expect(screen.getByLabelText('Text 2')).toHaveValue('Small print')
    expect(screen.queryByLabelText('Title')).not.toBeInTheDocument()
  })

  it('shows another slide’s fields, and opens on the slide it was asked to', async () => {
    setup({ initialSlide: 1 })
    expect(screen.getByLabelText('Title')).toHaveValue('Plan')
    expect(screen.getByLabelText('Note item 2')).toHaveValue('Call mum')
    expect(screen.getByLabelText('Paragraph 1')).toHaveValue('One paragraph')
    await userEvent.click(screen.getByRole('button', { name: 'Next slide' }))
    expect(screen.getByLabelText('Bubble 1')).toHaveValue('Hi')
  })

  it('marks an edited field, resets it, and counts the changes', async () => {
    setup()
    expect(screen.getByRole('button', { name: 'Review and save' })).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Text 2'), ' more')
    expect(screen.getByText('Edited')).toBeInTheDocument()
    expect(screen.getByTestId('slide-text-summary')).toHaveTextContent('1 field changed on 1 slide')
    await userEvent.click(screen.getByRole('button', { name: 'Reset' }))
    expect(screen.getByLabelText('Text 2')).toHaveValue('Small print')
    expect(screen.getByRole('button', { name: 'Review and save' })).toBeDisabled()
  })
})

describe('SlideTextEditor preview', () => {
  it('builds the page from the edited spec with the media URL and the font folder', async () => {
    setup()
    const first = frame().getAttribute('srcdoc')!
    expect(first).toContain('Hook line<br>second line')
    expect(first).toContain('src="https://storage.example/11111111-1111-4111-8111-111111111111.jpg"')
    expect(first).toContain('/slideshow-fonts/tiktok-sans-700.ttf')

    fireEvent.change(screen.getByLabelText('Text 1'), { target: { value: 'Fresh hook\nnew second' } })
    await waitFor(() => expect(frame().getAttribute('srcdoc')).toContain('Fresh hook<br>new second'))
    expect(frame().getAttribute('srcdoc')).not.toContain('Hook line')
  })

  it('switches ratio with the tabs', async () => {
    setup()
    expect(frame(1, '9:16').getAttribute('srcdoc')).toContain('--H:1920px')
    await userEvent.click(screen.getByRole('tab', { name: '4:5' }))
    await waitFor(() => expect(frame(1, '4:5').getAttribute('srcdoc')).toContain('--H:1350px'))
  })

  it('shows the safe-zone overlay on 9:16 and lets it be turned off', async () => {
    setup()
    expect(screen.getByTestId('safe-zones')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Show safe zones'))
    expect(screen.queryByTestId('safe-zones')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: '4:5' }))
    expect(screen.queryByLabelText('Show safe zones')).not.toBeInTheDocument()
  })

  it('shows the page’s checks by rule once it has measured itself', async () => {
    setup()
    expect(screen.getByText('Measuring…')).toBeInTheDocument()
    const f = frame()
    Object.defineProperty(f, 'contentWindow', {
      configurable: true,
      value: {
        __READY: true,
        __CHECKS: [
          { slide: 1, ratio: '9x16', rule: 'wordsPerLine', severity: 'warning', message: 'text line has 9 words' },
          { slide: 1, ratio: '9x16', rule: 'safeZone', severity: 'error', message: '"Hook line" intrudes into the top 220px overlay band' },
        ],
      },
    })
    fireEvent.load(f)
    const list = await screen.findByTestId('slide-checks')
    const items = within(list).getAllByRole('listitem')
    expect(items[0]).toHaveAttribute('data-severity', 'error') // errors first
    expect(within(list).getByTestId('slide-check-safeZone')).toHaveTextContent('Error · Safe zone')
    expect(within(list).getByTestId('slide-check-wordsPerLine')).toHaveTextContent('Warning · Words per line')
  })

  it('says so when the page reports nothing wrong', async () => {
    setup()
    const f = frame()
    Object.defineProperty(f, 'contentWindow', { configurable: true, value: { __READY: true, __CHECKS: [] } })
    fireEvent.load(f)
    expect(await screen.findByText('No problems found.')).toBeInTheDocument()
  })
})

describe('SlideTextEditor save flow', () => {
  async function editAndReview() {
    await userEvent.type(screen.getByLabelText('Text 2'), '!')
    await userEvent.click(screen.getByRole('button', { name: 'Review and save' }))
    return screen.findByTestId('slide-text-confirm')
  }

  it('lists the affected Posts with what happens to each, and where it renders', async () => {
    setup()
    const confirm = await editAndReview()
    expect(within(confirm).getByText('Slide 1 · Text 2')).toBeInTheDocument()
    const posts = within(confirm).getByTestId('affected-posts')
    expect(within(posts).getByTestId('affected-back-to-review')).toHaveTextContent('RE-101')
    expect(within(posts).getByTestId('affected-back-to-review')).toHaveTextContent('goes back to review')
    expect(within(posts).getByTestId('affected-new-round')).toHaveTextContent('starts a new review round')
    expect(within(posts).getByTestId('affected-updated')).toHaveTextContent('RE-103')
    expect(within(posts).getByTestId('affected-unchanged')).toHaveTextContent('already published')
    expect(screen.getByTestId('render-availability')).toHaveTextContent('byo-test')
  })

  it('warns when nothing can render', async () => {
    routes({ availability: { runtime: 'self-hosted', targetName: null, daemonOnline: false } })
    setup()
    await editAndReview()
    expect(screen.getByTestId('render-availability')).toHaveTextContent('no daemon is online')
    expect(screen.getByTestId('render-availability')).toHaveTextContent('conductor creative render-slides')
  })

  it('says where it renders when a daemon is expected but not reported', async () => {
    routes({ availability: { runtime: 'self-hosted', targetName: null, daemonOnline: null } })
    setup()
    await editAndReview()
    expect(screen.getByTestId('render-availability')).toHaveTextContent('self-hosted runner')
  })

  it('posts the edits against the Creative’s version, shows rendering, then confirms once applied', async () => {
    post.mockResolvedValue({ revision: { id: 'rev-9', status: 'PENDING' }, renderRun: { runId: 'r1', status: 'PENDING' } })
    const { onApplied } = setup()
    await editAndReview()
    let settled = false
    get.mockImplementation(async (path: string) => {
      if (path.endsWith('/creatives/cr-1')) {
        return settled
          ? creative({ latestRevision: { id: 'rev-9', revisionNo: 3, status: 'APPLIED', source: 'EDITOR', createdAt: '2026-11-02T10:00:00Z' } })
          : creative({ pendingRevision: { id: 'rev-9', status: 'PENDING', createdAt: '2026-11-02T10:00:00Z' } })
      }
      return {}
    })
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))
    expect(post).toHaveBeenCalledWith(
      '/api/v2/projects/p/marketing/creatives/cr-1/slide-text-edits',
      { version: 4, edits: [{ slide: 1, path: 'texts[1].t', text: 'Small print!' }] },
      't',
    )
    expect(await screen.findByTestId('slide-text-rendering')).toBeInTheDocument()
    expect(onApplied).not.toHaveBeenCalled()
    settled = true
    expect(await screen.findByTestId('slide-text-applied')).toBeInTheDocument()
    expect(onApplied).toHaveBeenCalledTimes(1)
  })

  it('shows the failing checks by slide, ratio and rule when the render fails', async () => {
    post.mockResolvedValue({ revision: { id: 'rev-9', status: 'PENDING' } })
    setup()
    await editAndReview()
    get.mockImplementation(async () =>
      creative({
        latestRevision: {
          id: 'rev-9',
          revisionNo: 3,
          status: 'FAILED',
          source: 'EDITOR',
          error: 'Slide checks failed',
          checks: [{ slide: 2, ratio: '9x16', rule: 'spill', severity: 'error', message: '"Plan" extends past the frame' }],
          createdAt: '2026-11-02T10:00:00Z',
        },
      }),
    )
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))
    const failed = await screen.findByTestId('slide-text-failed')
    expect(failed).toHaveTextContent('Slide checks failed')
    expect(within(failed).getByTestId('slide-check-spill')).toHaveTextContent('Error · Spills off the slide · Slide 2, 9:16')
    expect(failed).toHaveTextContent('Nothing was changed')
    await userEvent.click(screen.getByRole('button', { name: 'Back to editing' }))
    expect(screen.getByLabelText('Text 2')).toHaveValue('Small print!') // the edit is still there
  })

  it('shows the API’s message for a 409 and stays on the edit', async () => {
    post.mockRejectedValue(Object.assign(new Error('x'), { status: 409, code: 'SLIDE_EDIT_ACTIVE', detail: 'busy' }))
    setup()
    await editAndReview()
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))
    expect(await screen.findByText(/Another text edit is still rendering/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Review and save' })).toBeInTheDocument()
  })

  it('shows a stale-version 409 with the server’s own words', async () => {
    post.mockRejectedValue(Object.assign(new Error('x'), { status: 409, detail: 'The Creative changed (version 5).' }))
    setup()
    await editAndReview()
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))
    expect(await screen.findByText('The Creative changed (version 5).')).toBeInTheDocument()
  })

  it('shows a 422’s violations', async () => {
    post.mockRejectedValue(
      Object.assign(new Error('x'), { status: 422, violations: [{ field: 'edits[0]', ruleId: 'slideTextEditInvalid', message: 'edits[0]: not an editable text field' }] }),
    )
    setup()
    await editAndReview()
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))
    expect(await screen.findByText('edits[0]: not an editable text field')).toBeInTheDocument()
  })

  it('opens straight onto the rendering state while an edit is pending, with no way to edit', async () => {
    setup({ creative: creative({ pendingRevision: { id: 'rev-1', status: 'PENDING', createdAt: '2026-11-02T10:00:00Z' } }) })
    expect(screen.getByTestId('slide-text-rendering')).toBeInTheDocument()
    expect(screen.queryByLabelText('Text 1')).not.toBeInTheDocument()
  })
})

describe('SlideTextEditor reviewer fix', () => {
  it('sends the Post id, keeps the fix in the round, and names the other Posts that go back', async () => {
    routes({
      affected: [
        { workItemId: 'w2', displayId: 'RE-102', title: 'This Post', status: 'IN_REVIEW', statusLabel: 'In Review', outcome: 'reviewer-fix' },
        { workItemId: 'w1', displayId: 'RE-101', title: 'Other cut', status: 'APPROVED', statusLabel: 'Approved', outcome: 'back-to-review' },
      ],
    })
    post.mockResolvedValue({ revision: { id: 'rev-9', status: 'PENDING' } })
    setup({ postId: 'w2' })
    expect(screen.getByRole('heading', { hidden: true, name: /Fix text · 158a/ })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Text 2'), '!')
    await userEvent.click(screen.getByRole('button', { name: 'Review and save' }))
    const confirm = await screen.findByTestId('slide-text-confirm')
    expect(get).toHaveBeenCalledWith(expect.stringContaining('/affected-posts?postId=w2'), 't')
    expect(confirm).toHaveTextContent('stays in this review round')
    expect(confirm).toHaveTextContent('Other Posts that will go back to review')
    expect(within(confirm).getByTestId('affected-back-to-review')).toHaveTextContent('RE-101')
    await userEvent.click(screen.getByRole('button', { name: 'Save and re-render' }))
    expect(post.mock.calls[0]![1]).toMatchObject({ postId: 'w2', version: 4 })
  })
})
