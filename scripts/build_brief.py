#!/usr/bin/env python3
"""
Builds the phone-readable coaching files from data/latest.db:

    data/training.json   — full structured report (for any agent)
    data/coach_brief.md  — readable brief the Claude mobile app reads from Drive

These are uploaded to Google Drive so the Claude app on the phone can read them
WITHOUT running any code (it can't unzip/query SQLite — only read text).

Usage:
    python3 scripts/build_brief.py
"""

import json
import sys
from collections import defaultdict
from datetime import datetime, date, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from report import build_report, DB_PATH  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
COACH_MD = ROOT / "coach.md"
PLAN_JSON = ROOT / "plan.json"

TYPE_LABEL = {
    "run":     "🏃 Run",
    "walk":    "🚶 Walk",
    "workout": "💪 Workout",
    "yoga":    "🧘 Yoga",
    "biking":  "🚴 Bike",
}


def iso_week(date_str):
    d = datetime.strptime(date_str, "%Y-%m-%d")
    y, w, _ = d.isocalendar()
    return f"{y}-W{w:02d}"


def weekly_running_volume(activities):
    """Sum distance per ISO week for runs/walks with GPS distance."""
    weeks = defaultdict(float)
    for a in activities:
        if a.get("distance_km") and a.get("date"):
            weeks[iso_week(a["date"])] += a["distance_km"]
    return dict(sorted(weeks.items()))


def week_prescription(report, plan):
    """
    Returns a dict describing the current plan week: targets, what's done,
    what sessions remain, and any recovery flags.
    """
    today = date.today()
    start = datetime.strptime(plan["start_date"], "%Y-%m-%d").date()
    race_date = datetime.strptime(plan["race_date"], "%Y-%m-%d").date()

    delta = (today - start).days
    if delta < 0 or today > race_date:
        return None

    week_num = min(delta // 7 + 1, len(plan["weeks"]))
    pw = plan["weeks"][week_num - 1]

    wk_start = start + timedelta(days=(week_num - 1) * 7)
    wk_end = wk_start + timedelta(days=6)

    # Activities this week
    this_week = [
        a for a in report["activities"]
        if a.get("date") and wk_start.isoformat() <= a["date"] <= wk_end.isoformat()
    ]

    runs = [a for a in this_week if a.get("type") == "run"]
    km_done = round(sum(a.get("distance_km") or 0 for a in this_week), 1)
    long_done = round(max((a.get("distance_km") or 0 for a in this_week), default=0), 1)
    sessions_done = len(this_week)
    sessions_remaining = max(0, plan["days_per_week"] - sessions_done)
    km_remaining = round(max(0.0, pw["target_total"] - km_done), 1)

    # Days left in the week (including today)
    days_left_in_week = (wk_end - today).days + 1

    # Recovery signals from daily data
    recent_daily = sorted(report["daily"], key=lambda d: d["date"], reverse=True)[:3]
    avg_steps = None
    last_resting_hr = None
    last_sleep_h = None
    steps_list = [d.get("steps") for d in recent_daily if d.get("steps")]
    if steps_list:
        avg_steps = round(sum(steps_list) / len(steps_list))
    rhr_list = [d.get("resting_hr_bpm") for d in recent_daily if d.get("resting_hr_bpm")]
    if rhr_list:
        last_resting_hr = rhr_list[0]
    sleep_list = [d.get("sleep_min") for d in recent_daily if d.get("sleep_min")]
    if sleep_list:
        last_sleep_h = round(sleep_list[0] / 60, 1)

    # Next event
    next_event = None
    for ev in plan.get("events", []):
        ev_date = datetime.strptime(ev["date"], "%Y-%m-%d").date()
        if ev_date >= today:
            days_to = (ev_date - today).days
            next_event = {**ev, "days_to_event": days_to}
            break

    return {
        "week_num": week_num,
        "week_start": wk_start.isoformat(),
        "week_end": wk_end.isoformat(),
        "phase": pw["phase"],
        "target_total": pw["target_total"],
        "target_long": pw["target_long"],
        "km_done": km_done,
        "km_remaining": km_remaining,
        "long_done": long_done,
        "sessions_done": sessions_done,
        "sessions_remaining": sessions_remaining,
        "days_left_in_week": days_left_in_week,
        "runs_this_week": runs,
        "all_activities_this_week": this_week,
        "avg_steps_3d": avg_steps,
        "last_resting_hr": last_resting_hr,
        "last_sleep_h": last_sleep_h,
        "next_event": next_event,
    }


def build_markdown(report, plan):
    rc = report["row_counts"]
    acts = report["activities"]
    daily = report["daily"]
    lines = []

    lines.append("# 🏃 Marathon Coach — Training Brief")
    lines.append("")
    lines.append(f"_Generated {report['generated_at']} NZT — newest Health Connect export._")
    lines.append("")

    # ── This week's prescription ───────────────────────────────────────────
    rx = week_prescription(report, plan)
    if rx:
        lines.append(f"## 📅 Week {rx['week_num']} of 20 — {rx['phase']} phase")
        lines.append(f"_{rx['week_start']} → {rx['week_end']}_")
        lines.append("")
        lines.append(
            f"- **Target:** {rx['target_total']} km total · long run ≥ {rx['target_long']} km"
        )
        lines.append(
            f"- **Done so far:** {rx['km_done']} km across {rx['sessions_done']} session(s)"
        )
        if rx["sessions_remaining"] > 0:
            lines.append(
                f"- **Remaining:** ~{rx['km_remaining']} km in {rx['sessions_remaining']} more "
                f"session(s) · {rx['days_left_in_week']} days left in the week"
            )
            # Prescribe remaining sessions
            long_needed = rx["long_done"] < rx["target_long"]
            if rx["sessions_remaining"] == 1:
                if long_needed:
                    lines.append(
                        f"  → 1 session left: make it the **long run ({rx['target_long']} km easy)**"
                    )
                else:
                    lines.append(
                        f"  → 1 session left: easy **{round(rx['km_remaining'], 1)} km**"
                    )
            elif rx["sessions_remaining"] >= 2:
                lines.append(
                    f"  → Suggested split: Easy run · Quality/intervals · "
                    f"Long run {rx['target_long']} km"
                    if long_needed else
                    f"  → Suggested split: Easy · Quality · wrap up the remaining {rx['km_remaining']} km"
                )
        else:
            lines.append("- ✅ **Week complete!** Great consistency.")

        # Recovery flags
        flags = []
        if rx["last_resting_hr"]:
            flags.append(f"Resting HR: **{rx['last_resting_hr']} bpm**")
        if rx["avg_steps_3d"]:
            flags.append(f"Avg steps (3d): **{rx['avg_steps_3d']:,}**")
        if rx["last_sleep_h"]:
            flags.append(f"Last night sleep: **{rx['last_sleep_h']}h**")
        if flags:
            lines.append("- **Recovery signals:** " + " · ".join(flags))

        # Days to next race
        if rx["next_event"]:
            ne = rx["next_event"]
            lines.append(
                f"- **Next race:** {ne['name']} ({ne['distance_km']} km) — "
                f"**{ne['days_to_event']} days away**"
            )
        lines.append("")

    # ── Data health ────────────────────────────────────────────────────────
    lines.append("## Data status")
    lines.append(
        f"- Exercise sessions: **{rc['exercise_sessions']}** · "
        f"distance records: **{rc['distance_records']}** · "
        f"heart-rate records: **{rc['heart_rate_records']}** · "
        f"sleep: **{rc['sleep_sessions']}** · weight: **{rc['weight_records']}**"
    )
    if rc["distance_records"] == 0 and rc["heart_rate_records"] == 0:
        lines.append(
            "- ⚠️ No distance/HR yet — Samsung Health → Health Connect sharing may "
            "still be syncing."
        )
    lines.append("")

    # ── Weekly running volume ──────────────────────────────────────────────
    wk = weekly_running_volume(acts)
    if wk:
        lines.append("## Weekly running volume")
        for week, km in list(wk.items())[-8:]:
            bar = "▰" * min(int(km // 5), 20)
            lines.append(f"- {week}: **{round(km, 1)} km** {bar}")
        lines.append("")

    # ── All recent activities (runs + cross-training) ──────────────────────
    lines.append("## Recent activities (all types)")
    if not acts:
        lines.append("_No activities recorded yet._")
    else:
        lines.append("| Date | Type | Dist | Time | Pace | Avg HR | Max HR |")
        lines.append("|---|---|---|---|---|---|---|")
        for a in acts[-20:][::-1]:
            label = TYPE_LABEL.get(a["type"], a["type"])
            lines.append(
                f"| {a['date']} | {label} | "
                f"{str(a['distance_km']) + ' km' if a.get('distance_km') else '–'} | "
                f"{str(a['duration_min']) + ' min' if a.get('duration_min') else '–'} | "
                f"{a.get('avg_pace_min_km') or '–'} | "
                f"{a.get('avg_hr_bpm') or '–'} | "
                f"{a.get('max_hr_bpm') or '–'} |"
            )
    lines.append("")

    # ── Recovery / daily signals ───────────────────────────────────────────
    recent_daily = [
        d for d in daily
        if any(d.get(k) for k in ("sleep_min", "resting_hr_bpm", "steps", "weight_kg"))
    ]
    if recent_daily:
        lines.append("## Recovery signals (last 14 days)")
        lines.append("| Date | Steps | Resting HR | Sleep | Weight |")
        lines.append("|---|---|---|---|---|")
        for d in recent_daily[-14:][::-1]:
            sleep = f"{round(d['sleep_min']/60, 1)}h" if d.get("sleep_min") else "–"
            lines.append(
                f"| {d['date']} | "
                f"{d.get('steps', '–')} | "
                f"{d.get('resting_hr_bpm', '–')} bpm | "
                f"{sleep} | "
                f"{str(d.get('weight_kg')) + ' kg' if d.get('weight_kg') else '–'} |"
            )
        lines.append("")

    # ── Athlete profile ────────────────────────────────────────────────────
    if COACH_MD.exists():
        lines.append("## Athlete profile & coaching strategy")
        lines.append("```")
        lines.append(COACH_MD.read_text().strip())
        lines.append("```")
        lines.append("")

    lines.append("---")
    lines.append(
        "_You are this athlete's marathon coach. Use the data above — activities, "
        "recovery signals, and the week's prescription — to give specific, "
        "actionable advice. Reference actual numbers (pace, HR, km done) when you can. "
        "If a key metric is missing (sleep, resting HR), ask the athlete to enable it._"
    )
    return "\n".join(lines)


def main():
    if not DB_PATH.exists():
        print(f"❌ No database at {DB_PATH}. Run scripts/refresh.sh first.", file=sys.stderr)
        sys.exit(1)

    import sqlite3
    con = sqlite3.connect(str(DB_PATH))
    report = build_report(con)

    plan = {}
    if PLAN_JSON.exists():
        plan = json.loads(PLAN_JSON.read_text())

    DATA.mkdir(exist_ok=True)
    (DATA / "training.json").write_text(json.dumps(report, indent=2))
    (DATA / "coach_brief.md").write_text(build_markdown(report, plan))

    print(f"✅ Wrote {DATA/'training.json'}")
    print(f"✅ Wrote {DATA/'coach_brief.md'}")


if __name__ == "__main__":
    main()
