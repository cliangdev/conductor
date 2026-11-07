// One plain line saying what a marketing metric counts, from the API's `OverviewMetricDefinition`. Shared by
// the definitions page and the channel detail charts so a metric reads the same everywhere.

import type { OverviewMetricDefinition } from '@/components/marketing/overview/types'

const MEASURE_WORDS: Record<string, string> = {
  impressions: 'impressions',
  unique_impressions: 'unique impressions',
  page_views: 'page views',
  unique_page_views: 'unique page views',
  first_time_downloads: 'first-time downloads',
  redownloads: 'redownloads',
  total_downloads: 'total downloads',
  downloads_of_record: 'downloads of record',
  tap_installs: 'tap installs',
  tap_new_downloads: 'tap new downloads',
  view_through_installs: 'view-through installs',
  taps: 'taps',
  spend: 'spend',
  visitors: 'visitors',
  sessions: 'sessions',
  pageviews: 'page views',
  conversion_sessions: 'converting sessions',
  conversion_events: 'conversion events',
  clicks: 'clicks',
  followers: 'followers',
  posts: 'posts',
  likes: 'likes',
  channel_views: 'channel views',
  website_clicks: 'website clicks',
  rating_count: 'ratings',
  rating_avg: 'average rating',
  position: 'average position',
}

const SOURCE_TYPE_WORDS: Record<string, string> = {
  APP_STORE_SEARCH: 'App Store search',
  APP_STORE_BROWSE: 'App Store browse',
  APP_REFERRER: 'App referrers',
  WEB_REFERRER: 'Web referrers',
  APP_CLIP: 'App Clips',
  INSTITUTIONAL_PURCHASE: 'institutional purchases',
  OTHER: 'other sources',
}

/** Measures that are a reading of a level, not a number of things. */
const READINGS = new Set(['rating_avg', 'position'])

const humanize = (raw: string) => raw.replace(/_/g, ' ').toLowerCase()

/** A measure name from the API's closed list (or `product:<key>`) in words. An unknown name stays readable. */
export function measureWords(measure: string): string {
  const known = MEASURE_WORDS[measure]
  if (known) return known
  if (measure.startsWith('product:')) return `${humanize(measure.slice('product:'.length))} (product metric)`
  return humanize(measure)
}

export function sourceTypeWords(sourceType: string): string {
  return SOURCE_TYPE_WORDS[sourceType] ?? humanize(sourceType)
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1)
}

function whatItCounts(definition: OverviewMetricDefinition): string {
  const numerator = measureWords(definition.numerator)
  if (definition.denominator) return `${capitalise(numerator)} divided by ${measureWords(definition.denominator)}`
  return `${READINGS.has(definition.numerator) ? 'Reads' : 'Counts'} ${numerator}`
}

/**
 * "Counts tap installs · Source: Apple Ads campaign report · Days in UTC". The source type is named only when it
 * narrows the number (ALL and null both mean every source); exclusions and time zone only when the API gives them.
 */
export function definitionLine(definition: OverviewMetricDefinition): string {
  const parts = [whatItCounts(definition)]
  const sourceType = definition.sourceType?.trim()
  if (sourceType && sourceType.toUpperCase() !== 'ALL') parts.push(`${sourceTypeWords(sourceType)} only`)
  if (definition.source?.trim()) parts.push(`Source: ${definition.source.trim()}`)
  if (definition.exclusions?.trim()) parts.push(`Excludes: ${definition.exclusions.trim()}`)
  if (definition.timeZone?.trim()) parts.push(`Days in ${definition.timeZone.trim()}`)
  return parts.join(' · ')
}
