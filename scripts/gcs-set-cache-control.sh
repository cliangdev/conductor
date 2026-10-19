#!/usr/bin/env bash
# Backfill `Cache-Control: private, max-age=86400` on existing objects in the Conductor GCS bucket.
#
# New objects get this header automatically (server writes, and the upload-confirm step for browser
# direct uploads). Objects uploaded before that change have none, so GCS serves them as non-cacheable.
# Run this ONCE per environment, by a person with storage.objects.update on the bucket.
#
# Usage:
#   scripts/gcs-set-cache-control.sh --dry-run BUCKET [PREFIX]   # list what would change, touch nothing
#   scripts/gcs-set-cache-control.sh BUCKET [PREFIX]             # apply
#
# Examples:
#   scripts/gcs-set-cache-control.sh --dry-run my-bucket projects/
#   scripts/gcs-set-cache-control.sh my-bucket projects/
#
# BUCKET may also come from $BUCKET. PREFIX is optional and limits the update to one path prefix.
set -euo pipefail

CACHE_CONTROL="private, max-age=86400"
DRY_RUN=false
ARGS=()
for arg in "$@"; do
  case "$arg" in
    --dry-run|-n) DRY_RUN=true ;;
    -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
    *) ARGS+=("$arg") ;;
  esac
done

BUCKET="${ARGS[0]:-${BUCKET:-}}"
PREFIX="${ARGS[1]:-}"
if [[ -z "$BUCKET" ]]; then
  echo "error: bucket name required (argument or \$BUCKET)" >&2
  exit 2
fi
if ! command -v gcloud >/dev/null 2>&1; then
  echo "error: gcloud not found; install the Google Cloud SDK" >&2
  exit 2
fi

PREFIX="${PREFIX#/}"
TARGET="gs://${BUCKET}/${PREFIX}**"

if $DRY_RUN; then
  echo "Dry run: objects matching ${TARGET} that would get 'Cache-Control: ${CACHE_CONTROL}':"
  gcloud storage ls "$TARGET"
  exit 0
fi

echo "Setting 'Cache-Control: ${CACHE_CONTROL}' on ${TARGET}"
# gcloud storage patches in parallel; gsutil -m runs one object at a time on macOS (~12s each).
gcloud storage objects update "$TARGET" --cache-control="${CACHE_CONTROL}"
