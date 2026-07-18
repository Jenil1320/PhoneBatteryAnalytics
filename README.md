# Phone Battery Analytics

Tools for measuring a phone's **battery State of Health (SoH)** — how much of its
original capacity remains — and tracking degradation over its life.

This repository has two parts:

| Part | What it is | Data access |
|---|---|---|
| **[`desktop/`](desktop/)** | Python + ADB tool that reads the phone's fuel gauge over USB and builds an offline HTML dashboard. | Full: reads protected `sysfs` nodes (`charge_full`, `charge_full_design`, `manufacturing_date`, cycle histogram, stress data) — needs a USB/ADB connection but no root. |
| **[`android/`](android/)** | **Cellspan** — an on-device Android app. Auto-detects the phone and shows its battery health with zero manual input. | Sandboxed: reads everything the public Android APIs allow, estimates SoH against a bundled device-capacity database, and unlocks the full `sysfs` data if optional elevated access (root / Shizuku) is present. |

Design references for the app live in [`docs/design-directions.html`](docs/design-directions.html).

## Why two tools?

A desktop-plus-ADB tool can read the phone's raw fuel-gauge data directly, which
gives an exact SoH and the real manufacturing date. A regular Play-Store app runs
in a sandbox that (on stock, unrooted Android) **cannot** read those `sysfs` nodes
— so the app is built to get as far as possible from the public APIs alone, and to
enrich itself automatically when elevated access happens to be available. The two
share the same analytics concepts so the numbers line up.

## The honest constraint on history

Neither tool can reconstruct a *timestamped* capacity history from before you start
logging — the phone stores cumulative totals and condition-based distributions, not
a month-by-month log. What **is** available immediately: the manufacturing date
(hence whole-life *average* fade and cycle rates), a cycle-count distribution across
charge levels, and a temperature/charge stress profile. True per-month trends build
forward as samples accumulate.

See [`desktop/README.md`](desktop/README.md) for the ADB tool and
[`android/README.md`](android/README.md) for the app.
