# Marketing report

`GET /api/v2/projects/{projectId}/marketing/report?window=7d|28d` returns everything a written marketing report needs in one compact payload. It is assembled by `MarketingReportService` from the same snapshot the overview uses (`MarketingOverviewQueryService.snapshot`), so the report and the overview can never disagree. Any project member can read it.

Percent values are fractions (0.12 = 12%). A window is full UTC days ending at each channel's `dataCompleteThrough`; `provisional` days are shown in `daily` but never counted.

## Sections

| Section | What it holds |
|---|---|
| `window`, `generatedAt` | The overall range (the funnel's window) and when the report was built. |
| `sources[]` | One entry per channel: connector, status, `dataCompleteThrough`, `lastFetchedAt`. |
| `headline[]` | In-app metrics the feed ranked as headline, in order, then ads spend and cost per install. Each carries value, previous, target, `status`, `missPct`, `sampleSize`, `goodDirection`. |
| `acquisition[]` | One row per source: reach, actions, installs, cost, `status` (the channel's). Unconnected channels are listed with their status and no numbers. |
| `funnel` | `basis` (COHORT or EVENT) and `stages[]` with `dropPct` from the previous stage and one `biggestDrop`. Uses the in-app stages when a feed declares them, else the cross-channel funnel. |
| `daily[]` | Store impressions and ad spend per day, clipped to the report window (`window.from` to `window.to`). |
| `scorecard[]` | Every metric that has a target or an area: value, target, `status`, `missPct`, `basis`, `sampleSize`, source. |
| `keywords[]` | Top 25 ads keywords by spend with bid, status, match type and in-app `trials` / `activations` joined by keyword id. `judgeable` needs at least 20 taps. |
| `ads[]` | Ad rows with creative type, product page, status and delivery. |
| `benchmarks[]` | Stored benchmarks joined to the report's own value. |
| `changes` | `open` (PLANNED, LIVE), `dueForReview` (LIVE past `dueOn`), `reviewedInWindow`, from Work Items on the `MARKETING_CHANGES` workflow. |

### Status bands

`ON_TRACK`, `WATCH` (miss within the band, default 10% of target), `OFF_TRACK`, `TOO_EARLY` (no value, or a rate on fewer than 20 observations) and `NO_TARGET`. A count or currency target that declares a window is prorated to the requested one; a target with no window is compared as stated.

### Organic App Store search

`app_store_search` is the store's `source:APP_STORE_SEARCH` downloads minus Apple Ads first-time downloads (the keyword rows' `tapNewDownloads`, so redownloads are not subtracted), falling back to the Apple Ads installs total when there are no keyword rows (a lower bound on organic then). It is floored at 0 and flagged `derived`, because Apple counts a paid first-time download as a search download too. Both are totals over each channel's own window; the row's `note` says so when the two end on different days. With no ads connected the row is not derived and its note says paid installs may be included.

### In-app outcomes by keyword

The in-app feed may send `keyword:{keywordId}:{text}` rows. The report joins its `trial_started` and `activated` sums to the ads keyword with the same id. A keyword with no in-app rows has `trials` and `activations` null (unknown), not zero.

### Changes

`reviewedInWindow` reads the Work Item's `updated_at`: a change is edited when it is reviewed. The list comes from one query that fetches the project and assignee, so it costs the same for one change as for a hundred.

## Benchmarks

An industry benchmark is stored per workspace and metric so a report can say how a number compares.

- `GET /projects/{projectId}/marketing/benchmarks` lists them (any member).
- `PUT /projects/{projectId}/marketing/benchmarks/{metricRef}` upserts one (ADMIN or CREATOR).
- `DELETE` on the same path removes it (ADMIN or CREATOR, idempotent).

`metricRef` is `CHANNEL.metric` (`STORE.conversion_rate`, `ADS.tap_through_rate`) or `PRODUCT.<key>`. A benchmark must name its source: `sourceName`, an http(s) `sourceUrl` and `checkedOn` (not in the future), so a figure is never recorded from memory.

A benchmark on a count or currency total (downloads, spend, ...) is stated **per 28 days** and prorated to the report window (7/28 of it for `7d`), so the 7d and 28d verdicts agree; the `benchmark` the report returns is the prorated figure. A ratio, rate, average or level (conversion rate, cost per install) is compared as stated.

In the report each benchmark gets `ratio` (value / prorated benchmark), `judged` (false when there is no value, or a rate rests on fewer than 20 observations) and `stale` (checked more than 90 days ago).
