#!/usr/bin/env bash
# Summarise request and page timings from Cloud Logging (see docs/performance.md).
#
# Prints:
#   1. per backend route (http_request + slow_request lines): n, p50/p95/max ms, and averages for
#      DB queries, DB ms, pool wait ms, connection checkouts, response bytes - sorted by p95 desc
#   2. per frontend page: page_ready p50/p95 and average API calls
#   3. API calls: average (clientMs - serverMs) overhead = network + queueing + parse, split by `via`
#      (direct cross-origin vs the opt-in same-origin proxy), overall and per path
#   4. the SQL statements most repeated inside slow requests (N+1 suspects)
#
# Configuration:
#   CONDUCTOR_GCP_PROJECT   GCP project ID (or pass --project)
#   CONDUCTOR_GCP_REGION    unused for reads, kept for parity with logs.sh
#
# Usage:
#   ./scripts/perf-report.sh                    # last 24h
#   ./scripts/perf-report.sh --since 2h
#   ./scripts/perf-report.sh --project my-proj --limit 20000
#
# Offline testing: PERF_REPORT_BACKEND_JSON / PERF_REPORT_FRONTEND_JSON may point at files holding
# the JSON array `gcloud logging read --format=json` would have returned.

set -euo pipefail

SINCE="24h"
PROJECT="${CONDUCTOR_GCP_PROJECT:-}"
LIMIT=50000
BACKEND_SERVICE="conductor-backend"
FRONTEND_SERVICE="conductor-frontend"

while [[ $# -gt 0 ]]; do
  case $1 in
    --since)   SINCE="$2"; shift 2 ;;
    --project) PROJECT="$2"; shift 2 ;;
    --limit)   LIMIT="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; exit 1; }

since_timestamp() {
  local since="$1" unit="${1: -1}" amount="${1%?}"
  case "$unit" in
    s) python3 -c "from datetime import datetime, timezone, timedelta; print((datetime.now(timezone.utc) - timedelta(seconds=$amount)).strftime('%Y-%m-%dT%H:%M:%SZ'))" ;;
    m) python3 -c "from datetime import datetime, timezone, timedelta; print((datetime.now(timezone.utc) - timedelta(minutes=$amount)).strftime('%Y-%m-%dT%H:%M:%SZ'))" ;;
    h) python3 -c "from datetime import datetime, timezone, timedelta; print((datetime.now(timezone.utc) - timedelta(hours=$amount)).strftime('%Y-%m-%dT%H:%M:%SZ'))" ;;
    d) python3 -c "from datetime import datetime, timezone, timedelta; print((datetime.now(timezone.utc) - timedelta(days=$amount)).strftime('%Y-%m-%dT%H:%M:%SZ'))" ;;
    *) echo "Invalid --since: $since (use e.g. 30m, 24h, 7d)" >&2; exit 1 ;;
  esac
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch() { # service, message-filter, outfile
  local service="$1" messages="$2" out="$3" ts
  ts="$(since_timestamp "$SINCE")"
  gcloud logging read \
    "resource.type=cloud_run_revision AND resource.labels.service_name=$service AND timestamp>=\"$ts\" AND jsonPayload.message=($messages)" \
    --project="$PROJECT" --limit="$LIMIT" --order=desc --format=json > "$out"
}

if [[ -n "${PERF_REPORT_BACKEND_JSON:-}" || -n "${PERF_REPORT_FRONTEND_JSON:-}" ]]; then
  BACKEND="${PERF_REPORT_BACKEND_JSON:-}"
  FRONTEND="${PERF_REPORT_FRONTEND_JSON:-}"
else
  PROJECT="${PROJECT:?set CONDUCTOR_GCP_PROJECT or pass --project}"
  BACKEND="$TMP/backend.json"
  FRONTEND="$TMP/frontend.json"
  echo "Fetching logs since $SINCE from $PROJECT (up to $LIMIT entries each)..." >&2
  fetch "$BACKEND_SERVICE" '"http_request" OR "slow_request"' "$BACKEND"
  fetch "$FRONTEND_SERVICE" '"page_ready" OR "api_call"' "$FRONTEND"
fi
[[ -s "${BACKEND:-}" ]] || { echo '[]' > "$TMP/backend.json"; BACKEND="$TMP/backend.json"; }
[[ -s "${FRONTEND:-}" ]] || { echo '[]' > "$TMP/frontend.json"; FRONTEND="$TMP/frontend.json"; }

# Shared jq helpers. Every field access tolerates missing values.
JQ_LIB='
def num: if type == "number" then . elif type == "string" then (tonumber? // 0) else 0 end;
def pct(p): sort | if length == 0 then 0 else .[((length * p) | ceil) - 1] end;
def avg: if length == 0 then 0 else (add / length) end;
def r1: (. * 10 | round) / 10;
def tsv: map(tostring) | join("\t");
'

table() { # header line on stdin first, then rows
  if command -v column >/dev/null 2>&1; then column -t -s $'\t'; else cat; fi
}

echo
echo "== Backend routes (sorted by p95 ms, desc) =="
jq -r "$JQ_LIB"'
  [ .[]? | .jsonPayload? // empty | select(.route != null) ] as $r
  | if ($r | length) == 0 then "(no http_request entries)" else
    ( ["route","n","p50","p95","max","dbQueries","dbMs","poolWaitMs","checkouts","respBytes"] | tsv ),
    ( $r | group_by("\(.method // "") \(.route)")
      | map({ key: "\(.[0].method // "") \(.[0].route)", v: . })
      | map( .v as $g | ($g | map(.ms | num)) as $ms | {
          key, n: ($g | length), p50: ($ms | pct(0.5)), p95: ($ms | pct(0.95)), max: ($ms | max),
          q: ($g | map(.dbQueries | num) | avg | r1), db: ($g | map(.dbMs | num) | avg | r1),
          pool: ($g | map(.poolWaitMs | num) | avg | r1), co: ($g | map(.connCheckouts | num) | avg | r1),
          bytes: ($g | map(.respBytes | num) | avg | round) } )
      | sort_by(-.p95)
      | .[] | [.key, .n, .p50, .p95, .max, .q, .db, .pool, .co, .bytes] | tsv )
    end' "$BACKEND" | table

echo
echo "== Frontend pages: page_ready =="
jq -r "$JQ_LIB"'
  [ .[]? | .jsonPayload? // empty | select(.type == "page_ready" or .message == "page_ready") ] as $r
  | if ($r | length) == 0 then "(no page_ready entries)" else
    ( ["page","n","p50ms","p95ms","avgApiCalls"] | tsv ),
    ( $r | group_by(.page // "?") | map( . as $g | ($g | map(.ms | num)) as $ms
        | { page: ($g[0].page // "?"), n: ($g | length), p50: ($ms | pct(0.5)), p95: ($ms | pct(0.95)),
            calls: ($g | map(.apiCalls | num) | avg | r1) } )
      | sort_by(-.p95) | .[] | [.page, .n, .p50, .p95, .calls] | tsv )
    end' "$FRONTEND" | table

echo
echo "== API calls: client minus server time (network + queueing + parsing) =="
jq -r "$JQ_LIB"'
  [ .[]? | .jsonPayload? // empty | select((.type == "api_call" or .message == "api_call") and (.serverMs != null)) ] as $r
  | def via: .via // "direct";
  if ($r | length) == 0 then "(no api_call entries with Server-Timing)" else
    "-- by via: direct (cross-origin) vs proxy (same-origin /_backend); samples without via count as direct",
    ( ["via","n","avgClientMs","avgServerMs","avgOverheadMs","p50OverheadMs","p95OverheadMs"] | tsv ),
    ( $r | group_by(via)
      | map( . as $g | ($g | map((.clientMs | num) - (.serverMs | num))) as $o | {
          v: ($g[0] | via), n: ($g | length),
          c: ($g | map(.clientMs | num) | avg | r1), s: ($g | map(.serverMs | num) | avg | r1),
          o: ($o | avg | r1), p50: ($o | pct(0.5) | r1), p95: ($o | pct(0.95) | r1) } )
      | .[] | [.v, .n, .c, .s, .o, .p50, .p95] | tsv ),
    "",
    "-- by path",
    ( ["via method path","n","avgClientMs","avgServerMs","avgOverheadMs","avgDbQueries"] | tsv ),
    ( $r | group_by("\(via) \(.method // "") \(.path // "?")")
      | map( . as $g | {
          key: "\($g[0] | via) \($g[0].method // "") \($g[0].path // "?")", n: ($g | length),
          c: ($g | map(.clientMs | num) | avg | r1), s: ($g | map(.serverMs | num) | avg | r1),
          o: ($g | map((.clientMs | num) - (.serverMs | num)) | avg | r1),
          q: ($g | map(.dbQueries | num) | avg | r1) } )
      | sort_by(-.o) | .[0:30] | .[] | [.key, .n, .c, .s, .o, .q] | tsv )
    end' "$FRONTEND" | table

echo
echo "== Most repeated SQL inside slow requests (N+1 suspects) =="
jq -r "$JQ_LIB"'
  [ .[]? | .jsonPayload? // empty | select(.topSql != null and .topSql != "")
    | (.topSql | split(" | ")[] | capture("^(?<c>[0-9]+)x (?<sql>.*)$")? // empty)
    | { sql, c: (.c | tonumber) } ] as $r
  | if ($r | length) == 0 then "(no slow_request entries with repeated SQL)" else
    ( ["repeats","slowRequests","sql"] | tsv ),
    ( $r | group_by(.sql) | map({ sql: .[0].sql, total: (map(.c) | add), reqs: length })
      | sort_by(-.total) | .[0:15] | .[] | [.total, .reqs, .sql] | tsv )
    end' "$BACKEND" | table
