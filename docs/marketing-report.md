# Marketing report

`GET /api/v2/projects/{projectId}/marketing/report?window=7d|28d` returns everything a written marketing report needs in one compact payload. It is assembled by `MarketingReportService` from the same snapshot the overview uses (`MarketingOverviewQueryService.snapshot`), so the report and the overview can never disagree. Any project member can read it.

Percent values are fractions (0.12 = 12%). A window is full UTC days ending at each channel's `dataCompleteThrough`; `provisional` days are shown in `daily` but never counted. A figure a source cannot give is `null`, never 0.

## Sections

| Section | What it holds |
|---|---|
| `window`, `generatedAt` | The overall range (the funnel's window) and when the report was built. |
| `sources[]` | One entry per channel: connector, status, `dataCompleteThrough`, `lastFetchedAt`, and `timeZone` (the zone that decides which day a row belongs to; Apple Ads reports `UTC`, with `searchTermTimeZone` `ORTZ` because search-term days are the org's report zone). Null where the connector does not state one. |
| `headline[]` | In-app metrics the feed ranked as headline, in order, then ads spend and cost per install. Each carries value, previous, target, `status`, `missPct`, `sampleSize`, `goodDirection` and its `definition`. |
| `acquisition[]` | One row per source: reach, actions, installs, cost, `status` (the channel's). Unconnected channels are listed with their status and no numbers. See below for the Apple Ads, organic search, web and social rows. |
| `funnel` | `basis` (COHORT or EVENT) and `stages[]` with `dropPct` from the previous stage and one `biggestDrop`. Uses the in-app stages when a feed declares them, else the cross-channel funnel. A stage read from a better source carries `note` (and `coverage` for Sales Reports). |
| `store` | `downloadsOfRecord` (Apple Sales Reports), `analyticsDownloads` (App Store Connect Analytics) and `coverage`. Null when App Store Connect is not connected. |
| `daily[]` | Store impressions and ad spend per day, clipped to the report window (`window.from` to `window.to`). |
| `scorecard[]` | Every metric that has a target or an area: value, target, `status`, `missPct`, `basis`, `sampleSize`, source, `definition`. |
| `keywords[]` | Top 25 ads keywords by spend with bid, status, match type, tap `installs`, first-time `newDownloads` and in-app `trials` / `activations` joined by keyword id. `judgeable` needs at least 20 taps. |
| `adsCoverage[]` | One row per Apple Ads campaign: how much of its spend the keyword and search-term rows account for. |
| `ads[]` | Ad rows with creative type, product page, status and delivery. |
| `benchmarks[]` | Stored benchmarks joined to the report's own value, judged only on a matching definition. |
| `definitions` | One definition per metric the report mentions, keyed by `CHANNEL.key`. |
| `changes` | `open` (PLANNED, LIVE), `dueForReview` (PLANNED or LIVE past `dueOn` with no verdict), `reviewedInWindow`, from Work Items on the `MARKETING_CHANGES` workflow. |
| `changeMarkers[]` | The day each LIVE or REVIEWED change went live, for a chart to mark. |

All of the sections after `acquisition` that are new (`store`, `adsCoverage`, `definitions`, `changeMarkers`, and the fields named below) are additive and optional: an older client that ignores them still reads every field it read before.

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

The in-app feed may send `keyword:{keywordId}:{text}` rows. The report joins its `trial_started` and `activated` sums to the ads keyword with the same id. A keyword with no in-app rows has `trials` and `activations` null (unknown), not zero.

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

## Weekly growth workflow

Every Monday 07:30 UTC the "Weekly growth review" workflow runs one Claude Code step. It reads the 28-day report, the verification ledger, the budget pacing and the metric definitions, looks at the changes already in flight, and proposes at most three actions. Each proposal becomes a PLANNED change on the `MARKETING_CHANGES` workflow, with a hypothesis, a review date and two pieces of evidence. A person reviews it and marks it live, or abandons it. The step can only read and propose: nothing is applied to an ad platform, a budget or a bid.

### Install and enable

- `POST /projects/{projectId}/marketing/weekly-growth/install` (ADMIN, a person rather than an agent; MCP `install_weekly_growth_workflow`) creates the workflow from the template `marketing/weekly-growth.yaml`. It is created **disabled**, so nothing runs until a person enables it. Calling it again returns the existing workflow unchanged (200 instead of 201, `created: false`), so an edited prompt or an enabled schedule is never overwritten.
- A `claude-code` step needs a runtime target and a Claude Code credential: set them under **Settings → AI Providers** before enabling (see [`claude-code`](workflows.md#claude-code--run-claude-code-headlessly) and [Cloud Run](workflows.md#cloud-run) in `workflows.md`).
- Enable it under **Workflows**. **Run Now** starts a review at any time.
- The step's `allowed_tools` are `get_marketing_report`, `get_verification_ledger`, `get_budget_pacing`, `get_marketing_definitions`, `list_work_items` and `propose_marketing_change`. Claude Code steps run unattended with permissions bypassed, so that list states what the step is meant to do. The rules below are enforced by the API, not by the prompt.

### Proposals

`POST /projects/{projectId}/marketing/proposals` (ADMIN or CREATOR; a workflow run token passes; MCP `propose_marketing_change`) takes `title`, `type` (a type of the workspace's `MARKETING_CHANGES` workflow), `hypothesis`, `reviewOn` (after today, UTC), `signals` and an optional `runId`. It answers 201 with `workItemId`, `displayId`, `proposalsThisWeek` and `weeklyLimit`.

- **Two signals, different metrics.** Exactly two, each `{metricRef, window, value, note?}`, on different `metricRef`s (`ADS.installs` and `ads.installs` are the same metric). One or three, or the same metric twice, is a 400.
- **Only verified numbers.** Each `metricRef` must be verified by the ledger for its `window`: a second source confirmed it and none disagrees (verdict `BOTH`). A refusal is a 422 that names every refused metric and its verdict: `ADS.installs is UNCHECKED: no second source`, `STORE.downloads has a MISMATCH (verdict CONNECTOR): a second source disagrees`. A metric with no ledger row (its channel is not connected, or it is a ratio of other metrics) and a verified metric with no figure in the window are refused too, never treated as verified or as zero. The `value` must be the ledger's figure for that window (within 0.01), so a number cannot be quoted from memory; the refusal says what the ledger shows.
- **At most three a week.** Per workspace and ISO week (Monday to Sunday, UTC); the fourth is a 409, `3 proposals already made this week`. The slots are rows in `work_item_import_key` (source `weekly-growth`, key `2026-W45:1` to `:3`), claimed in the transaction that creates the Work Item, so two proposals made at once cannot both take the last slot. A refused proposal creates nothing and takes no slot. Deleting a proposal's Work Item frees its slot.
- **What it creates.** A PLANNED change with the given `type` and `title`, the `hypothesis` field, `dueOn` set to `reviewOn`, and an `evidence` field with one bullet per signal: `- ADS.installs = 23 over 2026-09-29..2026-10-26 (verified: BOTH) — note`. A rolling window shows the days it resolved to and keeps its name (`over 2026-10-09..2026-11-05 (28d)`). The description says it was proposed by the weekly growth workflow (with the `runId` when given) and awaits human approval.
