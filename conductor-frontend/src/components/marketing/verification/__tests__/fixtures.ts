// Shared fixtures for the verification ledger tests.

import type {
  MarketingVerificationLedger,
  VerificationBackend,
  VerificationPlatformRead,
  VerificationRow,
  VerificationSummary,
} from '../types'

export function backend(overrides: Partial<VerificationBackend> = {}): VerificationBackend {
  return {
    value: 23,
    conductorValue: 23,
    daysCompared: 12,
    source: 'Rexipe backend',
    from: '2026-09-14',
    to: '2026-09-25',
    matches: true,
    ...overrides,
  }
}

export function platformRead(overrides: Partial<VerificationPlatformRead> = {}): VerificationPlatformRead {
  return {
    id: 'read-1',
    value: 550,
    from: '2026-09-01',
    to: '2026-09-28',
    sourceName: 'Search Console UI',
    recordedAt: '2026-09-30T10:00:00Z',
    conductorValueOverReadDays: 550,
    matches: true,
    ...overrides,
  }
}

export function row(overrides: Partial<VerificationRow> = {}): VerificationRow {
  return {
    channel: 'SEARCH',
    metric: 'impressions',
    metricRef: 'SEARCH.impressions',
    label: 'Impressions',
    unit: 'COUNT',
    windowFrom: '2026-09-01',
    windowTo: '2026-09-28',
    conductorValue: 550,
    backend: null,
    platformRead: platformRead(),
    verdict: 'BOTH',
    mismatch: false,
    readStale: false,
    trustNote: 'Search Console and the platform read agree.',
    ...overrides,
  }
}

/** BOTH (Search impressions), UNCHECKED (Social views) and a mismatched CONNECTOR (Ads installs). */
export function ledger(overrides: Partial<MarketingVerificationLedger> = {}): MarketingVerificationLedger {
  const rows: VerificationRow[] = [
    row(),
    row({
      channel: 'ADS',
      metric: 'installs',
      metricRef: 'ADS.installs',
      label: 'Installs',
      conductorValue: 7,
      windowFrom: '2026-09-01',
      windowTo: '2026-09-28',
      backend: backend({ value: 23, conductorValue: 7, matches: false }),
      platformRead: null,
      verdict: 'CONNECTOR',
      mismatch: true,
      trustNote: 'The backend disagrees and no platform read exists yet, so trust the connector until a read says otherwise.',
    }),
    row({
      channel: 'SOCIAL',
      metric: 'views',
      metricRef: 'SOCIAL.views',
      label: 'Post views',
      conductorValue: 1200,
      backend: null,
      platformRead: null,
      verdict: 'UNCHECKED',
      mismatch: false,
      trustNote: 'Only Conductor reports this; nothing has checked it.',
    }),
  ]
  const summary: VerificationSummary = {
    both: 1,
    connector: 1,
    backend: 0,
    platform: 0,
    unchecked: 1,
    mismatched: 1,
    staleReads: 0,
  }
  return { window: { from: '2026-09-01', to: '2026-09-28' }, rows, summary, ...overrides }
}
