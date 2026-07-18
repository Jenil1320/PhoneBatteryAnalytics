#!/usr/bin/env python3
"""
Phone Battery Analytics — Pixel / Android battery State-of-Health tracker.

Reads real battery data from an Android phone over ADB (no root required),
computes State of Health (SoH = current full-charge capacity / factory design
capacity), stores every reading in a local SQLite database, and renders a
self-contained offline HTML dashboard showing degradation over time.

Standard library only — no pip installs.

Usage:
    python battery.py collect [--report] [--open] [--serial S]
    python battery.py report  [--open]
    python battery.py show    [--serial S]

Environment:
    ADB   Path to the adb executable (otherwise auto-detected).
"""
from __future__ import annotations

import argparse
import html
import os
import shutil
import sqlite3
import subprocess
import sys
import webbrowser
from datetime import datetime, timezone
from pathlib import Path

# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #

HERE = Path(__file__).resolve().parent
DB_PATH = HERE / "battery_history.db"
DASHBOARD_PATH = HERE / "dashboard.html"

# Google's Pixel 7 replacement guidance: below 80% capacity OR 800 cycles.
REPLACE_SOH_PCT = 80.0
REPLACE_CYCLES = 800

# Battery power-supply node in sysfs. Overridable if a device uses another name
# (e.g. "google_battery", "maxfg", "bms"). "battery" works on the Pixel 7.
POWER_SUPPLY = "/sys/class/power_supply/battery"

# sysfs nodes to read. Values are integers in the units noted.
INT_NODES = [
    "charge_full",          # current full-charge capacity (uAh)
    "charge_full_design",   # factory-rated design capacity (uAh)
    "cycle_count",          # charge cycles
    "charge_counter",       # present charge remaining (uAh)
    "capacity",             # battery level (%)
    "voltage_now",          # (uV)
    "current_now",          # (uA), sign is device-specific
    "temp",                 # (deci-degrees C, e.g. 282 = 28.2 C)
    "resistance",           # internal resistance (uOhm)
    "manufacturing_date",   # battery manufacture date (unix epoch seconds)
    "first_usage_date",     # first power-on date (unix epoch seconds; 0 if unset)
]
STR_NODES = ["health", "status", "technology", "serial_number",
             "cycle_counts", "swelling_data"]

# Epoch nodes where a literal 0 means "not recorded" rather than 1970.
EPOCH_NODES = ["manufacturing_date", "first_usage_date"]


# --------------------------------------------------------------------------- #
# ADB helpers
# --------------------------------------------------------------------------- #

def find_adb() -> str:
    """Locate the adb executable, preferring $ADB, then PATH, then the SDK."""
    candidates = []
    if os.environ.get("ADB"):
        candidates.append(os.environ["ADB"])
    on_path = shutil.which("adb")
    if on_path:
        candidates.append(on_path)
    # Common Windows SDK locations.
    local = os.environ.get("LOCALAPPDATA", "")
    home = str(Path.home())
    candidates += [
        str(Path(home) / "Android" / "Sdk" / "platform-tools" / "adb.exe"),
        str(Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe"),
    ]
    for c in candidates:
        if c and Path(c).exists():
            return c
    # Fall back to bare "adb" and let the OS resolve / error out clearly.
    return "adb"


ADB = find_adb()


def adb(*args: str, serial: str | None = None, timeout: int = 30) -> str:
    """Run an adb command and return stdout, raising on failure."""
    cmd = [ADB]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    proc = subprocess.run(
        cmd, capture_output=True, text=True, timeout=timeout
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"adb {' '.join(args)} failed (exit {proc.returncode}): "
            f"{proc.stderr.strip() or proc.stdout.strip()}"
        )
    return proc.stdout


def list_devices() -> list[tuple[str, str]]:
    """Return [(serial, model), ...] for devices in 'device' state."""
    out = adb("devices", "-l")
    devices = []
    for line in out.splitlines()[1:]:
        line = line.strip()
        if not line or "device" not in line.split():
            # Only accept authorized devices (state exactly "device").
            parts = line.split()
            if len(parts) < 2 or parts[1] != "device":
                continue
        parts = line.split()
        serial = parts[0]
        model = ""
        for p in parts:
            if p.startswith("model:"):
                model = p.split(":", 1)[1]
        devices.append((serial, model))
    return devices


def pick_serial(requested: str | None) -> str:
    """Choose which device to talk to; error clearly if ambiguous/none."""
    devices = list_devices()
    if not devices:
        raise SystemExit(
            "No authorized device found. Connect the phone over USB, enable "
            "USB debugging, and accept the 'Allow USB debugging?' prompt.\n"
            f"(adb: {ADB})"
        )
    if requested:
        for s, _ in devices:
            if s == requested:
                return s
        raise SystemExit(
            f"Requested serial '{requested}' not connected. "
            f"Available: {', '.join(s for s, _ in devices)}"
        )
    if len(devices) == 1:
        return devices[0][0]
    listing = ", ".join(f"{s} ({m})" for s, m in devices)
    raise SystemExit(
        f"Multiple devices connected: {listing}\n"
        "Re-run with --serial <serial> to choose one."
    )


def read_battery(serial: str) -> dict:
    """Read all battery nodes + model in a single adb shell round-trip."""
    nodes = INT_NODES + STR_NODES
    # Build a tiny shell script that prints key=value for each node.
    # Flatten newlines so multi-line nodes (swelling_data) stay on one line.
    script_lines = [
        f'v=$(cat {POWER_SUPPLY}/{n} 2>/dev/null | tr "\\n" " "); echo "{n}=$v"'
        for n in nodes
    ]
    script_lines.append('echo "model=$(getprop ro.product.model)"')
    script_lines.append('echo "manufacturer=$(getprop ro.product.manufacturer)"')
    script = "; ".join(script_lines)
    out = adb("shell", script, serial=serial)

    raw: dict[str, str] = {}
    for line in out.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            raw[k.strip()] = v.strip()

    data: dict[str, object] = {}
    for n in INT_NODES:
        val = raw.get(n, "")
        try:
            data[n] = int(val) if val != "" else None
        except ValueError:
            data[n] = None
    for n in STR_NODES:
        data[n] = raw.get(n) or None
    for n in EPOCH_NODES:  # 0 = not recorded
        if data.get(n) in (0, None):
            data[n] = None
    data["model"] = raw.get("model") or None
    data["manufacturer"] = raw.get("manufacturer") or None

    cf = data.get("charge_full")
    cfd = data.get("charge_full_design")
    if cf and cfd and cfd > 0:
        data["soh_pct"] = round(cf / cfd * 100.0, 2)
    else:
        data["soh_pct"] = None
    return data


# --------------------------------------------------------------------------- #
# Storage
# --------------------------------------------------------------------------- #

def db_connect() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH)
    conn.execute(
        """
        CREATE TABLE IF NOT EXISTS samples (
            id                     INTEGER PRIMARY KEY AUTOINCREMENT,
            ts_local               TEXT NOT NULL,
            ts_utc                 TEXT NOT NULL,
            serial                 TEXT,
            model                  TEXT,
            manufacturer           TEXT,
            charge_full_uah        INTEGER,
            charge_full_design_uah INTEGER,
            soh_pct                REAL,
            cycle_count            INTEGER,
            charge_counter_uah     INTEGER,
            capacity_pct           INTEGER,
            voltage_uv             INTEGER,
            current_ua             INTEGER,
            temp_decic             INTEGER,
            health                 TEXT,
            status                 TEXT,
            technology             TEXT,
            resistance_uohm        INTEGER,
            manufacturing_epoch    INTEGER,
            first_usage_epoch      INTEGER,
            battery_serial         TEXT,
            cycle_counts_raw       TEXT,
            swelling_raw           TEXT
        )
        """
    )
    # Migrate older databases that predate newer columns.
    existing = {row[1] for row in conn.execute("PRAGMA table_info(samples)")}
    for col, decl in [
        ("resistance_uohm", "INTEGER"),
        ("manufacturing_epoch", "INTEGER"),
        ("first_usage_epoch", "INTEGER"),
        ("battery_serial", "TEXT"),
        ("cycle_counts_raw", "TEXT"),
        ("swelling_raw", "TEXT"),
    ]:
        if col not in existing:
            conn.execute(f"ALTER TABLE samples ADD COLUMN {col} {decl}")
    conn.commit()
    return conn


def store_sample(data: dict, serial: str) -> int:
    now = datetime.now()
    now_utc = datetime.now(timezone.utc)
    conn = db_connect()
    cur = conn.execute(
        """
        INSERT INTO samples (
            ts_local, ts_utc, serial, model, manufacturer,
            charge_full_uah, charge_full_design_uah, soh_pct, cycle_count,
            charge_counter_uah, capacity_pct, voltage_uv, current_ua,
            temp_decic, health, status, technology,
            resistance_uohm, manufacturing_epoch, first_usage_epoch,
            battery_serial, cycle_counts_raw, swelling_raw
        ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        (
            now.isoformat(timespec="seconds"),
            now_utc.isoformat(timespec="seconds"),
            serial,
            data.get("model"),
            data.get("manufacturer"),
            data.get("charge_full"),
            data.get("charge_full_design"),
            data.get("soh_pct"),
            data.get("cycle_count"),
            data.get("charge_counter"),
            data.get("capacity"),
            data.get("voltage_now"),
            data.get("current_now"),
            data.get("temp"),
            data.get("health"),
            data.get("status"),
            data.get("technology"),
            data.get("resistance"),
            data.get("manufacturing_date"),
            data.get("first_usage_date"),
            data.get("serial_number"),
            data.get("cycle_counts"),
            data.get("swelling_data"),
        ),
    )
    conn.commit()
    rowid = cur.lastrowid
    conn.close()
    return rowid


def load_samples() -> list[sqlite3.Row]:
    if not DB_PATH.exists():
        return []
    conn = db_connect()
    conn.row_factory = sqlite3.Row
    rows = conn.execute("SELECT * FROM samples ORDER BY ts_utc ASC").fetchall()
    conn.close()
    return rows


# --------------------------------------------------------------------------- #
# Formatting helpers
# --------------------------------------------------------------------------- #

def fmt_mah(uah) -> str:
    return f"{uah / 1000:.0f} mAh" if uah is not None else "—"


def fmt_soh(pct) -> str:
    return f"{pct:.1f}%" if pct is not None else "—"


def fmt_age(days: float) -> str:
    years = int(days // 365.25)
    months = int((days - years * 365.25) // 30.437)
    if years and months:
        return f"{years} yr {months} mo"
    if years:
        return f"{years} yr"
    return f"{months} mo"


def soh_status(pct):
    if pct is None:
        return "unknown", "var(--muted)"
    if pct >= 85:
        return "healthy", "var(--good)"
    if pct >= REPLACE_SOH_PCT:
        return "aging", "var(--warn)"
    return "replace soon", "var(--bad)"


def cycle_status(cycles):
    if cycles is None:
        return "unknown", "var(--muted)"
    if cycles < 600:
        return "healthy", "var(--good)"
    if cycles < REPLACE_CYCLES:
        return "aging", "var(--warn)"
    return "at threshold", "var(--bad)"


# --------------------------------------------------------------------------- #
# Linear-regression outlook
# --------------------------------------------------------------------------- #

def _linreg(xs: list[float], ys: list[float]):
    """Return (slope, intercept) or None if it can't be fit."""
    n = len(xs)
    if n < 2:
        return None
    mx = sum(xs) / n
    my = sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    if sxx == 0:
        return None
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    slope = sxy / sxx
    return slope, my - slope * mx


def project_threshold(rows, value_key: str, target: float, going_down: bool):
    """
    Fit value vs. days-since-first-sample and estimate the date it reaches
    `target`. Returns (eta_datetime, per_day_rate) or None.
    """
    pts = []
    for r in rows:
        v = r[value_key]
        if v is None:
            continue
        t = datetime.fromisoformat(r["ts_utc"])
        pts.append((t, float(v)))
    if len(pts) < 2:
        return None
    t0 = pts[0][0]
    xs = [(t - t0).total_seconds() / 86400.0 for t, _ in pts]
    ys = [v for _, v in pts]
    fit = _linreg(xs, ys)
    if not fit:
        return None
    slope, intercept = fit  # per day
    # Direction sanity: decreasing metric should have negative slope, etc.
    if going_down and slope >= 0:
        return (None, slope)
    if not going_down and slope <= 0:
        return (None, slope)
    day_at_target = (target - intercept) / slope
    if day_at_target < xs[-1]:
        # Already past the target at the latest sample.
        return ("reached", slope)
    from datetime import timedelta
    eta = t0 + timedelta(days=day_at_target)
    return (eta, slope)


# --------------------------------------------------------------------------- #
# Lifetime + period analytics
# --------------------------------------------------------------------------- #

def epoch_to_date(epoch):
    if not epoch:
        return None
    return datetime.fromtimestamp(int(epoch), tz=timezone.utc)


def lifetime_stats(latest) -> dict | None:
    """
    Retrospective stats over the whole battery life, derivable from a single
    reading: (manufacture date, current cycle count, current SoH).
    Returns None if the manufacture date isn't available.
    """
    mfg = epoch_to_date(latest["manufacturing_epoch"])
    if mfg is None:
        return None
    now = datetime.now(timezone.utc)
    age_days = (now - mfg).total_seconds() / 86400.0
    if age_days <= 0:
        return None
    age_years = age_days / 365.25
    age_months = age_days / 30.437

    soh = latest["soh_pct"]
    cycles = latest["cycle_count"]
    out = {
        "mfg_date": mfg,
        "age_days": age_days,
        "age_years": age_years,
        "age_months": age_months,
        "cycles": cycles,
        "soh": soh,
        "cycles_per_month": (cycles / age_months) if cycles else None,
        "cycles_per_year": (cycles / age_years) if cycles else None,
        "fade_total_pts": (100.0 - soh) if soh is not None else None,
        "fade_per_year": ((100.0 - soh) / age_years) if soh is not None else None,
        "fade_per_month": ((100.0 - soh) / age_months) if soh is not None else None,
    }

    # Lifetime-average projections (coarse; refined later by logged trend).
    from datetime import timedelta
    if out["fade_per_month"] and soh is not None and soh > REPLACE_SOH_PCT:
        m = (soh - REPLACE_SOH_PCT) / out["fade_per_month"]
        out["eta_soh"] = now + timedelta(days=m * 30.437)
    else:
        out["eta_soh"] = "reached" if (soh is not None and soh <= REPLACE_SOH_PCT) else None
    if out["cycles_per_month"] and cycles is not None and cycles < REPLACE_CYCLES:
        m = (REPLACE_CYCLES - cycles) / out["cycles_per_month"]
        out["eta_cycles"] = now + timedelta(days=m * 30.437)
    else:
        out["eta_cycles"] = "reached" if (cycles is not None and cycles >= REPLACE_CYCLES) else None
    return out


def period_deltas(rows, key: str, granularity: str) -> dict:
    """
    Sum the change in `key` between consecutive logged samples, bucketed by
    calendar period. granularity 'year' -> 'YYYY', 'month' -> 'YYYY-MM'.
    Change is attributed to the period of the later sample. Returns
    {period: summed_delta}. Positive delta = the value increased.
    """
    fmt = "%Y" if granularity == "year" else "%Y-%m"
    buckets: dict[str, float] = {}
    prev = None
    for r in rows:
        v = r[key]
        if v is None:
            prev = None
            continue
        t = datetime.fromisoformat(r["ts_local"])
        if prev is not None:
            pv, _ = prev
            buckets[t.strftime(fmt)] = buckets.get(t.strftime(fmt), 0.0) + (v - pv)
        prev = (v, t)
    return buckets


# --------------------------------------------------------------------------- #
# Lifetime usage-distribution parsers (retrospective, condition-bucketed)
# --------------------------------------------------------------------------- #

def parse_cycle_counts(raw):
    """`cycle_counts` = accumulated cycle activity across 10 charge-level bands
    (low -> high state of charge). Returns a list of ints or None."""
    if not raw:
        return None
    vals = []
    for tok in raw.replace(",", " ").split():
        try:
            vals.append(int(tok))
        except ValueError:
            pass
    return vals or None


def parse_swelling(raw):
    """`swelling_data` = cumulative seconds spent charging / discharging at each
    (temperature, state-of-charge) bucket. Returns list of dicts or None."""
    if not raw:
        return None
    import re
    rows = []
    for m in re.finditer(r"(\d+)/(\d+)\s+(\d+)\s+(\d+)", raw):
        t, soc, chg, dis = (int(g) for g in m.groups())
        rows.append({"temp": t, "soc": soc, "charge_s": chg, "discharge_s": dis})
    return rows or None


def render_cycle_distribution(vals) -> str:
    total = sum(vals) or 1
    peak = max(vals) or 1
    n = len(vals)
    bars = []
    for i, v in enumerate(vals):
        lo, hi = round(i * 100 / n), round((i + 1) * 100 / n)
        share = v / total * 100
        width = v / peak * 100
        bars.append(
            f'<div class="barrow"><span class="barlabel mono">{lo}–{hi}%</span>'
            f'<span class="bartrack"><span class="barfill" '
            f'style="width:{width:.1f}%"></span></span>'
            f'<span class="barval mono">{share:.0f}%</span></div>')
    return (
        '<div class="chart"><h3>Where your cycles happen</h3>'
        '<div class="chartsub">Share of lifetime cycling by charge level '
        '(low → high)</div>'
        f'{"".join(bars)}'
        '<div class="note">Bands are the fuel gauge\'s ten charge-level buckets; '
        'boundaries are approximate. Cycling concentrated in the middle of the '
        'range is gentler on the cell than heavy time at the extremes.</div></div>')


def render_stress_profile(swell) -> str:
    def hrs(s):
        return f"{s / 3600:.0f} h" if s else "0"
    trows = []
    for r in sorted(swell, key=lambda x: (-x["temp"], -x["soc"])):
        total_h = (r["charge_s"] + r["discharge_s"]) / 3600
        hot = r["temp"] >= 35 and r["soc"] >= 80
        tag = ('<span class="tag warn">high stress</span>'
               if hot and total_h > 1 else "")
        trows.append(
            f'<tr><td>{r["temp"]}°C</td><td>{r["soc"]}%</td>'
            f'<td>{hrs(r["charge_s"])}</td><td>{hrs(r["discharge_s"])}</td>'
            f'<td>{tag}</td></tr>')
    return (
        '<div class="chart"><h3>Temperature &amp; charge stress</h3>'
        '<div class="chartsub">Lifetime hours spent at each temperature / '
        'charge-level combination</div>'
        '<table class="tbl"><thead><tr><th>Temp</th><th>Charge</th>'
        '<th>Charging</th><th>Discharging</th><th></th></tr></thead>'
        f'<tbody>{"".join(trows)}</tbody></table>'
        '<div class="note">Heat combined with a high charge level is the biggest '
        'driver of lithium-ion aging. Long hours in the high-temp, high-charge '
        'rows accelerate capacity fade.</div></div>')


# --------------------------------------------------------------------------- #
# SVG charting (server-side, no JS, no external assets)
# --------------------------------------------------------------------------- #

def _nice_ticks(lo: float, hi: float, n: int = 5) -> list[float]:
    if hi <= lo:
        hi = lo + 1
    step = (hi - lo) / (n - 1)
    return [lo + step * i for i in range(n)]


def render_chart(rows, value_key: str, title: str, unit: str,
                 y_fmt, threshold: float | None = None,
                 value_scale: float = 1.0) -> str:
    """Render a line chart of value_key over time as inline SVG."""
    pts = []
    for r in rows:
        v = r[value_key]
        if v is None:
            continue
        t = datetime.fromisoformat(r["ts_local"])
        pts.append((t, float(v) * value_scale))

    if not pts:
        return (f'<div class="chart"><h3>{html.escape(title)}</h3>'
                '<p class="empty">No data yet.</p></div>')

    W, H = 760, 300
    ml, mr, mt, mb = 64, 20, 24, 46
    pw, ph = W - ml - mr, H - mt - mb

    ys = [v for _, v in pts]
    ymin, ymax = min(ys), max(ys)
    if threshold is not None:
        ymin = min(ymin, threshold)
        ymax = max(ymax, threshold)
    if ymin == ymax:
        ymin -= 1
        ymax += 1
    pad = (ymax - ymin) * 0.08
    ymin -= pad
    ymax += pad

    xs_t = [t.timestamp() for t, _ in pts]
    xmin, xmax = min(xs_t), max(xs_t)
    if xmin == xmax:
        xmin -= 1
        xmax += 1

    def px(t):
        return ml + (t.timestamp() - xmin) / (xmax - xmin) * pw

    def py(v):
        return mt + (ymax - v) / (ymax - ymin) * ph

    parts = [f'<svg viewBox="0 0 {W} {H}" class="svgchart" '
             f'preserveAspectRatio="xMidYMid meet" role="img" '
             f'aria-label="{html.escape(title)}">']

    # Y gridlines + labels.
    for tick in _nice_ticks(ymin + pad, ymax - pad, 5):
        y = py(tick)
        parts.append(
            f'<line x1="{ml}" y1="{y:.1f}" x2="{ml + pw}" y2="{y:.1f}" '
            f'class="grid"/>'
        )
        parts.append(
            f'<text x="{ml - 8}" y="{y + 4:.1f}" class="ylabel">'
            f'{y_fmt(tick)}</text>'
        )

    # Threshold line.
    if threshold is not None:
        ty = py(threshold)
        parts.append(
            f'<line x1="{ml}" y1="{ty:.1f}" x2="{ml + pw}" y2="{ty:.1f}" '
            f'class="threshold"/>'
        )
        parts.append(
            f'<text x="{ml + pw}" y="{ty - 6:.1f}" class="thresholdlabel" '
            f'text-anchor="end">{y_fmt(threshold)} threshold</text>'
        )

    # X labels (first, middle, last).
    label_idx = sorted({0, len(pts) // 2, len(pts) - 1})
    for i in label_idx:
        t = pts[i][0]
        x = px(t)
        anchor = "middle"
        if i == 0:
            anchor = "start"
        elif i == len(pts) - 1:
            anchor = "end"
        parts.append(
            f'<text x="{x:.1f}" y="{H - 14}" class="xlabel" '
            f'text-anchor="{anchor}">{t.strftime("%m-%d %H:%M")}</text>'
        )

    # Data polyline.
    if len(pts) > 1:
        poly = " ".join(f"{px(t):.1f},{py(v):.1f}" for t, v in pts)
        parts.append(f'<polyline points="{poly}" class="dataline"/>')

    # Points with native tooltips.
    for t, v in pts:
        cx, cy = px(t), py(v)
        tip = f'{t.strftime("%Y-%m-%d %H:%M")}  ·  {y_fmt(v)}'
        parts.append(
            f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="3.5" class="dot">'
            f'<title>{html.escape(tip)}</title></circle>'
        )

    parts.append('</svg>')
    subtitle = f'{len(pts)} sample{"s" if len(pts) != 1 else ""} · {unit}'
    return (f'<div class="chart"><h3>{html.escape(title)}</h3>'
            f'<div class="chartsub">{html.escape(subtitle)}</div>'
            f'{"".join(parts)}</div>')


# --------------------------------------------------------------------------- #
# Dashboard
# --------------------------------------------------------------------------- #

def build_dashboard(rows) -> str:
    generated = datetime.now().strftime("%Y-%m-%d %H:%M")
    if not rows:
        body = ('<div class="empty-state"><h1>No data yet</h1>'
                '<p>Run <code>python battery.py collect</code> with the phone '
                'connected to record your first sample.</p></div>')
        return HTML_SHELL.format(title="Battery Analytics",
                                 generated=generated, body=body)

    latest = rows[-1]
    first = rows[0]
    model = latest["model"] or latest["serial"] or "device"
    soh = latest["soh_pct"]
    cycles = latest["cycle_count"]
    s_label, s_color = soh_status(soh)
    c_label, c_color = cycle_status(cycles)

    temp_c = latest["temp_decic"] / 10 if latest["temp_decic"] is not None else None
    volt_v = latest["voltage_uv"] / 1e6 if latest["voltage_uv"] is not None else None

    def card(label, value, sub="", color=None):
        style = f' style="color:{color}"' if color else ""
        subhtml = f'<div class="sub">{html.escape(sub)}</div>' if sub else ""
        return (f'<div class="card"><div class="label">{html.escape(label)}'
                f'</div><div class="value"{style}>{value}</div>{subhtml}</div>')

    cards = "".join([
        card("State of Health", fmt_soh(soh), s_label, s_color),
        card("Full-charge capacity", fmt_mah(latest["charge_full_uah"]),
             f'design {fmt_mah(latest["charge_full_design_uah"])}'),
        card("Charge cycles", str(cycles) if cycles is not None else "—",
             f'{c_label} · limit {REPLACE_CYCLES}', c_color),
        card("Battery level",
             f'{latest["capacity_pct"]}%' if latest["capacity_pct"] is not None else "—",
             (latest["status"] or "").lower()),
        card("Temperature", f'{temp_c:.1f}°C' if temp_c is not None else "—",
             latest["health"] or ""),
        card("Voltage", f'{volt_v:.2f} V' if volt_v is not None else "—",
             latest["technology"] or ""),
    ])

    # ---- Battery spec panel --------------------------------------------- #
    def spec_row(k, v):
        return (f'<div class="specrow"><span class="k">{html.escape(k)}</span>'
                f'<span class="v">{v}</span></div>')

    life = lifetime_stats(latest)
    mfg_date = epoch_to_date(latest["manufacturing_epoch"])
    first_use = epoch_to_date(latest["first_usage_epoch"])
    res = latest["resistance_uohm"]
    spec_items = [
        spec_row("Manufacturer (phone)", html.escape(latest["manufacturer"] or "—")),
        spec_row("Model", html.escape(latest["model"] or "—")),
        spec_row("Chemistry", html.escape(latest["technology"] or "—")),
        spec_row("Design capacity", fmt_mah(latest["charge_full_design_uah"])),
        spec_row("Current full charge", fmt_mah(latest["charge_full_uah"])),
        spec_row("Manufactured",
                 mfg_date.strftime("%Y-%m-%d") if mfg_date else "not exposed"),
        spec_row("Age",
                 fmt_age(life["age_days"]) if life else "—"),
        spec_row("First power-on",
                 first_use.strftime("%Y-%m-%d") if first_use else "not recorded"),
        spec_row("Internal resistance",
                 f"{res / 1000:.0f} mΩ" if res else "—"),
        spec_row("Battery serial",
                 f'<code>{html.escape(latest["battery_serial"] or "—")}</code>'),
    ]
    spec = ('<section class="panel"><h2>Battery spec</h2>'
            f'<div class="speclist">{"".join(spec_items)}</div>'
            '<div class="note">Cell manufacturer is not exposed by name on this '
            'device; the serial encodes the supplier and a 2022-11 date code.</div>'
            '</section>')

    # ---- Lifetime (since manufacture) ----------------------------------- #
    if life:
        def eta_txt(eta):
            if eta == "reached":
                return "already reached"
            if eta is None:
                return "n/a"
            return eta.strftime("%Y-%m-%d")

        life_cards = "".join([
            card("Battery age", fmt_age(life["age_days"]),
                 f'since {life["mfg_date"].strftime("%Y-%m-%d")}'),
            card("Avg cycles / month",
                 f'{life["cycles_per_month"]:.1f}' if life["cycles_per_month"] else "—",
                 f'{life["cycles"]} cycles total'),
            card("Avg fade / year",
                 f'{life["fade_per_year"]:.1f} pts' if life["fade_per_year"] else "—",
                 f'{life["fade_total_pts"]:.0f} pts lost so far'
                 if life["fade_total_pts"] is not None else ""),
        ])
        life_note = (
            f'At the lifetime-average rate, this battery reaches '
            f'<strong>{REPLACE_SOH_PCT:.0f}% capacity</strong> around '
            f'<strong>{eta_txt(life["eta_soh"])}</strong> and '
            f'<strong>{REPLACE_CYCLES} cycles</strong> around '
            f'<strong>{eta_txt(life["eta_cycles"])}</strong>. '
            'These are whole-life averages; the Outlook below refines them '
            'from your logged trend as samples accumulate.')
        lifetime = ('<section class="panel"><h2>Since manufacture</h2>'
                    f'<section class="cards">{life_cards}</section>'
                    f'<div class="note">{life_note}</div></section>')
    else:
        lifetime = ''

    # Outlook / projection.
    soh_proj = project_threshold(rows, "soh_pct", REPLACE_SOH_PCT, going_down=True)
    cyc_proj = project_threshold(rows, "cycle_count", REPLACE_CYCLES, going_down=False)

    def describe_proj(proj, unit_desc, target_desc):
        if proj is None:
            return (f"Not enough history yet to project {target_desc}. "
                    "Keep collecting samples over the coming days.")
        eta, rate = proj
        if eta == "reached":
            return f"Already at or past {target_desc}."
        if eta is None:
            return (f"No clear trend toward {target_desc} yet "
                    f"(rate {rate:+.3f} {unit_desc}).")
        days = (eta - datetime.now(timezone.utc)).days
        return (f"Projected to reach {target_desc} around "
                f"<strong>{eta.strftime('%Y-%m-%d')}</strong> "
                f"(~{days} days), trending {rate:+.3f} {unit_desc}.")

    outlook = (
        '<div class="outlook"><h2>Outlook</h2>'
        f'<p>{describe_proj(soh_proj, "%/day", f"{REPLACE_SOH_PCT:.0f}% capacity")}</p>'
        f'<p>{describe_proj(cyc_proj, "cycles/day", f"{REPLACE_CYCLES} cycles")}</p>'
        '</div>'
    )

    # ---- Per-year breakdown (measured from logged samples) -------------- #
    yr_cycles = period_deltas(rows, "cycle_count", "year")
    yr_soh = period_deltas(rows, "soh_pct", "year")
    years = sorted(set(yr_cycles) | set(yr_soh))
    if years:
        worst_year = min(years, key=lambda y: yr_soh.get(y, 0.0))  # most fade
        busiest_year = max(years, key=lambda y: yr_cycles.get(y, 0.0))
        trows = []
        for y in years:
            c = yr_cycles.get(y, 0.0)
            s = yr_soh.get(y, 0.0)
            flags = []
            if y == worst_year and s < 0:
                flags.append('<span class="tag bad">most aging</span>')
            if y == busiest_year and c > 0:
                flags.append('<span class="tag warn">most cycles</span>')
            trows.append(
                f'<tr><td>{y}</td><td>{c:+.0f}</td>'
                f'<td>{s:+.1f} pts</td><td>{" ".join(flags)}</td></tr>')
        year_table = (
            '<table class="tbl"><thead><tr><th>Year</th><th>Cycles added</th>'
            '<th>Capacity change</th><th></th></tr></thead>'
            f'<tbody>{"".join(trows)}</tbody></table>')
    else:
        year_table = ('<div class="note">Not enough logged history yet — measured '
                      'per-year changes appear once samples span more than one '
                      'reading. The lifetime averages above already summarise the '
                      'whole battery life.</div>')

    # ---- Monthly extremes ----------------------------------------------- #
    mo_cycles = period_deltas(rows, "cycle_count", "month")
    mo_soh = period_deltas(rows, "soh_pct", "month")
    if mo_cycles:
        bm = max(mo_cycles, key=lambda m: mo_cycles[m])
        most_cycles = f'<strong>{bm}</strong> — {mo_cycles[bm]:+.0f} cycles'
    else:
        most_cycles = 'needs samples spanning at least two readings'
    if mo_soh and min(mo_soh.values()) < 0:
        wm = min(mo_soh, key=lambda m: mo_soh[m])
        most_aging = f'<strong>{wm}</strong> — {mo_soh[wm]:+.1f} pts capacity'
    else:
        most_aging = 'needs samples spanning at least two readings'

    analytics = (
        '<section class="panel"><h2>Yearly &amp; monthly analytics</h2>'
        f'{year_table}'
        '<div class="minigrid">'
        f'<div class="minicard"><div class="label">Month with most cycles</div>'
        f'<div class="mv">{most_cycles}</div></div>'
        f'<div class="minicard"><div class="label">Fastest-aging month</div>'
        f'<div class="mv">{most_aging}</div></div>'
        '</div></section>')

    # ---- Lifetime usage distribution (retrospective, available now) ----- #
    cc = parse_cycle_counts(latest["cycle_counts_raw"])
    sw = parse_swelling(latest["swelling_raw"])
    usage_parts = []
    if cc:
        usage_parts.append(render_cycle_distribution(cc))
    if sw:
        usage_parts.append(render_stress_profile(sw))
    if usage_parts:
        usage = ('<section class="panel"><h2>How this battery has been used</h2>'
                 '<div class="note" style="margin:0 0 12px">Whole-life data the '
                 'fuel gauge has accumulated — organised by condition, not by '
                 'date.</div>'
                 f'<section class="charts">{"".join(usage_parts)}</section>'
                 '</section>')
    else:
        usage = ''

    charts = "".join([
        render_chart(rows, "soh_pct", "State of Health over time", "%",
                     lambda v: f"{v:.1f}%", threshold=REPLACE_SOH_PCT),
        render_chart(rows, "cycle_count", "Charge cycles over time", "cycles",
                     lambda v: f"{v:.0f}", threshold=REPLACE_CYCLES),
        render_chart(rows, "charge_full_uah",
                     "Full-charge capacity over time", "mAh",
                     lambda v: f"{v:.0f}", value_scale=1 / 1000.0),
    ])

    span = ""
    try:
        d0 = datetime.fromisoformat(first["ts_local"])
        d1 = datetime.fromisoformat(latest["ts_local"])
        ndays = (d1 - d0).days
        span = f" · {len(rows)} samples over {ndays} day{'s' if ndays != 1 else ''}"
    except Exception:
        pass

    body = f"""
      <header>
        <h1>{html.escape(model)} — Battery Analytics</h1>
        <div class="meta">Last reading {html.escape(latest['ts_local'])}{span}</div>
      </header>
      <section class="cards">{cards}</section>
      {spec}
      {lifetime}
      {outlook}
      {analytics}
      {usage}
      <section class="charts">{charts}</section>
      <footer>
        SoH = current full-charge capacity ÷ factory design capacity.
        Replacement guidance for Pixel 7: below {REPLACE_SOH_PCT:.0f}% capacity
        or {REPLACE_CYCLES} cycles. Data read over ADB from
        {html.escape(POWER_SUPPLY)}.
      </footer>
    """
    return HTML_SHELL.format(title=f"{model} Battery Analytics",
                             generated=generated, body=body)


HTML_SHELL = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<style>
  :root {{
    --bg:#f6f7f9; --panel:#ffffff; --ink:#1a1c1f; --muted:#6b7280;
    --line:#e5e7eb; --grid:#eef0f3; --accent:#3b82f6;
    --good:#16a34a; --warn:#d97706; --bad:#dc2626;
  }}
  @media (prefers-color-scheme: dark) {{
    :root {{
      --bg:#0f1114; --panel:#181b20; --ink:#e8eaed; --muted:#9aa0a6;
      --line:#2a2e35; --grid:#23272e; --accent:#60a5fa;
      --good:#22c55e; --warn:#f59e0b; --bad:#ef4444;
    }}
  }}
  * {{ box-sizing:border-box; }}
  body {{ margin:0; background:var(--bg); color:var(--ink);
    font:15px/1.5 -apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif; }}
  .wrap {{ max-width:900px; margin:0 auto; padding:28px 20px 60px; }}
  header h1 {{ margin:0 0 4px; font-size:24px; }}
  .meta {{ color:var(--muted); font-size:13px; }}
  .cards {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(160px,1fr));
    gap:12px; margin:22px 0; }}
  .card {{ background:var(--panel); border:1px solid var(--line);
    border-radius:12px; padding:14px 16px; }}
  .card .label {{ color:var(--muted); font-size:12px; text-transform:uppercase;
    letter-spacing:.04em; }}
  .card .value {{ font-size:24px; font-weight:650; margin-top:4px;
    white-space:nowrap; }}
  .card .sub {{ color:var(--muted); font-size:12px; margin-top:2px; }}
  .outlook,.panel {{ background:var(--panel); border:1px solid var(--line);
    border-radius:12px; padding:16px 18px; margin:8px 0 22px; }}
  .outlook h2,.panel h2 {{ margin:0 0 12px; font-size:16px; }}
  .outlook p {{ margin:4px 0; color:var(--ink); }}
  .panel .cards {{ margin:0 0 6px; }}
  .note {{ color:var(--muted); font-size:12.5px; margin-top:10px; }}
  .speclist {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(260px,1fr));
    gap:2px 24px; }}
  .specrow {{ display:flex; justify-content:space-between; gap:12px;
    padding:7px 0; border-bottom:1px solid var(--line); font-size:14px; }}
  .specrow .k {{ color:var(--muted); }}
  .specrow .v {{ text-align:right; font-weight:550; }}
  .specrow code {{ font-size:11px; word-break:break-all; }}
  .tbl {{ width:100%; border-collapse:collapse; font-size:14px; }}
  .tbl th {{ text-align:left; color:var(--muted); font-weight:600;
    font-size:12px; text-transform:uppercase; letter-spacing:.03em;
    padding:6px 10px; border-bottom:1px solid var(--line); }}
  .tbl td {{ padding:8px 10px; border-bottom:1px solid var(--line); }}
  .tag {{ font-size:11px; padding:2px 7px; border-radius:20px; margin-right:4px;
    color:#fff; }}
  .tag.bad {{ background:var(--bad); }}
  .tag.warn {{ background:var(--warn); }}
  .minigrid {{ display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr));
    gap:12px; margin-top:14px; }}
  .minicard {{ border:1px solid var(--line); border-radius:10px; padding:12px 14px; }}
  .minicard .label {{ color:var(--muted); font-size:12px; text-transform:uppercase;
    letter-spacing:.04em; margin-bottom:5px; }}
  .minicard .mv {{ font-size:15px; }}
  .barrow {{ display:flex; align-items:center; gap:12px; margin:6px 0; }}
  .barlabel {{ width:64px; font-size:12px; color:var(--muted); text-align:right; }}
  .bartrack {{ flex:1; height:16px; background:var(--grid); border-radius:5px;
    overflow:hidden; }}
  .barfill {{ display:block; height:100%; background:var(--accent);
    border-radius:5px; }}
  .barval {{ width:40px; font-size:12px; color:var(--muted); }}
  .charts {{ display:flex; flex-direction:column; gap:18px; }}
  .chart {{ background:var(--panel); border:1px solid var(--line);
    border-radius:12px; padding:14px 16px; overflow-x:auto; }}
  .chart h3 {{ margin:0; font-size:15px; }}
  .chartsub {{ color:var(--muted); font-size:12px; margin:2px 0 8px; }}
  .svgchart {{ width:100%; height:auto; min-width:520px; }}
  .grid {{ stroke:var(--grid); stroke-width:1; }}
  .threshold {{ stroke:var(--bad); stroke-width:1.5; stroke-dasharray:5 4; opacity:.8; }}
  .thresholdlabel {{ fill:var(--bad); font-size:11px; }}
  .dataline {{ fill:none; stroke:var(--accent); stroke-width:2.5;
    stroke-linejoin:round; stroke-linecap:round; }}
  .dot {{ fill:var(--accent); }}
  .ylabel,.xlabel {{ fill:var(--muted); font-size:11px; }}
  .ylabel {{ text-anchor:end; }}
  .empty {{ color:var(--muted); }}
  .empty-state {{ text-align:center; padding:80px 20px; color:var(--muted); }}
  .empty-state code {{ background:var(--panel); padding:2px 6px; border-radius:6px; }}
  footer {{ margin-top:30px; color:var(--muted); font-size:12px;
    border-top:1px solid var(--line); padding-top:14px; }}
</style>
</head>
<body>
  <div class="wrap">
    {body}
    <div class="meta" style="margin-top:20px">Generated {generated}</div>
  </div>
</body>
</html>
"""


# --------------------------------------------------------------------------- #
# Commands
# --------------------------------------------------------------------------- #

def cmd_collect(args) -> None:
    serial = pick_serial(args.serial)
    data = read_battery(serial)
    if data.get("charge_full") is None or data.get("charge_full_design") is None:
        print("WARNING: could not read charge_full / charge_full_design from "
              f"{POWER_SUPPLY}. The power-supply node name may differ on this "
              "device. Run:  adb shell ls /sys/class/power_supply/", file=sys.stderr)
    rowid = store_sample(data, serial)
    print(f"[{datetime.now():%Y-%m-%d %H:%M:%S}] sample #{rowid} stored: "
          f"SoH {fmt_soh(data.get('soh_pct'))}, "
          f"{fmt_mah(data.get('charge_full'))}/"
          f"{fmt_mah(data.get('charge_full_design'))}, "
          f"{data.get('cycle_count')} cycles, "
          f"{data.get('capacity')}% ({data.get('status')})")
    if args.report:
        cmd_report(args)


def cmd_report(args) -> None:
    rows = load_samples()
    DASHBOARD_PATH.write_text(build_dashboard(rows), encoding="utf-8")
    print(f"Dashboard written: {DASHBOARD_PATH}  ({len(rows)} samples)")
    if getattr(args, "open", False):
        webbrowser.open(DASHBOARD_PATH.as_uri())


def cmd_show(args) -> None:
    serial = pick_serial(args.serial)
    d = read_battery(serial)
    mfg = epoch_to_date(d.get("manufacturing_date"))
    print(f"Device         : {d.get('manufacturer')} {d.get('model')} ({serial})")
    print(f"Chemistry      : {d.get('technology')}")
    if mfg:
        age = fmt_age((datetime.now(timezone.utc) - mfg).total_seconds() / 86400)
        print(f"Manufactured   : {mfg.strftime('%Y-%m-%d')}  (age {age})")
    print(f"State of Health: {fmt_soh(d.get('soh_pct'))}")
    print(f"Full charge    : {fmt_mah(d.get('charge_full'))} "
          f"(design {fmt_mah(d.get('charge_full_design'))})")
    print(f"Cycle count    : {d.get('cycle_count')}")
    print(f"Battery level  : {d.get('capacity')}%  ({d.get('status')})")
    t = d.get("temp")
    print(f"Temperature    : {t / 10:.1f}°C" if t is not None else "Temperature    : —")
    v = d.get("voltage_now")
    print(f"Voltage        : {v / 1e6:.3f} V" if v is not None else "Voltage        : —")
    r = d.get("resistance")
    print(f"Resistance     : {r / 1000:.0f} mΩ" if r else "Resistance     : —")
    print(f"Battery serial : {d.get('serial_number')}")
    print(f"Health flag    : {d.get('health')}")


def main() -> None:
    try:  # ensure °C etc. render on the Windows console
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
    p = argparse.ArgumentParser(description="Phone battery State-of-Health tracker.")
    sub = p.add_subparsers(dest="cmd")

    pc = sub.add_parser("collect", help="Read the phone and store a sample.")
    pc.add_argument("--serial", help="Target device serial (if multiple).")
    pc.add_argument("--report", action="store_true", help="Also rebuild the dashboard.")
    pc.add_argument("--open", action="store_true", help="Open the dashboard after.")
    pc.set_defaults(func=cmd_collect)

    pr = sub.add_parser("report", help="Rebuild the HTML dashboard from stored data.")
    pr.add_argument("--open", action="store_true", help="Open the dashboard after.")
    pr.set_defaults(func=cmd_report)

    ps = sub.add_parser("show", help="Print a live reading (does not store).")
    ps.add_argument("--serial", help="Target device serial (if multiple).")
    ps.set_defaults(func=cmd_show)

    args = p.parse_args()
    if not args.cmd:
        p.print_help()
        return
    args.func(args)


if __name__ == "__main__":
    main()
