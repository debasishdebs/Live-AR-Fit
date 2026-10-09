# Live AR Fit

Live AR Fit is a fitness heads-up display for Rokid AR glasses. A Wear OS watch measures your heart rate, steps,
distance and (optionally) GPS route during a walk, run or ride; your Android phone is the hub that runs the workout,
stores your history and drives the glasses. The glasses show your live stats, a route map and your music, and you can
control the workout with gestures or offline voice commands.

Live AR Fit has no account, no ads, no analytics and no server of its own. See the
[privacy policy](https://debasishdebs.github.io/Live-AR-Fit/privacy-policy.html).

## Hardware

- **Phone:** Android 10 or newer.
- **Watch:** Wear OS 3 or newer, paired with the phone.
- **Glasses:** Rokid AR glasses, set up with the **Hi Rokid** phone app.

## Architecture

```
               Rokid glasses (com.livear.fit.glasses)
               HUD, gestures, glasses microphone
                         ^
                         |  Rokid CXR-L link (Bluetooth)
                         v
   +-------------------------------------------------+
   |  Phone hub (com.livear.fit)                     |
   |  workout control, history (Room), music,        |
   |  on-device voice, map rendering for the glasses |
   +-------------------------------------------------+
                         ^
                         |  Wear OS Data Layer (Google Play services)
                         v
               Wear OS watch (com.livear.fit)
               Health Services workout: heart rate,
               steps, distance, calories, GPS
```

Modules:

- `core/model`, `core/services`, `core/map`: shared types, service contracts and map/tile code (no Android imports).
- `services/*`: one module per service: `workout`, `sync`, `glasses-link`, `watch-link`, `music`, `voice`,
  `voice-android`, `confirm`, `history`.
- Three apps: `phone`, `watch`, `glasses`.

The phone and watch share the application ID `com.livear.fit` (one Google Play listing, and required by the Data
Layer). The Kotlin packages stay `com.debasish.livefit.*`.

## Build

You need JDK 17.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug
```

Map tiles come from MapTiler. Put your MapTiler key in `local.properties` (untracked) at the repo root:

```properties
LIVEAR_TILES_KEY=<key>
```

Debug builds work without it: they fall back to the OpenStreetMap tile server, which is fine for personal and beta use.
Release builds fail without it. `LIVEAR_TILES_KEY` can also be set as an environment variable.

## Release keystore (owner, once)

All three apps are signed with the same upload key. Create it once with the JDK's `keytool`:

```sh
keytool -genkeypair -v -keystore ~/keys/livear-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore file and both passwords in a password manager, with an offline backup. This matters:

- **Google Play:** Play App Signing holds the real app-signing key. If you lose the upload key, Play support can reset it.
- **GitHub glasses APK:** there is no such reset. A lost key means users must uninstall the glasses app before they can
  install an update signed with a new key.

Point the build at the keystore with a `keystore.properties` file at the repo root. It is untracked; never commit it.

```properties
LIVEAR_KEYSTORE=/absolute/path/livear-upload.jks
LIVEAR_KEY_ALIAS=upload
LIVEAR_STORE_PASSWORD=…
LIVEAR_KEY_PASSWORD=…
```

The same four names also work as environment variables.

Certificate fingerprint (needed for the CI variable below):

```sh
keytool -list -v -keystore ~/keys/livear-upload.jks -alias upload | grep SHA256
```

Without a keystore, release builds are debug-signed and named `*-unsigned-diagnostic`. They are for local testing
only and must never be distributed.

On the first upload to Play, enable **Play App Signing**.

## Release builds

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:bundleRelease :watch:bundleRelease :glasses:assembleRelease
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew check16kb
```

`check16kb` checks every native library for 16 KB page-size support. Misaligned LOAD segments or zip alignment fail
the build; a RELRO end that isn't 16 KB aligned is only a warning, to be listed in the release notes.

### Versioning

One key in `gradle.properties` sets the version of all three apps:

```properties
livefit.version=1.0.0
```

`versionCode = major*1_000_000 + minor*10_000 + patch*100 + formFactor`, where formFactor is phone 0, watch 1 and
glasses 2. For 1.0.0:

| App | versionCode |
|---|---|
| Phone | 1000000 |
| Watch | 1000001 |
| Glasses | 1000002 |

A `-beta` suffix (for example `1.1.0-beta`) does not change the versionCode. Never upload a beta and its final release
with the same numbers: bump the version between them.

## CI and tag releases

GitHub Actions runs the unit tests and the debug builds on every push and pull request. A tag `v*` runs the release
job. Add these in the repository's **Settings → Secrets and variables → Actions**.

Secrets:

| Secret | Value |
|---|---|
| `LIVEAR_KEYSTORE_BASE64` | the keystore as base64: `base64 -i ~/keys/livear-upload.jks \| pbcopy` |
| `LIVEAR_KEY_ALIAS` | `upload` |
| `LIVEAR_STORE_PASSWORD` | the keystore password |
| `LIVEAR_KEY_PASSWORD` | the key password |
| `LIVEAR_TILES_KEY` | the MapTiler key |

Variable:

| Variable | Value |
|---|---|
| `LIVEAR_UPLOAD_CERT_SHA256` | the upload certificate's SHA-256 fingerprint (from `keytool -list` above) |

The release job fails if any secret is missing. To release:

```sh
git tag v1.0.0 && git push origin v1.0.0
```

The job builds the signed artifacts, checks the glasses APK's certificate against `LIVEAR_UPLOAD_CERT_SHA256`, runs the
16 KB check, and creates a **draft** GitHub Release with the glasses APK and `SHA256SUMS.txt`. The phone and watch AABs
are a workflow artifact; download them from the workflow run and upload them to Play Console.

## Installing

- **Phone and watch:** from Google Play (one listing, "Live AR Fit"). Install the phone app on the phone, and the watch
  app from the Play Store on the watch (or from the phone's Play Store page, which can send it to a paired watch).
- **Glasses:** from GitHub Releases. See [docs/install-glasses.md](docs/install-glasses.md).

All three apps must run the same release version.

**Upgrading from the old test builds:** builds with the package `com.debasish.livefit` are a different app. Uninstall
them from the phone, watch and glasses. Their local workout history is not migrated.

## Crash logs

Each app writes its last crash (stack trace and version) to app-private storage. Nothing is sent automatically.

- **Phone:** Settings → About → **Share last crash** opens the share sheet.
- **Watch** (debuggable builds only): `adb -s <watch-serial> shell run-as com.livear.fit cat files/last-crash.txt`
- **Glasses** (debuggable builds only): `adb -s <glasses-serial> shell run-as com.livear.fit.glasses cat files/last-crash.txt`

## Licence, privacy and contact

- Licence: [Apache License 2.0](LICENSE). Third-party notices: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Privacy policy: https://debasishdebs.github.io/Live-AR-Fit/privacy-policy.html (source:
  [docs/privacy-policy.md](docs/privacy-policy.md)).
- Changes: [CHANGELOG.md](CHANGELOG.md).
- Contact: d.kanhar@gmail.com
