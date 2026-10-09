# Live AR Fit — production release (sub-project B) — design

Status: draft for owner review · 2026-10-09 · Owner decisions are in memory `release-plan` and the session log, entries 52–54.

## 1. Goal

Ship **v1.0.0**:
- **Google Play:** the phone and Wear OS apps as one listing, "Live AR Fit".
- **GitHub Releases:** the Rokid glasses app, with install instructions.

This sub-project makes the code, build and documents publishable. Hi Rokid (C) and the outdoor map verification also gate 1.0.0, but they are tracked separately.

Success means:
- `bundleRelease` (phone and watch) and `assembleRelease` (glasses) produce signed, R8-shrunk artifacts.
- Those artifacts run on the owner's devices.
- They pass Play's pre-launch checks with no restricted-permission or policy blockers.
- Every Play Console declaration has a drafted answer, and the owner has reviewed it.

## 2. Identity and versioning

| | Phone | Watch | Glasses |
|---|---|---|---|
| applicationId | `com.livear.fit` | `com.livear.fit` (must equal phone for Data Layer and one listing) | `com.livear.fit.glasses` |
| Label | Live AR Fit | Live AR Fit | Live AR Fit |
| Distribution | Play (phone) | Play (Wear OS form factor, same listing) | GitHub Releases APK |

- **Kotlin packages:** they stay `com.debasish.livefit.*`. Changing the applicationId does not require moving the code; the `namespace` stays as it is.
- **Places that hard-code the package and must follow it:**
  - the CXR glasses-app package (`customAppPackageName`)
  - `<queries>`
  - DebugReceiver component names in scripts
  - `tools/*`
  - deep links
  - the Data Layer paths, which are already `/lf/*` and unaffected
- **Version:**
  - `versionName` comes from a single `gradle.properties` key `livefit.version=1.0.0`, shared by all three apps.
  - `versionCode = major*1_000_000 + minor*10_000 + patch*100 + formFactor`, where formFactor is phone 0, watch 1 and glasses 2.
  - Play requires distinct codes for the two form factors.
- **Upgrade note:** existing sideloaded installs (`com.debasish.livefit`) become a different app. Reinstall once; local history is not migrated, which is acceptable pre-1.0.
- **Labels:** move them into `res/values/strings.xml` in each app. Each app gets a placeholder adaptive launcher icon (vector, plus a round one for Wear). The real logo is the separate brand task.

## 3. Release build

- **Signing:**
  - The owner creates one upload keystore. The command is in the README.
  - The keystore lives outside the repo. It is referenced from an untracked `keystore.properties`, or from the env vars `LIVEAR_KEYSTORE`, `LIVEAR_KEY_ALIAS`, `LIVEAR_STORE_PASSWORD` and `LIVEAR_KEY_PASSWORD`.
  - The phone and watch use the same key, and Play App Signing is enabled.
  - The glasses APK is signed with the same upload key, so GitHub updates install over each other.
  - Without a keystore, release builds fall back to debug signing and log a loud warning, so CI and contributors can still build.
- **R8:**
  - `isMinifyEnabled = true` and `isShrinkResources = true` for the phone, watch and glasses release builds.
  - Keep rules go in `proguard-rules.pro`. They cover:
    - kotlinx.serialization: `@Serializable` classes, companions and serializers, for all wire and settings types in `:core:model` and `:services:*`.
    - Room entities and DAOs; the KSP-generated code is mostly covered by Room's consumer rules, plus explicit keeps for our entities.
    - The Rokid CXR SDK packages `com.rokid.cxr.**`.
    - The reflectively accessed `com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper` (CxrGlassesLink).
    - Health Services, Wearable and Media classes only where the release smoke test shows breakage.
- **Logging:** release builds strip `Log.v/d/i` via `-assumenosideeffects`. They keep `w/e`. User data stays out of `w/e` messages: no coordinates, no track titles.
- **Target SDK:** phone and watch move to `targetSdk = 36`. Play's 2026 rule for new apps is the API level released within the last year. This brings behaviour changes, so the smoke test re-checks foreground services, notifications and Bluetooth. The glasses app stays on its current target, since the Rokid OS is fixed.
- **Removals:**
  - the unused `USE_FAKE_SERVICES` flag
  - the `LIVE_*` debug flags in release builds (they become constants)
  - `phone/src/debug` is already excluded from release

## 4. Permissions and Play policy

| Item | Change |
|---|---|
| `USE_FULL_SCREEN_INTENT` (watch) | **Removed.** When a workout starts from the phone, the watch posts its foreground-service notification as a Wear **Ongoing Activity**, which shows the system "return to workout" chip on every Wear OS 3+ watch. A tap on the notification opens LiveFit. The existing `startActivity` raise stays where it's allowed; it is a best effort. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (phone) | **Removed.** Replaced by `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`, which needs no permission. While the app is not exempt, every app open shows a **bold banner**: "Battery optimisation is on — LiveFit may stop tracking or lose the glasses/watch link when the screen is off." It has an **Open settings** button. Samsung gets extra steps ("Settings → Battery → Background usage limits → Never sleeping apps → add Live AR Fit") with a screenshot-style illustration. Other OEMs get generic text. |
| Notification listener (YouTube Music control) | A **prominent disclosure** screen comes before sending the user to the system settings: what is read (media session and now-playing only), why, and that nothing leaves the phone. Decline = music features off, everything else works. |
| Location (phone and watch) | A prominent disclosure before the runtime prompt: GPS is only used during workouts with "Use GPS outdoors" on, never in the background when no workout is running. No `ACCESS_BACKGROUND_LOCATION` (already true). |
| Microphone (phone and glasses voice) | An in-context rationale before the first use. Audio is processed on the phone and not uploaded. **To verify in the implementation:** the speech recogniser engine's on-device versus network mode. The privacy policy states whichever is true. |
| Body sensors / heart rate / activity recognition (watch) | The rationale screen already exists in setup, so its copy needs a review. The **Health apps declaration** is filled in (§7). |
| Foreground services | Phone: `connectedDevice|location`. Watch: `health|location`. FGS declarations come with justification text and a short screen-recording script for each type (§7). |
| Exported components | The `livefit://` intent filters drop `BROWSABLE`, so web pages can't trigger them. The Wearable listeners on both sides ignore messages whose source node doesn't advertise the peer capability (`livefit_phone`/`livefit_watch`). |
| Backup | `android:allowBackup="false"` plus `dataExtractionRules` excluding everything, so tokens and the workout DB aren't copied off the device. |

## 5. Map tiles

- Production uses a **raster tile provider with an API key** behind the existing `TileSource` interface, set at build time. A provider setting comes after v1.0.
- **Owner picks the provider:**
  - **MapTiler** (free tier ~100k tiles/month, "streets" raster)
  - **Thunderforest** (free hobby tier ~150k/month, "outdoors"/"atlas" styles)
- The key goes into `local.properties`/env as `LIVEAR_TILES_KEY`, then into BuildConfig. A missing key means debug builds fall back to the OSM server, which is fine for personal and beta use, and release builds fail the build.
- The User-Agent becomes `LiveARFit/<version> (com.livear.fit; contact: d.kanhar@gmail.com)`.
- Attribution ("© <provider> © OpenStreetMap contributors") shows on the glasses map image, the watch Map page and the phone history thumbnail. The phone links to the provider's copyright page.

## 6. Robustness

- **Main-thread file I/O moves to `Dispatchers.IO`:**
  - `FileDeltaBuffer`
  - `GpsPreferences`
  - `PageSettingsFile`
  - SharedPreferences hot paths in `SettingsStore`/`CompanionLinker`
  - the glasses map PNG decode (Task 23 minor)
- **Crash log:**
  - An `UncaughtExceptionHandler` in each app writes the last crash (stack plus version) to app-private storage.
  - Phone Settings → About → "Share last crash" opens a share sheet, so nothing is sent automatically.
  - No third-party crash SDK, which keeps the Data safety form simple.
- **Room:** `exportSchema = true`, with the schema JSON committed for the v2 database and a migration test.

## 7. Documents (drafted by Claude, reviewed by the owner)

| File | Purpose |
|---|---|
| `README.md` | What it is, the hardware, the architecture sketch, building, the release keystore setup, and how to install each app |
| `docs/install-glasses.md` | Rokid glasses developer mode/adb, `adb install`, permissions, first pairing via Hi Rokid, upgrades, and the matching-version rule |
| `LICENSE` | **Owner chooses**: Apache-2.0 recommended, or MIT or GPL-3.0 |
| `NOTICE`/`THIRD_PARTY_NOTICES.md` | OSM (ODbL data attribution), the tile provider terms, Rokid CXR SDK terms (verify redistribution), and AndroidX/Kotlin libraries (Apache-2.0) |
| `CHANGELOG.md` | 0.1.0-beta, 0.2.0-beta, 1.0.0 |
| `docs/privacy-policy.md` | Published via GitHub Pages; the URL goes into Play. Covers health data, location, audio, music metadata, local storage, no accounts, no analytics, the tile provider seeing IP and tile coordinates, data deletion (Clear history / uninstall), and the contact email |
| `docs/play/` | Data safety answers, Health apps declaration, FGS justifications plus recording scripts, permission rationales, store listing text (short and long description, phone and Wear), content rating answers |

## 8. CI (GitHub Actions)

- `ci.yml`: on push/PR, run JDK 17, all unit tests, and `assembleDebug` for the three apps. It caches Gradle and Rokid Maven.
- `release.yml`: on a `v*` tag, build the release artifacts. If the signing secrets are present, it signs; otherwise it uploads unsigned artifacts. It also attaches the glasses APK plus its SHA-256 to a draft GitHub Release. The owner adds the secrets.

## 9. Testing

- All existing unit tests pass.
- New tests:
  - the versionCode scheme
  - the battery-banner decision
  - the sender-node validation
  - the Room migration test with exported schema
- Release smoke test on the owner's devices with release-signed builds of all three apps:
  - pair
  - start/stop a workout from the phone and from the watch
  - glasses pages and gestures
  - the map page renders
  - music controls
  - voice command

  This catches R8 breakage, which is the biggest release risk.

## 10. Out of scope

- The final brand and logo
- App Actions/Gemini (D)
- The tile-provider setting
- Drive mode (v2)
- Localisation
- Wear tiles and complications

## 11. Owner decisions (2026-10-09)

1. Licence: **Apache-2.0**.
2. Tile provider: **MapTiler** (raster "streets-v2", 256 px PNG). The owner creates the account and key; `LIVEAR_TILES_KEY` is injected at build time.
3. Upload keystore: the owner generates it locally with the JDK's `keytool` (README step) and keeps it and its passwords in a password manager, with an offline backup. Play App Signing holds the real app-signing key, so a lost upload key can be reset through Play support. The glasses APK on GitHub has no such reset: a lost key forces users to uninstall before they can update.
4. Contact email: **d.kanhar@gmail.com**.
5. Public repo hygiene: no device serials, adb names or MACs in tracked files. History was scrubbed before the first push.
