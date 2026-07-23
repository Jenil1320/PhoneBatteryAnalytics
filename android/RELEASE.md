# Releasing Cellspan to Google Play

This app is on-device-only (no Internet permission). The steps below cover
signing, building the release artefacts, and the Play data-safety answers. For the
Play Console listing itself see [`docs/PUBLISHING.md`](../docs/PUBLISHING.md).

## 1. Back up the two signing files (do this before anything else)

The release key already exists. Two files matter:

- `android/cellspan-release.jks` — the keystore.
- `android/keystore.properties` — the passwords and alias the build reads.

Both are gitignored, so they exist on this machine only. Nothing in the repo, and
no Git history, holds a copy. **Back both up somewhere off this machine** — a
password manager entry or an encrypted drive, ideally both. Keep the passwords in
the password manager, never in a file in the repo.

Losing them is not equally bad in both directions:

- Play App Signing can **reset a lost upload key**. You ask Play support, register
  a replacement, and keep shipping.
- Nothing can reset the **app signing key**. If Play holds it under Play App
  Signing, you are covered. If you opted out and hold it yourself, losing it means
  no further updates to `io.github.jenil1320.cellspan` — ever. Users would have to
  install a new listing by hand.

So keep Play App Signing enabled when you create the app.

## 2. How the build finds the key

`android/keystore.properties` looks like this (real values are already in place —
this is the shape, for restoring from backup):

```properties
storeFile=C:/Users/<you>/Documents/phoneBatteryAnalytics/android/cellspan-release.jks
storePassword=…
keyAlias=cellspan
keyPassword=…
```

Use an **absolute** path for `storeFile`. Gradle resolves a relative one against
`android/app`, not against the folder holding the properties file, so a relative
path points somewhere that does not exist and the signing task fails.

The build reads this file automatically. If it is missing, the release build still
compiles but stays unsigned — fine for inspection, not installable.

## 3. Build the release artefacts

```bash
cd android
./gradlew assembleRelease bundleRelease
```

That produces both artefacts, each signed with the release key:

- `app/build/outputs/apk/release/app-release.apk` — sideload to testers. Play does
  not accept an APK.
- `app/build/outputs/bundle/release/app-release.aab` — upload this to Play.

R8 minification and resource shrinking are on for release, so install the APK on a
real device and check it before uploading the bundle. Minified builds can behave
differently from debug.

### Upload the mapping file with every release

R8 renames classes, so release crash reports arrive unreadable unless Play holds
the matching deobfuscation map. Upload
`app/build/outputs/mapping/release/mapping.txt` with **each** release — the Play
Console asks for a deobfuscation file on the release page after the bundle
uploads. Each build overwrites `mapping.txt`, so upload the one produced by the
build you actually shipped.

## 4. Play Console — Data safety form

Answer these to match the app's actual behaviour:

- **Does your app collect or share user data?** No.
- **Data processed only on the device?** Yes.
- **Is all data encrypted in transit?** Not applicable — no data leaves the device.
- **Can users request data deletion?** Uninstalling deletes all data.
- **Privacy policy URL:** host `docs/privacy-policy.md` at a public URL (for example
  GitHub Pages) and paste that link.

## 5. Permissions to declare

- `ACCESS_WIFI_STATE` — normal permission, no declaration form needed.
- `POST_NOTIFICATIONS` — runtime permission, asked for on Android 13 and later.
  Only used for the optional charge alarm the user sets themselves.
- `PACKAGE_USAGE_STATS` (Usage Access) — user-granted special access. Play may ask
  why: it powers the optional per-app screen-time estimate, which is shown on
  screen only and never stored or exported.

## 6. Store listing assets

Already generated, in [`docs/play/`](../docs/play): the 512×512 icon, the 1024×500
feature graphic, and four phone screenshots. Short and full descriptions are still
written by hand in the Console.
