# Performance measurement

Where does a warm request spend its time? The backend and frontend log enough per request to answer that
from Cloud Logging, with no extra infrastructure. This page covers what is logged, how to read it, and how
to turn it into a report.

## What is logged

### Backend (`conductor-backend`, package `com.conductor.observability`)

Logs are one JSON object per line (`com.conductor.config.GcpJsonLogFormatter`), so Cloud Logging parses
them into `jsonPayload`. Every line has `severity` (DEBUG/INFO/WARNING/ERROR), `message`, `logger`,
`thread`, `timestamp`; errors add `stack_trace` (also appended to `message`, which is where Error Reporting
looks). SLF4J key-values become top-level fields. The `local` profile and the test suite stay on plain text.

**`http_request`** - INFO, one per request (health checks excluded; OPTIONS preflights included with
`preflight=true`):

| Field | Meaning |
|---|---|
| `route` | Spring route template (`/api/v2/projects/{projectId}/work-items/{id}`), or `unmatched` |
| `method`, `status` | HTTP method and response status |
| `ms` | Total time in the servlet filter chain, auth included |
| `respBytes` | `Content-Length` if set, otherwise bytes counted as they were written |
| `dbQueries`, `dbMs` | JDBC statements executed and time spent in them |
| `poolWaitMs`, `connCheckouts` | Time spent in `DataSource.getConnection()` (waiting for the Hikari pool) and how many checkouts |
| `signedUrls`, `signMs` | GCS signed URLs generated and time spent signing |
| `gcsCalls`, `gcsMs` | GCS reads/writes/deletes/lists and time spent in them |
| `poolActive`, `poolIdle`, `poolWaiting` | Hikari pool state when the request finished |

**`slow_request`** - WARN, replaces `http_request` for requests over `observability.slow-request-ms`
(default 1000). Same fields plus `topSql`: the five most repeated normalized statements as
`20x select ... where id = ? | 8x ...`. A statement repeated many times is the N+1.

**`scheduled_job`** - INFO, one per `@Scheduled` run that took over 500 ms: `job` (`Class.method`), `ms`,
`dbQueries`, `dbMs`, `poolWaitMs`. Use it to spot background work competing with user requests for the
5-connection pool.

How it works: `RequestStats` is a ThreadLocal accumulator opened by `RequestMetricsFilter` (highest
precedence, so it wraps the Spring Security filters) and by `ScheduledJobMetrics` (a wrapper around the
scheduling `TaskScheduler`). `QueryStatsDataSourcePostProcessor` wraps the Hikari `DataSource` with `datasource-proxy`
and records every statement plus `getConnection()` time into whichever scope is open on the calling thread.
A JDBC batch (Hibernate `jdbc.batch_size=50`) counts as ONE in `dbQueries` - it is one round trip - and appears once in `topSql`. SQL is normalized (literals become `?`, `IN` lists collapse, whitespace is squeezed, 300 chars max, 200
distinct statements per request). Work done on other threads (`@Async`, executors) is not attributed.

Switches (all default `true`): `observability.request-metrics.enabled`, `observability.datasource-proxy.enabled`,
`observability.scheduled-jobs.enabled`. Threshold: `observability.slow-request-ms`.

### Server-Timing

Every response carries

```
Server-Timing: db;dur=34;desc="12 queries", pool;dur=1, sign;dur=6, gcs;dur=0, app;dur=88
Timing-Allow-Origin: *
```

`Server-Timing` is in the CORS exposed headers, so `fetch` can read it cross-origin. In Chrome devtools open
Network, pick a request, and see the **Timing** tab (Server Timing section). The header must be set before the
response commits and the body is never buffered, so it is written just before the first body write, flush,
`sendError` or redirect (or after the handler returns if there is no body). `app` is therefore the time up to
that point: controller, services and DB work, but not serializing the response body.

### Frontend (`conductor-frontend`)

`src/lib/telemetry.ts` buffers samples in memory, flushes every 5 s and on `visibilitychange=hidden` /
`pagehide` with `navigator.sendBeacon('/api/telemetry')`. The route (`src/app/api/telemetry/route.ts`)
validates, caps (100 samples, 64 KB, strings truncated, unknown types dropped) and prints each sample as a JSON
line on stdout. Nothing is sent in tests or on the server; set `NEXT_PUBLIC_TELEMETRY=off` to disable.

| `message` | Fields |
|---|---|
| `api_call` | `path` (ids replaced with `:id`), `method`, `status`, `clientMs`, `serverMs`, `dbMs`, `dbQueries` (from Server-Timing), `page` |
| `web_vital` | `name` (LCP, INP, FCP, TTFB, CLS), `value` (ms; CLS is x1000), `rating`, `page` |
| `page_ready` | `page` (a stable name such as `work-item-detail`), `path`, `ms` since navigation start, `apiCalls` made since then |

`clientMs - serverMs` is the network + queueing + parsing overhead of a call. `page_ready` is recorded once
per navigation by `markPageReady(name)` (`src/lib/perf.ts`) from: work-item list and detail, project doc,
knowledge home, media library and creatives library. To instrument another page call it from an effect once
its primary data has rendered.

## perf-report.sh

```bash
export CONDUCTOR_GCP_PROJECT=<project>
./scripts/perf-report.sh                 # last 24h
./scripts/perf-report.sh --since 2h --limit 20000
```

Requires `gcloud`, `jq` and `python3`. It prints, per backend route, n / p50 / p95 / max ms with average
queries, DB ms, pool wait, checkouts and response bytes (sorted by p95); per frontend page `page_ready` p50/p95
and average API calls; per API path the average client-minus-server overhead; and the SQL most repeated inside
slow requests. Missing fields count as 0.

## Handy Cloud Logging queries

```
resource.labels.service_name="conductor-backend" jsonPayload.message="slow_request"
resource.labels.service_name="conductor-backend" jsonPayload.message="http_request" jsonPayload.poolWaitMs>0
resource.labels.service_name="conductor-backend" jsonPayload.message="http_request" jsonPayload.route="/api/v2/projects/{projectId}/work-items/{id}"
resource.labels.service_name="conductor-backend" jsonPayload.message="scheduled_job"
resource.labels.service_name="conductor-frontend" jsonPayload.message="page_ready" jsonPayload.page="work-item-detail"
```

`scripts/logs.sh` prints `jsonPayload.message` (falling back to `textPayload`) with route/status/ms appended
for request lines.

## Supabase Query Performance

The request logs say which endpoints are slow and how many statements they run; Supabase says which
statements are slow. In the dashboard open **Database -> Query Performance** (backed by `pg_stat_statements`)
and sort by total time and by calls: the top entries by calls are the N+1s, the top by mean time are missing
indexes. Match them to `topSql` from `slow_request` lines.

## Media caching

- `GcpStorageService.generateSignedUrl` returns the same signed URL per `(path, expiry)` until 75% of its lifetime has passed, so browsers reuse cached images across requests and polls. Upload URLs are never cached; `delete` evicts a path's entries.
- Display-only read URLs last 60 minutes. Publish-path URLs (TikTok 90, Meta 60, YouTube 240, render specs 45) keep their own constants.
- Objects carry `Cache-Control: private, max-age=86400`: set on server writes, and best-effort at the upload-confirm step for browser direct uploads.
- Backfill objects uploaded before this change (run once per environment by someone with bucket access): `scripts/gcs-set-cache-control.sh --dry-run <bucket> [prefix]`, then without `--dry-run`. Production (`ai-conductor-prod-storage`) was backfilled on 2026-10-19.

## CORS preflights

The frontend calls the backend cross-origin, so browsers send an `OPTIONS` preflight before most API calls; the
answer is cached per exact URL, and our URLs carry ids, so `max-age` rarely helps. A same-origin proxy through
the frontend (`/_backend/*` rewrite, #609) was measured in production on 2026-10-20 and then removed (it adds a
hop through the frontend service to every call):

| | calls | p50 overhead | p95 overhead |
|---|---|---|---|
| direct (cross-origin) | 27 | 205 ms | 885 ms |
| proxy (same-origin) | 88 | 162 ms | 293 ms |

"Overhead" is client time minus server time. If this is revisited, serve the API from the app's domain at the
load balancer instead, which removes preflights without the extra hop.

## Posts list sizing (server paging decision)

`GET /work-items?workflow=...` returns the whole workflow and the page filters, searches and renders it
client-side. For publishing workflows each row now also carries a thumbnail URL, per-destination states and
its Creative, loaded for the whole page in a fixed number of batched reads (the first image Asset per Post,
publish targets, and the Creative frame/Creative lookups), never per row. Workflows that do not declare
publishing (Engineering) are skipped, and only the first image of each Post is read and signed.

Re-measured on 2026-10-27 after the review fixes with 300 Posts (2 destinations and 1 image each), local Postgres in Testcontainers
(`WorkItemListEnricherIntegrationTest`) and jsdom render (`WorkItemList.render-cost.test.tsx`):

| | before | after |
|---|---|---|
| list endpoint work (query + mapping, median of 7; three runs) | 17-26 ms | 32-39 ms (+5 to +20 ms, about 18 typical) |
| render 300 list rows (jsdom) | 305 ms | 362 ms |
| render 300 grid cards (jsdom) | n/a | 99 ms |

Decision threshold for adding keyset paging and server-side filters (`limit`/`cursor`/`q`, as Media has):
the list endpoint over 500 ms for ~300 Posts, or a 300-row render over 1 s. Both are well under, so paging
is **not** built. Revisit with `scripts/perf-report.sh` once a workspace passes ~1000 Posts; the response
also grows by roughly one signed URL per Post (~0.5 KB each) for publishing workflows.
