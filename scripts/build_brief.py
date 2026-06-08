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
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from report import build_report, DB_PATH  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
COACH_MD = ROOT / "coach.md"


def iso_week(date_str):
    d = datetime.strptime(date_str, "%Y-%m-%d")
    y, w, _ = d.isocalendar()
    return f"{y}-W{w:02d}"


def weekly_mileage(activities):
    """Sum running/walking distance per ISO week."""
    weeks = defaultdict(float)
    for a in activities:
        if a.get("distance_km") and a.get("date"):
            weeks[iso_week(a["date"])] += a["distance_km"]
    return dict(sorted(weeks.items()))


def build_markdown(report):
    rc = report["row_counts"]
    acts = report["activities"]
    daily = report["daily"]
    lines = []

    lines.append("# 🏃 Marathon Coach — Training Brief")
    lines.append("")
    lines.append(f"_Generated {report['generated_at']} — newest Health Connect export._")
    lines.append("")

    # Data health
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
            "still be syncing. Runs won't appear until that flows."
        )
    lines.append("")

    # Weekly mileage
    wk = weekly_mileage(acts)
    if wk:
        lines.append("## Weekly running volume")
        for week, km in list(wk.items())[-8:]:
            bar = "▰" * min(int(km // 5), 20)
            lines.append(f"- {week}: **{round(km, 1)} km** {bar}")
        lines.append("")

    # Recent activities
    lines.append("## Recent activities")
    if not acts:
        lines.append("_No activities recorded yet._")
    else:
        lines.append("| Date | Type | Dist | Time | Pace | Avg HR |")
        lines.append("|---|---|---|---|---|---|")
        for a in acts[-15:][::-1]:
            lines.append(
                f"| {a['date']} | {a['type']} | "
                f"{a['distance_km'] or '–'} km | "
                f"{a['duration_min'] or '–'} min | "
                f"{a['avg_pace_min_km'] or '–'} | "
                f"{a['avg_hr_bpm'] or '–'} |"
            )
    lines.append("")

    # Recovery snapshot
    recent_daily = [d for d in daily if any(k in d for k in ("sleep_min", "resting_hr_bpm", "steps"))]
    if recent_daily:
        lines.append("## Recent days (recovery signals)")
        lines.append("| Date | Steps | Resting HR | Sleep | Weight |")
        lines.append("|---|---|---|---|---|")
        for d in recent_daily[-10:][::-1]:
            sleep = f"{round(d['sleep_min']/60, 1)}h" if d.get("sleep_min") else "–"
            lines.append(
                f"| {d['date']} | {d.get('steps', '–')} | "
                f"{d.get('resting_hr_bpm', '–')} | {sleep} | "
                f"{d.get('weight_kg', '–')} |"
            )
        lines.append("")

    # Athlete context
    if COACH_MD.exists():
        lines.append("## Athlete profile & goals")
        lines.append("```")
        lines.append(COACH_MD.read_text().strip())
        lines.append("```")
        lines.append("")

    lines.append("---")
    lines.append(
        "_You are this athlete's marathon coach. Use the data above plus the profile "
        "to give specific, periodized advice. If asked about a day with no data, say so._"
    )
    return "\n".join(lines)


def main():
    if not DB_PATH.exists():
        print(f"❌ No database at {DB_PATH}. Run scripts/refresh.sh first.", file=sys.stderr)
        sys.exit(1)

    import sqlite3
    con = sqlite3.connect(str(DB_PATH))
    report = build_report(con)

    DATA.mkdir(exist_ok=True)
    (DATA / "training.json").write_text(json.dumps(report, indent=2))
    (DATA / "coach_brief.md").write_text(build_markdown(report))

    print(f"✅ Wrote {DATA/'training.json'}")
    print(f"✅ Wrote {DATA/'coach_brief.md'}")


if __name__ == "__main__":
    main()
