import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { EMPTY_POSTHOG_FORM, buildPostHogConfigPatch, type PostHogFormValues } from '@/lib/posthogSettings'
import { PostHogSettingsFields } from './PostHogSettingsFields'

describe('PostHogSettingsFields', () => {
  it('shows the internal users cohort and excluded page prefixes with help text', () => {
    render(<PostHogSettingsFields values={EMPTY_POSTHOG_FORM} onChange={vi.fn()} />)
    expect(screen.getByLabelText(/Internal users cohort ID/)).toHaveValue('')
    expect(screen.getByLabelText(/Excluded page prefixes/)).toHaveValue('')
    expect(screen.getByText(/Digits only/)).toBeInTheDocument()
    expect(screen.getByText(/Comma-separated paths, each starting with \//)).toBeInTheDocument()
  })

  it('reports each typed value and the edit patch then carries both', () => {
    let values: PostHogFormValues = { ...EMPTY_POSTHOG_FORM, projectId: '42' }
    const onChange = vi.fn((next: PostHogFormValues) => {
      values = next
    })
    const { rerender } = render(<PostHogSettingsFields values={values} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText(/Internal users cohort ID/), { target: { value: '339420' } })
    rerender(<PostHogSettingsFields values={values} onChange={onChange} />)
    fireEvent.change(screen.getByLabelText(/Excluded page prefixes/), { target: { value: '/share, /s/' } })
    expect(buildPostHogConfigPatch(values)).toMatchObject({
      projectId: '42',
      internalCohortId: '339420',
      excludePathPrefixes: '/share, /s/',
    })
  })

  it('shows stored values when editing', () => {
    render(
      <PostHogSettingsFields
        values={{ ...EMPTY_POSTHOG_FORM, projectId: '1', internalCohortId: '339420', excludePathPrefixes: '/share' }}
        onChange={vi.fn()}
        idPrefix="edit"
      />,
    )
    expect(screen.getByLabelText(/Internal users cohort ID/)).toHaveValue('339420')
    expect(screen.getByLabelText(/Excluded page prefixes/)).toHaveValue('/share')
  })
})
