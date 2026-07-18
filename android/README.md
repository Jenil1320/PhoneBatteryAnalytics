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

**History.** A `WorkManager` job records a sample a few times a day into a local
SQLite database, so the Trends screen fills in over the following weeks. Nothing
leaves the device.

## Screens

- **Health** — the headline SoH number, a condition pill, the capacity trend, key
  stats, and a plain-language forecast of when it reaches 80%.
- **Trends** — SoH and cycle history over time, plus since-manufacture averages.
- **Diagnose** — full battery spec and a transparent note on the current data source.

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
