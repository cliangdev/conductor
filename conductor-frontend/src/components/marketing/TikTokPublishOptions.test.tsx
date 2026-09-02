import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  TikTokPublishOptions,
  EMPTY_TIKTOK_OPTIONS,
  isPrivatePrivacyLevel,
  normalizeTikTokOptions,
  privacyLevelLabel,
  tiktokOptionsProblem,
  type TikTokPublishOptionValues,
} from './TikTokPublishOptions'

const ALL_LEVELS = ['PUBLIC_TO_EVERYONE', 'MUTUAL_FOLLOW_FRIENDS', 'FOLLOWER_OF_CREATOR', 'SELF_ONLY']

function values(overrides: Partial<TikTokPublishOptionValues> = {}): TikTokPublishOptionValues {
  return { ...EMPTY_TIKTOK_OPTIONS, ...overrides }
}

function renderOptions(props: Partial<React.ComponentProps<typeof TikTokPublishOptions>> = {}) {
  const onChange = vi.fn()
  const result = render(
    <TikTokPublishOptions
      idPrefix="tiktok-conn-a"
      accountLabel="@acme"
      privacyLevelOptions={ALL_LEVELS}
      value={values()}
      onChange={onChange}
      {...props}
    />
  )
  return { onChange, ...result }
}

describe('privacyLevelLabel', () => {
  it("uses TikTok's own wording for the levels it reports", () => {
    expect(privacyLevelLabel('PUBLIC_TO_EVERYONE')).toBe('Everyone')
    expect(privacyLevelLabel('MUTUAL_FOLLOW_FRIENDS')).toBe('Friends')
    expect(privacyLevelLabel('FOLLOWER_OF_CREATOR')).toBe('Followers')
    expect(privacyLevelLabel('SELF_ONLY')).toBe('Only me (private)')
  })

  it('humanizes a level TikTok adds that this build has never seen', () => {
    expect(privacyLevelLabel('SOME_NEW_LEVEL')).toBe('Some new level')
  })
})

describe('isPrivatePrivacyLevel', () => {
  it('is true only for the creator-only level', () => {
    expect(isPrivatePrivacyLevel('SELF_ONLY')).toBe(true)
    expect(isPrivatePrivacyLevel('PUBLIC_TO_EVERYONE')).toBe(false)
    expect(isPrivatePrivacyLevel(null)).toBe(false)
  })
})

describe('normalizeTikTokOptions', () => {
  it('fills every toggle from a partial payload without inventing a privacy level', () => {
    expect(normalizeTikTokOptions({ disableDuet: true })).toEqual({
      ...EMPTY_TIKTOK_OPTIONS,
      disableDuet: true,
    })
    expect(normalizeTikTokOptions(null).privacyLevel).toBeNull()
    expect(normalizeTikTokOptions(undefined).privacyLevel).toBeNull()
  })
})

describe('tiktokOptionsProblem', () => {
  it('reports a missing privacy level rather than defaulting to one', () => {
    expect(tiktokOptionsProblem(values())).toMatch(/who can see/i)
  })

  it('is clear once a privacy level is chosen', () => {
    expect(tiktokOptionsProblem(values({ privacyLevel: 'PUBLIC_TO_EVERYONE' }))).toBeNull()
  })

  it('rejects branded content combined with a private privacy level', () => {
    const problem = tiktokOptionsProblem(
      values({ privacyLevel: 'SELF_ONLY', brandContentToggle: true })
    )
    expect(problem).toMatch(/branded content/i)
    expect(problem).toMatch(/can.t be posted privately/i)
  })

  it('allows a private post that only discloses the creator’s own brand', () => {
    expect(
      tiktokOptionsProblem(values({ privacyLevel: 'SELF_ONLY', brandOrganicToggle: true }))
    ).toBeNull()
  })
})

describe('TikTokPublishOptions', () => {
  it('lists exactly the privacy levels the creator reported and nothing more', () => {
    renderOptions({ privacyLevelOptions: ['PUBLIC_TO_EVERYONE', 'SELF_ONLY'] })

    const select = screen.getByLabelText(/who can view this video/i)
    const rendered = Array.from(select.querySelectorAll('option')).map((o) => o.textContent)
    expect(rendered).toEqual(['Select who can view this video…', 'Everyone', 'Only me (private)'])
    expect(screen.queryByRole('option', { name: 'Friends' })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Followers' })).not.toBeInTheDocument()
  })

  it('preselects no privacy level', () => {
    renderOptions()
    expect(screen.getByLabelText(/who can view this video/i)).toHaveValue('')
  })

  it('says so when TikTok reported no privacy options for this account', () => {
    renderOptions({ privacyLevelOptions: [] })
    expect(screen.getByRole('alert')).toHaveTextContent(/no privacy options/i)
  })

  it('reports the chosen privacy level upward', async () => {
    const { onChange } = renderOptions()
    await userEvent.selectOptions(screen.getByLabelText(/who can view this video/i), 'SELF_ONLY')
    expect(onChange).toHaveBeenCalledWith(values({ privacyLevel: 'SELF_ONLY' }))
  })

  it('offers the comment, duet and stitch interaction toggles', () => {
    renderOptions()
    expect(screen.getByRole('switch', { name: 'Comment' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Duet' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Stitch' })).toBeInTheDocument()
  })

  it('renders an allowed interaction as on and turning it off sets the disable flag', async () => {
    const { onChange } = renderOptions({ value: values({ privacyLevel: 'PUBLIC_TO_EVERYONE' }) })

    const comment = screen.getByRole('switch', { name: 'Comment' })
    expect(comment).toBeChecked()
    await userEvent.click(comment)

    expect(onChange).toHaveBeenCalledWith(
      values({ privacyLevel: 'PUBLIC_TO_EVERYONE', disableComment: true })
    )
  })

  it('keeps the two disclosure toggles hidden until commercial content is disclosed', async () => {
    renderOptions()
    expect(screen.queryByRole('switch', { name: 'Your Brand' })).not.toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: 'Branded Content' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('switch', { name: /disclose commercial content/i }))

    expect(screen.getByRole('switch', { name: 'Your Brand' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Branded Content' })).toBeInTheDocument()
  })

  it('labels Your Brand as promotional content and Branded Content as a paid partnership', async () => {
    renderOptions({ value: values({ privacyLevel: 'PUBLIC_TO_EVERYONE', brandOrganicToggle: true }) })

    expect(screen.getByRole('switch', { name: 'Your Brand' })).toBeChecked()
    expect(screen.getByText(/Promotional content/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('switch', { name: 'Branded Content' }))
    expect(screen.getByText(/Paid partnership/)).toBeInTheDocument()
  })

  it('shows both disclosure toggles already open when the Post carries a disclosure', () => {
    renderOptions({ value: values({ privacyLevel: 'PUBLIC_TO_EVERYONE', brandContentToggle: true }) })
    expect(screen.getByRole('switch', { name: /disclose commercial content/i })).toBeChecked()
    expect(screen.getByRole('switch', { name: 'Branded Content' })).toBeChecked()
  })

  it('clears both disclosure toggles when commercial content is un-disclosed', async () => {
    const { onChange } = renderOptions({
      value: values({
        privacyLevel: 'PUBLIC_TO_EVERYONE',
        brandContentToggle: true,
        brandOrganicToggle: true,
      }),
    })

    await userEvent.click(screen.getByRole('switch', { name: /disclose commercial content/i }))

    expect(onChange).toHaveBeenCalledWith(values({ privacyLevel: 'PUBLIC_TO_EVERYONE' }))
  })

  it('explains why branded content cannot be posted privately instead of silently disabling it', async () => {
    renderOptions({ value: values({ privacyLevel: 'SELF_ONLY', brandContentToggle: true }) })

    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent(/branded content/i)
    expect(alert).toHaveTextContent(/can.t be posted privately/i)
    // The toggle stays reachable so the creator can resolve it either way.
    expect(screen.getByRole('switch', { name: 'Branded Content' })).toBeEnabled()
    expect(screen.getByLabelText(/who can view this video/i)).toBeEnabled()
  })

  it('does not complain about a private post with no branded content', () => {
    renderOptions({ value: values({ privacyLevel: 'SELF_ONLY' }) })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('scopes its control ids to the target so two accounts do not collide', () => {
    const { unmount } = renderOptions({ idPrefix: 'tiktok-conn-a' })
    expect(screen.getByLabelText(/who can view this video/i).id).toBe('tiktok-conn-a-privacy')
    unmount()

    renderOptions({ idPrefix: 'tiktok-conn-b' })
    expect(screen.getByLabelText(/who can view this video/i).id).toBe('tiktok-conn-b-privacy')
  })

  it('disables every control while a save is in flight', () => {
    renderOptions({ disabled: true, value: values({ brandContentToggle: true }) })
    expect(screen.getByLabelText(/who can view this video/i)).toBeDisabled()
    expect(screen.getByRole('switch', { name: 'Comment' })).toBeDisabled()
    expect(screen.getByRole('switch', { name: 'Branded Content' })).toBeDisabled()
  })
})
