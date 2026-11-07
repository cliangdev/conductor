# Product metrics contract (v3)

The `product-metrics` connector reads your product's own numbers (first opens, trials, paid, cost per trial)
from a URL you host, so the marketing overview can show the in-app half of the funnel next to the web, store
and ads channels. Conductor owns this contract. Your backend serves it; Conductor only reads.

Conductor reads **version 1, version 2 and version 3**. Versions 2 and 3 only add optional fields and wider
limits, so a v1 or v2 backend keeps working unchanged; see [Version 2](#version-2) and [Version 3](#version-3)
for what they add.

The machine-readable shape is [`integrations-product-metrics.schema.json`](integrations-product-metrics.schema.json)
(JSON Schema, draft 2020-12). It is the single source of truth for fields and types, versions 1 to 3. This page
explains what the fields mean. A backend can be a mobile app's API, a data warehouse job or a small serverless function.

## Connecting

In **Integrations**, add **Product metrics** (you may add several, one per backend) and give it:

| Field | What it is |
|---|---|
| Metrics URL | The endpoint below. `https` only. Hosts that resolve to private or internal addresses are refused. |
| API key | Sent as `Authorization: Bearer <key>`. Use a read-only key. |

Conductor checks the connection when you save it (a one-day request) and refuses it with a plain message if
the URL is wrong, the key is rejected or the response breaks the contract.

## Request

```
GET {baseUrl}?from=YYYY-MM-DD&to=YYYY-MM-DD
Authorization: Bearer <key>
Accept: application/json
```

`from` and `to` are inclusive days in the timezone the response declares. Conductor asks for at most 14 days at
a time, never more, even when re-reading a long `restatementDays` window: the first sync backfills 90 days in
chunks, later syncs (every 6 hours) re-read the last `restatementDays` days in consecutive chunks. When a response
adds metric keys or changes `contractVersion`, Conductor re-walks the 90-day history so new metrics get their
history. An HTTP 400 is treated as a transient error (retried with backoff), not a setup problem; 401, 403, 404
and redirects still ask the user to fix the connection.
Redirects are not followed. Respond within about 30 seconds.

## Response

```json
{
  "contractVersion": 1,
  "timezone": "America/Los_Angeles",
  "dataCompleteThrough": "2026-10-14",
  "restatementDays": 3,
  "metrics": [
    { "key": "first_open", "label": "First opens", "unit": "COUNT", "aggregation": "SUM", "good": "UP", "funnelOrder": 1 },
    { "key": "trial_started", "label": "Trials started", "unit": "COUNT", "aggregation": "SUM", "good": "UP", "funnelOrder": 4,
      "target": { "value": 200, "direction": "UP", "window": "28d" } },
    { "key": "paid_spend", "label": "Paid spend", "unit": "CURRENCY", "aggregation": "SUM", "good": "DOWN" },
    { "key": "paid_trials", "label": "Paid trials", "unit": "COUNT", "aggregation": "SUM", "good": "UP" },
    { "key": "cost_per_trial", "label": "Cost per trial", "unit": "CURRENCY", "aggregation": "RATIO", "good": "DOWN",
      "numerator": "paid_spend", "denominator": "paid_trials" }
  ],
  "points": [
    { "metric": "first_open", "day": "2026-10-13", "value": 118, "dimension": null, "sampleSize": 118 },
    { "metric": "first_open", "day": "2026-10-13", "value": 47, "dimension": "source:apple_search_ads", "sampleSize": 118 }
  ],
  "reference": [
    { "metric": "store_downloads", "day": "2026-10-13", "value": 131, "dimension": null },
    { "metric": "ads_spend", "day": "2026-10-13", "value": 212.40, "dimension": null }
  ]
}
```

### Top-level fields

| Field | Meaning |
|---|---|
| `contractVersion` | `1`, `2` or `3`. Any other value is refused with "the server speaks contract version N". The v2-only fields below are read only when this is `2` or more, the v3-only fields only when it is `3`. |
| `timezone` | IANA zone the days are reckoned in. Days are never re-bucketed to UTC. |
| `dataCompleteThrough` | The last day whose numbers are final. Days **after** it are stored **provisional**: shown on charts, left out of totals and changes, and re-read on the next sync until the backend moves this date forward. |
| `restatementDays` | How many days back you may still change a number after reporting it. Conductor re-reads that many days (or back to `dataCompleteThrough`, if that lags more) on every sync. 0 to 30 on v1, 0 to 60 on v2 and v3. |
| `metrics` | The metric definitions (1 to 50). Conductor replaces its stored set on every sync, so a metric you stop sending disappears. |
| `points` | Daily values. |
| `reference` | Optional cross-check figures (see below). |

Unknown extra fields are ignored. A response that breaks the contract is rejected whole (nothing is half
applied) and the connection shows the reason.

### Metric definitions

| Field | Meaning |
|---|---|
| `key` | Stable id: lowercase letters, digits, underscores, starts with a letter, at most 64 characters, must not start with `ref_`. |
| `label` | What people see. |
| `unit` | `COUNT`, `CURRENCY` or `PERCENT` (v3 also `POSITION`, a rank). |
| `aggregation` | `SUM`: daily values add up over a window (opens, spend). `LEVEL`: a stock whose latest value counts (active subscribers). `RATIO`: see below. |
| `numerator`, `denominator` | `RATIO` only. Keys of other metrics in the same response, which must be `SUM` or `LEVEL` (not another `RATIO`). |
| `good` | `UP` (default) or `DOWN`: which direction reads as good news (`DOWN` for cost per trial). |
| `funnelOrder` | Optional, 1 first. Metrics that have one become the in-app stages of the funnel, in that order, right after store downloads. The first stage shows its conversion from downloads, each later stage from the one before. |
| `target` | Optional `{ value, direction, window, watchBandPct }` (`watchBandPct` is v2). Conductor judges the metric against it: see [Target status](#target-status). Read-only in Conductor: it shows as "Set in {connection name}", and `PUT`/`DELETE` on `/marketing/targets/PRODUCT/{metric}` answer 409. Change it in your backend and it updates on the next sync. |

**RATIO.** Send the numerator and the denominator as ordinary `SUM`/`LEVEL` metrics and name them. Conductor
computes the value for a window as `sum(numerator) / sum(denominator)` over that window, never an average of
daily ratios. Do not send points for a `RATIO` metric. Cost per trial is `paid_spend` over `paid_trials`.

### Points

| Field | Meaning |
|---|---|
| `metric` | A `key` from `metrics`. |
| `day` | `YYYY-MM-DD` in `timezone`. |
| `value` | A number. |
| `dimension` | `null` for the day's total, or `source:<name>` / `campaign:<id>:<name>` for a breakdown row (v2 also `keyword:<id>:<text>` and `ad:<id>:<name>`; v3 also `offer:`, `link:`, `message:`, `intent:`, `aso:`, `channel:` and `paywall:`, see [Version 3](#version-3)). The overview's headline numbers read the totals; breakdown rows feed the "By source" and "By campaign" tables. Send totals for every metric you want to show. |
| `sampleSize` | Optional whole number: how many users or events the value rests on. Under 20 for a window is flagged **insufficient data** and never read as a real change. Without it, Conductor judges noise itself. |

Points outside the requested `from`..`to` are ignored.

### Reference values

`reference` lets Conductor check its own connectors against your backend. Two metrics are understood on every
version (v3 adds two more, see [Version 3](#version-3)):

- `store_downloads`: downloads as your backend counts them. Compared with the App Store channel's downloads.
- `ads_spend`: paid acquisition spend as your backend counts it. Compared with the Ads channel's spend.

Reference rows are totals (`dimension` null). Over the settled days both sides have, the two sums are compared
in a band: counts under 50 may differ by 1, larger counts and money by 2% of the larger figure. Outside the band,
that channel's status becomes `MISMATCH`, and the overview and the verification ledger
(`GET /marketing/verification`) show both figures, the days compared and which source to trust. Reference values
are never listed as metrics.

A reference metric Conductor does not understand is **ignored**, not refused, on every version (Conductor logs
its name once per response). A backend can therefore start sending a newer reference series before its connected
Conductor reads it, and a v1 or v2 response that carries a v3 reference metric simply has it skipped.

## Version 2

Everything in v1 still applies. A `contractVersion: 2` response may add these **optional** per-metric fields and
limits:

| Field | Meaning |
|---|---|
| `basis` | `EVENT` (default) or `COHORT`. See below. |
| `area` | `ACQUISITION`, `ACTIVATION`, `RETENTION` or `REVENUE`: where the metric sits on a growth scorecard. |
| `description` | Up to 240 characters, what the metric counts in plain words, for example `"Installs that started a free trial"`. Shown under the metric. |
| `headline` | Whole number from 1: the priority among your headline metrics. The in-app card leads with them in that order. |
| `target.watchBandPct` | Percent of the target within which a miss reads as **Watch** rather than **Off target**. Default 10. |
| `restatementDays` | Up to 60 (v1: 30), so cohorts have time to mature. |
| `dimension` | Also `keyword:<id>:<text>` and `ad:<id>:<name>`, each at most 190 characters after the prefix. |

### Cohort basis

A funnel counted by event answers "how many trials started this week". A funnel counted by **cohort** answers
"of the people who first opened the app this week, how many have started a trial by now". Mark such a metric
`"basis": "COHORT"`:

- a point's `day` is the **cohort day**: the day its members first opened;
- its `value` is how many members of that day's cohort reached the stage by `dataCompleteThrough`;
- because members keep reaching later stages, **restate cohort days as they mature**: send the recent cohort
  days again with larger numbers on later syncs, and set `restatementDays` to how far back they can still move.

Conductor sums the cohort days inside the requested window, exactly as it sums event days, and labels the funnel
"people who first opened in the window, followed to now". A cohort stage is therefore never larger than the
cohort it came from, and a recent window's later stages are still filling in.

```json
{
  "contractVersion": 2,
  "timezone": "America/Los_Angeles",
  "dataCompleteThrough": "2026-10-14",
  "restatementDays": 45,
  "metrics": [
    { "key": "first_open", "label": "First opens", "unit": "COUNT", "aggregation": "SUM", "funnelOrder": 1,
      "basis": "COHORT", "area": "ACQUISITION", "description": "People who opened the app for the first time" },
    { "key": "trial_started", "label": "Trials started", "unit": "COUNT", "aggregation": "SUM", "funnelOrder": 2,
      "basis": "COHORT", "area": "REVENUE", "description": "Opens that started a free trial",
      "target": { "value": 200, "direction": "UP", "window": "28d" } }
  ],
  "points": [
    { "metric": "first_open", "day": "2026-10-01", "value": 118, "dimension": null, "sampleSize": 118 },
    { "metric": "trial_started", "day": "2026-10-01", "value": 9, "dimension": null, "sampleSize": 118 },
    { "metric": "trial_started", "day": "2026-10-01", "value": 4, "dimension": "keyword:42:meal planner", "sampleSize": 31 }
  ]
}
```

The next sync may send `trial_started` for `2026-10-01` again as `11`: the cohort has had more time.

### A rate with a sample size

Send a rate as a `RATIO` of two `SUM` metrics and put the **denominator's count in `sampleSize`**. Conductor
computes the window's rate as sum over sum, and uses the denominator as the sample size behind it.

```json
{
  "contractVersion": 2,
  "timezone": "America/Los_Angeles",
  "dataCompleteThrough": "2026-10-14",
  "restatementDays": 14,
  "metrics": [
    { "key": "retained_d7", "label": "Retained at day 7", "unit": "COUNT", "aggregation": "SUM", "area": "RETENTION" },
    { "key": "matured_d7", "label": "Reached day 7", "unit": "COUNT", "aggregation": "SUM", "area": "RETENTION" },
    { "key": "retention_d7", "label": "Day-7 retention", "unit": "PERCENT", "aggregation": "RATIO", "good": "UP",
      "numerator": "retained_d7", "denominator": "matured_d7", "area": "RETENTION", "headline": 1,
      "description": "Share of opens still active 7 days later",
      "target": { "value": 0.25, "direction": "UP", "watchBandPct": 15 } }
  ],
  "points": [
    { "metric": "retained_d7", "day": "2026-10-06", "value": 31, "dimension": null, "sampleSize": 120 },
    { "metric": "matured_d7", "day": "2026-10-06", "value": 120, "dimension": null, "sampleSize": 120 }
  ]
}
```

`PERCENT` values are fractions (`0.25` is 25%), targets included.

### Target status

Every metric with a target (one your backend declares, or one a person set in Conductor on another channel)
gets a standing:

| Status | Shown as | When |
|---|---|---|
| `ON_TRACK` | On target | The target is met, in its `direction`. |
| `WATCH` | Watch | Missed by no more than `watchBandPct` (default 10%) of the target, or a `PERCENT` rate whose miss is too small a sample to call real: the target is still inside the 95% Wilson interval drawn over the rate's denominator. |
| `OFF_TRACK` | Off target | Missed by more. |
| `TOO_EARLY` | Too early | There is no value yet, or the metric is a rate (`PERCENT` unit or `RATIO`) resting on fewer than 20 observations. |
| `STOPPED` | Stopped arriving | A metric your feed last sent more than 7 days before the rest of its data: a snapshot (`LEVEL`), or a flow with no value left in the window. It is not judged until it arrives again; the metric's note says when it was last reported. |
| `NO_TARGET` | no label | The metric has no target. |

`missPct` is the shortfall as a fraction of the target (0.05 is 5% short; 0 when met). A **count or currency
total** target that declares a `window` is prorated to the requested window (a 7-day total is compared with 7/28
of a `28d` target); a target with no window is compared as stated.
Rates and levels are compared as they are.

## Version 3

Everything in v1 and v2 still applies. A `contractVersion: 3` response may add the following, all **optional**.
It is read only when `contractVersion` is `3`:

| Field | Meaning |
|---|---|
| `exclusions` | Per metric: a string of up to 240 characters saying what the value leaves out, for example `"Likely App Review installs (7 in the window)"`. Conductor keeps it with the metric definition so a reader can see what the number does not include. |
| `unit` | Also `POSITION`: a rank, where 1 is first. Use it with `aggregation: LEVEL` and `good: DOWN` (`aso_rank`). |
| `dimension` | Also the seven prefixes below, each followed by one value of 1 to 190 characters. |
| `reference` | Also `ads_tap_installs` and `sales_first_time_downloads`, see below. |

Leave `exclusions` out when you have nothing to say. Absent means "not stated"; Conductor never reads it as
"nothing is excluded". An empty or blank string is treated as absent.

**In a v1 or v2 response** a v3-only field is **ignored**: `exclusions` is dropped and an `ads_tap_installs` or
`sales_first_time_downloads` reference value is skipped. A v3-only **dimension prefix** or the `POSITION` unit is
**refused** there, exactly as any other unknown prefix or unit is. An over-long `exclusions` text (more than 240
characters) or dimension value (more than 190) in a v3 response is refused with the reason, like an over-long
`description`.

### Reference metrics

Four reference metrics are understood on v3. Each is a daily total (`dimension` null), and is stored as a hidden
`ref_<metric>` series for cross-checking Conductor's own connectors.

| Metric | Added in | What it is |
|---|---|---|
| `store_downloads` | v1 | Downloads as your backend counts them. |
| `ads_spend` | v1 | Paid acquisition spend as your backend counts it. |
| `ads_tap_installs` | v3 | Installs your backend attributes to Apple Ads taps (first-time downloads and redownloads). |
| `sales_first_time_downloads` | v3 | First-time downloads as Apple's Sales Reports count them. |

Any other reference metric is ignored on every version.

### Dimension prefixes

A point carries **one** dimension. Each v3 prefix is followed by a single value, at most 190 characters (and at
least 1); the value may itself contain colons.

| Prefix | Rows are broken down by | Example |
|---|---|---|
| `offer:<code>` | Offer code | `offer:CREATOR10` |
| `link:<token>` | Tracked-link token. It names a link, never a person or a device. | `link:ab12cd` |
| `message:<tag>` | Message or promise tag | `message:fast weeknight dinners` |
| `intent:<pick>` | The answer people picked when asked what they came to do | `intent:plan meals` |
| `aso:<keyword>` | Tracked App Store keyword | `aso:meal planner` |
| `channel:<name>` | Acquisition channel, by name | `channel:email` |
| `paywall:<headline>` | Paywall variant, by its headline | `paywall:Cook smarter: try it free` |

Do not join two dimensions in one string. Instead:

- **Quality by source, campaign, keyword or ad** uses the existing `source:`, `campaign:`, `keyword:` and `ad:`
  dimensions on the same metric keys (`activated`, `trial_started`, `paid`, ...).
- **Intent mix** is the count of a metric on `intent:<pick>` rows, for example `activated` at `intent:plan meals`.
- **Intent mix per message** is one metric per pick, `intent_<pick>` (for example `intent_plan_meals`), with its
  points on `message:<tag>` rows.

Conductor stores every accepted dimension row. Today's tables read only the `source:`, `campaign:`, `keyword:` and
`ad:` rows; the v3 prefixes are read by name by the features that use them.

### Well-known metric keys

All optional. Conductor reads these keys by name when you send them, as counts or levels (never a pre-computed
rate under one of these keys). Any other key you send shows up as an ordinary metric.

| Key | Unit, aggregation | What it counts | Sent with |
|---|---|---|---|
| `activated` | COUNT, SUM | Installs that reached your activation moment | total |
| `retained_d1` | COUNT, SUM | Installs still active on day 1 | total |
| `retained_d7` | COUNT, SUM | Installs still active on day 7 | total |
| `retained_w2` | COUNT, SUM | Installs still active in week 2 | total |
| `trial_started` | COUNT, SUM | Installs that started a free trial | total |
| `paid` | COUNT, SUM | Installs that became paying subscribers | total |
| `proceeds_net` | CURRENCY, SUM | Proceeds after Apple's commission, counted by cohort, 12-month horizon | total |
| `app_review_installs_excluded` | COUNT, SUM | Likely App Review installs left out of the other numbers | total |
| `email_sends` | COUNT, SUM | Lifecycle emails sent | total |
| `email_clicks` | COUNT, SUM | Clicks in those emails | total |
| `email_trials_saved` | COUNT, SUM | Free trials kept after a lifecycle email; state your rule in `description` | total |
| `share_links_created` | COUNT, SUM | Share links created in the app | total |
| `share_views` | COUNT, SUM | Views of shared pages | total |
| `share_installs` | COUNT, SUM | Installs that came from a share link | total |
| `offer_redemptions` | COUNT, SUM | Offer-code redemptions | total, `offer:<code>` |
| `aso_rank` | POSITION, LEVEL | Organic App Store rank for a keyword, 1 first; send `good: DOWN` | `aso:<keyword>` |
| `aso_popularity` | COUNT, LEVEL | Search popularity score of a keyword | `aso:<keyword>` |

The quality metrics (`activated`, `retained_*`, `trial_started`, `paid`, `proceeds_net`) can also be sent by
source, campaign, keyword and ad: use the existing `source:`, `campaign:`, `keyword:` and `ad:` dimensions on the
same keys.

`proceeds_net` is counted by cohort over a 12-month horizon: mark it `"basis": "COHORT"` and restate it as the
cohort matures (see [Cohort basis](#cohort-basis)), so a recent cohort stays low until it has had the time. Use
`exclusions` to say what a number leaves out, for example the App Review installs that
`app_review_installs_excluded` counts.

### Example

```json
{
  "contractVersion": 3,
  "timezone": "America/Los_Angeles",
  "dataCompleteThrough": "2026-10-14",
  "restatementDays": 45,
  "metrics": [
    { "key": "activated", "label": "Activated installs", "unit": "COUNT", "aggregation": "SUM", "basis": "COHORT",
      "area": "ACTIVATION", "exclusions": "Likely App Review installs (7 in the window)" },
    { "key": "intent_plan_meals", "label": "Picked: plan meals", "unit": "COUNT", "aggregation": "SUM" },
    { "key": "offer_redemptions", "label": "Offer redemptions", "unit": "COUNT", "aggregation": "SUM" },
    { "key": "aso_rank", "label": "Organic rank", "unit": "POSITION", "aggregation": "LEVEL", "good": "DOWN" }
  ],
  "points": [
    { "metric": "activated", "day": "2026-10-01", "value": 41, "dimension": null },
    { "metric": "activated", "day": "2026-10-01", "value": 6, "dimension": "keyword:42:meal planner" },
    { "metric": "activated", "day": "2026-10-01", "value": 9, "dimension": "intent:plan meals" },
    { "metric": "intent_plan_meals", "day": "2026-10-01", "value": 5, "dimension": "message:fast weeknight dinners" },
    { "metric": "offer_redemptions", "day": "2026-10-01", "value": 3, "dimension": "offer:CREATOR10" },
    { "metric": "aso_rank", "day": "2026-10-01", "value": 14, "dimension": "aso:meal planner" }
  ],
  "reference": [
    { "metric": "ads_tap_installs", "day": "2026-10-01", "value": 58, "dimension": null },
    { "metric": "sales_first_time_downloads", "day": "2026-10-01", "value": 129, "dimension": null }
  ]
}
```

## Privacy

Send aggregates only. **Never send user ids, install ids, device ids, emails or any other per-person
identifier**, not even inside a `dimension` string (a `link:` token identifies a link, never the person who
opened it). `sampleSize` is a count, never a list.

## Errors

Use plain HTTP status codes with an RFC 7807 body (`application/problem+json`, see
[`api-guidelines.md`](api-guidelines.md)). Conductor shows the `detail` (or `title`) to the person who
connected the integration, so write it for them.

| Status | What Conductor tells the user |
|---|---|
| 200 | Parsed against the contract. |
| 401 / 403 | "The server rejected the API key." Setup is marked as needing attention. |
| 404 | "The server has no metrics endpoint at that URL." |
| 3xx | "The server redirected the request." Give the final URL; redirects are not followed. |
| 429 | Retried later; the connection shows as degraded. |
| 5xx, timeouts | Retried at the next sync; the connection shows as degraded. |
| 200 with a body that is not valid JSON, has an unsupported `contractVersion`, a bad v2 `basis` or `area`, an unknown `aggregation`, an unknown dimension prefix or `unit` for the version, an over-long v3 `exclusions` or dimension value, a `RATIO` naming a metric that is not in the response, or a point for an undefined metric | The reason, in plain words. |

## Where the numbers show up

- The overview's **In-app** channel lists the metrics (funnel stages first, or your `headline` metrics first),
  with change against the previous period, target status and a daily series.
- The funnel gains the in-app stages after store downloads.
- `GET /marketing/channels/PRODUCT` adds "By source" and "By campaign" tables when you send those dimensions.

## Notes for implementers

- Conductor stores one row per metric, dimension and day; it keeps 90 days of history from the first sync.
- Make the endpoint cheap to repeat: the same days are requested again on every sync while they are provisional.
- Keep `key`s stable. Renaming one starts a new series.
- To change a definition (label, unit, target), just send the new one; it replaces the old on the next sync.
- A schema check is available offline: validate your response against
  `docs/integrations-product-metrics.schema.json` with any JSON Schema 2020-12 validator before connecting.
