# Phone Battery Analytics

Track your phone's battery **State of Health (SoH)** and degradation over time,
reading real data straight from the device over ADB — no root, no third-party
app, standard-library Python only.

Built and tested against a **Google Pixel 7**.

## What it measures

**State of Health = current full-charge capacity ÷ factory design capacity.**

Both numbers come from the phone's fuel gauge via
`/sys/class/power_supply/battery`:

| Metric | sysfs node | Meaning |
|---|---|---|
| Full-charge capacity | `charge_full` | What the battery can hold **today** (µAh) |
| Design capacity | `charge_full_design` | Factory rating when new (µAh) |
| Charge cycles | `cycle_count` | Equivalent full charge/discharge cycles |
| Battery level | `capacity` | Current % |
| Chemistry | `technology` | e.g. Li-ion |
| Manufacturing date | `manufacturing_date` | Battery build date (Unix epoch) |
| Internal resistance | `resistance` | Rises as the cell ages (µΩ) |
| Battery serial | `serial_number` | Cell serial (encodes supplier + date code) |
| Voltage / current / temp | `voltage_now` / `current_now` / `temp` | Live electrical state |
| Health flag | `health` | Fuel-gauge health status |

### What the dashboard shows

- **Snapshot** — SoH, capacity, cycles, level, temperature, voltage.
- **Battery spec** — manufacturer, model, chemistry, design & current capacity,
  manufacture date, age, internal resistance, serial.
- **Since manufacture** — lifetime averages: age, average cycles/month, average
  capacity fade per year, and a lifetime-average projection of when the battery
  crosses 80% capacity / 800 cycles.
- **Outlook** — the same thresholds re-projected from your *logged* trend (more
  accurate than the lifetime average once enough samples exist).
- **Yearly & monthly analytics** — per-year cycles added and capacity change,
  plus the month with the most cycles and the fastest-aging month.
- **Trend charts** — SoH, cycles, and capacity over time.

### Two kinds of history — read this

The tool gives you **two layers** of historical insight:

1. **Retrospective, available immediately.** This Pixel 7 (Android 16) exposes a
   real `manufacturing_date` (2022-11-08). Combined with today's cycle count and
   capacity, that yields whole-life *averages* — how many cycles per month and
   how many capacity points per year the battery has lost across its entire life.

2. **Per-calendar-period, builds forward.** The phone stores only cumulative
   totals, **not** a month-by-month log. So "which specific month had the most
   cycles" and true per-year trend lines cannot be reconstructed for the past —
   they populate as you keep sampling. The yearly/monthly tables start sparse and
   fill in over weeks; the lifetime averages above cover the gap in the meantime.

### Your baseline (first reading, 2026-07-18)

- Manufactured **2022-11-08** — battery age ~3 yr 8 mo.
- State of Health: **~83%** (3,642 mAh of 4,386 mAh design); ~17 points lost,
  about **4.6 points/year** on average.
- Charge cycles: **796**, averaging **~18 cycles/month**.
- Google's Pixel 7 replacement guidance: **below 80% capacity or 800 cycles**.
  At the lifetime-average rate, this battery reaches 800 cycles around late
  **July 2026** and 80% capacity around **March 2027** — so it's essentially at
  the cycle threshold now.

## Requirements

- **Python 3.10+** (uses the `py` launcher on Windows).
- **ADB** (Android platform-tools). Auto-detected from `PATH`, the `ADB`
  environment variable, or `%USERPROFILE%\Android\Sdk\platform-tools\adb.exe`.
- Phone connected over USB with **USB debugging** on
  (Settings → System → Developer options → USB debugging), and the
  "Allow USB debugging?" prompt accepted.

## Usage

```bash
py battery.py show               # print a live reading (does not store anything)
py battery.py collect            # read the phone and store one sample
py battery.py collect --report   # store a sample AND rebuild dashboard.html
py battery.py report --open      # rebuild the dashboard and open it in a browser
```

Or just double-click:

- **`log-sample.bat`** — record a sample and refresh the dashboard.
- **`open-dashboard.bat`** — rebuild and open `dashboard.html`.

Data lives in `battery_history.db` (SQLite). The dashboard is written to
`dashboard.html` — a single self-contained file that works offline.

## Automate collection (recommended)

Cycle count and full-charge capacity move slowly, so a few samples per day is
plenty. Use **Windows Task Scheduler**:

1. Open **Task Scheduler** → **Create Basic Task**.
2. Name it e.g. "Battery sample". Trigger: **Daily**, then after creating,
   edit the trigger to **Repeat every 6 hours** (optional).
3. Action: **Start a program**.
   - Program/script: `log-sample.bat`
   - Start in: `C:\Users\JenilAgrawal\Documents\phoneBatteryAnalytics`
4. Finish. Samples are recorded whenever the phone is plugged in; runs where the
   phone isn't connected simply log a warning and skip.

The more consistently you sample (ideally with the phone plugged in so the
gauge reads a settled value), the cleaner the degradation curve and the more
accurate the "reaches 80% / 800 cycles" projection.

## Notes & accuracy

- `charge_full` recalibrates over time and reads most reliably at a high, rested
  charge level. Day-to-day values jitter a little; the trend across many samples
  is what matters, not any single point.
- If `charge_full` / `charge_full_design` come back empty, the fuel-gauge node
  may have a different name on your device. Check with:
  `adb shell ls /sys/class/power_supply/` and set `POWER_SUPPLY` near the top of
  `battery.py` (e.g. `google_battery`, `maxfg`, or `bms`).

## Files

```
battery.py            Collector + dashboard generator (all logic)
battery_history.db    SQLite store of every sample (created on first run)
dashboard.html        Generated offline dashboard
log-sample.bat        Record a sample + refresh dashboard
open-dashboard.bat    Rebuild + open the dashboard
```
