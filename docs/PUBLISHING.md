# Publishing Cellspan to Google Play

These are the manual steps **you** perform from your own Google Play developer
account. Nothing here can be automated on your behalf — signing keys and Play
Console actions must stay with you.

Signing and building are not covered here. They live in
[`android/RELEASE.md`](../android/RELEASE.md), which is the single source of truth
for the key, the keystore file, and the release build commands.

## 1. One-time setup

- A **Google Play Developer account** (one-time US$25 registration).
- The release keystore and `android/keystore.properties`, both already in place —
  see [`android/RELEASE.md`](../android/RELEASE.md) for what to back up and why.

## 2. Version the release

In [`android/app/build.gradle.kts`](../android/app/build.gradle.kts) bump these on
every release:

```kotlin
versionCode = 2        // integer, must increase every upload
versionName = "1.1.0"  // user-visible string
```

## 3. Build the upload artefacts

`cd android && ./gradlew assembleRelease bundleRelease` — full detail, including
the mapping file you must upload with each release, is in
[`android/RELEASE.md`](../android/RELEASE.md). Play requires the **.aab** (App
Bundle); the APK is only for handing to testers directly.

## 4. Play Console

1. **Create app** → name "Cellspan", default language, app (not game), free.
2. **Store listing** — short/long description, plus the screenshots, 512×512 icon,
   and feature graphic already generated in [`play/`](play).
3. **Privacy policy** — required. Cellspan keeps all data on the device and sends
   nothing to a server, which makes this short, but a hosted URL is mandatory.
4. **Data safety** — declare **no data collected and no data shared**; readings are
   stored locally only. This is accurate for the current app.
5. **Content rating** questionnaire and **target audience**.
6. **Release** → pick a track (start with **Internal testing**), upload the
   `.aab` and its `mapping.txt`, add release notes, and roll out. Promote to
   Production when ready.

## Notes

- The app requests one runtime permission, `POST_NOTIFICATIONS`, and only for the
  optional charge alarm. Everything else is public battery APIs plus the
  user-granted Usage Access, which keeps the review straightforward.
- Check that the app name **Cellspan** isn't already taken on Play before
  committing to branding; the package id `io.github.jenil1320.cellspan` is yours
  and won't collide.
