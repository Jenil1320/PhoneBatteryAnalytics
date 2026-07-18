# Publishing Cellspan to Google Play

These are the manual steps **you** perform from your own Google Play developer
account. Nothing here can be automated on your behalf — signing keys and Play
Console actions must stay with you.

## 1. One-time setup

- A **Google Play Developer account** (one-time US$25 registration).
- **Android Studio** (for the signing UI and release build), or the command line
  below with the SDK already installed.

## 2. Version the release

In [`android/app/build.gradle.kts`](../android/app/build.gradle.kts) bump these on
every release:

```kotlin
versionCode = 1        // integer, must increase every upload
versionName = "1.0.0"  // user-visible string
```

## 3. Create an upload keystore (once, keep it safe forever)

```bash
keytool -genkey -v -keystore cellspan-upload.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias cellspan
```

Store `cellspan-upload.jks` and its passwords somewhere safe and **never commit
them** — the repo's `.gitignore` already excludes `*.jks` and `keystore.properties`.
Losing this key means you can't ship updates under the same app.

## 4. Wire up release signing

Create `android/keystore.properties` (git-ignored):

```properties
storeFile=../cellspan-upload.jks
storePassword=…
keyAlias=cellspan
keyPassword=…
```

Then add a `signingConfigs` block reading those values in `app/build.gradle.kts`
and reference it from `buildTypes { release { signingConfig = … } }`. (Android
Studio's **Build → Generate Signed Bundle** wizard does this for you if you
prefer clicking through it.)

For a production release also flip on shrinking:

```kotlin
release { isMinifyEnabled = true }
```

## 5. Build the release bundle

```bash
cd android
./gradlew :app:bundleRelease
# → app/build/outputs/bundle/release/app-release.aab
```

Play requires an **.aab** (App Bundle), not an APK.

## 6. Play Console

1. **Create app** → name "Cellspan", default language, app (not game), free.
2. **Store listing** — short/long description, screenshots (grab from the running
   app), a 512×512 icon, and a feature graphic.
3. **Privacy policy** — required. Cellspan keeps all data on the device and sends
   nothing to a server, which makes this short, but a hosted URL is mandatory.
4. **Data safety** — declare **no data collected and no data shared**; readings are
   stored locally only. This is accurate for the current app.
5. **Content rating** questionnaire and **target audience**.
6. **Release** → pick a track (start with **Internal testing**), upload the
   `.aab`, add release notes, and roll out. Promote to Production when ready.

## Notes

- The app requests **no runtime permissions** and reads only public battery APIs,
  which keeps the review straightforward.
- Check that the app name **Cellspan** isn't already taken on Play before
  committing to branding; the package id `io.github.jenil1320.cellspan` is yours
  and won't collide.
