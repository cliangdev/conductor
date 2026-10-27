#!/usr/bin/env bash
# Create or update the Cloud Scheduler jobs that trigger Conductor's timed work.
#
# Each job POSTs <backend>/internal/v1/scheduler/jobs/<job> with a Google OIDC ID token minted for the
# conductor-scheduler service account. Idempotent: re-running updates jobs in place.
# See docs/workflows.md#timed-jobs for the runbook.
#
# --backend-url must equal the BACKEND_URL repo variable CD uses (it becomes the OIDC audience).
# Usage: scripts/setup-cloud-scheduler.sh [--project P] [--region R] [--backend-url URL] [--dry-run] [--prune]
set -euo pipefail

PROJECT="ai-conductor-prod"
REGION="us-central1"
SERVICE="conductor-backend"
BACKEND_URL=""
DRY_RUN=0
PRUNE=0

# ONE table of jobs. Names must equal ScheduledJobRegistry's (enforced by ScheduledJobCloudSchedulerScriptTest).
# Columns: job | cron (Etc/UTC) | attempt-deadline | max-retry-attempts | min-backoff | max-backoff
# Sub-minute registry jobs (30s) run every minute: Cloud Scheduler's floor.
JOBS=(
  "action-invocation-retry|* * * * *|300s|0|30s|300s"
  "connector-feed-poll|* * * * *|300s|0|30s|300s"
  "knowledge-ingest-poll|* * * * *|300s|0|30s|300s"
  "native-handoff-sweep|* * * * *|300s|0|30s|300s"
  "native-publish-confirmation-poll|* * * * *|300s|0|30s|300s"
  "post-publish-poll|* * * * *|300s|0|30s|300s"
  "webhook-retry|* * * * *|300s|0|30s|300s"
  "workflow-local-pickup-timeout|* * * * *|300s|0|30s|300s"
  "workflow-schedule-poll|* * * * *|300s|0|30s|300s"
  "publish-reconcile-stranded-targets|*/5 * * * *|900s|0|30s|300s"
  "marketing-insights-feed-provisioner|7 * * * *|900s|2|30s|300s"
  "knowledge-retention-sweep|23 * * * *|900s|2|30s|300s"
  "daemon-event-purge|41 * * * *|900s|2|30s|300s"
  "memory-maintenance|17 3 * * *|1800s|3|5m|1h"
  "workflow-stuck-run-cleanup|0 2 * * *|1800s|3|5m|1h"
)

usage() { sed -n "2,9p" "$0"; exit "${1:-0}"; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project) PROJECT="${2:?--project needs a value}"; shift 2 ;;
    --region) REGION="${2:?--region needs a value}"; shift 2 ;;
    --backend-url) BACKEND_URL="${2:?--backend-url needs a value}"; shift 2 ;;
    --dry-run) DRY_RUN=1; shift ;;
    --prune) PRUNE=1; shift ;;
    -h|--help) usage 0 ;;
    *) echo "unknown flag: $1" >&2; usage 1 ;;
  esac
done

# Run a state-changing command, or just print it under --dry-run.
run() {
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '[dry-run]'
    for a in "$@"; do
      if [[ "$a" =~ [^A-Za-z0-9_./:=@-] ]]; then printf " '%s'" "$a"; else printf ' %s' "$a"; fi
    done
    printf '\n'
  else
    "$@"
  fi
}

if [[ -z "$BACKEND_URL" ]]; then
  BACKEND_URL="$(gcloud run services describe "$SERVICE" --project "$PROJECT" --region "$REGION" --format='value(status.url)')"
fi
BACKEND_URL="${BACKEND_URL%/}"
SA_NAME="conductor-scheduler"
SA_EMAIL="${SA_NAME}@${PROJECT}.iam.gserviceaccount.com"
AUDIENCE="${BACKEND_URL}/internal/v1/scheduler"

echo "project=$PROJECT region=$REGION backend=$BACKEND_URL sa=$SA_EMAIL dry-run=$DRY_RUN prune=$PRUNE"

echo "== enable Cloud Scheduler API"
run gcloud services enable cloudscheduler.googleapis.com --project "$PROJECT"

echo "== service account"
if [[ $DRY_RUN -eq 0 ]] && gcloud iam service-accounts describe "$SA_EMAIL" --project "$PROJECT" >/dev/null 2>&1; then
  echo "exists: $SA_EMAIL"
else
  run gcloud iam service-accounts create "$SA_NAME" --project "$PROJECT" \
    --display-name "Conductor Cloud Scheduler invoker"
fi

echo "== run.invoker on $SERVICE (harmless while public; needed if the service goes private)"
run gcloud run services add-iam-policy-binding "$SERVICE" --project "$PROJECT" --region "$REGION" \
  --member "serviceAccount:${SA_EMAIL}" --role roles/run.invoker

echo "== jobs"
names=()
for row in "${JOBS[@]}"; do
  IFS='|' read -r job schedule deadline retries min_backoff max_backoff <<<"$row"
  name="conductor-${job}"
  names+=("$name")
  if [[ $DRY_RUN -eq 0 ]] && gcloud scheduler jobs describe "$name" --project "$PROJECT" --location "$REGION" >/dev/null 2>&1; then
    verb=update
  else
    verb=create
  fi
  run gcloud scheduler jobs "$verb" http "$name" --project "$PROJECT" --location "$REGION" \
    --schedule "$schedule" --time-zone Etc/UTC \
    --http-method POST --uri "${BACKEND_URL}/internal/v1/scheduler/jobs/${job}" \
    --oidc-service-account-email "$SA_EMAIL" --oidc-token-audience "$AUDIENCE" \
    --attempt-deadline "$deadline" --max-retry-attempts "$retries" \
    --min-backoff "$min_backoff" --max-backoff "$max_backoff"
done

if [[ $PRUNE -eq 1 ]]; then
  echo "== prune conductor-* jobs not in the table"
  existing="$(gcloud scheduler jobs list --project "$PROJECT" --location "$REGION" --format='value(name)' || true)"
  while IFS= read -r full; do
    [[ -z "$full" ]] && continue
    short="${full##*/}"
    [[ "$short" == conductor-* ]] || continue
    keep=0
    for n in "${names[@]}"; do [[ "$n" == "$short" ]] && keep=1; done
    if [[ $keep -eq 0 ]]; then
      run gcloud scheduler jobs delete "$short" --project "$PROJECT" --location "$REGION" --quiet
    fi
  done <<<"$existing"
fi

cat <<OUT

== set these on the backend (CD sets them on deploy; or use gcloud run services update --update-env-vars)
CONDUCTOR_SCHEDULER_OIDC_AUDIENCE=${AUDIENCE}
CONDUCTOR_SCHEDULER_INVOKER_EMAIL=${SA_EMAIL}
OUT
