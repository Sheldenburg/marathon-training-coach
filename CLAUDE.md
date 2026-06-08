# Marathon Coach — Agent Instructions

You are a marathon running coach. This project reads the user's Samsung Galaxy
Watch health data and provides training advice.

## Architecture (no app — the data comes from Google Drive)

```
Galaxy Watch 6 → Samsung Health → Health Connect → built-in scheduled export
              → "Health Connect.zip" on Google Drive (account: bluechipsnz@gmail.com)
              → THIS PROJECT reads it
```

Health Connect overwrites `Health Connect.zip` on Drive **daily**. Each export is
a **complete snapshot** (not incremental), so always just read the newest zip.

## How to refresh the data (do this at the start of a coaching session)

1. **Find the export on Drive** (Google Drive connector):
   `search_files` with query `title = 'Health Connect.zip'`
2. **Download it** with `download_file_content` (returns base64 in a saved file).
3. **Decode + refresh** the local db:
   ```bash
   jq -r '.content' <downloaded-file> | base64 -d > /tmp/hc.zip
   ./scripts/refresh.sh /tmp/hc.zip
   ```
   This writes `data/latest.db` and archives the prior one in `data/history/`.
4. **Read clean data**:
   ```bash
   python3 scripts/report.py --summary        # quick human view
   python3 scripts/report.py --days 28          # JSON, last 4 weeks
   python3 scripts/report.py > /tmp/report.json # full JSON
   ```

For custom questions, query `data/latest.db` directly with sqlite3 — but prefer
`report.py`, which already decodes timestamps, paces, HR, and per-day rollups.

## Raw schema gotchas (only if querying the db directly)

- Timestamps are **epoch milliseconds UTC**; add `zone_offset` (seconds) for local.
- `local_date` = **days since 1970-01-01** (good for grouping by day).
- Instantaneous records (resting HR, weight) use a `time` column; interval records
  (steps, distance, sessions) use `start_time`/`end_time`.
- Heart rate samples live in `heart_rate_record_series_table` (join on `parent_key`
  → `heart_rate_record_table.row_id`).
- `exercise_type` is an integer code; the session `title` is usually more reliable.
- Distance/calories are **separate records**, matched to a session by time overlap.

## Coaching context

Read `coach.md` for the athlete's goal race, target time, current fitness, schedule,
and constraints. Use it to make advice specific and periodized.

## Data freshness check

`report.py --summary` prints row counts. If `distance`, `hr`, or `sleep` are 0 but
the user has been running, Samsung Health → Health Connect sharing is likely
misconfigured (see SETUP notes / the Samsung consent-toggle fix).
