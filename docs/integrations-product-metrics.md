# Product metrics contract (v1)

The `product-metrics` connector reads your product's own numbers (first opens, trials, paid, cost per trial)
from a URL you host, so the marketing overview can show the in-app half of the funnel next to the web, store
and ads channels. Conductor owns this contract. Your backend serves it; Conductor only reads.

The machine-readable shape is [`integrations-product-metrics.schema.json`](integrations-product-metrics.schema.json)
(JSON Schema, draft 2020-12). It is the single source of truth for fields and types; this page explains what
they mean. A backend can be a mobile app's API, a data warehouse job or a small serverless function.

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
a time: the first sync backfills 90 days in chunks, later syncs (every 6 hours) re-read the last few days.
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
| `contractVersion` | Must be `1`. Any other value is refused with "the server speaks contract version N". |
| `timezone` | IANA zone the days are reckoned in. Days are never re-bucketed to UTC. |
| `dataCompleteThrough` | The last day whose numbers are final. Days **after** it are stored **provisional**: shown on charts, left out of totals and changes, and re-read on the next sync until the backend moves this date forward. |
| `restatementDays` | How many days back you may still change a number after reporting it. Conductor re-reads that many days (or back to `dataCompleteThrough`, if that lags more) on every sync. 0 to 30. |
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
| `unit` | `COUNT`, `CURRENCY` or `PERCENT`. |
| `aggregation` | `SUM`: daily values add up over a window (opens, spend). `LEVEL`: a stock whose latest value counts (active subscribers). `RATIO`: see below. |
| `numerator`, `denominator` | `RATIO` only. Keys of other metrics in the same response, which must be `SUM` or `LEVEL` (not another `RATIO`). |
| `good` | `UP` (default) or `DOWN`: which direction reads as good news (`DOWN` for cost per trial). |
| `funnelOrder` | Optional, 1 first. Metrics that have one become the in-app stages of the funnel, in that order, right after store downloads. The first stage shows its conversion from downloads, each later stage from the one before. |
| `target` | Optional `{ value, direction, window }`. Read-only in Conductor: it shows as "Set in {connection name}", and `PUT`/`DELETE` on `/marketing/targets/PRODUCT/{metric}` answer 409. Change it in your backend and it updates on the next sync. |

**RATIO.** Send the numerator and the denominator as ordinary `SUM`/`LEVEL` metrics and name them. Conductor
computes the value for a window as `sum(numerator) / sum(denominator)` over that window, never an average of
daily ratios. Do not send points for a `RATIO` metric. Cost per trial is `paid_spend` over `paid_trials`.

### Points

| Field | Meaning |
|---|---|
| `metric` | A `key` from `metrics`. |
| `day` | `YYYY-MM-DD` in `timezone`. |
| `value` | A number. |
| `dimension` | `null` for the day's total, or `source:<name>` / `campaign:<id>:<name>` for a breakdown row. The overview's headline numbers read the totals; breakdown rows feed the "By source" and "By campaign" tables. Send totals for every metric you want to show. |
| `sampleSize` | Optional whole number: how many users or events the value rests on. Under 20 for a window is flagged **insufficient data** and never read as a real change. Without it, Conductor judges noise itself. |

Points outside the requested `from`..`to` are ignored.

### Reference values

`reference` lets Conductor check its own connectors against your backend. Two metrics are understood:

- `store_downloads`: downloads as your backend counts them. Compared with the App Store channel's downloads.
- `ads_spend`: paid acquisition spend as your backend counts it. Compared with the Ads channel's spend.

Reference rows are totals (`dimension` null). Over the settled days both sides have, if the two sums differ by
more than 1% of the larger, that channel's status becomes `MISMATCH`, and the overview shows both figures and
the gap. Reference values are never listed as metrics.

## Privacy

Send aggregates only. **Never send user ids, install ids, device ids, emails or any other per-person
identifier**, not even inside a `dimension` string. `sampleSize` is a count, never a list.

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
| 200 with a body that is not valid JSON, has another `contractVersion`, an unknown `aggregation`, a `RATIO` naming a metric that is not in the response, or a point for an undefined metric | The reason, in plain words. |

## Where the numbers show up

- The overview's **In-app** channel lists the metrics (funnel stages first), with change against the previous
  period, targets and a daily series.
- The funnel gains the in-app stages after store downloads.
- `GET /marketing/channels/PRODUCT` adds "By source" and "By campaign" tables when you send those dimensions.

## Notes for implementers

- Conductor stores one row per metric, dimension and day; it keeps 90 days of history from the first sync.
- Make the endpoint cheap to repeat: the same days are requested again on every sync while they are provisional.
- Keep `key`s stable. Renaming one starts a new series.
- To change a definition (label, unit, target), just send the new one; it replaces the old on the next sync.
- A schema check is available offline: validate your response against
  `docs/integrations-product-metrics.schema.json` with any JSON Schema 2020-12 validator before connecting.
