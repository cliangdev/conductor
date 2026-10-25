import { describe, expect, it } from 'vitest'
import {
  buildPostHogConfig,
  buildPostHogConfigPatch,
  effectiveHealth,
  EMPTY_POSTHOG_FORM,
  formatChartTick,
  formFromConfig,
  validatePostHogForm,
} from './posthogSettings'

describe('buildPostHogConfig', () => {
  it('sends no host for US cloud and leaves empty optional fields out', () => {
    expect(buildPostHogConfig({ ...EMPTY_POSTHOG_FORM, projectId: ' 42 ' })).toEqual({ projectId: '42' })
  })
  it('sends eu, a self-hosted URL, the filter and the conversion event', () => {
    expect(buildPostHogConfig({ ...EMPTY_POSTHOG_FORM, projectId: '1', region: 'eu', eventFilter: "$host = 'rexipe.io'", conversionEvent: 'signup' }))
      .toEqual({ projectId: '1', host: 'eu', eventFilter: "$host = 'rexipe.io'", conversionEvent: 'signup' })
    expect(buildPostHogConfig({ ...EMPTY_POSTHOG_FORM, projectId: '1', region: 'custom', customHost: 'https://ph.example.com' }).host)
      .toBe('https://ph.example.com')
  })
})

describe('buildPostHogConfigPatch', () => {
  it('sends cleared fields as empty strings so they are cleared', () => {
    expect(buildPostHogConfigPatch({ ...EMPTY_POSTHOG_FORM, projectId: '1' }))
      .toEqual({ projectId: '1', host: '', eventFilter: '', conversionEvent: '' })
  })
})

describe('formFromConfig', () => {
  it('reads a stored config back into the form', () => {
    expect(formFromConfig({ projectId: 7, host: 'EU', eventFilter: "a = 'b'" }))
      .toMatchObject({ projectId: '7', region: 'eu', eventFilter: "a = 'b'", conversionEvent: '' })
    expect(formFromConfig({ host: 'https://ph.example.com' })).toMatchObject({ region: 'custom', customHost: 'https://ph.example.com' })
    expect(formFromConfig(null).region).toBe('us')
  })
})

describe('validatePostHogForm', () => {
  const ok = { ...EMPTY_POSTHOG_FORM, projectId: '1' }
  it('accepts a minimal form', () => expect(validatePostHogForm(ok)).toBeNull())
  it('requires the project id and a self-hosted URL', () => {
    expect(validatePostHogForm(EMPTY_POSTHOG_FORM)).toMatch(/Project ID/)
    expect(validatePostHogForm({ ...ok, region: 'custom' })).toMatch(/self-hosted/)
  })
  it('checks the filter shape', () => {
    expect(validatePostHogForm({ ...ok, eventFilter: "$host = 'rexipe.io'" })).toBeNull()
    expect(validatePostHogForm({ ...ok, eventFilter: 'nope' })).toMatch(/property = 'value'/)
  })
})

describe('formatChartTick', () => {
  it('shortens dates to "Oct 3"', () => {
    expect(formatChartTick('2026-10-03')).toBe('Oct 3')
    expect(formatChartTick('2026-10-03T00:00:00Z')).toBe('Oct 3')
    expect(formatChartTick('Oct-2026')).toBe('Oct-2026')
  })
})

describe('effectiveHealth', () => {
  it('shows DEGRADED with its message even when the last fetch was HEALTHY', () => {
    expect(effectiveHealth({ healthStatus: 'DEGRADED', healthMessage: 'No new data since Sep 10' }, 'HEALTHY'))
      .toEqual({ status: 'DEGRADED', message: 'No new data since Sep 10' })
  })
  it('lets a failed fetch outrank a healthy connection', () => {
    expect(effectiveHealth({ healthStatus: 'HEALTHY' }, 'SETUP_REQUIRED', 'Fix the key'))
      .toEqual({ status: 'SETUP_REQUIRED', message: 'Fix the key' })
  })
  it('is healthy only when both are', () => {
    expect(effectiveHealth({ healthStatus: 'HEALTHY' }, 'HEALTHY')).toEqual({ status: 'HEALTHY', message: null })
    expect(effectiveHealth(null, null)).toEqual({ status: null, message: null })
  })
})
