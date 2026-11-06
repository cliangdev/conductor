import { describe, expect, it } from 'vitest'
import { definitionLine, measureWords, sourceTypeWords } from '../definitionLine'

describe('definitionLine', () => {
  it('says a plain count, its source and its time zone', () => {
    expect(definitionLine({ source: 'Apple Ads campaign report, tapInstalls', numerator: 'tap_installs', timeZone: 'UTC' })).toBe(
      'Counts tap installs · Source: Apple Ads campaign report, tapInstalls · Days in UTC',
    )
  })

  it('says a ratio as numerator divided by denominator, with a source type other than ALL', () => {
    expect(
      definitionLine({
        source: 'App Store Connect analytics',
        numerator: 'total_downloads',
        denominator: 'unique_impressions',
        sourceType: 'APP_STORE_SEARCH',
      }),
    ).toBe('Total downloads divided by unique impressions · App Store search only · Source: App Store Connect analytics')
  })

  it('leaves out an ALL or missing source type and says what is excluded', () => {
    const line = definitionLine({
      source: 'PostHog $pageview events',
      numerator: 'sessions',
      sourceType: 'ALL',
      exclusions: 'team browsers and share pages',
    })
    expect(line).toBe('Counts sessions · Source: PostHog $pageview events · Excludes: team browsers and share pages')
    expect(definitionLine({ source: 's', numerator: 'sessions', sourceType: null })).not.toMatch(/only/)
  })

  it('reads a level metric as a reading, not a count', () => {
    expect(definitionLine({ source: 'App Store ratings', numerator: 'rating_avg' })).toMatch(/^Reads average rating/)
    expect(definitionLine({ source: 'Search Console', numerator: 'position' })).toMatch(/^Reads average position/)
  })

  it('names a product metric by its key and tolerates null fields', () => {
    expect(
      definitionLine({ source: 'Product metrics feed', numerator: 'product:trial_started', denominator: null, exclusions: null, timeZone: null }),
    ).toBe('Counts trial started (product metric) · Source: Product metrics feed')
  })

  it('falls back to the raw words for a measure it does not know', () => {
    expect(measureWords('brand_new_measure')).toBe('brand new measure')
    expect(sourceTypeWords('SOME_NEW_TYPE')).toBe('some new type')
  })
})
