# Where Cellspan stores your battery data

Everything is stored **locally on the phone** in Cellspan's private SQLite
database. Nothing is sent off the device.

## Location

```
/data/data/io.github.jenil1320.cellspan/databases/cellspan.db
```

This is app-private storage — other apps and file managers cannot read it, and
on stock (unrooted) Android you cannot browse to it directly. The supported way
to get the data out for external analysis is the **Export** button in Settings,
which writes the whole dataset to a JSON file you choose. **Import** reads one
back — from this phone or an older one. Import merges: it never deletes or
overwrites what is already stored, and importing the same file twice leaves the
database exactly as the first import left it.

Implementation: [`HistoryStore.kt`](../android/app/src/main/java/io/github/jenil1320/cellspan/data/HistoryStore.kt).

## Tables and retention

| Table | What it holds | Cadence | Retention |
|---|---|---|---|
| `samples` | SoH %, cycle count, capacity (mAh), level, temperature, and the *quality* of that reading | hourly + on app open (≥10 min apart) | **kept forever** |
| `daily_vitals` | per-day min/max/mean temperature, mean voltage, mean current, latest cycle count | one row per day | **kept forever** |
| `daily_usage` | per-day discharged percentage (equivalent cycles), split into screen-on and screen-off drain and time | one row per day | **kept forever** |
| `capacity_sessions` | measured full-charge capacity from each charge session | one per charge session | **kept forever** |
| `soc_log` | high-resolution live vitals (level, temp, voltage, current) for the live charts | ~every 60 s while the app is open | trimmed to **3 days** |

Only the high-resolution `soc_log` is trimmed (it exists to draw the live "today"
charts). Everything used for long-term trends is retained indefinitely.

### Two years of data — is it kept, and how big?

Yes. At roughly 4–10 `samples` rows per day, two years is on the order of
3,000–7,000 rows; `daily_vitals` and `daily_usage` add ~730 rows each. That is a
few hundred kilobytes — trivial for SQLite. So a full two years of history is
retained on-device and can be exported at any time.

## What the export contains

Every long-term table, including the fields added in schema 9:

- `samples` — plus `quality`, which records whether each health reading was a real
  measurement or the noisy live estimate. The forecaster weights its trend line by it,
  so a restore that dropped it would move the projected replacement date.
- `daily_usage` — plus the screen-on and screen-off drain split (percentage points and
  milliseconds in each state). That accumulates over weeks and cannot be recreated on a
  new phone.
- `daily_vitals`, `capacity_sessions` and `sessions` in full.

The high-resolution `soc_log` is deliberately omitted: it is pruned to three days on the
device anyway and exists only to draw the live "today" charts.

## Schema version

The database is at **version 9**. Upgrades are applied step by step from whatever
version an install happens to be on, and each step is independent — a column is
only added when a check confirms it is genuinely absent, so an interrupted or
repeated upgrade cannot leave the database wedged.

## Export format

The Export button (Diagnose → "Export history (JSON)") produces one JSON object:

```json
{
  "app": "cellspan",
  "schema": 6,
  "exported_ms": 1737000000000,
  "samples":          [ { "ts": 1736..., "soh": 78.6, "cycles": 797, "capacity_mah": 3454, "level": 41, "temp_c": 31.6 }, ... ],
  "daily_vitals":     [ { "day": "2026-07-19", "temp_min": 27.6, "temp_max": 37.4, "temp_avg": 31.2, "volt_avg": 4.05, "curr_avg": 154, "cycles": 797 }, ... ],
  "daily_usage":      [ { "day": "2026-07-19", "discharged_pct": 24.0 }, ... ],
  "capacity_sessions":[ { "ts": 1736..., "start_level": 40, "end_level": 92, "measured_mah": 3454 }, ... ]
}
```

`ts` / `exported_ms` are Unix epoch milliseconds. This is directly loadable into
Python/pandas, a spreadsheet (after a flatten), or any JSON tool for offline
analysis months or years later.
