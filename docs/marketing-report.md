# Marketing report

`GET /api/v2/projects/{projectId}/marketing/report?window=7d|28d` returns everything a written marketing report needs in one compact payload. It is assembled by `MarketingReportService` from the same snapshot the overview uses (`MarketingOverviewQueryService.snapshot`), so the report and the overview can never disagree. Any project member can read it.

Percent values are fractions (0.12 = 12%). A window is full UTC days ending at each channel's `dataCompleteThrough`; `provisional` days are shown in `daily` but never counted. A figure a source cannot give is `null`, never 0.

## Sections

| Section | What it holds |
|---|---|
| `window`, `generatedAt` | The overall range (the funnel's window) and when the report was built. |
| `sources[]` | One entry per channel: connector, status, `dataCompleteThrough`, `lastFetchedAt`, and `timeZone` (the zone that decides which day a row belongs to; Apple Ads reports `UTC`, with `searchTermTimeZone` `ORTZ` because search-term days are the org's report zone). Null where the connector does not state one. |
| `headline[]` | In-app metrics the feed ranked as headline, in order, then ads spend and cost per install. Each carries value, previous, target, `status`, `missPct`, `sampleSize`, `goodDirection` and its `definition`. |
| `acquisition[]` | One row per source: reach, actions, installs, cost, `status` (the channel's). Unconnected channels are listed with their status and no numbers. See below for the Apple Ads, organic search, web and social rows, and [Channel quality](#channel-quality-product-metrics-contract-v3) for the `email`, `shares` and `link_family:{family}` rows. |
| `funnel` | `basis` (COHORT or EVENT) and `stages[]` with `dropPct` from the previous stage and one `biggestDrop`. Uses the in-app stages when a feed declares them, else the cross-channel funnel. A stage read from a better source carries `note` (and `coverage` for Sales Reports). |
| `store` | `downloadsOfRecord` (Apple Sales Reports), `analyticsDownloads` (App Store Connect Analytics) and `coverage`. Null when App Store Connect is not connected. |
| `daily[]` | Store impressions and ad spend per day, clipped to the report window (`window.from` to `window.to`). |
| `scorecard[]` | Every metric that has a target or an area: value, target, `status`, `missPct`, `basis`, `sampleSize`, source, `definition`. |
| `keywords[]` | Top 25 ads keywords by spend with bid, status, match type, tap `installs`, first-time `newDownloads` and in-app `trials` / `activations` / `retainedW2` joined by keyword id, with the cost per activated install, week-2 retained install and trial. `judgeable` needs at least 20 taps. |
| `adsCoverage[]` | One row per Apple Ads campaign: how much of its spend the keyword and search-term rows account for. |
| `ads[]` | Ad rows with creative type, product page, status and delivery. |
| `benchmarks[]` | Stored benchmarks joined to the report's own value, judged only on a matching definition. |
| `definitions` | One definition per metric the report mentions, keyed by `CHANNEL.key`. |
| `changes` | `open` (PLANNED, LIVE), `dueForReview` (PLANNED or LIVE past `dueOn` with no verdict), `reviewedInWindow`, from Work Items on the `MARKETING_CHANGES` workflow. |
| `changeMarkers[]` | The day each LIVE or REVIEWED change went live, for a chart to mark. |
| `payback` | Cohort proceeds over spend per paid channel (Apple Ads today) and in total, on the contract's 12-month horizon. Null without a `proceeds_net` metric or any channel with spend. |
| `exclusions[]` | What the numbers leave out: likely App Review installs, with the feed's own wording. Empty when the feed reports none. |
| `asoKeywords[]` | Tracked App Store keywords: latest organic `rank`, `popularity` and the `rankChange` since the previous window's end. At most 50, best rank first (the MCP tool returns the best 20). |
| `verification` | The verification ledger's tally for the same window: `unchecked`, `mismatched`, `staleReads`, `both`. Null when nothing is connected. |

All of the sections after `acquisition` that are new (`store`, `adsCoverage`, `definitions`, `changeMarkers`, `payback`, `exclusions`, `asoKeywords`, `verification` and the fields named below) are additive and optional: an older client that ignores them still reads every field it read before.

### Status bands

`ON_TRACK`, `WATCH` (miss within the band, default 10% of target), `OFF_TRACK`, `TOO_EARLY` (no value, or a rate on fewer than 20 observations) and `NO_TARGET`. A count or currency target that declares a window is prorated to the requested one; a target with no window is compared as stated.

### Apple Ads

The `apple_ads` row reads Apple's **tap installs** (`tapNewDownloads` + `tapRedownloads`), the figure Apple's console shows as "installs"; `cost` is spend and the headline `cost_per_install` is spend divided by tap installs. `viewThroughInstalls` is Apple's `viewInstalls` (installs with no tap): it is reported beside the row and never added to `installs`. It is null when the feed reports none.

On `keywords[]`, `installs` is the same tap-install count as on a campaign row and `cpi` is spend over tap installs. This changes what keyword `installs` meant before RE-99 (first-time downloads); first-time downloads are now `newDownloads`, a subset of `installs`.

`adsCoverage[]` answers "do the keyword rows account for the campaign's spend?" per campaign, largest spend first: `spend` (the campaign row), `keywordSpend` (keyword rows whose state names the campaign), `searchTermSpend` (search-term rows whose key starts with the campaign id), `keywordCoveragePct` (`keywordSpend / spend`, null when the campaign has no spend) and `rows` (keyword plus search-term rows). A campaign that no keyword or search-term row reaches shows 0 and `rows` 0, which is the gap to look at. Two limits apply: a keyword row is attributed by the `campaignId` the connector stored in its state, so a row without it counts for no campaign; and the search-term list is the 50 largest rows of the account, so `searchTermSpend` is a lower bound on an account with more terms.

### Organic App Store search

`app_store_search` is organic search net of what Apple Ads bought, computed over the days both sources have settled:

- **D** is every day in the report window where the store's `downloads` at `source:APP_STORE_SEARCH` and Apple Ads' `new_downloads` both have a settled value.
- `installs` = store search first-time downloads over D minus Apple Ads first-time downloads over D.
- `reach` (label "Impressions (organic)") = store search impressions over D minus Apple Ads impressions over D.
- Each is floored at 0 **once, after summing**: flooring day by day would turn a day where paid exceeded search into 0 and overstate organic on the rest. The two sides never use different day ranges.
- `derivation` shows the work: `from`, `to`, `days` (how many days of D), `grossInstalls`, `paidInstalls`, `grossReach`, `paidReach`. The days need not be consecutive.
- The `note` reads "App Store search first-time downloads minus Apple Ads first-time downloads, and search impressions minus Apple Ads impressions, over the N days both have settled (from–to), floored at 0."

Cases where it is not computed:

| Situation | `installs`, `reach` | `derived` | `note` |
|---|---|---|---|
| Apple Ads not connected, or connected with no days at all in the window | the store's gross figures | false | "Apple Ads is not reporting, so any paid installs are counted here." |
| Apple Ads has days but none with first-time downloads (data from before that field) | null | false | "Waiting for Apple Ads first-time downloads for these days" |
| Both have first-time downloads but no day in common | null | false | "No day in this window has both App Store search downloads and Apple Ads first-time downloads settled yet." |
| Impressions are missing from either source on some days of D | `reach` null, `installs` computed | true | the note adds that organic impressions are unknown |

### Downloads of record

When App Store Connect has Sales Reports (`downloads_of_record`, needs the connection's vendor number), the funnel's `store_downloads` stage reads it instead of Analytics, because Sales Reports are never privacy-thresholded. The stage carries `coverage` (`daysCovered` of `daysInWindow`, from the days a Sales Reports file was read) and a `note`. `store` gives both figures side by side: `downloadsOfRecord` and `analyticsDownloads`. Coverage is null when no day count was stored, not 0. A figure with partial coverage is still used; read `coverage` before comparing it with Analytics.

The traffic-source rows (`app_store_search`, `app_store_browse`, `web_referrer`) stay on Analytics: Sales Reports have no source split, so their installs add up to the Analytics total, not to `downloadsOfRecord`.

### Web and social

The `web_referrer` row's `actions`, and the funnel stage `web_conversion_clicks`, count `conversion_sessions` (label "Sessions with an App Store tap") when the web connection reports them, else `conversion_clicks` as before. The funnel stage's note gives the share of sessions ("12 of 68 sessions"). The `social` row's `reach` is followers across Meta, YouTube and TikTok, and `likes` is the accounts' total likes (TikTok), null when none is reported.

### In-app outcomes by keyword

The in-app feed may send `keyword:{keywordId}:{text}` rows. The report joins its `trial_started`, `activated` and `retained_w2` sums to the ads keyword with the same id, as `trials`, `activations` and `retainedW2`. A keyword with no in-app rows has all three null (unknown), not zero; so does one whose feed does not declare the metric at all. See [Keyword quality](#keyword-quality) for the cost per outcome.

## Channel quality (product-metrics contract v3)

These sections read the keys the product backend sends under [contract v3](integrations-product-metrics.md#version-3). Each is additive and optional, and the one rule is the contract's: **a key the feed did not send is null (the figure, row or section is omitted), never 0.** A value the feed reported as 0 reads 0. Nothing here is stored: every figure is computed on read from the feed's rows.

| Section | Source keys |
|---|---|
| `keywords[]` quality | `activated`, `retained_w2`, `trial_started` at `keyword:{id}:{text}`; the ads keyword's `spend` |
| `payback` | `proceeds_net` at `source:{name}`; the acquisition row's `cost` (spend) |
| `exclusions[]` | `app_review_installs_excluded` (window sum) and that metric's `exclusions` text |
| `email` row | `email_sends`, `email_clicks`, `email_trials_saved`, totals or `channel:email` |
| `shares` row | `share_links_created`, `share_views`, `share_installs`, totals or `channel:shares` |
| `link_family:{family}` rows | Tracked links: `STORE campaign_downloads` at `ct:{token}` and the Posts' latest views |
| `asoKeywords[]` | `aso_rank`, `aso_popularity` at `aso:{keyword}` |
| `verification` | The verification ledger (see [Verification](#verification)) |

The `aso:` and `channel:` rows are not in the overview's tables, so the report reads them with **one extra query** (the feed's `channel:` and `aso:` rows for the metrics it declares, from one window before the report window to its end). It is a single query whatever the number of keywords or channels, and it is skipped when the feed declares none of those metrics. `MarketingReportQueryCountTest` seeds both and checks one row costs the same as twenty.

### Keyword quality

On each `keywords[]` row, `activations`, `retainedW2` and `trials` are the feed's counts for the keyword (the window's cohorts), and:

- `costPerActivatedInstall` = `spend / activations`
- `costPerRetainedW2Install` = `spend / retainedW2`
- `costPerTrial` = `spend / trials`

Each cost is null when the spend or its count is null, and when the count is 0. A count the feed reported as 0 is named in `qualityNote` ("No activations yet", "No week-2 retained installs yet", "No trials yet", joined with `;`), so a null cost reads as "none yet" rather than "unknown". With spend 17.51, 2 activations, 1 retained and 1 trial the costs are 8.755, 17.51 and 17.51. The row is judged like the others: read `judgeable` (20 taps) before acting on a cost.

### Payback

`payback.channels[]` has one row per acquisition row that has spend and whose channel the feed names as a source: today Apple Ads, matched to the feed's `source:` row whose name reads `apple_search_ads`, `apple_ads` or `asa` (case and punctuation ignored). Each row is `{channel, label, spend, proceedsNet12m, ratio, note}`: `proceedsNet12m` is the feed's `proceeds_net` at that source summed over the window's cohorts and `ratio` is `proceedsNet12m / spend`.

- **Horizon.** `proceeds_net` is the contract's figure: proceeds after Apple's commission, counted by cohort over a 12-month horizon and restated as a cohort matures. The ratio therefore compares the spend of the window with the proceeds its cohorts have earned *so far*; a recent window stays low until its cohorts have had the time. `payback.basis` says this in the payload.
- A feed that declares `proceeds_net` but sent nothing for the channel's source gives the row `proceedsNet12m` and `ratio` null and a `note`. Spend of 0 gives a null ratio.
- `payback.total` sums the listed channels' spend and proceeds. Its `ratio` is null unless every channel has proceeds, so a missing channel never inflates the total. Organic proceeds are not credited to paid spend.
- No `proceeds_net` metric, or no channel with spend, means `payback` is null.

### Exclusions

`exclusions[]` is `[{key: "app_review_installs", label: "Likely App Review installs", value, note}]`. `value` is the window sum of `app_review_installs_excluded`, the installs the feed left out of its other numbers (7 in the reference window); `note` is that metric's definition `exclusions` text when the feed gave one. A metric with no value in the window gives no entry; a reported 0 gives `value` 0.

### Email and shares

The `email` row reads `email_sends` as `reach` ("Emails sent"), `email_clicks` as `actions` ("Clicks") and `email_trials_saved` as `installs` ("Trials saved", in `installsLabel`). The `shares` row reads `share_links_created` as `reach` ("Links shared"), `share_views` as `actions` ("Views") and `share_installs` as `installs`. Each figure is the feed's day total over the window; when a key has no total, the sum of its `channel:email` or `channel:shares` rows is used instead. A figure with neither is null and its label is null; a row with none of its three figures is omitted. Both rows carry a `definitionLine` and the matching `definitions` entries (`PRODUCT.email_sends`, ...); the Email line adds the feed's own rule for "trials saved" from that metric's description.

### Tracked-link families

One acquisition row per tracked-link family (`TrackedLinkService.performance` over the report window), `link_family:{family}`, labelled `"{family} links ({kind})"`. `installs` is the family's App Store campaign downloads (`installsLabel` "Downloads"); a POST family's `reach` is its Posts' latest views ("Post views"). Apple omits a link under its reporting threshold, so a family with no reported row has `installs` null and the note "below Apple's reporting threshold", never 0. Without a connected App Store the note says the downloads cannot be read instead. A workspace with no tracked links has no such rows.

### ASO keywords

`asoKeywords[]` lists each tracked keyword with `rank` and `popularity`, both *levels*: the latest reading at or before the window's last day. `previousRank` is the latest rank at or before the day before the window starts, and `rankChange = rank - previousRank`, so **a negative change is an improvement** (a lower rank is better). `rankChange` and `previousRank` are null when there is no earlier reading, or none since it (the same reading is never compared with itself). Best rank first, keywords with only a popularity last, at most 50.

### Verification

`verification` is the ledger's `summary` for the window the report was asked for: `unchecked` (no second source, never "verified"), `mismatched`, `staleReads` and `both`. It lets a reader of the report see how much of it is verified; the per-metric detail is the ledger itself. It is null when no channel is connected. It costs a second snapshot of the same window, because the ledger builds its own.

### Definitions

`definitions` maps `CHANNEL.key` (for example `ADS.installs`) to the metric's definition: `source` (the platform report or field), `numerator`, `denominator`, `sourceType`, `exclusions` and `timeZone`. It covers every metric the report mentions: the headline and scorecard metrics, the metrics each acquisition row reads, the metric each benchmark was compared with and the `downloads_of_record` and conversion-session figures when they are used. Each headline metric and scorecard row also carries its own `definition`. `get_marketing_definitions` lists every visible metric, not only the ones in the report.

### Changes

`reviewedInWindow` reads the Work Item's `completed_at` (when it reached REVIEWED) and falls back to `updated_at` for an item with none. `dueForReview` holds PLANNED or LIVE changes whose `dueOn` (the review date) is before today and whose `verdict` field is empty; a change with a verdict is reviewed, whatever its status. The list comes from one query that fetches the project and assignee, so it costs the same for one change as for a hundred.

`changeMarkers` come from the same rows: LIVE or REVIEWED changes whose `liveSince` field is a date on or before the window's last day, oldest first. A field that is not a date is skipped. A REVIEWED change that was reviewed before the window is not in the query, so it has no marker.

## Benchmarks

An industry benchmark is stored per workspace and metric so a report can say how a number compares.

- `GET /projects/{projectId}/marketing/benchmarks` lists them (any member).
- `PUT /projects/{projectId}/marketing/benchmarks/{metricRef}` upserts one (Editor role or Admin).
- `DELETE` on the same path removes it (ADMIN or CREATOR, idempotent).

`metricRef` is `CHANNEL.metric` (`STORE.conversion_rate`, `ADS.tap_through_rate`) or `PRODUCT.<key>`. A benchmark must name its source: `sourceName`, an http(s) `sourceUrl` and `checkedOn` (not in the future), so a figure is never recorded from memory.

A benchmark on a count or currency total (downloads, spend, ...) is stated **per 28 days** and prorated to the report window (7/28 of it for `7d`), so the 7d and 28d verdicts agree; the `benchmark` the report returns is the prorated figure. A ratio, rate, average or level (conversion rate, cost per install) is compared as stated.

### Comparing like with like

A benchmark may declare `numerator`, `denominator` and `sourceType`. It is judged only against a metric whose definition has the same three (a null source type means `ALL`; see `BenchmarkComparability`):

1. Take the metric the benchmark's `metricRef` names. If its definition matches, compare against it.
2. If it does not, look for another visible metric of the **same channel** whose definition matches and compare against that one. `comparedMetricRef` names it. Example: a benchmark stored on `STORE.conversion_rate` as total downloads over unique impressions for App Store search is judged against `STORE.conversion_rate_search` (33 / 5,639 = 0.59% in the reference window), not the all-source `conversion_rate` (30 / 5,652 = 0.53%).
3. If none matches, `comparable` is false and `reason` names both definitions ("benchmark: total downloads / unique impressions (App Store search); metric: first-time downloads / unique impressions (all sources)"). `value` still shows the named metric's figure.
4. A benchmark with no definition is `comparable: false`, `reason: "definition missing"`, and never judged; its value is still shown. So is one whose `metricRef` is not a metric of the report (`reason: "metric not in the report"`).

In the report each benchmark gets `ratio` (compared value / prorated benchmark), `judged` (false unless `comparable`, there is a value, and a rate rests on at least 20 observations), `stale` (checked more than 90 days ago), its own `numerator` / `denominator` / `sourceType`, `comparedMetricRef`, `metricDefinition` (the compared metric's definition) and `comparable` / `reason`. A non-comparable pair is never `judged: true`.

## Reference window

Every acceptance number for RE-99 is stated for 2026-09-29..2026-10-26 (28 days): Apple Ads 23 tap installs (1 view-through) for USD 162.86, 3,405 impressions, 89 taps and a cost per install of USD 7.08; organic search 6 first-time downloads (29 - 23) and 3,696 impressions (7,101 - 3,405); search conversion 0.59%; Sales Reports 30 downloads on 28 of 28 days. `MarketingReportReferenceWindowTest` rebuilds that window from rows and asserts each. To check a live workspace, read the report for exactly those days with the fixed-window form of the `window` parameter (`window=2026-09-29..2026-10-26`, from the fixed-window task), after each connector's re-walk has run.
