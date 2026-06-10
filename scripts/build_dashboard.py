#!/usr/bin/env python3
"""
Builds docs/data.json — the data the GitHub Pages dashboard renders.

Combines:
  • plan.json          — the 20-week Sydney half plan (targets per week)
  • data/latest.db      — actual activities from the Health Connect export

Output (docs/data.json) is plain JSON the static site fetches and charts.
NOTE: this is published to a PUBLIC GitHub Pages site. It publishes training
metrics (distance, pace, HR for sessions) and daily steps/sleep totals.
It intentionally does NOT publish weight or resting heart rate.

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


WEEK_DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']


def parse_date(s):
    return datetime.strptime(s, "%Y-%m-%d").date()


def generate_weekly_schedule(phase, week_start, activities_this_week):
    """
    Return a 7-entry list (Mon–Sun) combining the planned session type
    with any activities actually logged on that day.
    """
    # Phase-sensitive strength detail
    if phase in ('Taper', 'Race'):
        s1 = {'type': 'mobility', 'label': 'Mobility / Yoga',
              'detail': 'Light stretching only — no heavy lifting during taper'}
        s2 = {'type': 'rest',     'label': 'Rest',
              'detail': 'Keep legs completely fresh this week'}
    elif phase in ('10k Race', 'Half Build'):
        s1 = {'type': 'strength', 'label': 'Strength A',
              'detail': 'KB swings 3×15 · goblet squat 3×10 · single-leg RDL 3×8'}
        s2 = {'type': 'strength', 'label': 'Strength B',
              'detail': 'Step-ups 3×10 · hip thrust 3×12 · band clamshells 3×15'}
    else:  # Base, Endurance, 10k Prep
        s1 = {'type': 'strength', 'label': 'Strength A',
              'detail': 'Band activation 10 min · KB swings 3×15 · goblet squat 3×10'}
        s2 = {'type': 'strength', 'label': 'Strength B',
              'detail': 'Reverse lunge 3×10 · step-ups 3×10 · DB row 3×12'}

    template = [
        {'type': 'cross',       'label': 'Cross trainer / Rest',
         'detail': '20–30 min easy cross trainer (RPE 4/10) or full rest'},
        {'type': 'run-easy',    'label': 'Easy run',
         'detail': 'Conversational pace — full sentences, no gasping'},
        s1,
        {'type': 'run-quality', 'label': 'Quality run',
         'detail': "This week's interval or tempo session"},
        {'type': 'cross',       'label': 'Cross trainer',
         'detail': '30 min aerobic at easy effort — active recovery'},
        s2,
        {'type': 'run-long',    'label': 'Long run',
         'detail': 'Slow and steady — walk breaks welcome'},
    ]

    acts_by_date = {}
    for a in activities_this_week:
        acts_by_date.setdefault(a['date'], []).append(a)

    today = date.today()
    schedule = []
    for i, (day_name, tmpl) in enumerate(zip(WEEK_DAYS, template)):
        day_date = week_start + timedelta(days=i)
        day_acts = acts_by_date.get(day_date.isoformat(), [])
        schedule.append({
            'day': day_name,
            'date': day_date.isoformat(),
            'is_today': day_date == today,
            'is_past': day_date < today,
            **tmpl,
            'logged': [
                {
                    'type': a['type'],
                    'distance_km': a.get('distance_km'),
                    'duration_min': a.get('duration_min'),
                    'avg_hr': a.get('avg_hr_bpm'),
                }
                for a in day_acts
            ],
        })
    return schedule


def main():
    plan = json.loads(PLAN.read_text())
    start = parse_date(plan["start_date"])
    race = parse_date(plan["race_date"])
    today = date.today()

    # Pull all activities + daily stats
    activities = []
    daily = []
    if DB.exists():
        con = sqlite3.connect(str(DB))
        report = build_report(con)
        activities = report["activities"]
        daily = report["daily"]
    else:
        report = {"row_counts": {}, "activities": [], "daily": []}

    # Runs are distance-bearing activities — used for training volume
    runs = [a for a in activities if a.get("distance_km")]

    def week_index(d):
        """Which plan week (1-based) a date falls into; None if outside plan."""
        delta = (d - start).days
        if delta < 0:
            return None
        wk = delta // 7 + 1
        return wk if 1 <= wk <= len(plan["weeks"]) else None

    # Aggregate run actuals per plan week
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

    # Current week date range
    wk_start_cur = start + timedelta(days=(current_week - 1) * 7)
    wk_end_cur = wk_start_cur + timedelta(days=6)

    # This-week activity breakdown (all types)
    this_week_acts = [
        a for a in activities
        if a.get("date") and wk_start_cur.isoformat() <= a["date"] <= wk_end_cur.isoformat()
    ]
    this_week_run_km = sum(a.get("distance_km") or 0 for a in this_week_acts if a.get("type") == "run")
    this_week_sessions = len(this_week_acts)

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

    # Summary stats — count ALL runs (incl. pre-plan), weekly breakdown is plan-bounded
    total_km = round(sum(r["distance_km"] for r in runs), 1)
    longest = round(max((r["distance_km"] for r in runs), default=0), 1)
    days_to_race = (race - today).days
    this_week_plan = next((w for w in weeks_out if w["state"] == "current"), None)

    # This week's full schedule (Mon–Sun)
    current_phase = plan["weeks"][current_week - 1]["phase"] if current_week <= len(plan["weeks"]) else "Base"
    this_week_schedule = generate_weekly_schedule(current_phase, wk_start_cur, this_week_acts)

    # Recent activities — ALL types, last 15, newest first
    recent_activities = [
        {
            "date": a["date"],
            "type": a["type"],
            "distance_km": a.get("distance_km"),
            "duration_min": a.get("duration_min"),
            "pace": a.get("avg_pace_min_km"),
            "avg_hr": a.get("avg_hr_bpm"),
            "max_hr": a.get("max_hr_bpm"),
            "calories": a.get("calories"),
        }
        for a in activities[-15:][::-1]
    ]

    # Recovery — last 14 days of daily stats (steps + sleep only; no weight/resting-HR)
    recovery = [
        {
            "date": d["date"],
            "steps": d.get("steps"),
            "sleep_h": round(d["sleep_min"] / 60, 1) if d.get("sleep_min") else None,
        }
        for d in daily[-14:][::-1]
        if d.get("steps") or d.get("sleep_min")
    ]

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
            "num_activities": len(activities),
            "this_week_target": this_week_plan["target_total"] if this_week_plan else None,
            "this_week_run_km": round(this_week_run_km, 1),
            "this_week_sessions": this_week_sessions,
        },
        "weeks": weeks_out,
        "this_week_schedule": this_week_schedule,
        "recent_activities": recent_activities,
        "recovery": recovery,
        "events": events_out,
        "next_event": next_event,
        "data_status": report["row_counts"],
    }

    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text(json.dumps(dashboard, indent=2))
    print(f"✅ Wrote {OUT}")
    print(f"   {len(activities)} activities ({len(runs)} runs) · {total_km} km · "
          f"week {current_week}/{len(plan['weeks'])} · {days_to_race} days to race")


if __name__ == "__main__":
    main()
