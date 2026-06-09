#!/usr/bin/env python3
"""
Turns the raw Health Connect export DB (data/latest.db) into clean,
coaching-ready JSON — so any agent can read your training without
relearning the gnarly raw schema.

Usage:
    python3 scripts/report.py              # full report as JSON
    python3 scripts/report.py --summary    # human-readable summary
    python3 scripts/report.py --days 28    # only last N days

Output sections:
    activities  — every exercise session (runs, walks, yoga, …) with
                  distance, duration, pace, avg/max HR, calories
    daily       — per-day steps, calories, resting HR, weight, sleep

Raw-schema notes (why this script exists):
  • timestamps are epoch milliseconds (UTC); we add zone_offset (seconds)
  • local_date is "days since 1970-01-01" — handy for grouping by day
  • heart rate lives in a separate series table keyed by parent_key
  • distance/calories are separate records, matched to a session by time overlap
"""

import argparse
import json
import sqlite3
import sys
from datetime import datetime, timezone, timedelta
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent.parent / "data" / "latest.db"

# Best-effort exercise type map for this DB's encoding.
# We lean on the session `title` first (Samsung Health writes readable titles);
# this is only a fallback. Refine as real activities appear.
EXERCISE_TYPES = {
    4:  "run",             # Samsung Health running (confirmed: distance + 6:48/km pace)
    8:  "biking",
    56: "run",             # Health Connect standard RUNNING
    57: "yoga",            # confirmed from a "5-Min Yoga" session in the export
    60: "workout",         # Samsung Health indoor workout (no GPS distance, HR 83-166)
    79: "walk",
}

SLEEP_STAGES = {
    1: "awake", 2: "sleeping", 3: "out_of_bed",
    4: "light", 5: "deep", 6: "rem", 7: "awake_in_bed",
}


def ms_to_iso(ms, offset_sec=0):
    if ms is None:
        return None
    dt = datetime.fromtimestamp(ms / 1000, tz=timezone.utc) + timedelta(seconds=offset_sec or 0)
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def local_date_str(local_date_days):
    """local_date is days since 1970-01-01."""
    if local_date_days is None:
        return None
    return (datetime(1970, 1, 1) + timedelta(days=local_date_days)).strftime("%Y-%m-%d")


def table_exists(con, name):
    r = con.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone()
    return r is not None


def count(con, table):
    if not table_exists(con, table):
        return 0
    return con.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]


def get_activities(con, since_ms=None):
    if not table_exists(con, "exercise_session_record_table"):
        return []
    where = "WHERE start_time >= ?" if since_ms else ""
    args = (since_ms,) if since_ms else ()
    rows = con.execute(f"""
        SELECT row_id, start_time, end_time, start_zone_offset,
               exercise_type, title, notes, local_date
        FROM exercise_session_record_table
        {where}
        ORDER BY start_time
    """, args).fetchall()

    activities = []
    for r in rows:
        (row_id, start, end, off, etype, title, notes, lday) = r
        dur_sec = (end - start) // 1000 if start and end else None

        # Distance: sum distance records that overlap the session window
        dist_m = con.execute("""
            SELECT COALESCE(SUM(distance), 0) FROM distance_record_table
            WHERE start_time >= ? AND end_time <= ?
        """, (start, end)).fetchone()[0] if table_exists(con, "distance_record_table") else 0
        dist_km = round(dist_m / 1000, 2) if dist_m else None

        # Calories overlapping the window
        cals = con.execute("""
            SELECT COALESCE(SUM(energy), 0) FROM total_calories_burned_record_table
            WHERE start_time >= ? AND end_time <= ?
        """, (start, end)).fetchone()[0] if table_exists(con, "total_calories_burned_record_table") else 0

        # Heart rate from the series table, for hr records overlapping the window
        hr_vals = []
        if table_exists(con, "heart_rate_record_table") and table_exists(con, "heart_rate_record_series_table"):
            hr_vals = [x[0] for x in con.execute("""
                SELECT s.beats_per_minute
                FROM heart_rate_record_series_table s
                JOIN heart_rate_record_table h ON s.parent_key = h.row_id
                WHERE h.start_time >= ? AND h.end_time <= ?
            """, (start, end)).fetchall()]

        avg_pace = None
        if dist_km and dur_sec:
            sec_per_km = dur_sec / dist_km
            avg_pace = f"{int(sec_per_km // 60)}:{int(sec_per_km % 60):02d}"

        activities.append({
            "date": local_date_str(lday),
            "start_time": ms_to_iso(start, off),
            "type": EXERCISE_TYPES.get(etype, title or f"type_{etype}"),
            "title": title,
            "duration_min": round(dur_sec / 60, 1) if dur_sec else None,
            "distance_km": dist_km,
            "avg_pace_min_km": avg_pace,
            "avg_hr_bpm": round(sum(hr_vals) / len(hr_vals)) if hr_vals else None,
            "max_hr_bpm": max(hr_vals) if hr_vals else None,
            "calories_kcal": round(cals) if cals else None,
            "notes": notes,
        })
    return activities


def get_daily(con, since_ms=None):
    """Aggregate per local_date: steps, calories, resting HR, weight, sleep."""
    daily = {}

    def add(date, key, value):
        if date is None:
            return
        daily.setdefault(date, {"date": date})
        daily[date][key] = value

    # Steps per day
    if table_exists(con, "steps_record_table"):
        for lday, total in con.execute("""
            SELECT local_date, SUM(count) FROM steps_record_table
            GROUP BY local_date
        """).fetchall():
            add(local_date_str(lday), "steps", total)

    # Resting HR (last reading per day) — instantaneous record uses `time`
    if table_exists(con, "resting_heart_rate_record_table"):
        for lday, bpm in con.execute("""
            SELECT local_date, beats_per_minute FROM resting_heart_rate_record_table
            ORDER BY time
        """).fetchall():
            add(local_date_str(lday), "resting_hr_bpm", bpm)

    # Weight (last reading per day) — value stored in grams or kg depending on source
    if table_exists(con, "weight_record_table"):
        cols = [c[1] for c in con.execute("PRAGMA table_info(weight_record_table)").fetchall()]
        wcol = "weight" if "weight" in cols else None
        if wcol:
            for lday, w in con.execute(f"""
                SELECT local_date, {wcol} FROM weight_record_table ORDER BY time
            """).fetchall():
                kg = round(w / 1000, 2) if w and w > 1000 else (round(w, 2) if w else None)
                add(local_date_str(lday), "weight_kg", kg)

    # Sleep total minutes + stage breakdown
    if table_exists(con, "sleep_session_record_table"):
        for row_id, lday, start, end in con.execute("""
            SELECT row_id, local_date, start_time, end_time FROM sleep_session_record_table
        """).fetchall():
            mins = (end - start) // 60000 if start and end else None
            add(local_date_str(lday), "sleep_min", mins)

    out = sorted(daily.values(), key=lambda d: d["date"])
    if since_ms:
        cutoff = local_date_str(int((since_ms / 1000) // 86400))
        out = [d for d in out if d["date"] >= cutoff]
    return out


def build_report(con, days=None):
    since_ms = None
    if days:
        since_ms = int((datetime.now(timezone.utc) - timedelta(days=days)).timestamp() * 1000)

    return {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC"),
        "row_counts": {
            "exercise_sessions": count(con, "exercise_session_record_table"),
            "steps_records": count(con, "steps_record_table"),
            "distance_records": count(con, "distance_record_table"),
            "heart_rate_records": count(con, "heart_rate_record_table"),
            "sleep_sessions": count(con, "sleep_session_record_table"),
            "weight_records": count(con, "weight_record_table"),
        },
        "activities": get_activities(con, since_ms),
        "daily": get_daily(con, since_ms),
    }


def print_summary(report):
    rc = report["row_counts"]
    print(f"📊 Health Connect data (as of {report['generated_at']})")
    print(f"   sessions={rc['exercise_sessions']}  steps={rc['steps_records']}  "
          f"distance={rc['distance_records']}  hr={rc['heart_rate_records']}  "
          f"sleep={rc['sleep_sessions']}  weight={rc['weight_records']}")
    print()
    acts = report["activities"]
    if not acts:
        print("   No activities found yet.")
    else:
        print(f"🏃 Activities ({len(acts)}):")
        for a in acts:
            line = f"   {a['date']}  {a['type']:<10}"
            if a["distance_km"]:
                line += f"  {a['distance_km']}km"
            if a["duration_min"]:
                line += f"  {a['duration_min']}min"
            if a["avg_pace_min_km"]:
                line += f"  @{a['avg_pace_min_km']}/km"
            if a["avg_hr_bpm"]:
                line += f"  hr{a['avg_hr_bpm']}"
            print(line)
    print()
    daily = report["daily"]
    if daily:
        print(f"📅 Recent days ({len(daily)}):")
        for d in daily[-7:]:
            bits = [f"{k}={v}" for k, v in d.items() if k != "date"]
            print(f"   {d['date']}  " + "  ".join(bits))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--summary", action="store_true", help="human-readable summary")
    ap.add_argument("--days", type=int, default=None, help="only last N days")
    ap.add_argument("--db", default=str(DB_PATH), help="path to SQLite db")
    args = ap.parse_args()

    if not Path(args.db).exists():
        print(f"❌ No database at {args.db}. Run scripts/refresh.sh first.", file=sys.stderr)
        sys.exit(1)

    con = sqlite3.connect(args.db)
    report = build_report(con, days=args.days)

    if args.summary:
        print_summary(report)
    else:
        print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
