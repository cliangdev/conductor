import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  WorkItemScheduleField,
  wallClockToInstant,
  instantToWallClock,
} from './WorkItemScheduleField'

const { toastErrorSpy } = vi.hoisted(() => ({ toastErrorSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async () => {
  const actual = await vi.importActual<typeof import('@/components/ui/toast')>('@/components/ui/toast')
  return { ...actual, toastError: toastErrorSpy }
})

const API = 'https://api.test'
let patchBodies: unknown[] = []
let patchRejection: { status: number; detail: string } | null = null

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  if ((init?.method ?? 'GET') === 'PATCH') {
    patchBodies.push(JSON.parse(String(init?.body ?? '{}')))
    if (patchRejection)
      return {
        ok: false,
        status: patchRejection.status,
        headers: { get: () => 'application/json' },
        json: async () => ({ detail: patchRejection!.detail }),
      }
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({}) }
  }
  throw new Error(`unexpected ${init?.method} ${url}`)
})

beforeEach(() => {
  patchBodies = []
  patchRejection = null
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

function renderField(props: Partial<React.ComponentProps<typeof WorkItemScheduleField>> = {}) {
  const onChanged = vi.fn()
  render(
    <WorkItemScheduleField
      projectId="project-1"
      issueId="post-1"
      token="tok"
      scheduledFor={null}
      scheduleTimezone={null}
      canEdit
      onChanged={onChanged}
      {...props}
    />
  )
  return { onChanged }
}

describe('wall clock ↔ instant, read in a named zone', () => {
  it('reads a wall clock in the given zone, not in the viewer own', () => {
    // 09:00 in New York on a summer date is 13:00 UTC (UTC-4).
    expect(wallClockToInstant('2026-07-04T09:00', 'America/New_York')).toBe('2026-07-04T13:00:00.000Z')
    // The same wall clock in Tokyo is a different instant entirely (UTC+9).
    expect(wallClockToInstant('2026-07-04T09:00', 'Asia/Tokyo')).toBe('2026-07-04T00:00:00.000Z')
  })

  it('resolves a wall clock sitting just after a DST jump, where one pass is not enough', () => {
    // US clocks go forward at 07:00 UTC on 2026-03-08, and 03:00 local that morning is the first hour
    // of EDT (UTC-4), i.e. 07:00 UTC. The first pass has to guess an offset from a timestamp that is
    // itself still wrong — it reads UTC-5, the offset *before* the jump, and lands an hour late at
    // 08:00Z. Correcting once more with the offset at the corrected instant settles it. This is the
    // case the loop exists for; without it the schedule silently moves by an hour.
    expect(wallClockToInstant('2026-03-08T03:00', 'America/New_York')).toBe('2026-03-08T07:00:00.000Z')
  })

  it('keeps a wall clock meaning itself across a DST change', () => {
    // US clocks go forward on 2026-03-08. 09:00 local is UTC-5 the day before and UTC-4 the day after;
    // a single-pass conversion picks one offset for both and puts one of them an hour out.
    expect(wallClockToInstant('2026-03-07T09:00', 'America/New_York')).toBe('2026-03-07T14:00:00.000Z')
    expect(wallClockToInstant('2026-03-09T09:00', 'America/New_York')).toBe('2026-03-09T13:00:00.000Z')
  })

  it('round-trips an instant back to the wall clock it was authored as', () => {
    const iso = wallClockToInstant('2026-11-02T17:30', 'Europe/Berlin')!
    expect(instantToWallClock(iso, 'Europe/Berlin')).toBe('2026-11-02T17:30')
  })

  it('refuses a value that is not a wall clock rather than inventing one', () => {
    expect(wallClockToInstant('', 'UTC')).toBeNull()
    expect(wallClockToInstant('tomorrow', 'UTC')).toBeNull()
  })
})

describe('WorkItemScheduleField', () => {
  it('says an unscheduled item is unscheduled, and offers to set one', () => {
    renderField()
    expect(screen.getByText('Not scheduled')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set' })).toBeInTheDocument()
  })

  it('sends the instant and the zone it was authored in', async () => {
    // The zone is stored beside the instant, not derived from it — that is what lets a schedule read
    // back as the wall-clock time its author meant.
    const { onChanged } = renderField()
    await userEvent.click(screen.getByRole('button', { name: 'Set' }))
    await userEvent.clear(screen.getByLabelText(/Scheduled date and time/i))
    await userEvent.type(screen.getByLabelText(/Scheduled date and time/i), '2026-07-04T09:00')
    await userEvent.selectOptions(screen.getByLabelText(/Schedule timezone/i), 'America/New_York')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    expect(patchBodies[0]).toEqual({
      scheduledFor: '2026-07-04T13:00:00.000Z',
      scheduleTimezone: 'America/New_York',
    })
    expect(onChanged).toHaveBeenCalledWith('2026-07-04T13:00:00.000Z', 'America/New_York')
  })

  it('shows an existing schedule in its own zone and offers to change it', () => {
    renderField({ scheduledFor: '2026-07-04T13:00:00.000Z', scheduleTimezone: 'America/New_York' })
    expect(screen.getByRole('button', { name: 'Change' })).toBeInTheDocument()
    expect(screen.getByText(/America\/New_York/)).toBeInTheDocument()
  })

  it('clears a schedule by sending nulls, not by sending an empty string', async () => {
    renderField({ scheduledFor: '2026-07-04T13:00:00.000Z', scheduleTimezone: 'America/New_York' })
    await userEvent.click(screen.getByRole('button', { name: 'Change' }))
    await userEvent.click(screen.getByRole('button', { name: /Clear/i }))

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    expect(patchBodies[0]).toEqual({ scheduledFor: null, scheduleTimezone: null })
  })

  it('will not save an empty date', async () => {
    renderField()
    await userEvent.click(screen.getByRole('button', { name: 'Set' }))
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled()
    expect(patchBodies).toHaveLength(0)
  })

  it('keeps the stored schedule and says why when the server refuses', async () => {
    // Editing a schedule is a publish-bundle edit and can be refused outright, so the server's own
    // words matter more here than a house message.
    patchRejection = { status: 422, detail: 'the fire time is less than 10 minutes in the future' }
    const { onChanged } = renderField()
    await userEvent.click(screen.getByRole('button', { name: 'Set' }))
    await userEvent.type(screen.getByLabelText(/Scheduled date and time/i), '2026-07-04T09:00')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
    expect(toastErrorSpy.mock.calls[0][0]).toContain('10 minutes')
    expect(onChanged).not.toHaveBeenCalled()
  })

  it('offers no edit control to someone who cannot edit', () => {
    renderField({ canEdit: false, scheduledFor: '2026-07-04T13:00:00.000Z', scheduleTimezone: 'UTC' })
    expect(screen.queryByRole('button', { name: /Set|Change/ })).not.toBeInTheDocument()
  })

  it('carries no vocabulary from any one Workflow', () => {
    // Same rule the calendar holds itself to: scheduledFor is a plain Work Item field and any Workflow
    // may put an item on a clock, so nothing here may mention posts, publishing or platforms.
    const source = require('fs').readFileSync(
      require('path').join(__dirname, 'WorkItemScheduleField.tsx'),
      'utf8'
    )
    for (const banned of [/\bplatform/i, /\bcaption/i, /\bmarketing\b/i, /\binstagram\b/i, /\btiktok\b/i]) {
      expect(source, `source must not mention ${banned}`).not.toMatch(banned)
    }
  })
})
