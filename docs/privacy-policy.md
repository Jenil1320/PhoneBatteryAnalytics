# Cellspan — Privacy Policy

_Last updated: 22 July 2026_

Cellspan is a battery-analytics app. It reads your phone's battery data and shows
you health, usage, and forecasts. This policy explains what the app does with data.

## The short version

- **Cellspan keeps all data on your device.** Nothing is sent to us or to any
  server. The app has **no Internet permission**, so it cannot upload data.
- **No accounts, no ads, no analytics, no tracking.**
- Data leaves the app **only when you choose to export it** to a file.

## What data the app uses

Cellspan reads standard battery values from Android: charge level, status,
temperature, voltage, current, charge counter, and (on Android 14 and later) the
battery cycle count. It also reads your device model to look up the battery's
rated capacity. All of this stays in the app's private storage.

### Optional: app screen time (Usage Access)

If you grant **Usage Access**, Cellspan estimates which apps used the screen most,
as a proxy for battery drain. This is optional. The app works without it.

- This data is used **only** to draw the on-screen "app drain" list.
- It is **not** stored in the database and **not** included in any export.
- You can revoke Usage Access at any time in **Settings → Apps → Special app
  access → Usage access**.

## Where data is stored

All history is stored in a private SQLite database inside the app's own storage,
which other apps cannot read. Short-term charge logs are kept for about three
days; longer-term daily summaries and health samples are kept so you can see
trends over time.

## Exporting your data

You can export your battery data to a file using **Export battery data**. You pick
the destination with the system file picker. The export contains only numeric
battery readings and timestamps. It does **not** contain app usage or any personal
identifiers. After you export a file, what happens to that file is up to you.

## Deleting your data

Uninstalling Cellspan permanently deletes all of its stored data from your device.

## Permissions summary

| Permission | Why | Type |
|---|---|---|
| `ACCESS_WIFI_STATE` | Show whether Wi-Fi is on, as a drain indicator | Normal (auto-granted) |
| `PACKAGE_USAGE_STATS` (Usage Access) | Optional per-app screen-time estimate | Special, user-granted |

Cellspan requests **no location, no contacts, no camera, no microphone, no
storage, and no Internet access.**

## Children

Cellspan does not target children and collects no personal data from anyone.

## Changes

If this policy changes, the updated version will be published at this same page and
the date above will change.

## Contact

Questions: jenilagrawal@gmail.com
