#!/usr/bin/env bash
#
# Refreshes data/latest.db from a Health Connect export zip.
#
# Usage:
#   ./scripts/refresh.sh <path-to-Health Connect.zip>
#
# The zip is produced by Health Connect's built-in scheduled export and lives
# in Google Drive. Claude (with the Drive connector) downloads it; this script
# turns it into a queryable SQLite db at data/latest.db.
#
# Each export is a FULL snapshot, so we just replace latest.db every time.

set -euo pipefail

ZIP="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DATA="$ROOT/data"

if [[ -z "$ZIP" || ! -f "$ZIP" ]]; then
  echo "Usage: $0 <path-to-Health Connect.zip>" >&2
  exit 1
fi

mkdir -p "$DATA"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

unzip -o -q "$ZIP" -d "$TMP"

# Find the .db inside (named health_connect_export.db, but be defensive)
DB_FILE="$(find "$TMP" -name '*.db' | head -1)"
if [[ -z "$DB_FILE" ]]; then
  echo "No .db found inside $ZIP" >&2
  exit 1
fi

# Archive the previous snapshot with a date stamp, then replace latest
if [[ -f "$DATA/latest.db" ]]; then
  STAMP="$(date -r "$DATA/latest.db" +%Y%m%d 2>/dev/null || date +%Y%m%d)"
  mkdir -p "$DATA/history"
  cp "$DATA/latest.db" "$DATA/history/health_$STAMP.db"
fi

cp "$DB_FILE" "$DATA/latest.db"
echo "✅ Refreshed: $DATA/latest.db ($(du -h "$DATA/latest.db" | cut -f1))"
