# Adding an integration connector

The connector SPI lives in `conductor-backend/src/main/java/com/conductor/integration/`. A connector
is auto-discovered as a Spring bean (no central registration list) and declares what it can do by the
capability interfaces it implements: `FetchConnector` (pull), `WebhookConnector` (push),
`ActionConnector` (outbound). Most data connectors are `FetchConnector`s.

## Checklist

1. **Backend connector** — `integration/connector/<id>/<Name>Connector.java`, annotated
   `@Component @Profile("!local")`, implementing `FetchConnector`. Give it a stable `getId()` (the
   `connectorId` used everywhere downstream), `getMetadata()` (`ConnectorCategory`, `iconLabel`),
   `getSpec()` (config fields), `fetchData()`, `checkHealth()`, and `getMaxCacheAge()`. Build the
   `RestTemplate` via `ConnectorHttp.restTemplate()` (don't hand-roll timeouts). Return data with
   `ConnectorData.healthy/degraded/setupRequired`; series points use the key **`value`** (the
   frontend reads `value`).
2. **Local stub** — `integration/connector/local/Local<Name>Connector.java`, `@Profile("local")`,
   returning realistic fake data so the page renders end-to-end without real credentials.
3. **OAuth (only if applicable)** — implement `OAuth2Connector` and return your scopes from
   `oauthScopes()`. The shared `OAuthFlowService` looks the connector up in `ConnectorRegistry` and
   asks it for scopes/endpoints/config — you do not touch that service. `OAuth2Connector`'s five other
   methods are `default`, describing **Google's** flow (every connector used to hardcode Google) — a
   Google-backed connector (GSC, PostHog-via-Google, GCP Billing, …) only needs `oauthScopes()`. A
   **non-Google** provider overrides all five:
   - `authorizationUrl()` — the consent-screen endpoint.
   - `tokenUrl()` — the authorization-code exchange / refresh-token endpoint.
   - `clientIdProperty()` / `clientSecretProperty()` — the env/config property names holding this
     provider's OAuth client id/secret (each provider gets its own property pair, not the shared
     `GOOGLE_OAUTH_CLIENT_ID`/`_SECRET`).
   - `extraAuthorizationParams()` — extra query params on the consent URL (Google's default requests
     `access_type=offline&prompt=consent` for a refresh token; override if your provider's equivalent
     differs or isn't needed).

   **Who owns the app.** By default a connector may inherit the deployment's app from those two
   properties when a workspace has stored nothing — right for the Google family, which shares one
   OAuth client across GSC, GCP Billing and the rest. Override `allowsDeploymentCredentials()` to
   `false` for a provider whose app must belong to the workspace: one carrying its own App Review,
   rate limits or creator relationship, as Meta, TikTok and YouTube all do. Then nothing reads the
   environment for it, the property names survive only as identifiers, and a project with no stored
   row simply cannot connect until an admin enters one under Settings → Integrations. Because a
   stored credential is keyed on the connector id, opting one Google-backed connector out (YouTube)
   leaves the others inheriting as before.

   **Completion hooks that call the provider as the app.** `OAuthCompletionRequest` carries the
   `clientId`/`clientSecret` the code exchange ran as. Use those rather than re-resolving: Meta's
   long-lived token swap is app-authenticated, and resolving again inside the connector is how a
   consent granted to the workspace's app gets completed against the deployment's.

   (Custom, non-OAuth2 auth — like Apple's signed-JWT exchange — skips `OAuth2Connector` entirely and
   stays fully inside your connector package; see `integrations-apple-search-ads.md`.)
4. **Outbound actions (only if applicable)** — implement `ActionConnector` instead of/in addition to
   `FetchConnector` for a connector that performs side effects (post a message, create a ticket) rather
   than (or as well as) pulling data:
   - `getActions()` returns the connector's `List<ActionDescriptor>` (id, label, input keys) — this is
     what MCP discovery tools (`list_integration_tools`'s `actions` array, backed by
     `IntegrationToolSpec.actions()`/`ActionSpec`) surface to an agent designing a workflow's `action`
     step. Give each action a stable `id` (the `with.action` value in the step's YAML) and document its
     `params`/`outputKeys` in the `ActionSpec` returned alongside it in tool metadata — don't make the
     agent guess the shape from a doc comment.
   - `invoke(actionId, input, ctx)` is called by `ActionInvocationService`, which owns idempotency
     (one row per caller-supplied key — re-invoking the same key never re-calls your connector),
     bounded-timeout execution, and retry/dead-lettering. Your connector only needs to classify
     failures correctly:
     - **Throw** for a transient failure (network error, 5xx, timeout) — the caller retries (inline,
       then a background sweep) before giving up.
     - **Return `ActionResult.error(...)`** for a permanent failure (a 4xx rejection, malformed input,
       bad webhook URL) — the caller does **not** retry; retrying a request the provider already
       rejected as invalid wastes attempts and can duplicate side effects on a provider that partially
       processed the request before rejecting it.
     - **Return `ActionResult.ok(output)`** on success, with whatever result keys downstream workflow
       steps should be able to reference as outputs.
   - See `DiscordActionConnector` (`integration/connector/discord/`) for a complete example: one action
     (`post_message`), permanent-vs-transient branching on `HttpClientErrorException` (4xx) vs. an
     uncaught 5xx/network throw, and output keys (`message_id`, `channel_id`) parsed from the response.
5. **Frontend page** — `conductor-frontend/src/components/integrations/<Name>ConnectorPage.tsx`
   (default export, `{ projectId }` prop). Reuse the shared `ConnectorHeader`, `StatCard`, and
   `lib/format` helpers rather than re-declaring them.
6. **Router switch** — add a `case '<id>':` to the switch in
   `conductor-frontend/src/app/app/projects/[projectId]/integrations/[connectorId]/page.tsx`
   rendering your page.
7. **Logo** — drop `conductor-frontend/public/integrations/<id>.svg` (filename **must equal**
   `getId()`). `ConnectorIcon` picks it up automatically and falls back to the `iconLabel` text badge
   if it's missing — no code change needed.
8. **Tests** — a connector unit test (mock the `RestTemplate`) asserting payload mapping and the
   `setupRequired`/`degraded` paths, matching the existing connector tests.
9. **Feeds (only if applicable)** — declare `ingest[]` in your tool-spec JSON to get a scheduled
   Knowledge Center feed provisioned automatically; see
   [Connector feeds (metrics digests)](#connector-feeds-metrics-digests) below. A `FetchConnector`
   needs no Java changes for a `SNAPSHOT`-mode feed — only `WINDOW` mode requires implementing
   `IngestConnector`.

No database migration is needed — the generic `connection` / `connection_data_cache` tables already
serve every connector. Only add to `openapi.yaml` if your connector needs bespoke endpoints (e.g. a
post-OAuth config picker).

## External API models

### Anti-corruption layer

Each connector is the anti-corruption layer (ACL) between a third-party API and our domain. External API concepts must not leak past the connector boundary — `ConnectorData.data` is always `Map<String, Object>`, and callers (MCP tools, workflow steps) never see third-party types. Keep all external API vocabulary inside the connector package.

### model/ subpackage

Create a `model/` subpackage for the typed request/response shapes your connector uses:

```
connector/<id>/
  <Name>Connector.java
  model/
    package-info.java        # canonical link to the external API's spec/discovery doc
    <Api>SomeResponse.java   # one record per response shape you deserialize
    <Api>SomeRequest.java    # one record per request body you construct
```

**Prefer typed records over `Map.class`.** Pass the record class to `RestTemplate.exchange(...)` — Jackson deserializes into it. Request bodies are records too (`@JsonInclude(NON_NULL)` on optional fields), replacing inline `Map.of(...)` calls. This makes the API surface explicit, catches field issues at compile time, and localizes updates when the external API changes.

**Official SDK?** Use one if it fits the auth model (e.g. `GcpBillingConnector` uses the BigQuery SDK for SQL queries). If the SDK doesn't support per-user OAuth2 access tokens or pulls in conflicting transitive dependencies, use RestTemplate + typed records instead.

**Spec reference.** In `package-info.java`, link to the external API's OpenAPI/discovery document — this is the source of truth for field names, types, and deprecation notices. If the connector grows to many endpoints, the `model/` package can be regenerated directly from that spec using OpenAPI Generator without changing the connector or domain code.

### REST best practices for outbound calls

- **Timeouts**: always build via `ConnectorHttp.restTemplate()` — never hand-roll a `RestTemplate` or omit timeouts.
- **Error handling**: branch by HTTP status code (`404` → setup problem, `403` → auth problem, `429` → rate limit, etc.). Don't catch everything as a generic error — map specific status codes to specific `ConnectorData` states (`setupRequired`, `degraded`).
- **Content-Type**: set `MediaType.APPLICATION_JSON` on POST request headers explicitly; don't rely on defaults.
- **Null fields**: use `@JsonInclude(NON_NULL)` on optional request record fields (or at the class level) so absent values are never serialized as `null` in the outbound body.
- **URL encoding**: when a resource identifier contains special characters (e.g. `sc-domain:example.com`), use `URI.create(...)` with a pre-encoded string rather than a raw `String` URL — Spring's `RestTemplate` double-encodes `%` in String URLs.

## Connector feeds (metrics digests)

A connector can optionally declare `ingest[]` in its tool-spec JSON (`connectors/tool-specs/<id>.json`,
alongside `operations`/`actions`) to get a scheduled Knowledge Center feed provisioned automatically —
no migration, no workflow YAML, and (for the common case) no Java at all. This section is the field
reference and implementation guide; the pipeline mechanics (aggregate → detect → structure → narrate,
noise gates, the steady-state valve) live in
[`docs/knowledge.md`](knowledge.md#metrics-digests) — read that first for *why* the shape below is
what it is.

### `ingest[]` reference

Each entry is an `IngestSpec`:

| Field | Required | Description |
|---|---|---|
| `id` | yes | Stable feed id within this connector — the `ingestId` a `connector_feed` row is keyed on (unique per connection). Renaming it orphans any already-provisioned feed rather than updating them in place. |
| `label` | yes | Human label shown in the Feeds panel and the connector catalog. |
| `description` | no | One-line description shown alongside the label. |
| `mode` | no, default `SNAPSHOT` | `SNAPSHOT` bridges the connector's existing `fetchData()` (see [The `SnapshotIngestAdapter` bridge](#the-snapshotingestadapter-bridge)); `WINDOW` asks an `IngestConnector` for a specific time slice each pull — see [When you actually need `IngestConnector`](#when-you-actually-need-ingestconnector). |
| `projectOperation` | no | A `ToolOperation#id()` from this connector's own `operations[]` (SNAPSHOT mode only) — projects the fetched snapshot down to this feed's series via that operation's `outputKeys`, the same filter `IntegrationStepExecutor` applies for a workflow `integration` step. Omit to feed the whole snapshot through unfiltered. |
| `sourceType` | no | The `KnowledgeSubmission.sourceType` stamped on filed items/digests. Supports only the platform placeholders `{connector}`, `{ingest}`, `{period}` — any other `{...}` token passes through literally. Typically `metrics.digest.{connector}.{ingest}` for a metric feed. |
| `defaultIntervalMinutes` | no, default `1440` | Seeds `connector_feed.interval_minutes` at first provisioning only — an operator can change it afterward via the Feeds panel / `PATCH .../feeds/{feedId}`, and that edit is never overwritten by this default again. |
| `window` | required iff `mode: WINDOW` | See below. Also used by a `SNAPSHOT`+`digest` feed (e.g. GSC) to select which slice of the already-fetched series to aggregate — it does not drive a separate pull in that case. |
| `suggestedDisposition` / `suggestedDomain` | no | One-time seeds for a `DispositionPolicy` row, read only at first provisioning (see `ConnectorFeedProvisioner`) — never consulted again at pull/digest time, so the platform never has two disagreeing copies of the same routing policy. |
| `digest` | no | Present **iff** this feed is a metric feed (`IngestSpec#isMetricFeed()`) — a `DigestSpec`, see below. Omit entirely for a feed that files raw items straight into the inbox instead of going through the digest/narration pipeline. |

`window` (`IngestWindowSpec`):

| Field | Description |
|---|---|
| `sizeDays` | Width of the slice in days. |
| `lagDays` | How many days back from "now" the slice ends — data sources are rarely complete for the most recent day(s). |
| `alignTo` | `DAY` \| `ISO_WEEK` \| `MONTH` — snaps the slice to a calendar boundary (e.g. `ISO_WEEK` always starts the slice on a Monday) regardless of when the pull actually runs. Defaults to `DAY`. |

`digest` (`DigestSpec` — only for a metric feed):

| Field | Description |
|---|---|
| `seriesPath` | Dotted path into the pulled/projected payload holding the row series, e.g. `"trend"`. |
| `dateField` | Per-row ISO-8601 date key, e.g. `"date"`. |
| `pagePath` | The Knowledge Center page the narrator's output eventually lands on (via the ordinary source → librarian pipeline). |
| `maxQuietPeriods` | The steady-state valve's threshold — periods of no material change tolerated before one forced emission. Default `13` (a quarter, for a weekly feed). |
| `metrics[]` | List of `MetricSpec` — see below. |
| `dimensions[]` | List of `DimensionSpec` — see below. |

`metrics[]` (`MetricSpec`):

| Field | Description |
|---|---|
| `key` / `label` / `unit` | Identity and display. |
| `agg` | `SUM` \| `MEAN` \| `WEIGHTED_MEAN` \| `LAST` \| `RATIO` — how `MetricsAggregator` rolls the window's rows into one value. `RATIO` sums `numerator`/`denominator` separately then divides (never a mean-of-ratios — the classic CTR bug). `WEIGHTED_MEAN` needs `weightField`. |
| `field` | Row key to read (`SUM`/`MEAN`/`WEIGHTED_MEAN`/`LAST`). |
| `weightField` | Row key for `WEIGHTED_MEAN`'s weight. |
| `numerator` / `denominator` | Row keys for `RATIO`. |
| `direction` | `UP_IS_GOOD` \| `DOWN_IS_GOOD` \| `NEUTRAL` (default) — which way a move reads as good news; the narrator is told this explicitly so a `DOWN_IS_GOOD` metric falling is written up as an improvement, not bad news. |
| `minAbsolute` / `minRelative` / `zThreshold` | The three noise-gate thresholds — see [`docs/knowledge.md`](knowledge.md#metrics-digests). Defaults `0.0` / `0.15` / `2.0`. |

`dimensions[]` (`DimensionSpec`):

| Field | Description |
|---|---|
| `key` / `label` | Identity and display — `key` also names the payload field holding the row list (e.g. `"topQueries"`). |
| `idField` / `valueField` | Row keys for the entry's identity and ranked value. |
| `topN` | How many top rows to watch for movers (defaults to all rows). |
| `baselineN` | How many rows to persist as the next period's baseline — bounds `connector_feed.last_stats` regardless of how wide the source's own top-N list is (defaults to the current row count). |
| `minAbsolute` / `minRelative` / `minRankMove` | Thresholds for flagging a rank-stable entry as a mover by value change, or a rank shift as material on its own. |

### The `SnapshotIngestAdapter` bridge

A `mode: SNAPSHOT` feed needs **zero Java changes** on a connector that already implements
`FetchConnector` — `SnapshotIngestAdapter` bridges `fetchData()` into the ingest pipeline automatically
whenever `ConnectorRegistry#findIngest` comes back empty for that connector id (see `FeedPullService`).
It re-fetches the connector's one dashboard-shaped snapshot on the feed's cadence (never
force-refreshing — a stale cache from a recent dashboard load is reused, not churned), optionally
projects it through `projectOperation`, and — for a metric feed — stamps the `metadata.periodKey`
`MetricsDigestService` requires (see the next section).

`gsc.json`'s `search_analytics_weekly` entry is the real, shipped example: `GscConnector` implements
only `FetchConnector`, yet gets a weekly-digest feed with zero connector code, purely from this JSON
declaration bridging its existing `search_analytics` operation.

### When you actually need `IngestConnector`

Implement `IngestConnector` (`pull(ConnectionContext, IngestRequest): IngestBatch`) only when:

- **`mode: WINDOW` is declared.** This is enforced at load time, not just documented: `Connector
  .getToolSpec()`'s `withValidIngest` drops a `WINDOW` entry with a WARN log for any connector that
  doesn't implement `IngestConnector`, rather than silently narrowing it to `SNAPSHOT` (a silently
  narrowed window would digest the wrong period — worse than no digest at all).
- **A single re-fetched snapshot genuinely isn't the right shape** — e.g. the source API has real
  pagination/cursor semantics you want to drive incrementally, rather than "re-pull the whole current
  dashboard and diff it," which is all `SnapshotIngestAdapter` can do.

**Contract to honor**, same as `FetchConnector#fetchData`: return `IngestBatch.degraded(...)` /
`.setupRequired(...)` for expected remote failures rather than throwing, and make `pull` idempotent —
re-pulling the same `IngestRequest#cursor()`/window after a crash or retry must yield the same
`IngestItem#dedupKey()`s. **For a metric feed specifically**, your `pull` must stamp
`item.metadata().get("periodKey")` yourself (a non-empty string identifying the period) —
`MetricsDigestService` throws if it's missing, since `SnapshotIngestAdapter` always stamps it and a
custom `IngestConnector` skipping it is a contract violation worth failing loudly on rather than
silently corrupting the digest's period bookkeeping.

### Worked example: Datadog (`IngestConnector` case)

**No `datadog.json` ships in this repo — Datadog is not a connector that exists here.** This is a
worked illustration of the `IngestConnector` path, not a connector to copy verbatim.

Say a `DatadogConnector` wants a daily digest of a metric query (e.g. active hosts) using Datadog's
timeseries query API with real cursor/window semantics, rather than bridging a single dashboard
snapshot. Its tool-spec would declare:

```json
{
  "ingest": [
    {
      "id": "active_hosts_daily",
      "label": "Active hosts (daily)",
      "description": "Daily active-host count digest",
      "mode": "WINDOW",
      "sourceType": "metrics.digest.{connector}.{ingest}",
      "defaultIntervalMinutes": 1440,
      "window": { "sizeDays": 1, "lagDays": 1, "alignTo": "DAY" },
      "suggestedDisposition": "KNOWLEDGE",
      "suggestedDomain": "engineering",
      "digest": {
        "seriesPath": "trend",
        "dateField": "date",
        "pagePath": "engineering/metrics/infrastructure.md",
        "metrics": [
          { "key": "active_hosts", "label": "Active hosts", "agg": "LAST", "field": "count",
            "minAbsolute": 5, "minRelative": 0.05 }
        ]
      }
    }
  ]
}
```

And `DatadogConnector implements IngestConnector` would, in `pull`:

1. Query Datadog's metrics API for `request.window().start()`–`end()`.
2. Shape the result into the same `{"trend": [{"date": "...", "count": ...}, ...]}` payload
   `MetricsAggregator` expects at `seriesPath`/`dateField`.
3. Build one `IngestItem` whose `metadata` includes `"periodKey"` (e.g. the ISO date of
   `request.window().start()`) — the self-stamping requirement above.
4. Return `IngestBatch.of(List.of(item), nextCursor, hasMore)` — or `.degraded(...)`/`.setupRequired(...)`
   for an expected Datadog API failure, never a thrown exception.

`FeedPullService` and `MetricsDigestService` handle everything downstream identically to the GSC
`SnapshotIngestAdapter` case — the digest pipeline doesn't know or care which path produced the item.

---

## Known SPI gaps / follow-ups

- **No `onDisconnect` hook.** Connectors that cache per-connection tokens (GitHub, Apple Search Ads)
  expose an `evict(connectionId)` method, but there is no generic disconnect lifecycle event to call
  it from. If per-connection cache purging on disconnect becomes important, add an `onDisconnect`
  extension point to the `Connector` SPI so all connectors can hook it symmetrically.
