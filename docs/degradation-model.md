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
| `DoD` | representative depth of discharge | fraction 0–1 |
| `Q_throughput_Ah` | charge throughput = equivalent full cycles × design capacity (Ah) | Ah |
| `Q_loss` | relative capacity loss | fraction (0.20 = 20 %) |

Fixed constants (verbatim from SimSES source / Schmalstieg 2014): calendar time
exponent **0.75**, cyclic exponent **0.5** (√ throughput), cyclic-aging voltage
vertex **3.667 V**, calendar activation energy ≈ 6976·R ≈ **58 kJ/mol**.

## Calibration cross-check (Pixel 7)

Google rates the Pixel 7 for **~80 % capacity after ~800 charge cycles**. With a
typical phone cycle-mean voltage (~3.7 V) and DoD, the **cyclic term alone** predicts
≈ 20 % loss at 800 equivalent full cycles (4355 mAh design) → ≈ **80 % modeled SoH** —
independently matching Google's figure. This is the anchor the unit tests assert.

## Important input choices (data-accuracy)

- **Cyclic term uses a fixed representative cycle-mean voltage (~3.7 V), not the
  measured average.** The recent sample-average voltage is dominated by whatever state
  the phone has been in — e.g. ~4.3 V while charging — which is a *calendar-at-high-SoC*
  condition, not the cycle mean. Feeding 4.3 V into β_cap's parabola over-predicts
  cyclic wear massively (it drove a spurious "61 % modeled" during testing before this
  was corrected). Cyclic wear is therefore driven by throughput (cycle count), which is
  what actually accumulates it.
- **Calendar term is only computed when battery age is known.** A sandboxed app cannot
  read the manufacture date (`manufacturing_date` in sysfs is SELinux-blocked for
  untrusted apps), so on stock devices only the cyclic term contributes and the UI shows
  "needs battery age" for the calendar component. With root / an ADB helper the calendar
  term switches on using the measured mean voltage (clamped to the model's 2.5–4.2 V
  window) and temperature.

## Caveats (why it's labeled "typical", not a manufacturer figure)

The coefficients are fit to a **4.2 V-max NMC 18650**. Modern phones charge to
**4.4–4.45 V** and many are LCO or LCO/NMC pouch cells, where high-voltage degradation
accelerates non-linearly beyond the fitted range. The Pixel 7 also runs *Battery Health
Assistance*, which lowers max charge voltage over life. So the model's **functional
structure and exponents transfer** (SEI-limited graphite-anode physics — √t calendar,
√Q cyclic, Arrhenius in T), but the absolute coefficients are approximate for a phone
cell. Cellspan presents the output as a typical reference for comparison, never as an
exact or manufacturer-accurate SoH.

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
- **Google Pixel battery longevity** (~80 % at ~800 cycles; Battery Health Assistance):
  `https://support.google.com/pixelphone/answer/15738128`.
