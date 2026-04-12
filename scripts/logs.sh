#!/usr/bin/env bash
# Fetch Cloud Run logs for conductor-backend or conductor-frontend
#
# Usage:
#   ./scripts/logs.sh                        # backend logs, last 50 lines
#   ./scripts/logs.sh frontend               # frontend logs, last 50 lines
#   ./scripts/logs.sh backend --lines 200    # backend logs, last 200 lines
#   ./scripts/logs.sh backend --since 1h     # backend logs from last 1 hour
#   ./scripts/logs.sh backend --tail         # stream live logs

set -euo pipefail

PROJECT="ai-conductor-prod"
REGION="us-central1"
SERVICE="conductor-backend"
LINES=50
SINCE=""
TAIL=false

# Parse args
while [[ $# -gt 0 ]]; do
  case $1 in
    frontend) SERVICE="conductor-frontend"; shift ;;
    backend)  SERVICE="conductor-backend";  shift ;;
    --lines)  LINES="$2"; shift 2 ;;
    --since)  SINCE="$2"; shift 2 ;;
    --tail)   TAIL=true;  shift ;;
    *) echo "Unknown arg: $1"; exit 1 ;;
  esac
done

echo "▶ Fetching logs for $SERVICE ($PROJECT / $REGION)"
echo ""

LOG_FORMAT='value(timestamp, textPayload, jsonPayload.message)'

since_timestamp() {
  local since="$1"
  local unit="${since: -1}"
  local amount="${since%?}"
  case "$unit" in
    s) python3 -c "from datetime import datetime, timezone, timedelta; print((datetime.now(timezone.utc) - timedelta(seconds=$amount)).strftime('%Y-%m-%dT%H:%M:%SZ'))" ;;
    m) python3 -c "from datetime import datetime, timezone, timedelta; print((datetime.now(timezone.utc) - timedelta(minutes=$amount)).strftime('%Y-%m-%dT%H:%M:%SZ'))" ;;
    h) python3 -c "from datetime import datetime, timezone, timedelta; print((datetime.now(timezone.utc) - timedelta(hours=$amount)).strftime('%Y-%m-%dT%H:%M:%SZ'))" ;;
    d) python3 -c "from datetime import datetime, timezone, timedelta; print((datetime.now(timezone.utc) - timedelta(days=$amount)).strftime('%Y-%m-%dT%H:%M:%SZ'))" ;;
    *) echo "Invalid since format: $since" >&2; exit 1 ;;
  esac
}

if [[ "$TAIL" == true ]]; then
  gcloud --configuration=conductor beta run services logs tail "$SERVICE" \
    --project="$PROJECT" \
    --region="$REGION"
elif [[ -n "$SINCE" ]]; then
  TS=$(since_timestamp "$SINCE")
  gcloud --configuration=conductor logging read \
    "resource.type=cloud_run_revision AND resource.labels.service_name=$SERVICE AND timestamp>=\"$TS\"" \
    --project="$PROJECT" \
    --format="$LOG_FORMAT" \
    --order=asc
else
  gcloud --configuration=conductor logging read \
    "resource.type=cloud_run_revision AND resource.labels.service_name=$SERVICE" \
    --project="$PROJECT" \
    --limit="$LINES" \
    --format="$LOG_FORMAT" \
    --order=desc \
  | tac
fi
