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
SQL is normalized (literals become `?`, `IN` lists collapse, whitespace is squeezed, 300 chars max, 200
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
| `api_call` | `path` (ids replaced with `:id`), `method`, `status`, `clientMs`, `serverMs`, `dbMs`, `dbQueries` (from Server-Timing), `via` (`direct` or `proxy`, see below), `page` |
| `web_vital` | `name` (LCP, INP, FCP, TTFB, CLS), `value` (ms; CLS is x1000), `rating`, `page` |
| `page_ready` | `page` (a stable name such as `work-item-detail`), `path`, `ms` since navigation start, `apiCalls` made since then |

`clientMs - serverMs` is the network + queueing + parsing overhead of a call. `page_ready` is recorded once
per navigation by `markPageReady(name)` (`src/lib/perf.ts`) from: work-item list and detail, project doc,
knowledge home, media library and creatives library. To instrument another page call it from an effect once
its primary data has rendered.

### Same-origin API proxy (opt-in, per browser)

By default the browser calls the backend cross-origin with an `Authorization` header, so every new URL pays a
CORS preflight (browsers cache preflights per exact URL, and ours embed ids). To measure what that costs, the
frontend can route API calls through itself instead: `/_backend/:path*` is a Next.js rewrite
(`next.config.mjs`) to `${NEXT_PUBLIC_API_URL}/:path*`. Same-origin means no preflight. The rewrite exists on
every deployment but nothing uses it until a browser opts in, so both modes run side by side on one deployment.

- Enable: open any app page with `?api=proxy`. Revert: `?api=direct`. The choice is stored in
  `localStorage` key `conductor.apiVia`, so it survives navigation and reloads. Without storage (private mode)
  the browser stays direct unless the param is on the current URL.
- Check: in devtools Network, API calls go to `/_backend/api/...` on the app's own host and show no OPTIONS
  request; `Server-Timing` is still present.
- Used by `timedFetch` in `src/lib/api.ts` and the doc image upload (multipart). Not used for the workflow log
  `EventSource` (cookie-credentialed stream) or for the webhook URL shown in the UI; server-side code always
  calls the backend directly. Browser-to-GCS uploads use signed URLs and never touch either path.
- Verified through the rewrite (production build against a stub backend): method (GET/POST/PATCH/PUT/DELETE),
  path, query string, body, `Authorization`, `Content-Type` (including multipart boundary), status codes,
  `Server-Timing`, `Content-Disposition` and binary bodies. Request bodies are buffered by Next because
  `src/middleware.ts` exists; `experimental.proxyClientMaxBodySize` is set to 50 MB (the backend's multipart
  request cap) because the 10 MB default truncates bigger bodies and the proxied call fails.
- Cost: every proxied call is an extra request through the frontend Cloud Run service (CPU, request count,
  and a second hop to the backend). Keep it an experiment for a few browsers, not a default, until the
  numbers justify it.

To compare: use the app for a while with `?api=proxy` in one browser and normally in another (or switch with
`?api=direct`), then run `./scripts/perf-report.sh`. Section 3 reports client-minus-server overhead per `via`
(overall with p50/p95, then per path); compare the `direct` and `proxy` rows. Samples from before this field
existed count as `direct`. In Cloud Logging: `jsonPayload.message="api_call" jsonPayload.via="proxy"`.

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
