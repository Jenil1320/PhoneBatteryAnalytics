# Cellspan degradation model — parameters and provenance

Cellspan shows a **modeled ("typical") State of Health** next to the measured one, so
the user can tell whether their battery is aging normally for its cycle count. This
note records where the model and its numbers come from, so the choice is auditable.

Implementation: [`DegradationModel.kt`](../android/app/src/main/java/io/github/jenil1320/cellspan/analytics/DegradationModel.kt),
unit tests in [`DegradationModelTest.kt`](../android/app/src/test/java/io/github/jenil1320/cellspan/DegradationModelTest.kt).

## Model

We use the **Schmalstieg holistic NMC aging model**, as implemented in **SimSES**
(the TU München open-source storage simulator, model `nmc_sanyo_ur18650e`). Of the
SimSES parameter sets it is the closest transcribable match to a consumer LCO/NMC
phone pouch cell: NMC/graphite, a sloped voltage curve, and — unusually — it is
parameterised in **cell voltage**, which a phone actually measures.

Capacity loss is the sum of a calendar term and a cyclic term (per-unit fraction;
×100 for %):

```
Q_loss = Q_loss,cal + Q_loss,cyc

Calendar:  Q_loss,cal = α_cap · t_days^0.75
           α_cap = (7.543·V − 23.75) · 1e6 · exp(−6976 / T_kelvin)

Cyclic:    Q_loss,cyc = β_cap · sqrt(Q_throughput_Ah)
           β_cap = 7.348e-3·(V − 3.667)^2 + 7.60e-4 + 4.081e-3·DoD
```

| Symbol | Meaning | Units |
|---|---|---|
| `t_days` | battery age | days |
| `T` | temperature | **kelvin** (Arrhenius, used directly) |
| `V` | mean cell voltage (calendar: mean rest/storage OCV; cyclic: mean voltage over a cycle) | volts |
| `DoD` | depth of discharge, measured from sessions where possible | fraction 0–1 |
| `Q_throughput_Ah` | charge throughput, expressed against the fitted reference cell: equivalent full cycles × 2 × 2.05 Ah (see below) | Ah |
| `Q_loss` | relative capacity loss | fraction (0.20 = 20 %) |

Fixed constants (verbatim from SimSES source / Schmalstieg 2014): calendar time
exponent **0.75**, cyclic exponent **0.5** (√ throughput), cyclic-aging voltage
vertex **3.667 V**, calendar activation energy ≈ 6976·R ≈ **58 kJ/mol**.

## What the cyclic term predicts (and why it is not anchored to an OEM rating)

With the fixed phone cycle-mean voltage (3.7 V) and the current DoD default of 0.5, the
**cyclic term alone** predicts **≈16.1 % loss at 800 equivalent full cycles** — for any
phone, regardless of pack size (see the normalisation section below). No calendar term is
added at that point unless a battery age is known.

**This figure is not a match for any manufacturer rating, and must not be tuned into
one.** An OEM number such as "80 % capacity after ~800 cycles" is a *whole-life field
rating*: it already includes years of calendar ageing as well as the cycling. If the
cyclic term is anchored to that rating and a calendar term is then added on top, calendar
ageing is counted twice. The unit tests therefore assert a wide sanity range at 800
cycles, not a calibration target.

## Important input choices (data-accuracy)

- **Cyclic term uses a fixed representative cycle-mean voltage (~3.7 V), not the
  measured average.** The recent sample-average voltage is dominated by whatever state
  the phone has been in — e.g. ~4.3 V while charging — which is a *calendar-at-high-SoC*
  condition, not the cycle mean. Feeding 4.3 V into β_cap's parabola over-predicts
  cyclic wear massively (it drove a spurious "61 % modeled" during testing before this
  was corrected). Cyclic wear is therefore driven by throughput (cycle count), which is
  what actually accumulates it.
- **Calendar term is computed whenever a battery age is known — which is most of the
  time.** The fuel gauge's manufacture date (`manufacturing_date` in sysfs) is
  SELinux-blocked for untrusted apps, so a stock device does not expose it. The app
  instead accepts a **user-supplied battery start date**, so the calendar term is active
  for most users. The UI shows "needs battery age" only until a date is given.
- **The calendar term always uses a fixed rest voltage of 3.8 V.** `DEFAULT_REST_V = 3.8`
  is applied unconditionally. The app holds no measured rest voltage, and there is no
  voltage clamp of any kind in the model. Root does not change this. The only measured
  input to the calendar term is temperature, which is legitimate: calendar ageing really
  does accrue at whatever temperature the cell sits at.
- **Depth of discharge is measured, and it dominates the cyclic term.** DoD supplies
  roughly **79 % of β_cap**, so it moves the result more than any other input. The
  fallback default moved from **0.7 to 0.5** (`DEFAULT_DOD`): 0.7 is an EV/grid-storage
  figure, and phones are topped up through the day and cycle far shallower, so 0.7
  over-predicts wear. The app now **measures** the real value from recorded discharge
  sessions — `HistoryStore.avgDischargeDoD`, depth-weighted, over a 90-day window, with a
  minimum of 5 sessions — and falls back to 0.5 only when there are too few sessions.

## Throughput normalisation (corrected 2026-08-22)

β_cap carries units of **Ah^-0.5** and was fitted to a single **2.05 Ah** cell, so the
ampere-hour figure fed to it only means what the paper intended near that cell size.

**The app used to pass the phone's own pack capacity.** Predicted fractional loss then
grew with the square root of battery size — the same model claimed different wear for
different phones at the same cycle count, purely because one had a bigger battery:

| Design capacity | Predicted cyclic loss at 800 cycles (old, incorrect) |
|---|---|
| 3000 mAh | ≈ 17.8 % |
| 4356 mAh | ≈ 21.4 % |
| 6000 mAh | ≈ 25.1 % |

That is not what a bigger battery does. Capacity fade is an **intensive** property: it is
driven by how many times the cell's chemistry has been cycled, not by how many
ampere-hours the pack holds. At equal cycle count a large pack and a small one have each
been cycled the same number of times and should be modelled as equally worn.

Throughput is therefore now expressed **against the reference cell**:

```
Q_throughput_Ah = equivalent_full_cycles × AH_PER_CYCLE × 2.05
```

Two constants there, both deliberate:

- **2.05 Ah** — the Sanyo UR18650E the coefficients were fitted to.
- **`AH_PER_CYCLE = 2.0`** — Schmalstieg counts an equivalent full cycle as throughput
  ÷ (2 × capacity): the charge going in and the discharge coming out both count. Android's
  `BATTERY_PROPERTY_CYCLE_COUNT` uses the ordinary consumer definition instead — one cycle
  is one capacity's worth of *discharge*. Converting between the two is this factor of
  two, and omitting it under-stated throughput by half.

The result is capacity-independent, which is the physically correct behaviour, and it is
pinned by a unit test (`cyclicWearDoesNotDependOnHowBigTheBatteryIs`). `designCapacityMah`
is still a parameter of `cyclicLossFrac`, but only as a guard: a phone whose design
capacity is unknown gets no cyclic term at all, because nothing else about it can be
trusted either.

The absolute output remains approximate for a modern phone cell — see the caveats below —
but it no longer varies with something that should not affect it.

## Caveats (why it's labeled "typical", not a manufacturer figure)

The coefficients are fit to a **4.2 V-max NMC 18650**. Modern phones charge to
**4.4–4.45 V** and many are LCO or LCO/NMC pouch cells, where high-voltage degradation
accelerates non-linearly beyond the fitted range. The Pixel 7 also runs *Battery Health
Assistance*, which lowers max charge voltage over life. So the model's **functional
structure and exponents transfer** (SEI-limited graphite-anode physics — √t calendar,
√Q cyclic, Arrhenius in T), but the absolute coefficients are approximate for a phone
cell. Cellspan presents the output as a typical reference for comparison, never as an
exact or manufacturer-accurate SoH.

## Honest limits

Three things about this model are worth stating plainly, because they bound what the
output can mean.

- **The coefficients come from a 2013 NMC 18650.** Applied to a modern phone pouch cell
  they run **pessimistic**. That residual is real. It is reported as-is, not tuned away,
  because tuning it against an OEM rating would reintroduce the double-count described
  above.
- **Three of the inputs are chosen representative values, not published constants.** The
  cycle-mean voltage (3.7 V), the rest voltage (3.8 V) and the DoD fallback (0.5) are
  engineering choices for a phone. Only the DoD is replaced by a measurement, and only
  once enough discharge sessions exist. The published coefficients are exact; these
  inputs are not.
- **The UI presents a band, not a point.** Modeled SoH is shown as a **±6 percentage
  point range** rather than a single figure. Roughly ±0.1 V on the assumed rest voltage,
  ±3 °C on temperature and ±0.15 on DoD together move the result by about that much, so a
  single number would claim precision the model does not have.

## Sources

- **SimSES** (TU München, EES), archived: `https://gitlab.lrz.de/open-ees-ses/simses`
  — models `simses/technology/lithium_ion/degradation/{calendar,cyclic}/nmc_sanyo_ur18650e.py`.
- **Schmalstieg, Käbitz, Ecker, Sauer**, "A holistic aging model for Li(NiMnCo)O₂ based
  18650 lithium-ion batteries," *J. Power Sources* 257 (2014) 325–334,
  DOI 10.1016/j.jpowsour.2014.02.012.
- **Naumann et al.** (LFP calendar/cyclic; model *structure* reference),
  DOI 10.1016/j.est.2018.01.019 and 10.1016/j.jpowsour.2019.227666.
- **Battery University BU-808** (consumer LCO/NMC calendar & cycle-vs-DoD trend tables,
  used as sanity checks): `https://batteryuniversity.com/article/bu-808-how-to-prolong-lithium-based-batteries`.
- **Google Pixel battery longevity** (Battery Health Assistance; cited for context only —
  its whole-life cycle rating is deliberately **not** used to calibrate this model, see
  above): `https://support.google.com/pixelphone/answer/15738128`.
