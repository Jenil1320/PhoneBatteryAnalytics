# Releasing Cellspan to Google Play

This app is on-device-only (no Internet permission). The steps below cover
signing, building a release bundle, and the Play data-safety answers.

## 1. Create a signing key (once, done by you)

Claude cannot generate or hold signing keys. Create one yourself:

```bash
keytool -genkeypair -v -keystore cellspan-release.jks \
  -keyalias cellspan -keyalg RSA -keysize 2048 -validity 10000
```

Keep `cellspan-release.jks` and its passwords safe and backed up. If you lose the
key you cannot update the app on Play.

## 2. Point the build at your key

Create `android/keystore.properties` (already gitignored — never commit it):

```properties
storeFile=/absolute/path/to/cellspan-release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=cellspan
keyPassword=YOUR_KEY_PASSWORD
```

The Gradle build reads this file automatically. Without it, a release build stays
unsigned (fine for inspection, not for install).

## 3. Build the release bundle (AAB) for Play

```bash
cd android
./gradlew bundleRelease
```

Output: `android/app/build/outputs/bundle/release/app-release.aab`.
Upload this `.aab` to the Play Console. (R8 minify + resource shrinking are on for
release — test the release build on a device before uploading, since minified
builds can behave differently from debug.)

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
- `PACKAGE_USAGE_STATS` (Usage Access) — user-granted special access. Play may ask
  why: it powers the optional per-app screen-time estimate, which is shown on
  screen only and never stored or exported.

## 6. Store listing assets you still need

- App icon (512×512 PNG) — export from the adaptive icon.
- Feature graphic (1024×500).
- At least 2 phone screenshots (the Now, Health, Trends, or History screens).
- Short description and full description.
