#!/usr/bin/env python3
"""
Builds docs/data.json — the data the GitHub Pages dashboard renders.

Combines:
  • plan.json          — the 20-week Sydney half plan (targets per week)
  • data/latest.db      — actual runs from the Health Connect export

Output (docs/data.json) is plain JSON the static site fetches and charts.
NOTE: this is published to a PUBLIC GitHub Pages site. It includes training
metrics (distance, pace, HR for runs). It intentionally does NOT publish
weight or daily resting-HR. Adjust below if you want more/less public.

Usage:
    python3 scripts/build_dashboard.py
"""

import json
import sqlite3
import sys
from datetime import datetime, date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from report import build_report  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "data" / "latest.db"
PLAN = ROOT / "plan.json"
OUT = ROOT / "docs" / "data.json"


def parse_date(s):
    return datetime.strptime(s, "%Y-%m-%d").date()


def main():
    plan = json.loads(PLAN.read_text())
    start = parse_date(plan["start_date"])
    race = parse_date(plan["race_date"])
    today = date.today()

    # Pull actual activities (runs/walks with distance)
    activities = []
    if DB.exists():
        con = sqlite3.connect(str(DB))
        report = build_report(con)
        activities = report["activities"]
    else:
        report = {"row_counts": {}, "activities": [], "daily": []}

    # Only distance-bearing activities count toward training volume
    runs = [a for a in activities if a.get("distance_km")]

    def week_index(d):
        """Which plan week (1-based) a date falls into; None if outside plan."""
        delta = (d - start).days
        if delta < 0:
            return None
        wk = delta // 7 + 1
        return wk if 1 <= wk <= len(plan["weeks"]) else None

    # Aggregate actuals per plan week
    actual_total = {w["week"]: 0.0 for w in plan["weeks"]}
    actual_long = {w["week"]: 0.0 for w in plan["weeks"]}
    for r in runs:
        try:
            d = parse_date(r["date"])
        except (TypeError, ValueError):
            continue
        wk = week_index(d)
        if wk:
            actual_total[wk] += r["distance_km"]
            actual_long[wk] = max(actual_long[wk], r["distance_km"])

    current_week = week_index(today) or (1 if today < start else len(plan["weeks"]))

    # Events (races)
    events_out = []
    next_event = None
    for ev in plan.get("events", []):
        ev_date = parse_date(ev["date"])
        days = (ev_date - today).days
        ev_out = {**ev, "days_to_event": days, "plan_week": week_index(ev_date)}
        events_out.append(ev_out)
        if days >= 0 and next_event is None:
            next_event = ev_out
    if next_event is None and events_out:
        next_event = events_out[-1]

    weeks_out = []
    for w in plan["weeks"]:
        n = w["week"]
        wk_start = start + timedelta(days=(n - 1) * 7)
        wk_end = wk_start + timedelta(days=6)
        is_done = wk_end < today
        is_current = wk_start <= today <= wk_end
        weeks_out.append({
            "week": n,
            "phase": w["phase"],
            "start": wk_start.isoformat(),
            "end": wk_end.isoformat(),
            "target_total": w["target_total"],
            "target_long": w["target_long"],
            "actual_total": round(actual_total[n], 1),
            "actual_long": round(actual_long[n], 1),
            "state": "done" if is_done else ("current" if is_current else "upcoming"),
        })

    # Summary stats
    total_km = round(sum(actual_total.values()), 1)
    longest = round(max(actual_long.values()), 1) if runs else 0
    days_to_race = (race - today).days
    this_week = next((w for w in weeks_out if w["state"] == "current"), None)

    dashboard = {
        "generated_at": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "goal": plan["goal"],
        "distance_km": plan["distance_km"],
        "target": plan["target"],
        "race_date": plan["race_date"],
        "start_date": plan["start_date"],
        "days_to_race": days_to_race,
        "current_week": current_week,
        "total_weeks": len(plan["weeks"]),
        "summary": {
            "total_km_run": total_km,
            "longest_run_km": longest,
            "num_runs": len(runs),
            "this_week_target": this_week["target_total"] if this_week else None,
            "this_week_actual": this_week["actual_total"] if this_week else None,
        },
        "weeks": weeks_out,
        "recent_runs": [
            {
                "date": r["date"],
                "type": r["type"],
                "distance_km": r["distance_km"],
                "duration_min": r["duration_min"],
                "pace": r["avg_pace_min_km"],
                "avg_hr": r["avg_hr_bpm"],
            }
            for r in runs[-15:][::-1]
        ],
        "events": events_out,
        "next_event": next_event,
        "data_status": report["row_counts"],
    }

    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(dashboard, indent=2))
    print(f"✅ Wrote {OUT}")
    print(f"   {len(runs)} runs · {total_km} km total · longest {longest} km · "
          f"week {current_week}/{len(plan['weeks'])} · {days_to_race} days to race")


if __name__ == "__main__":
    main()
