# 🏃 Marathon Training Coach

AI-powered running coach that reads Samsung Galaxy Watch data and visualises
training progress on a GitHub Pages dashboard.

**Race calendar:**
- 🏁 Sydney Running Festival 10km — Sun 13 Sep 2026
- 🏆 BBBRUN26 Half Marathon — Sun 25 Oct 2026

**Live dashboard:** https://sheldenburg.github.io/marathon-training-coach/

---

## How it works

```
Samsung Galaxy Watch 6
        │
        ▼ (syncs automatically)
Samsung Health app (on phone)
        │
        ▼ (Health Connect data sharing)
Health Connect (Android)
        │
        ▼ (built-in scheduled export — daily)
Health Connect.zip on Google Drive
        │  (full SQLite snapshot, overwrites daily)
        │
        ▼ [daily 6 AM routine — Claude desktop]
  scripts/refresh.sh
        │  unzips → data/latest.db  (local, gitignored)
        ▼
  scripts/build_brief.py
        │  generates plain-text coaching summary
        ▼
  data/coach_brief.md ──────────────────► Google Drive
        │                                 (phone can read this via Claude app)
        ▼
  scripts/build_dashboard.py
        │  reads plan.json + latest.db
        ▼
  docs/data.json ──► git push ──► GitHub Pages dashboard
```

---

## Data pipeline in detail

### 1. Watch → Google Drive

Samsung Galaxy Watch 6 syncs workout data to **Samsung Health**, which shares it
with **Health Connect** (Android's on-device health API). Health Connect's
built-in export runs on a daily schedule and writes a ZIP file called
`Health Connect.zip` to Google Drive. Each export is a **complete snapshot** —
not incremental — so only the latest ZIP is needed.

No custom app required. Everything uses Health Connect's native export feature.

### 2. Drive → local SQLite (`scripts/refresh.sh`)

The Claude desktop agent downloads `Health Connect.zip` from Google Drive,
decodes it, and runs `refresh.sh`:

```bash
# Done automatically by the daily routine, or manually:
jq -r '.content' <downloaded-file> | base64 -d > /tmp/hc.zip
./scripts/refresh.sh /tmp/hc.zip
```

This extracts the SQLite database inside the ZIP and saves it to
`data/latest.db`. The previous database is archived to `data/history/`.
Health data never leaves your machine and is excluded from git (see `.gitignore`).

### 3. SQLite → coaching brief (`scripts/build_brief.py`)

Reads `data/latest.db` and generates:
- `data/training.json` — structured training data
- `data/coach_brief.md` — human-readable markdown summary

`coach_brief.md` is uploaded back to Google Drive so you can read it in the
**Claude mobile app** and chat with the coach on your phone.

### 4. SQLite → dashboard data (`scripts/build_dashboard.py`)

Combines `plan.json` (the 20-week training plan) with actual run data from
`data/latest.db` and writes `docs/data.json`. This is the only file committed
and pushed to GitHub — no health data is ever published.

```bash
python3 scripts/build_dashboard.py
```

Output includes: weekly actual vs target volume, long run progression,
upcoming races, recent runs, and plan phase/state per week.

### 5. GitHub Pages dashboard (`docs/index.html`)

A static Chart.js page that fetches `data.json` and renders:
- Hero with race countdown and both race chips
- 4 metric cards (total km, longest run, this week, runs logged)
- Weekly volume chart — actual bars vs plan target line
- Long run progression chart
- Recent runs table
- Full 20-week plan table with phase colours and done/current/upcoming state

The page is served from the `docs/` folder on the `main` branch.

---

## Daily routine

A scheduled task runs at **6 AM NZ time** via the Claude desktop app:

1. Search Google Drive for `Health Connect.zip`
2. Download and decode it
3. Run `refresh.sh` → updates `data/latest.db`
4. Run `build_brief.py` → uploads `coach_brief.md` to Drive
5. Run `build_dashboard.py` → updates `docs/data.json`
6. `git push` → GitHub Pages rebuilds the dashboard

> The Claude desktop app must be open (or launch) for the routine to run.
> If it was closed overnight, the routine fires on next launch.

---

## Repository structure

```
.
├── plan.json                 # 20-week training plan (targets per week)
├── coach.md                  # Athlete profile & coaching strategy
├── training_plan.md          # Full narrative plan with week-by-week sessions
├── CLAUDE.md                 # Agent instructions (how to refresh data, schema notes)
│
├── scripts/
│   ├── refresh.sh            # Unzip Health Connect export → data/latest.db
│   ├── report.py             # Decode raw SQLite schema → clean JSON
│   ├── build_brief.py        # Generate coach_brief.md for phone
│   └── build_dashboard.py    # Generate docs/data.json for GitHub Pages
│
├── docs/
│   ├── index.html            # GitHub Pages dashboard (Chart.js)
│   └── data.json             # Generated dashboard data (committed)
│
└── data/                     # Gitignored — health data stays local
    ├── latest.db
    └── history/
```

---

## Privacy

- `data/latest.db` and all raw health exports are **gitignored** — they never
  leave your local machine.
- `docs/data.json` publishes **training metrics only** (distance, pace, HR for
  runs). Weight and daily resting HR are intentionally excluded.
- The GitHub Pages site is public. If you want it private, switch to a GitHub
  Pro account and enable private Pages, or self-host `docs/`.

---

## Manual usage

```bash
# Refresh from a downloaded Health Connect.zip
./scripts/refresh.sh /path/to/Health\ Connect.zip

# Quick data summary
python3 scripts/report.py --summary

# Full report (last 28 days)
python3 scripts/report.py --days 28

# Rebuild dashboard data
python3 scripts/build_dashboard.py

# Rebuild phone brief
python3 scripts/build_brief.py

# Query the database directly
sqlite3 data/latest.db "SELECT * FROM exercise_session_record_table LIMIT 5;"
```
