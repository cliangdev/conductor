import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { WorkItemTagsField, normalizeTag } from './WorkItemTagsField'

const { toastErrorSpy } = vi.hoisted(() => ({ toastErrorSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async () => {
  const actual = await vi.importActual<typeof import('@/components/ui/toast')>('@/components/ui/toast')
  return { ...actual, toastError: toastErrorSpy }
})

let patchBodies: { tags?: string[] }[] = []
let patchRejection: { status: number; detail: string } | null = null

const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
  patchBodies.push(JSON.parse(String(init?.body ?? '{}')))
  if (patchRejection)
    return {
      ok: false, status: patchRejection.status,
      headers: { get: () => 'application/json' },
      json: async () => ({ detail: patchRejection!.detail }),
    }
  return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({}) }
})

beforeEach(() => {
  patchBodies = []
  patchRejection = null
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', 'https://api.test')
  vi.stubGlobal('fetch', fetchMock)
})

function renderField(props: Partial<React.ComponentProps<typeof WorkItemTagsField>> = {}) {
  const onChanged = vi.fn()
  render(
    <WorkItemTagsField
      projectId="proj-1"
      workItemId="post-1"
      token="tok"
      tags={[]}
      known={[]}
      canEdit
      onChanged={onChanged}
      {...props}
    />
  )
  return { onChanged }
}

describe('normalizeTag', () => {
  it('matches the server: trimmed and lower-cased', () => {
    // Or "Autumn" and "autumn" become two tags that look identical in a filter list and match
    // different items.
    expect(normalizeTag('  Autumn-Campaign ')).toBe('autumn-campaign')
  })
})

describe('WorkItemTagsField', () => {
  it('adds a tag on Enter, lower-cased', async () => {
    const { onChanged } = renderField()

    await userEvent.type(screen.getByLabelText(/Add a tag/i), 'Autumn-Campaign')
    await userEvent.keyboard('{Enter}')

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    expect(patchBodies[0].tags).toEqual(['autumn-campaign'])
    expect(onChanged).toHaveBeenCalledWith(['autumn-campaign'])
  })

  it('adds on a comma too, because people type one or the other without thinking', async () => {
    renderField({ tags: ['paid'] })

    await userEvent.type(screen.getByLabelText(/Add a tag/i), 'evergreen,')

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    expect(patchBodies[0].tags).toEqual(['paid', 'evergreen'])
  })

  it('sends the whole set, so adding one never drops another', async () => {
    renderField({ tags: ['paid', 'evergreen'] })

    await userEvent.type(screen.getByLabelText(/Add a tag/i), 'autumn{Enter}')

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    expect(patchBodies[0].tags).toEqual(['paid', 'evergreen', 'autumn'])
  })

  it('re-adding a tag the item already has is a no-op, not a duplicate', async () => {
    renderField({ tags: ['paid'] })

    await userEvent.type(screen.getByLabelText(/Add a tag/i), 'PAID{Enter}')

    expect(patchBodies).toHaveLength(0)
  })

  it('removes a tag', async () => {
    renderField({ tags: ['paid', 'evergreen'] })

    await userEvent.click(screen.getByRole('button', { name: 'Remove tag paid' }))

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    expect(patchBodies[0].tags).toEqual(['evergreen'])
  })

  it('suggests tags already in use, minus the ones already on this item', () => {
    renderField({ tags: ['paid'], known: ['paid', 'evergreen', 'autumn'] })

    const options = [...document.querySelectorAll('datalist option')].map((o) => o.getAttribute('value'))
    expect(options).toEqual(['evergreen', 'autumn'])
  })

  it('keeps the stored tags and says why when the server refuses', async () => {
    patchRejection = { status: 422, detail: 'Tag too long' }
    const { onChanged } = renderField({ tags: ['paid'] })

    await userEvent.type(screen.getByLabelText(/Add a tag/i), 'x{Enter}')

    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
    expect(toastErrorSpy.mock.calls[0][0]).toContain('Tag too long')
    expect(onChanged).not.toHaveBeenCalled()
  })

  it('offers no editing to a reader', () => {
    renderField({ canEdit: false, tags: ['paid'] })

    expect(screen.getByText('paid')).toBeInTheDocument()
    expect(screen.queryByLabelText(/Add a tag/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove tag/ })).not.toBeInTheDocument()
  })
})
