# Cellspan (Android)

**Cellspan** — "cell" + "lifespan" — is a zero-input battery health app. Open it
and it reads your phone, works out how much of its original capacity remains, and
shows the trend over time. No sign-in, no permissions, no manual entry.

## What it reads (and the honest limits)

Everything comes from the device automatically:

- **Identity** — `Build.MANUFACTURER` / `Build.MODEL` (e.g. Google · Pixel 7).
- **Live battery** — level, status, health flag, technology, temperature, voltage,
  current, and remaining charge (µAh) via `BatteryManager` and the
  `ACTION_BATTERY_CHANGED` broadcast.
- **Cycle count** — from the public battery broadcast on Android 14+ (when the
  device reports it).

**State of Health.** A sandboxed Play-Store app cannot read the fuel gauge's
`charge_full_design`, so Cellspan estimates SoH by dividing an estimated present
full-charge capacity (from remaining charge ÷ level) by the model's published
design capacity from a bundled [`DeviceCapacityTable`](app/src/main/java/io/github/jenil1320/cellspan/data/DeviceCapacityTable.kt).
This is clearly labelled as an estimate in the app.

**Automatic enrichment.** If the protected fuel-gauge nodes happen to be readable
(rooted device, or an ADB helper such as Shizuku), Cellspan uses the *exact*
`charge_full`, `charge_full_design`, cycle count and manufacture date instead —
turning the estimate into a measurement and unlocking lifetime stats. On stock,
unrooted Android those nodes are blocked by SELinux and the app falls back cleanly.

**History.** A `WorkManager` job records a sample every hour into a local SQLite
database, so the Trends screen fills in over the following weeks. Nothing leaves the
device. Settings can export the whole history to a JSON file and import one back —
merging, never replacing, so importing the same file twice changes nothing.

**Battery life saver.** A plug-in reminder at the bottom of the charge band, a
charge limit at the top, and an optional overheat warning.

The reminders fire at the *exact* level you pick. That needs explaining, because it
is the one thing an obvious implementation gets wrong. `ACTION_BATTERY_CHANGED` is
the only signal Android sends on every single percentage point, and the platform
refuses to deliver it to a receiver declared in a manifest — it only goes to a
running process. So Cellspan runs one:
[`BatteryGuardService`](app/src/main/java/io/github/jenil1320/cellspan/data/BatteryGuardService.kt),
a foreground service that exists solely to listen. It does no polling, holds no
wake lock, and costs one quiet card in the notification shade, which doubles as a
live readout. It runs only while you have armed a reminder.

The previous design polled with `WorkManager` instead, and could not do better than
"somewhere past the level you chose": the charge side checked every three minutes,
and a 65 W charger gains about four points a minute, so an 80% target announced
itself in the high eighties. The discharge side was an ordinary deferrable job, so
App Standby could hold a check back for hours.

How hard the reminders push is yours to set — once, keep asking, or insistent.
Below 5% the choice is overridden: that alert uses the alarm sound and bypasses Do
Not Disturb, because a reminder you sleep through at 4% has failed at its only job.
All decisions live in the pure, unit-tested
[`GuardPolicy`](app/src/main/java/io/github/jenil1320/cellspan/data/GuardPolicy.kt).

On a rooted phone the limit genuinely stops the charge:
[`ChargeControl`](app/src/main/java/io/github/jenil1320/cellspan/data/ChargeControl.kt)
finds the kernel's charging switch (Pixel `charge_stop_level`, or a
`charging_enabled`-style boolean) and the guard drives it, releasing it on unplug so
the phone can never be left refusing to charge. Without root no app can cut the
power, so the limit falls back to a repeating unplug reminder and links the phone's
own charging-limit setting.

**Screen-on vs screen-off drain.** Because the guard sees every percentage point
*and* every screen transition, it can attribute each one to whichever state the
phone was in, weighted by time. That gives the figure every competing app leads
with — what your screen actually costs you per hour, against what the phone spends
asleep — without any extra sampling.

**Every number carries its error bar.** Health is shown with the source that
produced it and how far it could be out — "6 charge sessions, ±2 pts" — because the
same 91% backed by ten agreeing sessions and by one shallow one are not the same
claim. Where the data cannot support a figure the app shows nothing rather than a
confident guess.

## Screens

- **Now** — live level, vitals, time to full while charging, how long the charge
  will last, and the screen-on versus screen-off drain split.
- **Health** — the headline SoH number with its confidence, a condition pill, the
  capacity trend, measured against a modelled "typical" cell, and a forecast of when
  it reaches 80%.
- **Trends** — temperature, voltage, current and cycles over a day, week, month or
  year, plus a custom date range.
- **History** — every charge and discharge session.
- **Settings** — the life saver band, background sampling, design capacity and
  battery age overrides, and data export/import.

The whole app follows the system light or dark theme.

## Build

Requires JDK 17 and the Android SDK (`ANDROID_HOME` set).

```bash
./gradlew :app:testDebugUnitTest   # run the pure-logic unit tests
./gradlew :app:assembleDebug       # build app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:lintDebug           # static analysis
```

Install on a connected phone with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Tech

Kotlin · Jetpack Compose (Material 3) · WorkManager · framework SQLite. No
annotation processing, so there's no code-gen step. Theme (dark, natural green,
Space Grotesk / IBM Plex Mono feel) follows [`../docs/design-directions.html`](../docs/design-directions.html);
system fonts stand in for the display faces and can be swapped for the exact
typefaces via downloadable Google Fonts as a polish step.

## Publishing

See the root project notes. In short: set `versionCode`/`versionName`, create an
upload keystore, `./gradlew :app:bundleRelease` for an `.aab`, and upload to the
Play Console. Publishing is a manual step done from your own developer account.
