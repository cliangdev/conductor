#!/usr/bin/env bash
# Fetch Cloud Run logs for conductor-backend or conductor-frontend.
#
# Configuration:
#   CONDUCTOR_GCP_PROJECT   (required)  GCP project ID hosting the Cloud Run service
#   CONDUCTOR_GCP_REGION    (optional)  defaults to us-central1
#
# Usage:
#   CONDUCTOR_GCP_PROJECT=my-project ./scripts/logs.sh
#   CONDUCTOR_GCP_PROJECT=my-project ./scripts/logs.sh frontend
#   CONDUCTOR_GCP_PROJECT=my-project ./scripts/logs.sh backend --lines 200
#   CONDUCTOR_GCP_PROJECT=my-project ./scripts/logs.sh backend --since 1h
#   CONDUCTOR_GCP_PROJECT=my-project ./scripts/logs.sh backend --tail

set -euo pipefail

PROJECT="${CONDUCTOR_GCP_PROJECT:?set CONDUCTOR_GCP_PROJECT to your GCP project ID}"
REGION="${CONDUCTOR_GCP_REGION:-us-central1}"
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
  gcloud beta run services logs tail "$SERVICE" \
    --project="$PROJECT" \
    --region="$REGION"
elif [[ -n "$SINCE" ]]; then
  TS=$(since_timestamp "$SINCE")
  gcloud logging read \
    "resource.type=cloud_run_revision AND resource.labels.service_name=$SERVICE AND timestamp>=\"$TS\"" \
    --project="$PROJECT" \
    --format="$LOG_FORMAT" \
    --order=asc
else
  gcloud logging read \
    "resource.type=cloud_run_revision AND resource.labels.service_name=$SERVICE" \
    --project="$PROJECT" \
    --limit="$LINES" \
    --format="$LOG_FORMAT" \
    --order=desc \
  | tac
fi
