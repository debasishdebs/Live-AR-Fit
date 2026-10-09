# Live AR Fit — Production Release (sub-project B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the phone + Wear OS apps publishable on Google Play as one "Live AR Fit" listing and the Rokid glasses app publishable on GitHub Releases: new identity, signed R8-shrunk release builds, a hard 16 KB native gate, Play-policy-clean permissions and disclosures, MapTiler tiles with attribution, crash log, Room schema export, documents and CI.

**Architecture:** Pure logic first and JVM-tested: versioning, signing/tile-key resolution and release detection live in a new `buildSrc` (no AGP dependency) and are called from the three app build scripts; the 16 KB checker is a dependency-free Python script with synthesized-ELF fixtures; permission lists, the URI entry guard, the Wearable sender gate, the battery banner decision, the disclosure texts, the MapTiler source and the crash log are small pure Kotlin units in the module that owns them. Android code only wires those units into manifests, activities and Compose screens. Documents quote the disclosure texts verbatim.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, Gradle 8.13 (`buildSrc` with `kotlin-dsl`), JDK 17, kotlinx.coroutines 1.9.0, kotlinx.serialization 1.7.3, Compose BOM 2024.09.00, Wear Compose 1.4.0, Room 2.6.1 + KSP, Robolectric 4.13, Play Services Wearable 19.0.0, `androidx.wear:wear-remote-interactions:1.1.0` (now also on the watch), Rokid CXR-L `com.rokid.cxr:client-l:1.1.2` (latest stable release) and CXR-S `com.rokid.cxr:cxr-service-bridge:1.5` (latest release; same class list and public API as 1.4), Python 3 (stdlib only) for the 16 KB gate, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-10-09-production-release-design.md` (revision 3, approved, binding). Read it alongside this plan.

## Global Constraints

Copied from the spec; every task's requirements implicitly include these.

- **applicationId:** phone `com.livear.fit`, watch `com.livear.fit` ("must equal phone for Data Layer and one listing"), glasses `com.livear.fit.glasses`. **Label:** "Live AR Fit" for all three.
- **Kotlin packages stay `com.debasish.livefit.*`;** "the `namespace` stays as it is."
- **Glasses package vs activity:** the package is `com.livear.fit.glasses`; the fully qualified activity is `com.debasish.livefit.glasses.MainActivity`, "which stays in the retained namespace". Acceptance: "the merged release manifest's component matches the CXR configuration, and the renamed glasses app opens through CXR."
- **Version:** "`versionName` comes from a single `gradle.properties` key `livefit.version=1.0.0`, shared by all three apps." "`versionCode = major*1_000_000 + minor*10_000 + patch*100 + formFactor`, where formFactor is phone 0, watch 1 and glasses 2."
- **Labels** in `res/values/strings.xml` in each app; "a placeholder adaptive launcher icon (vector, plus a round one for Wear)".
- **Signing:** keystore outside the repo, referenced from an untracked `keystore.properties` or the env vars `LIVEAR_KEYSTORE`, `LIVEAR_KEY_ALIAS`, `LIVEAR_STORE_PASSWORD`, `LIVEAR_KEY_PASSWORD`. Phone, watch and glasses use the same upload key. "Without a keystore, a local release build is debug-signed and clearly named `*-unsigned-diagnostic`. It is never distributed." "The tag release job fails if the release credentials are missing." The glasses APK certificate "is checked against the expected upload-key fingerprint, stored as a CI variable."
- **R8:** "`isMinifyEnabled = true` and `isShrinkResources = true` for the phone, watch and glasses release builds"; keep rules in `proguard-rules.pro` for kotlinx.serialization, Room, `com.rokid.cxr.**`, and the reflective `com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper`; Health Services/Wearable/Media rules "only where the release smoke test shows breakage".
- **Logging:** "release builds strip `Log.v/d/i` via `-assumenosideeffects`. They keep `w/e`. User data stays out of `w/e` messages: no coordinates, no track titles."
- **16 KB (hard gate, no exceptions):** every `.so` in the phone and watch AABs and the glasses APK: "LOAD `p_align ≥ 0x4000`, and `(PT_GNU_RELRO.p_vaddr + p_memsz) % 0x4000 == 0`", with a pass/fail table; plus the runtime smoke test where "`adb shell getconf PAGE_SIZE` = 16384". "A 'documented misalignment' is not an acceptable outcome." "If Rokid can't supply aligned CXR libraries, the release is blocked."
- **Target SDK:** "phone and watch move to `targetSdk = 36`"; "The glasses app stays on its current target."
- **Removals:** "the unused `USE_FAKE_SERVICES` flag"; "the `LIVE_*` debug flags in release builds (they become constants)"; "`phone/src/debug` is already excluded from release".
- **Permissions:** `USE_FULL_SCREEN_INTENT` (watch) removed → Ongoing Activity; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (phone) removed → `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`; banner text **"Battery optimisation is on — LiveFit may stop tracking or lose the glasses/watch link when the screen is off."** with **Open settings**; Samsung: "Settings → Battery → Background usage limits → Never sleeping apps → add Live AR Fit"; `BODY_SENSORS` with `android:maxSdkVersion="35"` and `android.permission.health.READ_HEART_RATE` for API 36+ via one helper `requiredHealthPermissions(sdkInt)`; "No background-health permissions"; no `ACCESS_BACKGROUND_LOCATION`.
- **Disclosures:** notification listener: title, artist, playback state and up-next queue, "**sent to your paired watch and glasses**", glasses via "the Rokid CXR Bluetooth link", watch via "the Wear OS Data Layer, which is Bluetooth when nearby. When Bluetooth isn't available, Google Play services may relay the message through **Google's cloud, encrypted**", "Live AR Fit has no server of its own"; "Decline = music features off, everything else works." Location: "GPS is only used during workouts with 'Use GPS outdoors' on, never in the background when no workout is running." Microphone: "**Speech recognition stays on-device only**", "Audio is never uploaded."
- **FGS:** phone `connectedDevice|location`, watch `health|location`.
- **Exported components:** "Keep the `livefit://` remote-launch filters, including `BROWSABLE`"; "**a URI entry never authorises anything by itself**"; "Unknown paths and extras are ignored"; Wearable listeners "ignore messages whose source node doesn't advertise the peer capability (`livefit_phone`/`livefit_watch`)".
- **Backup:** "`android:allowBackup="false"` plus `dataExtractionRules` excluding everything".
- **Tiles:** MapTiler raster "streets-v2", 256 px PNG, Free plan; key `LIVEAR_TILES_KEY` from `local.properties`/env into BuildConfig; "A missing key means debug builds fall back to the OSM server … and release builds fail the build." User-Agent `LiveARFit/<version> (com.livear.fit; contact: d.kanhar@gmail.com)`. Attribution "the **MapTiler logo** plus the text "© MapTiler © OpenStreetMap contributors" on every map display"; watch "ⓘ Map data" sheet with maptiler.com/copyright and openstreetmap.org/copyright and "Open on phone".
- **Crash log:** app-private, "Phone Settings → About → 'Share last crash' opens a share sheet, so nothing is sent automatically. No third-party crash SDK."
- **Room:** "`exportSchema = true`, with the schema JSON committed for the v2 database and a migration test."
- **Licence Apache-2.0; contact d.kanhar@gmail.com.** "Public repo hygiene: no device serials, adb names or MACs in tracked files."
- Repo rules (existing, not from this spec): JDK 17 — prefix every Gradle command with `export JAVA_HOME=$(/usr/libexec/java_home -v 17) &&`; `:core:model`, `:core:map`, `:core:services`, `:services:sync`, `:services:workout` contain no `android.*` imports; install phone APKs with `--user 0`; device serials are written as `<phone-serial>`, `<watch-serial>`, `<glasses-serial>` placeholders only.

## Review Focus

Five conditions the spec implies but does not test, most likely to bite first; each is pinned by a test in its owning task.

1. **A CI secret that is unset expands to `""`, or `keystore.properties` is half filled** → the release build fails naming the missing value; it never silently produces a debug-signed "diagnostic" build that someone uploads. Pinned in Task 2 (`partialOrBlankCredentialsAreAnErrorNotADiagnosticBuild`, `nothingConfiguredIsADiagnosticBuild` with `requireSigning`).
2. **`LIVEAR_TILES_KEY` is blank (missing secret) — or only the glasses are being built** → a phone/watch release build fails instead of shipping a keyless MapTiler URL that 403s, while `:glasses:assembleRelease` (which configures the phone script too) does not demand the phone's key. Pinned in Task 2 (`blankTilesKeyIsMissing`, `anotherProjectsReleaseTaskDoesNotCount`).
3. **The MapTiler key is revoked or the Free plan quota is exhausted (401/403/429)** → maps show "No map — route only" and the fetcher backs off (no request storm against a metered key), and the key never appears in the User-Agent or `toString`. Pinned in Task 8 (`keyAndQuotaErrorsBackOffLikeAnyFailure`, `theKeyStaysOutOfTheUserAgentAndToString`).
4. **The phone (or watch) app was just reinstalled, or Play services fails one capability lookup** → the sender gate accepts the genuine peer on its next message after the capability propagates and does not cache a failure, so workout commands are not silently dropped. Pinned in Task 7 (`aNewlyAdvertisedPeerIsAcceptedAfterOneRefresh`, `aFailedLookupRejectsButCachesNothing`).
5. **The 16 KB gate is pointed at a wrong or empty output directory (renamed AAB, diagnostic name, failed build)** → it exits 2 with an error, never a vacuous "0 libraries, 0 failing" pass. Pinned in Task 3 (`test_missing_or_empty_artifact_path_is_an_error_not_a_pass`).

---

## Inspection results that shape this plan (2026-10-09)

- `CxrGlassesLink.kt:198` builds the activity as `"$GLASSES_PKG.MainActivity"` and `GlassAppWatcher.kt:30` uses `GLASSES_PKG` — both change in Task 1. `<queries>` (phone manifest :23-26) lists only Hi Rokid and YouTube Music — nothing to rename there. Deep links (`livefit://`) and Data Layer paths (`/lf/*`) are package-independent.
- `adb shell am broadcast -n com.debasish.livefit/.phone.DebugReceiver` (DebugReceiver.kt:16, :33) — after the rename the `.phone.X` shorthand would expand to `com.livear.fit.phone.X`, which doesn't exist; the component must be written fully: `com.livear.fit/com.debasish.livefit.phone.DebugReceiver`.
- `client-l:1.1.2` (latest stable; newer only `1.2.X-SNAPSHOT`) depends on `cxr-service-bridge:1.0-20260715.121510-107` — a **SNAPSHOT**. Task 2 pins the phone to the release `1.5`. `cxr-service-bridge` ships classes in `com.rokid.cxr` **and `com.rokid.cxrservice`**, and its AAR has an empty `proguard.txt`, so both packages need keep rules.
- The checker below, run on the vendor AARs: `cxr-service-bridge` 1.4 and the SNAPSHOT fail the RELRO-end check on all 5 arm64 libraries; **1.5 still fails 4 of 5** (`libcaps`, `libflora-cli`, `libmutils`, `libcxr-bridge-jni`; `libcxr-sock-proto-jni` passes). `androidx.graphics:graphics-path` **1.0.1 and 1.1.0 (newest stable) both fail** the RELRO-end check (LOAD alignment passes). So the gate is expected to block v1.0.0 until Rokid ships aligned libraries and the RELRO question for graphics-path is settled by the owner (see Task 3).
- `WatchRuntime.scope` is `Dispatchers.Main.immediate` and runs the recorder/controller, so every `FileDeltaBuffer` write and ack happens on the main thread; `GpsPreferences` reads its file in the constructor; `WatchClient.start()` loads `pages.json` on the caller's (main) thread; the glasses decode the map PNG inside `remember` on the main thread (MainActivity.kt:119-121).
- `HistoryDatabase` v1 → v2 (`MIGRATION_1_2`) exists; `exportSchema = false`. v1 had `session`, `delta`, `sample` (commit b1f1131); v2 adds `route_point`.
- The phone history thumbnail is route-only (no tiles, `RouteThumbnail`), but the spec requires the attribution there too; Task 8 adds it as specified.

## Decisions this plan makes where the spec is silent

- **`LIVE_*` flags** are already `static final boolean = true` in every build type (phone build.gradle.kts:19-22), so R8 folds the Fake branches; "become constants" needs no code change and Task 2 leaves them.
- **Release detection** is per project (`ReleaseGate.requested(taskNames, projectPath)`): unqualified `assembleRelease`/`assemble`/`bundle`/`build`/`check16kb` count for every app; `:glasses:assembleRelease` does not make the phone script demand a tile key. Gradle task-name abbreviations (`aR`) are not recognised — spell release tasks out.
- **Diagnostic naming** uses `base.archivesName` (`phone-unsigned-diagnostic-release.aab`), set only when a release task is requested without any keystore, so debug output names (used by `tools/install-all.sh`) never change.
- **Tag job** passes `-Plivefit.requireSigning=true`, which turns "no keystore" from a diagnostic build into an error.
- **32-bit libraries** are reported `SKIP` by the 16 KB checker (16 KB pages exist only on 64-bit devices); every 64-bit library must pass.
- **Watch thread model:** `WatchRuntime.scope` becomes a serial background dispatcher (`Dispatchers.IO.limitedParallelism(1)`), keeping the "single-threaded caller" assumption of recorder and controller while moving their file I/O off the main thread; a separate `uiScope` (Main) serves the Map page's tile loader. The one-time crash-recovery scan in `WatchSessionRecorder`'s constructor stays synchronous in `WatchRuntime.init` (every entry point needs the recovered state immediately); it is wrapped in `StrictMode.allowThreadDiskReads()` so it is the only, explicit exception.
- **SharedPreferences:** `apply()` writes are already asynchronous; `LiveFitApp.onCreate` calls `getSharedPreferences` for `settings`, `companion` and `rokid` first, which starts their background load before any main-thread read.
- **Disclosure UI:** one `DisclosureActivity` (dialog) serves Setup, Linked music and the Permissions list, so every route to notification access or the location prompt passes the disclosure. The watch shows its own short location disclosure before its location prompt.
- **Watch "settings/about page"** doesn't exist yet: a new About page opens from an ⓘ button on the watch Ready screen.
- **Crash logs** on watch and glasses are written the same way; only the phone has "Share last crash" (spec); README documents `adb shell run-as` retrieval for the other two.
- **Battery banner** lives at the top of Home (every app open lands there) and re-checks on every resume.
- **versionCode ignores a `-suffix`** (`0.2.0-beta` = `0.2.0`): never upload a beta and its release with the same numbers to Play (README states this).
- **Privacy policy URL:** `https://debasishdebs.github.io/Live-AR-Fit/privacy-policy.html` (GitHub Pages from `main` `/docs`); one constant `Disclosures.PRIVACY_POLICY_URL`.

---

## File structure (locked decomposition)

```
gradle.properties                                   (T1) livefit.version=1.0.0
buildSrc/build.gradle.kts                           (T1) kotlin-dsl + tests
buildSrc/src/main/kotlin/LiveFitVersion.kt          (T1) versionCode scheme
buildSrc/src/main/kotlin/ReleaseConfig.kt           (T2) SigningInput, SigningResolution, ReleaseSigning, ReleaseGate, TilesKey
buildSrc/src/test/kotlin/LiveFitVersionTest.kt      (T1)
buildSrc/src/test/kotlin/ReleaseConfigTest.kt       (T2)
build.gradle.kts                                    (T3) check16kb task
.gitignore                                          (T2) keystore files
phone|watch|glasses/build.gradle.kts                (T1 ids/version; T2 full release config)
phone|watch|glasses/proguard-rules.pro              (T2)
phone|watch|glasses/src/main/AndroidManifest.xml    (T1 label/icon/backup; phone T5/T6, watch T4)
phone|watch|glasses/src/main/res/{values/strings.xml, values/ic_launcher_background.xml, drawable/ic_launcher_foreground.xml,
    mipmap-anydpi-v26/ic_launcher.xml, mipmap-anydpi-v26/ic_launcher_round.xml, xml/data_extraction_rules.xml}   (T1)
phone|watch/src/main/res/drawable/maptiler_logo.xml (T8)
phone|watch|glasses/src/test/.../ManifestPolicyTest.kt (T1; phone extended T5/T6, watch extended T4)
services/glasses-link/build.gradle.kts              (T2) pin cxr-service-bridge 1.5
services/glasses-link/.../CxrGlassesLink.kt         (T1 package/activity; T2 log line)
services/glasses-link/src/test/.../GlassesIdentityTest.kt (T1)
core/model/build.gradle.kts                         (T7) coroutines-test
core/model/.../Disclosures.kt (+DisclosuresTest)    (T4)
core/model/.../PeerGate.kt (+PeerGateTest)          (T7)
core/map/.../Tiles.kt, core/map/src/test/.../TilesTest.kt (T8)
services/sync/.../GpsPreferences.kt, CrashLog.kt (+CrashLogTest, GpsPreferencesLazyTest)   (T9)
services/history/build.gradle.kts, HistoryDatabase.kt, schemas/…/2.json, HistoryMigrationTest.kt (T9)
watch: HealthPermissions.kt(+test), MainActivity.kt, HealthServicesExercise.kt, WatchFront.kt, ui/WatchApp.kt, ui/WatchOverlays.kt (T4);
       LaunchEntry.kt(+test), DiscoverableActivity.kt, PhoneCommandListener.kt (T7); ui/WatchAbout.kt (T6);
       map/WatchTiles.kt, ui/WatchMap.kt, ui/MapAttributionUi.kt, OpenOnPhone.kt (T8); WatchRuntime.kt, WatchClient.kt (T9)
phone: WatchListener.kt (T7); BatteryAdvice.kt(+test), ui/components/BatteryBanner.kt, ui/home/HomeScreen.kt (T5);
       setup/SetupScreen.kt, ui/list/sources/PermissionSource.kt (T5, T6); ui/DisclosureKind.kt(+test), ui/DisclosureActivity.kt,
       ui/linked/LinkedMusicScreen.kt, ui/settings/SimpleScreens.kt (T6); ServiceGraph.kt, map/GlassesMapRenderer.kt,
       ui/history/SessionDetailScreen.kt, ui/components/MapAttributionRow.kt (T8); LiveFitApp.kt, PhoneCrash.kt (T9)
glasses: hud/HudScreen.kt (T1); MainActivity.kt (T9)
tools/install-all.sh, tools/device-tests/offline.sh, phone/src/debug/** (T1)
tools/release/check_16kb.py, tools/release/test_check_16kb.py (T3); tools/release/test_workflows.py (T11); tools/release/test_docs.py (T10)
.github/workflows/ci.yml, .github/workflows/release.yml (T11)
README.md, LICENSE, THIRD_PARTY_NOTICES.md, CHANGELOG.md, docs/install-glasses.md, docs/privacy-policy.md, docs/play/*.md (T10)
```

## Task index and parallel lanes

| # | Task | Lane | Depends on |
|---|---|---|---|
| 1 | Identity, versioning, labels, icons, backup | Phase 0 (alone) | — |
| 2 | Release build: signing, R8, keep rules, log stripping, targetSdk 36, tile key, Rokid deps | A | 1 |
| 3 | 16 KB native gate (checker + Gradle task) | A | 2 |
| 4 | Watch permissions: OS-dependent heart rate, location disclosure, no full-screen intent | U | 1 |
| 5 | Phone battery-optimisation banner | U | 1 |
| 6 | Disclosures (music, location, mic) and in-app Privacy policy | U | 4, 5, 8 (`OpenOnPhone`), 9 (`PhoneCrash`) |
| 7 | Hardening: `livefit://` entry guard, Wearable sender gate | U | 4 |
| 8 | MapTiler tile source and attribution everywhere | M | 1, 2 (`BuildConfig.TILES_KEY`, watch dependency) |
| 9 | Robustness: I/O off the main thread, crash log, Room schema + migration test | R | 1 |
| 10 | Documents: README, install guide, LICENSE, notices, changelog, privacy policy, Play drafts | D | 4 (wording) |
| 11 | CI: `ci.yml` and `release.yml` | A | 3 |
| 12 | Release acceptance on devices (controller-run) | finish | all |

**Lanes** (Task 1 runs first and is merged before any lane starts; within a lane, top to bottom; a task waits for its cross-lane dependencies):
- **Lane A — build & release:** 2 → 3 → 11
- **Lane U — permissions, policy UX, hardening:** 4 → 7 → 5 → 6 (6 waits for 8 and 9)
- **Lane M — maps:** 8 (waits for 2)
- **Lane R — robustness:** 9
- **Lane D — docs:** 10 (waits for 4)
- **Finish:** 12 after every lane is merged.

**File ownership after Task 1** (each file in exactly one lane): Lane A owns `phone|watch|glasses/build.gradle.kts`, root `build.gradle.kts`, `buildSrc/**`, `*/proguard-rules.pro`, `.gitignore`, `services/glasses-link/build.gradle.kts`, `CxrGlassesLink.kt`, `tools/release/check_16kb.py`, `tools/release/test_check_16kb.py`, `tools/release/test_workflows.py`, `.github/**`. Lane U owns both app manifests of phone and watch (the glasses manifest is not touched after Task 1), every `ManifestPolicyTest.kt`, `core/model/build.gradle.kts`, `Disclosures.kt`, `PeerGate.kt`, and the watch files `HealthPermissions.kt`, `MainActivity.kt`, `HealthServicesExercise.kt`, `WatchFront.kt`, `LaunchEntry.kt`, `DiscoverableActivity.kt`, `PhoneCommandListener.kt`, `ui/WatchApp.kt`, `ui/WatchOverlays.kt`, `ui/WatchAbout.kt`, and the phone files `WatchListener.kt`, `BatteryAdvice.kt`, `ui/components/BatteryBanner.kt`, `ui/home/HomeScreen.kt`, `setup/SetupScreen.kt`, `ui/list/sources/PermissionSource.kt`, `ui/DisclosureKind.kt`, `ui/DisclosureActivity.kt`, `ui/linked/LinkedMusicScreen.kt`, `ui/settings/SimpleScreens.kt`. Lane M owns `core/map/**`, `ServiceGraph.kt`, `GlassesMapRenderer.kt`, `SessionDetailScreen.kt`, `ui/components/MapAttributionRow.kt`, `WatchTiles.kt`, `WatchMap.kt`, `MapAttributionUi.kt`, `OpenOnPhone.kt`, both `maptiler_logo.xml`. Lane R owns `services/sync/**` (`GpsPreferences.kt`, `CrashLog.kt` + tests), `services/history/**`, `WatchRuntime.kt`, `WatchClient.kt`, `LiveFitApp.kt`, `PhoneCrash.kt`, `glasses/.../MainActivity.kt`. Lane D owns `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `CHANGELOG.md`, `docs/install-glasses.md`, `docs/privacy-policy.md`, `docs/play/**`, `tools/release/test_docs.py`. Cross-lane needs are routed: the watch's `wear-remote-interactions` dependency (Task 8) and `BuildConfig.TILES_KEY` (Task 8) are added by Task 2; the About screen rows for crash sharing (Task 9's `PhoneCrash`) are added by Task 6.

---

## Phase 0

### Task 1: Identity, versioning, labels, icons, backup

**Files:**
- Modify: `gradle.properties:4` (append), `phone/build.gradle.kts:7-17`, `watch/build.gradle.kts:7-18`, `glasses/build.gradle.kts:7-16`
- Create: `buildSrc/build.gradle.kts`, `buildSrc/src/main/kotlin/LiveFitVersion.kt`, `buildSrc/src/test/kotlin/LiveFitVersionTest.kt`
- Modify: `phone/src/main/AndroidManifest.xml:28-31`, `watch/src/main/AndroidManifest.xml:25-27`, `glasses/src/main/AndroidManifest.xml:7-9`
- Create (in each of `phone/`, `watch/`, `glasses/` + `src/main/res/`): `values/strings.xml`, `values/ic_launcher_background.xml`, `drawable/ic_launcher_foreground.xml`, `mipmap-anydpi-v26/ic_launcher.xml`, `mipmap-anydpi-v26/ic_launcher_round.xml`, `xml/data_extraction_rules.xml`
- Modify: `services/glasses-link/src/main/kotlin/com/debasish/livefit/services/glasses/CxrGlassesLink.kt:198,314-316` (`GlassAppWatcher.kt:30` and `CxrGlassesLink.kt:192` keep using `GLASSES_PKG`, which now holds the new package)
- Modify: `phone/src/debug/java/com/debasish/livefit/phone/RokidLink.kt:32-33`, `phone/src/debug/java/com/debasish/livefit/phone/DebugReceiver.kt:16,33`, `tools/install-all.sh:25-26`, `tools/device-tests/offline.sh:13`
- Modify (user-visible "Rokid LiveFit" → "Live AR Fit"): `phone/.../setup/SetupScreen.kt:113,117`, `phone/.../ui/home/HomeScreen.kt:89`, `phone/.../ui/linked/LinkedWatchScreen.kt:63,72`, `phone/.../ui/settings/SettingsScreen.kt:145`, `phone/.../ui/settings/SimpleScreens.kt:50-51`, `phone/.../LiveFitHubService.kt:81`, `watch/.../ExerciseService.kt:38`, `watch/.../WatchFront.kt:65`, `glasses/.../hud/HudScreen.kt:203`
- Test: `buildSrc/src/test/kotlin/LiveFitVersionTest.kt`, `services/glasses-link/src/test/kotlin/com/debasish/livefit/services/glasses/GlassesIdentityTest.kt`, `phone/src/test/java/com/debasish/livefit/phone/ManifestPolicyTest.kt`, `watch/src/test/java/com/debasish/livefit/watch/ManifestPolicyTest.kt`, `glasses/src/test/java/com/debasish/livefit/glasses/ManifestPolicyTest.kt`

**Interfaces:**
- Produces: `object LiveFitVersion { enum class FormFactor(val digit: Int) { Phone(0), Watch(1), Glasses(2) }; fun code(versionName: String, formFactor: FormFactor): Int }` (default package, visible to every build script); Gradle property `livefit.version`; `CxrGlassesLink.GLASSES_PKG = "com.livear.fit.glasses"`, `CxrGlassesLink.GLASSES_ACTIVITY = "com.debasish.livefit.glasses.MainActivity"`; `@string/app_name`, `@mipmap/ic_launcher`, `@mipmap/ic_launcher_round`, `@xml/data_extraction_rules` in each app; the three `ManifestPolicyTest` classes (later tasks add cases).

- [ ] **Step 1: Write the failing tests**

`buildSrc/build.gradle.kts`:

```kotlin
plugins { `kotlin-dsl` }

repositories { mavenCentral() }

dependencies { testImplementation(kotlin("test")) }

tasks.test { useJUnitPlatform() }
```

`buildSrc/src/test/kotlin/LiveFitVersionTest.kt`:

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveFitVersionTest {
    @Test fun schemeGivesEachFormFactorItsOwnCode() {
        assertEquals(1_000_000, LiveFitVersion.code("1.0.0", LiveFitVersion.FormFactor.Phone))
        assertEquals(1_000_001, LiveFitVersion.code("1.0.0", LiveFitVersion.FormFactor.Watch))
        assertEquals(1_000_002, LiveFitVersion.code("1.0.0", LiveFitVersion.FormFactor.Glasses))
        assertEquals(2_134_501, LiveFitVersion.code("2.13.45", LiveFitVersion.FormFactor.Watch))
    }

    @Test fun preReleaseSuffixDoesNotChangeTheCode() =
        assertEquals(20_000, LiveFitVersion.code("0.2.0-beta", LiveFitVersion.FormFactor.Phone))

    @Test fun everyLaterVersionHasAHigherCode() {
        val ordered = listOf("0.1.0", "0.2.0", "0.2.1", "0.10.0", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
        val codes = ordered.map { LiveFitVersion.code(it, LiveFitVersion.FormFactor.Glasses) }
        assertEquals(codes.sorted(), codes)
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test fun malformedOrOutOfRangeVersionsFailTheBuild() {
        for (bad in listOf("1.0", "v1.0.0", "1.0.0.0", "1.100.0", "1.0.100", "2100.0.0", " 1.0.0", ""))
            assertFailsWith<IllegalArgumentException>(bad) { LiveFitVersion.code(bad, LiveFitVersion.FormFactor.Phone) }
    }
}
```

`services/glasses-link/src/test/kotlin/com/debasish/livefit/services/glasses/GlassesIdentityTest.kt`:

```kotlin
package com.debasish.livefit.services.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Spec §2 (review P1-3): after the rename the CXR package and the activity class are two separate values. */
class GlassesIdentityTest {
    @Test fun packageAndActivityAreSeparateValues() {
        assertEquals("com.livear.fit.glasses", CxrGlassesLink.GLASSES_PKG)
        assertEquals("com.debasish.livefit.glasses.MainActivity", CxrGlassesLink.GLASSES_ACTIVITY)
        assertFalse(CxrGlassesLink.GLASSES_ACTIVITY.startsWith(CxrGlassesLink.GLASSES_PKG + "."), "the activity keeps the retained namespace")
    }
}
```

`phone/src/test/java/com/debasish/livefit/phone/ManifestPolicyTest.kt` (the watch and glasses copies are identical except for the `package` line: `com.debasish.livefit.watch` / `com.debasish.livefit.glasses`):

```kotlin
package com.debasish.livefit.phone

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Play-policy guard on our own manifest (unit tests run with the module directory as working directory). */
class ManifestPolicyTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test fun labelAndIconComeFromResources() {
        assertTrue("android:label=\"@string/app_name\"" in manifest)
        assertTrue("android:icon=\"@mipmap/ic_launcher\"" in manifest)
        assertTrue("android:roundIcon=\"@mipmap/ic_launcher_round\"" in manifest)
    }

    /** Spec §4: tokens and the workout DB are never copied off the device (cloud backup or device transfer). */
    @Test fun backupIsOff() {
        assertTrue("android:allowBackup=\"false\"" in manifest)
        assertTrue("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew -p buildSrc test && ./gradlew :services:glasses-link:testDebugUnitTest :phone:testDebugUnitTest :watch:testDebugUnitTest :glasses:testDebugUnitTest --tests '*ManifestPolicyTest' --tests '*GlassesIdentityTest'`
Expected: FAIL — `Unresolved reference: LiveFitVersion`, `Unresolved reference: GLASSES_ACTIVITY`, and the manifest assertions.

- [ ] **Step 3: Implement**

`buildSrc/src/main/kotlin/LiveFitVersion.kt`:

```kotlin
/** Spec §2: one versionName for all three apps; versionCode = major*1_000_000 + minor*10_000 + patch*100 + formFactor. */
object LiveFitVersion {
    enum class FormFactor(val digit: Int) { Phone(0), Watch(1), Glasses(2) }

    private val SEMVER = Regex("""(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?""")

    fun code(versionName: String, formFactor: FormFactor): Int {
        val m = requireNotNull(SEMVER.matchEntire(versionName)) { "livefit.version '$versionName' must be MAJOR.MINOR.PATCH[-suffix]" }
        val (major, minor, patch) = m.groupValues.drop(1).take(3).map { it.toInt() }
        require(minor <= 99 && patch <= 99) { "livefit.version '$versionName': minor and patch must be 0..99" }
        require(major <= 2_099) { "livefit.version '$versionName': major must be <= 2099 (Play's versionCode limit)" }
        return major * 1_000_000 + minor * 10_000 + patch * 100 + formFactor.digit
    }
}
```

Append to `gradle.properties`:

```properties
# Spec §2: the one versionName of phone, watch and glasses (versionCode is derived per form factor).
livefit.version=1.0.0
```

`phone/build.gradle.kts` — before `android {` add `val liveFitVersion = providers.gradleProperty("livefit.version").get()` and replace lines 12-17 with:

```kotlin
        // Must match :watch so the Wearable Data Layer pairs the two apps (spec §2).
        applicationId = "com.livear.fit"
        minSdk = 29
        targetSdk = 35
        versionName = liveFitVersion
        versionCode = LiveFitVersion.code(liveFitVersion, LiveFitVersion.FormFactor.Phone)
```

`watch/build.gradle.kts` — same `val liveFitVersion = …` line, and lines 12-17 become the same block with `minSdk = 30` and `LiveFitVersion.FormFactor.Watch` (keep line 18 for now; Task 2 removes it). `glasses/build.gradle.kts` — same `val`, and lines 12-16 become:

```kotlin
        applicationId = "com.livear.fit.glasses"
        minSdk = 28
        targetSdk = 32
        versionName = liveFitVersion
        versionCode = LiveFitVersion.code(liveFitVersion, LiveFitVersion.FormFactor.Glasses)
```

Resources — create these six files with exactly this content in **each** of `phone/src/main/res/`, `watch/src/main/res/`, `glasses/src/main/res/`:

`values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Live AR Fit</string>
</resources>
```

`values/ic_launcher_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#FF0E1A17</color>
</resources>
```

`drawable/ic_launcher_foreground.xml` (placeholder heart in the 66 dp safe zone; the real logo is the separate brand task):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <group android:translateX="27" android:translateY="27" android:scaleX="2.25" android:scaleY="2.25">
        <path android:fillColor="#FF14C3A2"
            android:pathData="M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z" />
    </group>
</vector>
```

`mipmap-anydpi-v26/ic_launcher.xml` and `mipmap-anydpi-v26/ic_launcher_round.xml` (same content):

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

`xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Spec §4: nothing is backed up or transferred (tokens, the workout DB, settings). -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="root" path="." />
        <exclude domain="file" path="." />
        <exclude domain="database" path="." />
        <exclude domain="sharedpref" path="." />
        <exclude domain="external" path="." />
    </device-transfer>
</data-extraction-rules>
```

Manifests — the `<application` opening tag of each app gets these attributes (keep the existing `android:name`/`android:theme`; remove the old `android:label="Rokid LiveFit"`):

```xml
        android:label="@string/app_name"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
```

`CxrGlassesLink.kt` — companion (lines 314-316) becomes:

```kotlin
    companion object {
        const val TAG = "LiveFitGlassesLink"
        /** The glasses app's applicationId (spec §2). */
        const val GLASSES_PKG = "com.livear.fit.glasses"
        /** Its launcher activity keeps the retained Kotlin namespace — it is not "$GLASSES_PKG.MainActivity" (review P1-3). */
        const val GLASSES_ACTIVITY = "com.debasish.livefit.glasses.MainActivity"
```

and line 198 `glassesActivityName = "$GLASSES_PKG.MainActivity",` becomes `glassesActivityName = GLASSES_ACTIVITY,`.

Debug and tools: `RokidLink.kt:32` → `const val GLASSES_PKG = "com.livear.fit.glasses"` (line 33 unchanged). `DebugReceiver.kt:16` and `:33`: replace `-n com.debasish.livefit/.phone.DebugReceiver` with `-n com.livear.fit/com.debasish.livefit.phone.DebugReceiver` (the `.phone.X` shorthand would resolve against the new package). `tools/install-all.sh:25-26`: `com.debasish.livefit.glasses` → `com.livear.fit.glasses` (both lines). `tools/device-tests/offline.sh:13`: `run-as com.debasish.livefit` → `run-as com.livear.fit`.

User-visible text: in the files listed above replace every `Rokid LiveFit` with `Live AR Fit` (SetupScreen :113 "Welcome to Live AR Fit", :117; HomeScreen :89; LinkedWatchScreen :63 (both strings), :72; LiveFitHubService :81; ExerciseService :38; WatchFront :65; HudScreen :203 "Open Live AR Fit"). Version strings: `SettingsScreen.kt:145` subtitle becomes `"Live AR Fit ${BuildConfig.VERSION_NAME}"` and `SimpleScreens.kt:50-51` become:

```kotlin
        Text("Live AR Fit", style = MaterialTheme.typography.headlineMedium)
        Text("Version ${com.debasish.livefit.phone.BuildConfig.VERSION_NAME}", color = LiveFitColors.InkSoft)
```

- [ ] **Step 4: Run the tests and verify the merged manifests**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew -p buildSrc test && ./gradlew test :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug`
Expected: PASS (all existing tests too).

Run: `AAPT2=$(ls -d $ANDROID_HOME/build-tools/*/ | sort -V | tail -1)aapt2; for a in phone watch glasses; do "$AAPT2" dump badging $a/build/outputs/apk/debug/$a-debug.apk | grep -E "^package:|launchable-activity|application-label:"; done`
Expected: phone and watch `package: name='com.livear.fit' versionCode='1000000'` / `'1000001'` `versionName='1.0.0'`; glasses `name='com.livear.fit.glasses' versionCode='1000002'` and `launchable-activity: name='com.debasish.livefit.glasses.MainActivity'` (= `CxrGlassesLink.GLASSES_ACTIVITY`); every `application-label:'Live AR Fit'`.

Run: `git grep -n "Rokid LiveFit\|com\.debasish\.livefit/\.\|run-as com\.debasish\|pm grant com\.debasish" -- phone watch glasses services tools`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add gradle.properties buildSrc phone watch glasses services/glasses-link tools
git commit -m "Identity: com.livear.fit / com.livear.fit.glasses, Live AR Fit labels and icons, versionCode scheme, backup off

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Lane A — build & release

### Task 2: Release build — signing, R8, keep rules, log stripping, targetSdk 36, tile key, Rokid deps

**Files:**
- Create: `buildSrc/src/main/kotlin/ReleaseConfig.kt`, `buildSrc/src/test/kotlin/ReleaseConfigTest.kt`
- Modify (full replacement shown): `phone/build.gradle.kts`, `watch/build.gradle.kts`, `glasses/build.gradle.kts`
- Create: `phone/proguard-rules.pro`, `watch/proguard-rules.pro`, `glasses/proguard-rules.pro`
- Modify: `services/glasses-link/build.gradle.kts:23-24`, `services/glasses-link/.../CxrGlassesLink.kt:233`, `.gitignore` (append)

**Interfaces:**
- Consumes: `LiveFitVersion` (Task 1).
- Produces: `data class SigningInput(storeFile, keyAlias, storePassword, keyPassword: String)`; `sealed interface SigningResolution { Release(input); Diagnostic; Misconfigured(missing: List<String>) }`; `ReleaseSigning.KEYS`, `ReleaseSigning.resolve(properties: Map<String,String>, env: Map<String,String>): SigningResolution`, `ReleaseSigning.problem(resolution, requireSigning: Boolean): String?`; `ReleaseGate.requested(taskNames: List<String>, projectPath: String): Boolean`; `TilesKey.NAME = "LIVEAR_TILES_KEY"`, `TilesKey.resolve(localProperties, env): String?`; **`BuildConfig.TILES_KEY: String`** in phone and watch (`""` = none → OSM in debug); Gradle property `livefit.requireSigning=true`; release outputs `phone/build/outputs/bundle/release/phone-release.aab`, `watch/build/outputs/bundle/release/watch-release.aab`, `glasses/build/outputs/apk/release/glasses-release.apk` (with `-unsigned-diagnostic` inserted after the module name when no keystore); watch dependency `androidx.wear:wear-remote-interactions:1.1.0` (for Task 8).

- [ ] **Step 1: Write the failing test** — `buildSrc/src/test/kotlin/ReleaseConfigTest.kt`:

```kotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseConfigTest {
    private val all = mapOf("LIVEAR_KEYSTORE" to "/k/upload.jks", "LIVEAR_KEY_ALIAS" to "upload", "LIVEAR_STORE_PASSWORD" to "s", "LIVEAR_KEY_PASSWORD" to "k")

    @Test fun propertiesOrEnvGiveARelease() {
        assertEquals(SigningResolution.Release(SigningInput("/k/upload.jks", "upload", "s", "k")), ReleaseSigning.resolve(all, emptyMap()))
        assertEquals(SigningResolution.Release(SigningInput("/k/upload.jks", "upload", "s", "k")), ReleaseSigning.resolve(emptyMap(), all))
    }

    @Test fun envWinsOverTheFile() {
        val r = ReleaseSigning.resolve(all, mapOf("LIVEAR_KEY_ALIAS" to "ci"))
        assertEquals("ci", (r as SigningResolution.Release).input.keyAlias)
    }

    @Test fun nothingConfiguredIsADiagnosticBuild() {
        assertEquals(SigningResolution.Diagnostic, ReleaseSigning.resolve(emptyMap(), emptyMap()))
        assertNull(ReleaseSigning.problem(SigningResolution.Diagnostic, requireSigning = false))
        assertNotNull(ReleaseSigning.problem(SigningResolution.Diagnostic, requireSigning = true), "the tag job never ships a diagnostic build")
    }

    /** Review focus 1: a CI secret that expands to "" (or a half-filled keystore.properties) fails loudly. */
    @Test fun partialOrBlankCredentialsAreAnErrorNotADiagnosticBuild() {
        assertEquals(SigningResolution.Misconfigured(listOf("LIVEAR_KEY_PASSWORD")), ReleaseSigning.resolve(all - "LIVEAR_KEY_PASSWORD", emptyMap()))
        val blank = ReleaseSigning.resolve(mapOf("LIVEAR_KEYSTORE" to "/k", "LIVEAR_KEY_ALIAS" to "a"), mapOf("LIVEAR_STORE_PASSWORD" to " "))
        assertEquals(SigningResolution.Misconfigured(listOf("LIVEAR_STORE_PASSWORD", "LIVEAR_KEY_PASSWORD")), blank)
        val p = assertNotNull(ReleaseSigning.problem(blank, requireSigning = false), "fails even without -Plivefit.requireSigning")
        assertTrue("LIVEAR_STORE_PASSWORD" in p)
    }

    @Test fun releaseTasksAreRecognisedPerProject() {
        assertTrue(ReleaseGate.requested(listOf(":phone:bundleRelease"), ":phone"))
        assertTrue(ReleaseGate.requested(listOf("assembleRelease"), ":watch"), "unqualified = every project")
        assertTrue(ReleaseGate.requested(listOf("assemble"), ":phone"))
        assertTrue(ReleaseGate.requested(listOf(":check16kb"), ":watch"))
        assertTrue(ReleaseGate.requested(listOf("check16kb"), ":phone"))
        assertFalse(ReleaseGate.requested(listOf("test", ":phone:assembleDebug", ":glasses:installDebug"), ":phone"))
    }

    /** Review focus 2: `:glasses:assembleRelease` configures the phone script too; it must not ask for the phone's tile key. */
    @Test fun anotherProjectsReleaseTaskDoesNotCount() {
        assertFalse(ReleaseGate.requested(listOf(":glasses:assembleRelease"), ":phone"))
        assertTrue(ReleaseGate.requested(listOf(":glasses:assembleRelease"), ":glasses"))
    }

    @Test fun tilesKeyFromEnvOrLocalProperties() {
        assertEquals("abcDEF123_-", TilesKey.resolve(emptyMap(), mapOf("LIVEAR_TILES_KEY" to " abcDEF123_- ")))
        assertEquals("fromFile", TilesKey.resolve(mapOf("LIVEAR_TILES_KEY" to "fromFile"), emptyMap()))
        assertEquals("env", TilesKey.resolve(mapOf("LIVEAR_TILES_KEY" to "fromFile"), mapOf("LIVEAR_TILES_KEY" to "env")))
    }

    /** Review focus 2: an unset CI secret expands to an empty string; that is "missing", so a release build fails. */
    @Test fun blankTilesKeyIsMissing() {
        assertNull(TilesKey.resolve(emptyMap(), mapOf("LIVEAR_TILES_KEY" to "")))
        assertNull(TilesKey.resolve(mapOf("LIVEAR_TILES_KEY" to "   "), emptyMap()))
        assertNull(TilesKey.resolve(emptyMap(), emptyMap()))
    }

    @Test fun aKeyThatWouldBreakBuildConfigIsRejected() {
        assertFailsWith<IllegalArgumentException> { TilesKey.resolve(emptyMap(), mapOf("LIVEAR_TILES_KEY" to "ab\"c")) }
        assertFailsWith<IllegalArgumentException> { TilesKey.resolve(emptyMap(), mapOf("LIVEAR_TILES_KEY" to "a b")) }
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew -p buildSrc test`
Expected: FAIL — `Unresolved reference: ReleaseSigning` (and the others).

- [ ] **Step 3: Implement** — `buildSrc/src/main/kotlin/ReleaseConfig.kt`:

```kotlin
/** Release signing inputs (spec §3): keystore.properties or env vars, env winning; blank counts as missing. */
data class SigningInput(val storeFile: String, val keyAlias: String, val storePassword: String, val keyPassword: String)

sealed interface SigningResolution {
    data class Release(val input: SigningInput) : SigningResolution
    /** Nothing configured: a local release build is debug-signed and named `*-unsigned-diagnostic`. */
    data object Diagnostic : SigningResolution
    /** Some but not all values set: always an error, never a silent diagnostic build. */
    data class Misconfigured(val missing: List<String>) : SigningResolution
}

object ReleaseSigning {
    val KEYS = listOf("LIVEAR_KEYSTORE", "LIVEAR_KEY_ALIAS", "LIVEAR_STORE_PASSWORD", "LIVEAR_KEY_PASSWORD")

    fun resolve(properties: Map<String, String>, env: Map<String, String>): SigningResolution {
        val values = KEYS.associateWith { k -> env[k]?.takeIf { it.isNotBlank() } ?: properties[k]?.takeIf { it.isNotBlank() } }
        val missing = values.filterValues { it == null }.keys.toList()
        return when (missing.size) {
            0 -> SigningResolution.Release(SigningInput(values.getValue(KEYS[0])!!, values.getValue(KEYS[1])!!, values.getValue(KEYS[2])!!, values.getValue(KEYS[3])!!))
            KEYS.size -> SigningResolution.Diagnostic
            else -> SigningResolution.Misconfigured(missing)
        }
    }

    /** Null when the build may go ahead; otherwise the error a requested release build must fail with. */
    fun problem(resolution: SigningResolution, requireSigning: Boolean): String? = when (resolution) {
        is SigningResolution.Release -> null
        is SigningResolution.Misconfigured -> "release signing is incomplete: ${resolution.missing.joinToString()} not set (keystore.properties or env)"
        SigningResolution.Diagnostic -> if (requireSigning) "release signing required (-Plivefit.requireSigning) but no keystore is configured" else null
    }
}

/**
 * True when the requested tasks build a release artifact of [projectPath] (so its release-only inputs must be present).
 * Every project's script is configured on each run, so `:glasses:assembleRelease` must not demand the phone's tile key:
 * a task qualified with another project's path doesn't count; unqualified names (`assembleRelease`, `assemble`) count for all.
 */
object ReleaseGate {
    private val AGGREGATES = setOf("assemble", "bundle", "build", "check16kb")

    fun requested(taskNames: List<String>, projectPath: String): Boolean = taskNames.any { t ->
        val owner = t.substringBeforeLast(':', missingDelimiterValue = "")
        if (owner.isNotEmpty() && owner != projectPath) return@any false
        val name = t.substringAfterLast(':')
        name.contains("Release") || name in AGGREGATES
    }
}

/** MapTiler key (spec §5): `LIVEAR_TILES_KEY` from env or local.properties; blank = missing; only [A-Za-z0-9_-] accepted. */
object TilesKey {
    const val NAME = "LIVEAR_TILES_KEY"
    private val VALID = Regex("[A-Za-z0-9_-]+")

    fun resolve(localProperties: Map<String, String>, env: Map<String, String>): String? {
        val raw = (env[NAME]?.takeIf { it.isNotBlank() } ?: localProperties[NAME]?.takeIf { it.isNotBlank() })?.trim() ?: return null
        require(VALID.matches(raw)) { "$NAME contains characters other than letters, digits, '_' and '-'" }
        return raw
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew -p buildSrc test`
Expected: PASS (13 tests incl. Task 1's).

- [ ] **Step 5: Replace the three app build files and add the keep rules**

`phone/build.gradle.kts` (whole file):

```kotlin
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun rootProperties(name: String): Map<String, String> = Properties().apply {
    rootProject.file(name).takeIf { it.isFile }?.inputStream()?.use { load(it) }
}.entries.associate { (k, v) -> k.toString() to v.toString() }

val liveFitVersion = providers.gradleProperty("livefit.version").get()
val releaseRequested = ReleaseGate.requested(gradle.startParameter.taskNames, path)
val signing = ReleaseSigning.resolve(rootProperties("keystore.properties"), System.getenv())
val tilesKey = TilesKey.resolve(rootProperties("local.properties"), System.getenv())
if (releaseRequested) {
    ReleaseSigning.problem(signing, providers.gradleProperty("livefit.requireSigning").orNull == "true")?.let { throw GradleException(it) }
    if (tilesKey == null) throw GradleException("${TilesKey.NAME} is required for release builds (local.properties or env) — spec §5")
}
base.archivesName.set(if (releaseRequested && signing is SigningResolution.Diagnostic) "phone-unsigned-diagnostic" else "phone")

android {
    namespace = "com.debasish.livefit.phone"
    compileSdk = 36

    defaultConfig {
        // Must match :watch so the Wearable Data Layer pairs the two apps (spec §2).
        applicationId = "com.livear.fit"
        minSdk = 29
        targetSdk = 36
        versionName = liveFitVersion
        versionCode = LiveFitVersion.code(liveFitVersion, LiveFitVersion.FormFactor.Phone)
        // MapTiler key (spec §5); "" = none: debug builds use the OSM server, release builds never get this far.
        buildConfigField("String", "TILES_KEY", "\"${tilesKey.orEmpty()}\"")
        // Compile-time constants (spec §3 "they become constants"): R8 folds the Fake branches away.
        buildConfigField("boolean", "LIVE_WATCH", "true")
        buildConfigField("boolean", "LIVE_GLASSES", "true")
        buildConfigField("boolean", "LIVE_MUSIC", "true")
        buildConfigField("boolean", "LIVE_VOICE", "true")
    }
    signingConfigs {
        create("release") {
            (signing as? SigningResolution.Release)?.input?.let { s ->
                storeFile = file(s.storeFile)
                storePassword = s.storePassword
                keyAlias = s.keyAlias
                keyPassword = s.keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // No keystore: debug-signed, named *-unsigned-diagnostic, never distributed (spec §3).
            signingConfig = signingConfigs.getByName(if (signing is SigningResolution.Release) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation(project(":core:services"))
    implementation(project(":services:workout"))
    implementation(project(":services:sync"))
    implementation(project(":services:confirm"))
    implementation(project(":services:history"))
    implementation(project(":services:glasses-link"))
    implementation(project(":services:watch-link"))
    implementation(project(":services:music"))
    implementation(project(":services:voice"))
    implementation(project(":services:voice-android"))
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.rokid.cxr:client-l:1.1.2")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
}
```

If the Kotlin DSL reports `base` as unresolved, write the archives line as `extensions.getByType<BasePluginExtension>().archivesName.set(…)` (same value) in all three files.

`watch/build.gradle.kts` (whole file):

```kotlin
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun rootProperties(name: String): Map<String, String> = Properties().apply {
    rootProject.file(name).takeIf { it.isFile }?.inputStream()?.use { load(it) }
}.entries.associate { (k, v) -> k.toString() to v.toString() }

val liveFitVersion = providers.gradleProperty("livefit.version").get()
val releaseRequested = ReleaseGate.requested(gradle.startParameter.taskNames, path)
val signing = ReleaseSigning.resolve(rootProperties("keystore.properties"), System.getenv())
val tilesKey = TilesKey.resolve(rootProperties("local.properties"), System.getenv())
if (releaseRequested) {
    ReleaseSigning.problem(signing, providers.gradleProperty("livefit.requireSigning").orNull == "true")?.let { throw GradleException(it) }
    if (tilesKey == null) throw GradleException("${TilesKey.NAME} is required for release builds (local.properties or env) — spec §5")
}
base.archivesName.set(if (releaseRequested && signing is SigningResolution.Diagnostic) "watch-unsigned-diagnostic" else "watch")

android {
    namespace = "com.debasish.livefit.watch"
    compileSdk = 36

    defaultConfig {
        // Must match :phone so the Wearable Data Layer pairs the two apps (spec §2).
        applicationId = "com.livear.fit"
        minSdk = 30
        targetSdk = 36
        versionName = liveFitVersion
        versionCode = LiveFitVersion.code(liveFitVersion, LiveFitVersion.FormFactor.Watch)
        buildConfigField("String", "TILES_KEY", "\"${tilesKey.orEmpty()}\"")
    }
    signingConfigs {
        create("release") {
            (signing as? SigningResolution.Release)?.input?.let { s ->
                storeFile = file(s.storeFile)
                storePassword = s.storePassword
                keyAlias = s.keyAlias
                keyPassword = s.keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (signing is SigningResolution.Release) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(project(":services:sync"))
    implementation(project(":services:music"))
    implementation("androidx.wear:wear-ongoing:1.0.0")
    implementation("androidx.wear:wear:1.3.0") // AmbientLifecycleObserver (B1: workout screen on AOD)
    implementation("androidx.wear:wear-remote-interactions:1.1.0") // "Open on phone" (spec §5, §7)
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

`glasses/build.gradle.kts` (whole file; no tile key — the glasses draw no tiles):

```kotlin
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun rootProperties(name: String): Map<String, String> = Properties().apply {
    rootProject.file(name).takeIf { it.isFile }?.inputStream()?.use { load(it) }
}.entries.associate { (k, v) -> k.toString() to v.toString() }

val liveFitVersion = providers.gradleProperty("livefit.version").get()
val releaseRequested = ReleaseGate.requested(gradle.startParameter.taskNames, path)
val signing = ReleaseSigning.resolve(rootProperties("keystore.properties"), System.getenv())
if (releaseRequested) {
    ReleaseSigning.problem(signing, providers.gradleProperty("livefit.requireSigning").orNull == "true")?.let { throw GradleException(it) }
}
base.archivesName.set(if (releaseRequested && signing is SigningResolution.Diagnostic) "glasses-unsigned-diagnostic" else "glasses")

android {
    namespace = "com.debasish.livefit.glasses"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.livear.fit.glasses"
        minSdk = 28
        targetSdk = 32 // Rokid OS is fixed (spec §3)
        versionName = liveFitVersion
        versionCode = LiveFitVersion.code(liveFitVersion, LiveFitVersion.FormFactor.Glasses)
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("release") {
            (signing as? SigningResolution.Release)?.input?.let { s ->
                storeFile = file(s.storeFile)
                storePassword = s.storePassword
                keyAlias = s.keyAlias
                keyPassword = s.keyPassword
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same upload key as phone/watch, so GitHub updates install over each other (spec §3).
            signingConfig = signingConfigs.getByName(if (signing is SigningResolution.Release) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":services:sync"))
    implementation(project(":services:voice"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.rokid.cxr:cxr-service-bridge:1.5") // newest release; same classes/API as 1.4
    implementation("androidx.core:core-ktx:1.13.1")
}
```

`services/glasses-link/build.gradle.kts` — after line 24 (`implementation("com.rokid.cxr:client-l:1.1.2")`) add:

```kotlin
    // client-l 1.1.2 (latest stable) depends on a cxr-service-bridge SNAPSHOT; pin the newest release instead (spec §3).
    implementation("com.rokid.cxr:cxr-service-bridge:1.5")
```

`phone/proguard-rules.pro`:

```proguard
# ---- kotlinx.serialization: wire and settings types in :core:model and :services:* (spec §3) ----
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature,EnclosingMethod
-keep,includedescriptorclasses class com.debasish.livefit.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.debasish.livefit.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.debasish.livefit.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**

# ---- Room: database, entities and DAOs (Room's consumer rules cover the generated code) ----
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.debasish.livefit.history.HistoryDatabase_Impl { *; }
-keep @androidx.room.Entity class com.debasish.livefit.history.** { *; }
-keep @androidx.room.Dao interface com.debasish.livefit.history.** { *; }

# ---- Rokid CXR-L / CXR bridge (JNI + AIDL + Gson models) ----
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.cxrservice.** { *; }
-dontwarn com.rokid.**
# CxrGlassesLink.preferGlobalHiRokid() writes the private field "a" by reflection: name and field must survive.
-keep class com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---- Release logging: strip v/d/i, keep w/e (spec §3) ----
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
```

`watch/proguard-rules.pro` — the **kotlinx.serialization** block and the **Release logging** block above, verbatim (the watch has no Room and no CXR).

`glasses/proguard-rules.pro` — the **kotlinx.serialization** block, this CXR block, and the **Release logging** block:

```proguard
# ---- Rokid CXR-S bridge (JNI) ----
-keep class com.rokid.cxr.** { *; }
-keep class com.rokid.cxrservice.** { *; }
-dontwarn com.rokid.**
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
```

`CxrGlassesLink.kt:233` — a decode failure must not log the payload (kotlinx exceptions quote the JSON, which can hold track titles):

```kotlin
            Log.w(TAG, "bad payload on $cmd: ${e.javaClass.simpleName}"); null
```

Append to `.gitignore`:

```
### Release signing (spec §3: the keystore lives outside the repo) ###
keystore.properties
*.jks
*.keystore
```

- [ ] **Step 6: Verify the release builds**

Run (no key at all): `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && env -u LIVEAR_TILES_KEY ./gradlew :phone:assembleRelease 2>&1 | grep -m1 "LIVEAR_TILES_KEY is required"`
Expected: the error line (build fails).

Run (glasses only, no key): `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && env -u LIVEAR_TILES_KEY ./gradlew :glasses:assembleRelease && ls glasses/build/outputs/apk/release/`
Expected: BUILD SUCCESSFUL; `glasses-unsigned-diagnostic-release.apk` (no keystore configured).

Run (dummy key, no keystore): `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && LIVEAR_TILES_KEY=dummy ./gradlew :phone:bundleRelease :watch:bundleRelease :phone:assembleRelease && ls phone/build/outputs/bundle/release watch/build/outputs/bundle/release && ls phone/build/outputs/mapping/release/mapping.txt`
Expected: `phone-unsigned-diagnostic-release.aab`, `watch-unsigned-diagnostic-release.aab`, a mapping file. Then `$(ls -d $ANDROID_HOME/build-tools/*/ | sort -V | tail -1)apksigner verify --print-certs phone/build/outputs/apk/release/phone-unsigned-diagnostic-release.apk | grep "DN:"` shows `CN=Android Debug`.

Run (tag-job mode): `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && LIVEAR_TILES_KEY=dummy ./gradlew -Plivefit.requireSigning=true :glasses:assembleRelease 2>&1 | grep -m1 "release signing required"`
Expected: the error line.

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug && ls phone/build/outputs/apk/debug/phone-debug.apk`
Expected: PASS; debug output names unchanged.

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:dependencies --configuration releaseRuntimeClasspath | grep "cxr-service-bridge"`
Expected: resolves to `1.5` (no SNAPSHOT).

- [ ] **Step 7: Commit**

```bash
git add buildSrc phone/build.gradle.kts watch/build.gradle.kts glasses/build.gradle.kts phone/proguard-rules.pro watch/proguard-rules.pro glasses/proguard-rules.pro services/glasses-link .gitignore
git commit -m "Release build: owner signing with diagnostic fallback, R8 + keep rules, log stripping, targetSdk 36, MapTiler key gate, cxr-service-bridge 1.5

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 3: 16 KB native gate

**Files:**
- Create: `tools/release/check_16kb.py`, `tools/release/test_check_16kb.py`
- Modify: `build.gradle.kts` (append `check16kb` task)

**Interfaces:**
- Consumes: release output directories from Task 2.
- Produces: `python3 tools/release/check_16kb.py <aab|apk|so|dir>...` → table of `PASS`/`FAIL`/`SKIP` rows + `"<n> libraries, <m> failing"`; exit 0 all pass, 1 any FAIL, 2 usage/I/O error or no artifact; Gradle task `:check16kb` (used by Task 11 and Task 12).

- [ ] **Step 1: Write the failing test** — `tools/release/test_check_16kb.py`:

```python
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(__file__))
import check_16kb  # noqa: E402

PT_LOAD, PT_GNU_RELRO = 1, 0x6474E552
SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "check_16kb.py")


def elf64(segments):
    """A minimal little-endian ELF64 shared object with the given (p_type, p_vaddr, p_memsz, p_align) headers."""
    phoff, phentsize = 64, 56
    header = b"\x7fELF" + bytes([2, 1, 1]) + bytes(9)
    header += struct.pack("<HHIQQQIHHHHHH", 3, 183, 1, 0, phoff, 0, 0, 64, phentsize, len(segments), 0, 0, 0)
    body = b"".join(struct.pack("<IIQQQQQQ", t, 4, 0, v, v, m, m, a) for t, v, m, a in segments)
    return header + body


# Known-good and known-bad fixtures (spec §9): synthesized so the repo carries no vendor binaries.
GOOD = elf64([(PT_LOAD, 0, 0x5000, 0x4000), (PT_LOAD, 0x8000, 0x2000, 0x4000), (PT_GNU_RELRO, 0x8000, 0x4000, 1)])
BAD_LOAD = elf64([(PT_LOAD, 0, 0x5000, 0x1000)])
BAD_RELRO = elf64([(PT_LOAD, 0, 0x5000, 0x4000), (PT_GNU_RELRO, 0x8000, 0x1a00, 1)])


def run(*args):
    return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True)


class Check16kbTest(unittest.TestCase):
    def test_good_library_passes(self):
        self.assertEqual(("PASS", ""), check_16kb.check_elf(GOOD))

    def test_4k_load_alignment_fails(self):
        status, detail = check_16kb.check_elf(BAD_LOAD)
        self.assertEqual("FAIL", status)
        self.assertIn("p_align", detail)

    def test_unaligned_relro_end_fails_even_with_16k_loads(self):
        status, detail = check_16kb.check_elf(BAD_RELRO)
        self.assertEqual("FAIL", status)
        self.assertIn("RELRO", detail)

    def test_32bit_is_skipped_and_garbage_fails(self):
        self.assertEqual("SKIP", check_16kb.check_elf(b"\x7fELF" + bytes([1]) + bytes(59))[0])
        self.assertEqual("FAIL", check_16kb.check_elf(b"not an elf")[0])
        self.assertEqual("FAIL", check_16kb.check_elf(GOOD[:80])[0], "truncated headers")

    def test_archive_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            good, bad = os.path.join(d, "good.apk"), os.path.join(d, "bad.aab")
            with zipfile.ZipFile(good, "w") as z:
                z.writestr("lib/arm64-v8a/libgood.so", GOOD)
                z.writestr("classes.dex", b"dex")
            with zipfile.ZipFile(bad, "w") as z:
                z.writestr("base/lib/arm64-v8a/libgood.so", GOOD)
                z.writestr("base/lib/arm64-v8a/libbad.so", BAD_RELRO)
            ok = run(good)
            self.assertEqual(0, ok.returncode, ok.stdout)
            ko = run(good, bad)
            self.assertEqual(1, ko.returncode)
            self.assertIn("FAIL  " + bad + "!base/lib/arm64-v8a/libbad.so", ko.stdout)
            self.assertIn("1 failing", ko.stdout)
            self.assertEqual(2, run().returncode)

    def test_directory_argument_scans_its_archives(self):
        with tempfile.TemporaryDirectory() as d:
            with zipfile.ZipFile(os.path.join(d, "app-release.aab"), "w") as z:
                z.writestr("base/lib/arm64-v8a/libbad.so", BAD_LOAD)
            with open(os.path.join(d, "output-metadata.json"), "w") as f:
                f.write("{}")
            r = run(d)
            self.assertEqual(1, r.returncode, r.stdout)
            self.assertIn("libbad.so", r.stdout)

    def test_missing_or_empty_artifact_path_is_an_error_not_a_pass(self):
        """Review focus 5: a wrong output path must never pass the hard gate with '0 libraries'."""
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(2, run(d).returncode, "empty directory")
            self.assertEqual(2, run(os.path.join(d, "nope.aab")).returncode, "missing file")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python3 -m unittest discover -s tools/release -p 'test_check_16kb.py' -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'check_16kb'`.

- [ ] **Step 3: Implement** — `tools/release/check_16kb.py` (make it executable: `chmod +x`):

```python
#!/usr/bin/env python3
"""16 KB page-size gate (spec §3, hard gate). Every 64-bit .so inside the given AAB/APK/.so files (a directory stands
for the .aab/.apk files in it) must have every LOAD p_align >= 0x4000 and, if it has a PT_GNU_RELRO segment, a RELRO
end (p_vaddr + p_memsz) that is a multiple of 0x4000. 32-bit libraries are listed as SKIP (no 16 KB devices).
Prints a pass/fail table. Exit 0 = all pass, 1 = a library fails, 2 = usage or I/O error (including no artifact)."""
import os
import struct
import sys
import zipfile

PAGE = 0x4000
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552


def check_elf(data):
    """Returns (status, detail): status is PASS, FAIL or SKIP (32-bit: no 16 KB devices)."""
    if len(data) < 64 or data[:4] != b"\x7fELF":
        return "FAIL", "not an ELF file"
    if data[4] != 2:
        return "SKIP", "32-bit"
    endian = "<" if data[5] == 1 else ">"
    phoff, = struct.unpack_from(endian + "Q", data, 0x20)
    phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    problems = []
    loads = 0
    for i in range(phnum):
        off = phoff + i * phentsize
        if off + 56 > len(data):
            return "FAIL", "truncated program headers"
        p_type, _flags, _offset, p_vaddr, _paddr, _filesz, p_memsz, p_align = struct.unpack_from(endian + "IIQQQQQQ", data, off)
        if p_type == PT_LOAD:
            loads += 1
            if p_align < PAGE:
                problems.append("LOAD p_align 0x%x < 0x4000" % p_align)
        elif p_type == PT_GNU_RELRO and (p_vaddr + p_memsz) % PAGE != 0:
            problems.append("RELRO end 0x%x not 16 KB aligned" % (p_vaddr + p_memsz))
    if loads == 0:
        problems.append("no LOAD segment")
    return ("FAIL", "; ".join(problems)) if problems else ("PASS", "")


def libraries(path):
    """(name, bytes) for a bare .so, or every .so inside an AAB/APK (zip)."""
    if path.endswith(".so"):
        with open(path, "rb") as f:
            yield path, f.read()
        return
    with zipfile.ZipFile(path) as z:
        for n in sorted(z.namelist()):
            if n.endswith(".so"):
                yield "%s!%s" % (path, n), z.read(n)


def artifacts(path):
    """A directory stands for the .aab/.apk files directly inside it; a missing path or an empty directory is an error."""
    if os.path.isdir(path):
        found = sorted(os.path.join(path, n) for n in os.listdir(path) if n.endswith((".aab", ".apk")))
        if not found:
            raise OSError("no .aab or .apk in %s" % path)
        return found
    if not os.path.isfile(path):
        raise OSError("no such file: %s" % path)
    return [path]


def main(argv):
    if not argv:
        print("usage: check_16kb.py <aab|apk|so|dir>...", file=sys.stderr)
        return 2
    failed = False
    rows = []
    try:
        for p in [a for arg in argv for a in artifacts(arg)]:
            for name, data in libraries(p):
                status, detail = check_elf(data)
                failed |= status == "FAIL"
                rows.append((status, name, detail))
    except (OSError, zipfile.BadZipFile) as e:
        print("error: %s" % e, file=sys.stderr)
        return 2
    for status, name, detail in rows:
        print("%-4s  %s%s" % (status, name, "  (" + detail + ")" if detail else ""))
    print("%d libraries, %d failing" % (len(rows), sum(r[0] == "FAIL" for r in rows)))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
```

Append to the root `build.gradle.kts`:

```kotlin
// Spec §3 hard gate: every native library in the phone and watch AABs and the glasses APK is 16 KB aligned.
tasks.register<Exec>("check16kb") {
    group = "verification"
    description = "16 KB page-size check (LOAD p_align and RELRO end) on the release AABs and the glasses APK."
    dependsOn(":phone:bundleRelease", ":watch:bundleRelease", ":glasses:assembleRelease")
    commandLine(
        "python3", "tools/release/check_16kb.py",
        "phone/build/outputs/bundle/release", "watch/build/outputs/bundle/release", "glasses/build/outputs/apk/release",
    )
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `python3 -m unittest discover -s tools/release -p 'test_check_16kb.py' -v`
Expected: PASS (7 tests).

- [ ] **Step 5: Run the gate on the real artifacts and record the table**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && LIVEAR_TILES_KEY=dummy ./gradlew check16kb; echo "exit=$?"`
Expected today (from the 2026-10-09 inspection): **FAIL** rows for `libandroidx.graphics.path.so` (phone, watch) and for `libcaps.so`, `libflora-cli.so`, `libmutils.so`, `libcxr-bridge-jni.so` (phone via client-l, glasses via cxr-service-bridge 1.5), `PASS` for `libcxr-sock-proto-jni.so`, `SKIP` for `armeabi-v7a`; non-zero exit. Paste the full table into the task report. This task's deliverable is the gate, not a passing release: per the spec the release stays **blocked** until the table is all PASS.

- [ ] **Step 6: Remediation attempts (record each outcome in the report)**

1. graphics-path: `./gradlew :phone:dependencies --configuration releaseRuntimeClasspath | grep graphics-path`; try `implementation("androidx.graphics:graphics-path:1.1.0")` in `phone/` and `watch/build.gradle.kts`, rebuild, re-run `check16kb`. Inspection says 1.1.0 still fails the RELRO-end rule; if so revert the line and raise it to the owner (see report) — do not weaken the checker.
2. Rokid: hand the owner the failing-library table for the Rokid request ("16 KB-aligned `cxr-service-bridge` builds", spec §3). No code change.

- [ ] **Step 7: Commit**

```bash
git add tools/release/check_16kb.py tools/release/test_check_16kb.py build.gradle.kts
git commit -m "16 KB gate: ELF LOAD/RELRO checker with fixture tests and check16kb Gradle task

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 11: CI — `ci.yml` and `release.yml`

**Files:**
- Create: `.github/workflows/ci.yml`, `.github/workflows/release.yml`, `tools/release/test_workflows.py`

**Interfaces:**
- Consumes: `ReleaseGate`/`-Plivefit.requireSigning` (Task 2), `tools/release/check_16kb.py` (Task 3).
- Produces: repository secrets the owner must add — `LIVEAR_KEYSTORE_BASE64`, `LIVEAR_KEY_ALIAS`, `LIVEAR_STORE_PASSWORD`, `LIVEAR_KEY_PASSWORD`, `LIVEAR_TILES_KEY` — and the repository variable `LIVEAR_UPLOAD_CERT_SHA256` (README, Task 10, documents them).

- [ ] **Step 1: Write the failing test** — `tools/release/test_workflows.py`:

```python
import os
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def read(name):
    with open(os.path.join(ROOT, ".github", "workflows", name)) as f:
        return f.read()


class WorkflowPolicyTest(unittest.TestCase):
    """Spec §8: what the two workflows must do (string checks; no YAML dependency)."""

    def test_ci_runs_all_unit_tests_and_three_debug_builds_on_jdk17(self):
        ci = read("ci.yml")
        for needed in ("push", "pull_request", "java-version: '17'", "gradle/actions/setup-gradle",
                       ":phone:assembleDebug", ":watch:assembleDebug", ":glasses:assembleDebug", " test ",
                       "-p buildSrc test", "python3 -m unittest discover -s tools/release"):
            self.assertIn(needed, ci)

    def test_release_fails_without_secrets_and_ships_only_owner_signed(self):
        rel = read("release.yml")
        for needed in ("tags:", "'v*'", "LIVEAR_KEYSTORE_BASE64", "LIVEAR_KEY_ALIAS", "LIVEAR_STORE_PASSWORD",
                       "LIVEAR_KEY_PASSWORD", "LIVEAR_TILES_KEY", "LIVEAR_UPLOAD_CERT_SHA256", "exit 1",
                       "-Plivefit.requireSigning=true", "check_16kb.py", "apksigner", "--print-certs",
                       "sha256sum", "draft: true"):
            self.assertIn(needed, rel)

    def test_release_gates_run_before_the_upload(self):
        rel = read("release.yml")
        self.assertLess(rel.index("check_16kb.py"), rel.index("action-gh-release"))
        self.assertLess(rel.index("--print-certs"), rel.index("action-gh-release"))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python3 -m unittest discover -s tools/release -p 'test_workflows.py' -v`
Expected: FAIL — `FileNotFoundError: … ci.yml`.

- [ ] **Step 3: Implement**

`.github/workflows/ci.yml`:

```yaml
name: ci
on:
  push:
  pull_request:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      # Caches ~/.gradle (wrapper, dependency cache incl. maven.rokid.com artifacts, build cache).
      - uses: gradle/actions/setup-gradle@v4
      - name: Build logic tests
        run: ./gradlew -p buildSrc test
      - name: Unit tests and debug builds
        run: ./gradlew test :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug --stacktrace
      - name: Release tooling tests
        run: python3 -m unittest discover -s tools/release -p 'test_*.py' -v
```

`.github/workflows/release.yml`:

```yaml
name: release
on:
  push:
    tags: ['v*']
permissions:
  contents: write
jobs:
  release:
    runs-on: ubuntu-latest
    env:
      LIVEAR_KEY_ALIAS: ${{ secrets.LIVEAR_KEY_ALIAS }}
      LIVEAR_STORE_PASSWORD: ${{ secrets.LIVEAR_STORE_PASSWORD }}
      LIVEAR_KEY_PASSWORD: ${{ secrets.LIVEAR_KEY_PASSWORD }}
      LIVEAR_TILES_KEY: ${{ secrets.LIVEAR_TILES_KEY }}
      EXPECTED_CERT_SHA256: ${{ vars.LIVEAR_UPLOAD_CERT_SHA256 }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - name: Require the release secrets (spec §8)
        env:
          KEYSTORE_B64: ${{ secrets.LIVEAR_KEYSTORE_BASE64 }}
        run: |
          for v in KEYSTORE_B64 LIVEAR_KEY_ALIAS LIVEAR_STORE_PASSWORD LIVEAR_KEY_PASSWORD LIVEAR_TILES_KEY EXPECTED_CERT_SHA256; do
            if [ -z "${!v}" ]; then echo "::error::$v is not set"; exit 1; fi
          done
          echo "$KEYSTORE_B64" | base64 -d > "$RUNNER_TEMP/upload.jks"
          echo "LIVEAR_KEYSTORE=$RUNNER_TEMP/upload.jks" >> "$GITHUB_ENV"
      - name: Tag matches livefit.version
        run: |
          want="$(sed -n 's/^livefit.version=//p' gradle.properties)"
          if [ "${GITHUB_REF_NAME#v}" != "$want" ]; then echo "::error::tag $GITHUB_REF_NAME != livefit.version $want"; exit 1; fi
      - name: Build owner-signed release artifacts
        run: ./gradlew -Plivefit.requireSigning=true :phone:bundleRelease :watch:bundleRelease :glasses:assembleRelease --stacktrace
      - name: 16 KB native check (hard gate)
        run: python3 tools/release/check_16kb.py phone/build/outputs/bundle/release watch/build/outputs/bundle/release glasses/build/outputs/apk/release
      - name: Glasses APK is signed with the upload key
        run: |
          APKSIGNER="$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)apksigner"
          actual="$("$APKSIGNER" verify --print-certs glasses/build/outputs/apk/release/glasses-release.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
          expected="$(echo "$EXPECTED_CERT_SHA256" | tr -d ': ' | tr 'A-F' 'a-f')"
          if [ -z "$actual" ] || [ "$actual" != "$expected" ]; then echo "::error::glasses certificate $actual does not match the upload key"; exit 1; fi
      - name: Stage the glasses APK and its SHA-256
        run: |
          mkdir dist
          cp glasses/build/outputs/apk/release/glasses-release.apk "dist/live-ar-fit-glasses-${GITHUB_REF_NAME}.apk"
          (cd dist && sha256sum *.apk > SHA256SUMS.txt)
      - name: Keep the AABs for the Play upload
        uses: actions/upload-artifact@v4
        with:
          name: play-bundles-${{ github.ref_name }}
          path: |
            phone/build/outputs/bundle/release/phone-release.aab
            watch/build/outputs/bundle/release/watch-release.aab
      - uses: softprops/action-gh-release@v2
        with:
          draft: true
          files: dist/*
```

- [ ] **Step 4: Run the tests to verify they pass, and parse the YAML**

Run: `python3 -m unittest discover -s tools/release -p 'test_*.py' -v && ruby -ryaml -e 'ARGV.each { |f| YAML.load_file(f) }; puts "yaml ok"' .github/workflows/ci.yml .github/workflows/release.yml`
Expected: PASS; `yaml ok`.

Run the CI steps locally once: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew -p buildSrc test && ./gradlew test :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug`
Expected: BUILD SUCCESSFUL. (The workflows themselves run once the owner pushes; that is not part of this task.)

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/ci.yml .github/workflows/release.yml tools/release/test_workflows.py
git commit -m "CI: unit tests + debug builds; tag release with secret check, 16 KB gate, cert check and draft GitHub release

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Lane U — permissions, policy UX, hardening

### Task 4: Watch permissions — OS-dependent heart rate, location disclosure, no full-screen intent

**Files:**
- Create: `watch/src/main/java/com/debasish/livefit/watch/HealthPermissions.kt`, `watch/src/test/java/com/debasish/livefit/watch/HealthPermissionsTest.kt`
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/Disclosures.kt`, `core/model/src/test/kotlin/com/debasish/livefit/model/DisclosuresTest.kt`
- Modify: `watch/src/main/AndroidManifest.xml:6-8,19-20`, `watch/.../MainActivity.kt:18-25,47,54`, `watch/.../HealthServicesExercise.kt:51-54`, `watch/.../WatchFront.kt:11-75`, `watch/.../ui/WatchApp.kt:116-131`, `watch/.../ui/WatchOverlays.kt` (append)
- Test: `watch/src/test/java/com/debasish/livefit/watch/ManifestPolicyTest.kt` (extend)

**Interfaces:**
- Produces: `object HealthPermissions { BODY_SENSORS, READ_HEART_RATE, ACTIVITY_RECOGNITION, POST_NOTIFICATIONS, FINE_LOCATION, COARSE_LOCATION, GRANULAR_HEALTH_SDK = 36; fun requiredHealthPermissions(sdkInt: Int): List<String>; fun runtimeRequest(sdkInt: Int): List<String>; val LOCATION: List<String> }`; `object Disclosures { PRIVACY_POLICY_URL, CONTACT_EMAIL, DATA_LAYER, LOCATION_TITLE, LOCATION, WATCH_LOCATION, MUSIC_TITLE, MUSIC, MIC_TITLE, MIC, WATCH_SUMMARY }` (all `const val String`; consumed by Tasks 6 and 10); `WatchApp(…, locationDisclosure: Boolean = false, onLocationDisclosure: (Boolean) -> Unit = {})`; `@Composable fun LocationDisclosureCard(onAnswer: (Boolean) -> Unit)`.

- [ ] **Step 1: Write the failing tests**

`watch/src/test/java/com/debasish/livefit/watch/HealthPermissionsTest.kt`:

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.watch.HealthPermissions.ACTIVITY_RECOGNITION
import com.debasish.livefit.watch.HealthPermissions.BODY_SENSORS
import com.debasish.livefit.watch.HealthPermissions.READ_HEART_RATE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HealthPermissionsTest {
    @Test fun api30And33And35UseBodySensors() {
        for (sdk in listOf(30, 33, 35)) assertEquals(listOf(BODY_SENSORS, ACTIVITY_RECOGNITION), HealthPermissions.requiredHealthPermissions(sdk), "API $sdk")
    }

    @Test fun api36UsesTheGranularHeartRatePermission() =
        assertEquals(listOf(READ_HEART_RATE, ACTIVITY_RECOGNITION), HealthPermissions.requiredHealthPermissions(36))

    /** The old bug: requiring both on every OS meant a watch below API 36 could never start a workout. */
    @Test fun neverBothHeartRatePermissions() {
        for (sdk in 30..37) {
            val p = HealthPermissions.requiredHealthPermissions(sdk)
            assertFalse(BODY_SENSORS in p && READ_HEART_RATE in p, "API $sdk")
        }
    }

    @Test fun firstRequestIsHealthPlusNotificationsFrom33WithoutLocation() {
        assertEquals(listOf(BODY_SENSORS, ACTIVITY_RECOGNITION), HealthPermissions.runtimeRequest(30))
        assertEquals(listOf(BODY_SENSORS, ACTIVITY_RECOGNITION, HealthPermissions.POST_NOTIFICATIONS), HealthPermissions.runtimeRequest(33))
        assertEquals(listOf(READ_HEART_RATE, ACTIVITY_RECOGNITION, HealthPermissions.POST_NOTIFICATIONS), HealthPermissions.runtimeRequest(36))
        assertFalse(HealthPermissions.FINE_LOCATION in HealthPermissions.runtimeRequest(36), "location only after its disclosure")
        assertTrue(HealthPermissions.FINE_LOCATION in HealthPermissions.LOCATION)
    }
}
```

`core/model/src/test/kotlin/com/debasish/livefit/model/DisclosuresTest.kt`:

```kotlin
package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §4: the disclosures must say these things; the privacy policy and Data safety drafts quote the same texts. */
class DisclosuresTest {
    private fun String.says(vararg phrases: String) = phrases.forEach { assertTrue(it in this, "missing \"$it\" in: $this") }

    @Test fun musicSaysWhatIsReadWhereItGoesAndHow() = Disclosures.MUSIC.says(
        "title, artist, playback state and up-next queue", "sent to your paired watch and glasses",
        "Rokid CXR Bluetooth link", "Wear OS Data Layer", "Google's cloud, encrypted", "no server of its own",
        "music features stay off and everything else works",
    )

    @Test fun locationIsWorkoutOnlyAndNamesTheTileProvider() {
        Disclosures.LOCATION.says("only during a workout", "\"Use GPS outdoors\"", "never uses your location in the background", "MapTiler", "IP address", "Google's cloud, encrypted")
        Disclosures.WATCH_LOCATION.says("only during workouts", "never in the background", "MapTiler")
    }

    @Test fun microphoneIsOnDeviceOnly() {
        Disclosures.MIC.says("on-device only", "never uploaded")
        assertFalse("cloud recognition" in Disclosures.MIC)
    }

    @Test fun policyIsPublishedAndHasAContact() {
        assertTrue(Disclosures.PRIVACY_POLICY_URL.startsWith("https://"))
        Disclosures.WATCH_SUMMARY.says("Clear history", Disclosures.CONTACT_EMAIL, "MapTiler")
    }
}
```

Append to `watch/src/test/java/com/debasish/livefit/watch/ManifestPolicyTest.kt` (inside the class):

```kotlin
    /** Spec §4: restricted permission removed; the Ongoing Activity chip replaces it. */
    @Test fun noFullScreenIntent() = assertTrue("USE_FULL_SCREEN_INTENT" !in manifest)

    /** Spec §4 (review P1-1): BODY_SENSORS only up to API 35, the granular permission for API 36+. */
    @Test fun heartRatePermissionIsOsDependent() {
        assertTrue(Regex("""android:name="android\.permission\.BODY_SENSORS"\s+android:maxSdkVersion="35"""").containsMatchIn(manifest))
        assertTrue("android.permission.health.READ_HEART_RATE" in manifest)
    }

    @Test fun noBackgroundLocationOrBackgroundHealth() {
        for (p in listOf("ACCESS_BACKGROUND_LOCATION", "BODY_SENSORS_BACKGROUND", "READ_HEALTH_DATA_IN_BACKGROUND")) assertTrue(p !in manifest, p)
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:testDebugUnitTest :core:model:test`
Expected: FAIL — unresolved `HealthPermissions`/`Disclosures`; `noFullScreenIntent` and `heartRatePermissionIsOsDependent` fail.

- [ ] **Step 3: Implement the pure units**

`watch/src/main/java/com/debasish/livefit/watch/HealthPermissions.kt`:

```kotlin
package com.debasish.livefit.watch

/**
 * Spec §4 (review P1-1): the heart-rate permission depends on the OS. API 36+ (Wear OS 6) uses the granular
 * `android.permission.health.READ_HEART_RATE`; up to API 35 it is `BODY_SENSORS` (declared with maxSdkVersion 35).
 * One list drives both the runtime request (MainActivity) and the backend's missing-permission check. Pure.
 */
object HealthPermissions {
    const val BODY_SENSORS = "android.permission.BODY_SENSORS"
    const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
    const val ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val GRANULAR_HEALTH_SDK = 36

    /** What an exercise needs before it may start (location is optional: GPS workouts only). */
    fun requiredHealthPermissions(sdkInt: Int): List<String> =
        listOf(if (sdkInt >= GRANULAR_HEALTH_SDK) READ_HEART_RATE else BODY_SENSORS, ACTIVITY_RECOGNITION)

    /** MainActivity's first request: the health set and notifications (API 33+). */
    fun runtimeRequest(sdkInt: Int): List<String> = requiredHealthPermissions(sdkInt) + listOfNotNull(POST_NOTIFICATIONS.takeIf { sdkInt >= 33 })

    /** Asked separately, only after the location disclosure (spec §4). */
    val LOCATION = listOf(FINE_LOCATION, COARSE_LOCATION)
}
```

`core/model/src/main/kotlin/com/debasish/livefit/model/Disclosures.kt`:

```kotlin
package com.debasish.livefit.model

/**
 * Prominent-disclosure and privacy wording (spec §4, §7) — one source for the phone and watch screens. The privacy
 * policy and the Play Data safety drafts (docs/) quote these texts verbatim; change them together.
 */
object Disclosures {
    const val PRIVACY_POLICY_URL = "https://debasishdebs.github.io/Live-AR-Fit/privacy-policy.html"
    const val CONTACT_EMAIL = "d.kanhar@gmail.com"

    /** How every phone↔watch message travels: workout, heart rate, location fixes, settings and music. */
    const val DATA_LAYER =
        "Between your phone and watch, data travels over the Wear OS Data Layer: Bluetooth when the watch is nearby. " +
            "When Bluetooth isn't available, Google Play services may relay it through Google's cloud, encrypted. " +
            "Live AR Fit has no server of its own and never uploads your data to one."

    const val LOCATION_TITLE = "Location for your route"
    const val LOCATION =
        "Live AR Fit uses GPS only during a workout with \"Use GPS outdoors\" on, to record your route and show the map " +
            "on your phone, watch and glasses. It never uses your location in the background when no workout is running. " +
            "Your route is stored on your phone and watch. To draw the map, the phone and watch download map tiles from " +
            "MapTiler, which sees your IP address and the map area shown. $DATA_LAYER"

    /** The watch screen is small: the same facts, shorter. */
    const val WATCH_LOCATION =
        "GPS is used only during workouts with \"Use GPS outdoors\" on — never in the background. Map tiles come from MapTiler, which sees your IP and the map area."

    const val MUSIC_TITLE = "Music control needs notification access"
    const val MUSIC =
        "Live AR Fit reads the active media session of your music app (YouTube Music): the title, artist, playback state " +
            "and up-next queue. This is sent to your paired watch and glasses so they can show and control your music. " +
            "To the glasses it goes over the Rokid CXR Bluetooth link. $DATA_LAYER " +
            "Android calls this \"notification access\"; Live AR Fit does not read your notifications. " +
            "If you decline, music features stay off and everything else works."

    const val MIC_TITLE = "Microphone for voice commands"
    const val MIC =
        "Voice commands are recognised on your phone, on-device only — there is no cloud speech service. Live AR Fit " +
            "listens only after you tap Talk or when it asks you a yes/no question. Audio from the glasses' microphone " +
            "travels to your phone over the Rokid Bluetooth link. Audio is never uploaded or stored."

    /** The watch About summary shown when the phone can't open the policy. */
    const val WATCH_SUMMARY =
        "No account, no ads, no analytics. Workout, heart-rate and route data stay on your phone and watch; music details " +
            "are sent only to your watch and glasses. Voice is recognised on the phone, on-device. Map tiles come from MapTiler. " +
            "Delete everything with Clear history on the phone, or uninstall. Contact: $CONTACT_EMAIL"
}
```

- [ ] **Step 4: Wire the watch**

`watch/src/main/AndroidManifest.xml` — lines 6-8 become:

```xml
    <!-- Spec §4 (review P1-1): BODY_SENSORS up to API 35; the granular permission from API 36 (Wear OS 6). -->
    <uses-permission android:name="android.permission.BODY_SENSORS" android:maxSdkVersion="35" />
    <uses-permission android:name="android.permission.health.READ_HEART_RATE" />
```

and delete lines 19-20 (the `A3` comment and `USE_FULL_SCREEN_INTENT`).

`HealthServicesExercise.kt:51` — replace the `required` list with the shared helper:

```kotlin
    private val required = HealthPermissions.requiredHealthPermissions(android.os.Build.VERSION.SDK_INT)
```

`WatchFront.kt` — remove the full-screen path; the Ongoing Activity posted by `ExerciseService` (ExerciseService.kt:40-44) is the "return to workout" chip. Replace the class KDoc (lines 11-19) and `raise`/`postFullScreen` (lines 43-75) with:

```kotlin
/**
 * Watch-side "bring the workout screen to the front" (A3), in addition to the phone's RemoteActivityHelper path.
 * A direct startActivity, allowed only when Android grants this background start an exemption (otherwise the platform
 * blocks it silently) — best effort. The reliable path is the workout's Ongoing Activity (ExerciseService), whose
 * "return to workout" chip and notification tap open LiveFit on every Wear OS 3+ watch (spec §4: no full-screen intent).
 */
```

```kotlin
    private fun raise(context: Context, reason: String) {
        val app = context.applicationContext
        WatchRuntime.ensureExerciseService() // the Ongoing Activity chip appears with the health FGS
        val open = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(open) }.onFailure { WatchRuntime.log("front: direct start failed ($reason): $it") }
    }
```

Keep `ID` and `onVisible`'s `cancel(ID)` (clears a heads-up left by an older build); delete `CHANNEL`, `TIMEOUT_MS` and the now-unused imports (`NotificationChannel`, `PendingIntent`, `Build`, `NotificationCompat`).

`MainActivity.kt` — lines 18-25 become:

```kotlin
    /** Health + notifications first (spec §4: the heart-rate permission depends on the OS). */
    private val healthPerms = HealthPermissions.runtimeRequest(Build.VERSION.SDK_INT).toTypedArray()
    /** True while the location disclosure should show (spec §4: disclosure before the runtime prompt). */
    private val locationAsk = MutableStateFlow(false)
    /** A grant must clear the permission card (it shows a stale PermissionMissing error otherwise). */
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        WatchRuntime.controller.recheckPermissions()
        locationAsk.value = checkSelfPermission(HealthPermissions.FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && !locationAnswered
    }
    private val locationRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { locationAsk.value = false }
    /** Asked once per process: "Not now" isn't repeated on every resume. */
    private var locationAnswered = false
```

line 47 becomes `permissionRequest.launch(healthPerms)`; inside `setContent` add `val askLocation by locationAsk.collectAsState()` and line 54's `WatchApp(...)` call gets `onGrantPermissions = { permissionRequest.launch(healthPerms) }` plus the two new arguments:

```kotlin
                locationDisclosure = askLocation,
                onLocationDisclosure = { ok ->
                    locationAnswered = true
                    if (ok) locationRequest.launch(HealthPermissions.LOCATION.toTypedArray()) else locationAsk.value = false
                },
```

(imports: `android.content.pm.PackageManager`, `android.os.Build`; drop `android.Manifest`.)

`ui/WatchApp.kt` — the `WatchApp` signature (line 116) gains `locationDisclosure: Boolean = false, onLocationDisclosure: (Boolean) -> Unit = {}`, and after the `PermissionCard` line (130) add:

```kotlin
            if (locationDisclosure && s.phase == WorkoutPhase.Idle && state.needsPermissions.isEmpty()) LocationDisclosureCard(onLocationDisclosure)
```

Append to `ui/WatchOverlays.kt`:

```kotlin
/** Spec §4: prominent disclosure before the watch's location prompt. */
@Composable
fun LocationDisclosureCard(onAnswer: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(rememberInsets().x(22))) {
            Text("Location for your route", fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(com.debasish.livefit.model.Disclosures.WATCH_LOCATION, fontSize = 11.sp, color = Color(0xFF9AA0A6), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Not now", fontSize = 14.sp, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF202327)).clickable { onAnswer(false) }.padding(horizontal = 14.dp, vertical = 8.dp))
                Text("Continue", fontSize = 14.sp, color = Color.Black, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF14C3A2)).clickable { onAnswer(true) }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:testDebugUnitTest :core:model:test :watch:assembleDebug`
Expected: PASS.

- [ ] **Step 6: Device check (any connected watch; record the API level)**

Run: `adb -s <watch-serial> uninstall com.livear.fit; adb -s <watch-serial> install watch/build/outputs/apk/debug/watch-debug.apk && adb -s <watch-serial> shell getprop ro.build.version.sdk`
Open the app: the first prompt lists heart rate (API ≤ 35: "Body sensors"; API 36: "Heart rate"), activity and notifications only; after it, the location disclosure card shows; Continue → the location prompt. Start a workout from the watch: it starts (no PermissionMissing card). Start one from the phone with the watch screen off: the "return to workout" chip appears on the watch face.

- [ ] **Step 7: Commit**

```bash
git add watch core/model
git commit -m "Watch permissions: OS-dependent heart rate via requiredHealthPermissions, location disclosure, no full-screen intent

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 7: Hardening — `livefit://` entry guard and Wearable sender gate

**Files:**
- Create: `watch/src/main/java/com/debasish/livefit/watch/LaunchEntry.kt`, `watch/src/test/java/com/debasish/livefit/watch/LaunchEntryTest.kt`
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/PeerGate.kt`, `core/model/src/test/kotlin/com/debasish/livefit/model/PeerGateTest.kt`
- Modify: `core/model/build.gradle.kts:12-14`, `watch/.../MainActivity.kt` (add `onNewIntent`), `watch/.../DiscoverableActivity.kt:55-56`, `watch/.../PhoneCommandListener.kt:16-53`, `phone/.../WatchListener.kt:9-15`

**Interfaces:**
- Consumes: `DiscoverableRequest.parse(text): Int?` (core/model Frames.kt:67), `CAPABILITY_PHONE`, `CAPABILITY_WATCH` (NodeChoice.kt:6-7).
- Produces: `sealed interface LaunchEntry { OpenWorkoutUi; ShowDiscoverablePrompt(seconds: Int); Ignore; companion fun of(scheme: String?, host: String?, path: String?, request: String?): LaunchEntry; MAX_REQUEST_CHARS = 256 }`; `class PeerGate(lookup: suspend () -> Set<String>, nowMs: () -> Long, refreshAfterMs: Long = 10_000) { fun known(sourceNodeId: String): Boolean; suspend fun allows(sourceNodeId: String): Boolean }`.

- [ ] **Step 1: Write the failing tests**

`watch/src/test/java/com/debasish/livefit/watch/LaunchEntryTest.kt`:

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.model.DiscoverableRequest
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals

class LaunchEntryTest {
    private val req = Wire.encode(DiscoverableRequest(seconds = 120))

    @Test fun workoutOnlyOpensTheUi() {
        assertEquals(LaunchEntry.OpenWorkoutUi, LaunchEntry.of("livefit", "workout", null, null))
        assertEquals(LaunchEntry.OpenWorkoutUi, LaunchEntry.of("livefit", "workout", "", null))
        // Query parameters never turn into commands: there is no entry that carries one.
        assertEquals(LaunchEntry.OpenWorkoutUi, LaunchEntry.of("livefit", "workout", "/", "start"))
    }

    @Test fun discoverableOnlyShowsThePromptForACurrentRequest() {
        assertEquals(LaunchEntry.ShowDiscoverablePrompt(120), LaunchEntry.of("livefit", "discoverable", null, req))
        assertEquals(LaunchEntry.ShowDiscoverablePrompt(300), LaunchEntry.of("livefit", "discoverable", null, Wire.encode(DiscoverableRequest(seconds = 9_999))))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "discoverable", null, null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "discoverable", null, "{not json"))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "discoverable", null, """{"protocolVersion":1,"seconds":60}"""))
    }

    @Test fun unknownPathsHostsAndSchemesAreIgnored() {
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "workout", "/start", null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "workout", "/stop", null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "takeover", null, null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", null, null, null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("https", "workout", null, null))
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of(null, null, null, null))
    }

    @Test fun anOversizedRequestIsIgnoredWithoutParsing() =
        assertEquals(LaunchEntry.Ignore, LaunchEntry.of("livefit", "discoverable", null, req + " ".repeat(LaunchEntry.MAX_REQUEST_CHARS)))
}
```

`core/model/src/test/kotlin/com/debasish/livefit/model/PeerGateTest.kt`:

```kotlin
package com.debasish.livefit.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerGateTest {
    private var now = 0L
    private var lookups = 0
    private var advertised: () -> Set<String> = { setOf("phone-node") }
    private val gate = PeerGate(lookup = { lookups++; advertised() }, nowMs = { now })

    @Test fun anAdvertisedPeerIsAllowedAndCached() = runTest {
        assertFalse(gate.known("phone-node"), "nothing verified yet")
        assertTrue(gate.allows("phone-node"))
        assertTrue(gate.known("phone-node"))
        assertTrue(gate.allows("phone-node"))
        assertEquals(1, lookups)
    }

    @Test fun anUnknownSenderIsIgnoredAndCannotForceALookupStorm() = runTest {
        assertFalse(gate.allows("stranger"))
        now += 5_000
        assertFalse(gate.allows("stranger"))
        assertEquals(1, lookups, "rate limited")
        now += 5_001
        assertFalse(gate.allows("stranger"))
        assertEquals(2, lookups)
    }

    /** Review focus 4: the peer app was just (re)installed and its capability shows up after the first lookup. */
    @Test fun aNewlyAdvertisedPeerIsAcceptedAfterOneRefresh() = runTest {
        assertTrue(gate.allows("phone-node"))
        advertised = { setOf("phone-node", "new-phone") }
        now += 10_000
        assertTrue(gate.allows("new-phone"))
        assertEquals(2, lookups)
    }

    /** Review focus 4: Play services failing once must not lock the genuine peer out until the rate limit ends. */
    @Test fun aFailedLookupRejectsButCachesNothing() = runTest {
        var fail = true
        advertised = { if (fail) error("Wearable API unavailable") else setOf("phone-node") }
        assertFalse(gate.allows("phone-node"))
        fail = false
        assertTrue(gate.allows("phone-node"), "retried at once")
        assertEquals(2, lookups)
    }
}
```

`core/model/build.gradle.kts` — the test dependencies block (lines 12-14) becomes:

```kotlin
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test :watch:testDebugUnitTest`
Expected: FAIL — unresolved `PeerGate`, `LaunchEntry`.

- [ ] **Step 3: Implement the pure units**

`watch/src/main/java/com/debasish/livefit/watch/LaunchEntry.kt`:

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.model.DiscoverableRequest

/**
 * What a `livefit://` URI may do on the watch (spec §4, review P1-2). The BROWSABLE filters stay (RemoteActivityHelper
 * needs them), but a URI never authorises anything: `livefit://workout` only opens or raises the UI — starts, stops and
 * takeovers still need the phone's Data Layer command and the normal confirmation — and `livefit://discoverable` only
 * shows the system prompt, which the user must accept. Unknown hosts, paths and parameters are ignored. Pure.
 */
sealed interface LaunchEntry {
    data object OpenWorkoutUi : LaunchEntry
    data class ShowDiscoverablePrompt(val seconds: Int) : LaunchEntry
    data object Ignore : LaunchEntry

    companion object {
        const val SCHEME = "livefit"
        const val MAX_REQUEST_CHARS = 256

        fun of(scheme: String?, host: String?, path: String?, request: String?): LaunchEntry {
            if (scheme != SCHEME || !(path.isNullOrEmpty() || path == "/")) return Ignore
            return when (host) {
                "workout" -> OpenWorkoutUi
                "discoverable" -> request?.takeIf { it.length <= MAX_REQUEST_CHARS }?.let(DiscoverableRequest::parse)?.let(::ShowDiscoverablePrompt) ?: Ignore
                else -> Ignore
            }
        }
    }
}
```

`core/model/src/main/kotlin/com/debasish/livefit/model/PeerGate.kt`:

```kotlin
package com.debasish.livefit.model

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * Spec §4 (exported components): a Wearable listener acts only on messages whose source node advertises the peer
 * capability ([CAPABILITY_PHONE] on the watch, [CAPABILITY_WATCH] on the phone). The advertised node ids are cached; an
 * unknown sender triggers a fresh [lookup] at most once per [refreshAfterMs], so a just-installed peer is accepted on
 * its first message after the capability propagates. A failed lookup rejects the message and caches nothing.
 */
class PeerGate(
    private val lookup: suspend () -> Set<String>,
    private val nowMs: () -> Long,
    private val refreshAfterMs: Long = 10_000,
) {
    private val mutex = Mutex()
    @Volatile private var known: Set<String> = emptySet()
    private var lastLookupMs: Long? = null

    /** Non-suspending fast path (time-sync replies): true only for an already-verified sender. */
    fun known(sourceNodeId: String): Boolean = sourceNodeId in known

    suspend fun allows(sourceNodeId: String): Boolean = mutex.withLock {
        if (sourceNodeId in known) return@withLock true
        val last = lastLookupMs
        if (last != null && nowMs() - last < refreshAfterMs) return@withLock false
        val fresh = try {
            lookup()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock false
        }
        known = fresh
        lastLookupMs = nowMs()
        sourceNodeId in fresh
    }
}
```

- [ ] **Step 4: Wire the listeners and activities**

`watch/.../PhoneCommandListener.kt` — replace `onMessageReceived` (lines 17-53) and add a companion:

```kotlin
    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data
        val source = event.sourceNodeId
        val gate = gate(this)
        // Time sync stays on the fast path (RTT ≤ 1 s) for an already-verified phone.
        if (path == WatchPaths.TIME_REQ && gate.known(source)) { replyTime(data, source); return }
        WatchRuntime.init(this)
        WatchRuntime.scope.launch {
            if (!gate.allows(source)) { WatchRuntime.log("ignored $path from a node without $CAPABILITY_PHONE"); return@launch }
            handle(path, data, source)
        }
    }

    private fun replyTime(data: ByteArray, source: String) {
        TimeSyncResponder.reply(String(data), System.currentTimeMillis())
            ?.let { Wearable.getMessageClient(this).sendMessage(source, WatchPaths.TIME_RES, it) }
    }

    private suspend fun handle(path: String, data: ByteArray, source: String) {
        when (path) {
            WatchPaths.TIME_REQ -> { replyTime(data, source); return }
            WatchPaths.BATTERY_REQ -> {
                val pct = getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                Wearable.getMessageClient(this).sendMessage(source, WatchPaths.BATTERY, pct.toString().toByteArray())
                return
            }
        }
        val text = String(data)
        if (Wire.versionOf(text) != PROTOCOL_VERSION) { WatchClient.onOutdated(); return }
        try {
            when (path) {
                WatchPaths.EXERCISE_REQ -> {
                    val req = Wire.decode<ExerciseRequest>(text)
                    WatchRuntime.controller.handle(req)
                    if (req.op is ExerciseOp.Start) WatchFront.onHubStart(this) // A3: don't wait for the phone's delayed launch
                }
                WatchPaths.ACK -> WatchRuntime.recorder.onAck(Wire.decode<DeltaAck>(text))
                WatchPaths.STATE -> WatchClient.onFrame(text)
                WatchPaths.SETTINGS -> WatchClient.onSettings(text)
                WatchPaths.QUEUE -> WatchClient.onQueue(text)
                WatchPaths.DISCOVERABLE -> DiscoverableActivity.start(this, text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Spec §3: no payload (track titles, coordinates) in release logs.
            android.util.Log.e(WatchRuntime.TAG, "bad $path message: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        @Volatile private var gate: PeerGate? = null

        /** Spec §4: only nodes advertising `livefit_phone` may command the watch. */
        private fun gate(context: android.content.Context): PeerGate = gate ?: synchronized(this) {
            gate ?: PeerGate(
                lookup = {
                    Wearable.getCapabilityClient(context.applicationContext).getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_ALL).await()
                        .nodes.mapTo(HashSet()) { it.id }
                },
                nowMs = System::currentTimeMillis,
            ).also { gate = it }
        }
    }
```

(imports: `com.debasish.livefit.model.CAPABILITY_PHONE`, `com.debasish.livefit.model.PeerGate`, `com.google.android.gms.wearable.CapabilityClient`, `kotlinx.coroutines.tasks.await`.)

`phone/.../WatchListener.kt` — whole class:

```kotlin
/** Wakes the phone for watch messages; building the graph starts the hub (spec §5.2). Only `livefit_watch` nodes count (spec §4). */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val graph = (application as LiveFitApp).services
        LiveFitHubService.start(this)
        val link = DataLayerWatchLink.instance ?: return
        val path = event.path
        val data = event.data
        val source = event.sourceNodeId
        val gate = gate(applicationContext)
        graph.scope.launch {
            if (!gate.allows(source)) { Log.w("LiveFitWatchLink", "ignored $path from a node without $CAPABILITY_WATCH"); return@launch }
            link.onMessage(path, data, source)
        }
    }

    companion object {
        @Volatile private var gate: PeerGate? = null

        private fun gate(context: Context): PeerGate = gate ?: synchronized(this) {
            gate ?: PeerGate(
                lookup = {
                    Wearable.getCapabilityClient(context).getCapability(CAPABILITY_WATCH, CapabilityClient.FILTER_ALL).await()
                        .nodes.mapTo(HashSet()) { it.id }
                },
                nowMs = System::currentTimeMillis,
            ).also { gate = it }
        }
    }
}
```

(imports: `android.content.Context`, `android.util.Log`, `com.debasish.livefit.model.CAPABILITY_WATCH`, `com.debasish.livefit.model.PeerGate`, `com.google.android.gms.wearable.CapabilityClient`, `com.google.android.gms.wearable.Wearable`, `kotlinx.coroutines.tasks.await`.)

`DiscoverableActivity.kt:55-56` — `requestedSeconds` routes the URI through the guard (an opaque URI makes `getQueryParameter` throw, hence `runCatching`):

```kotlin
        private fun requestedSeconds(intent: Intent): Int? {
            intent.data?.let { d ->
                val req = runCatching { d.getQueryParameter(EXTRA_REQUEST) }.getOrNull()
                return (LaunchEntry.of(d.scheme, d.host, d.path, req) as? LaunchEntry.ShowDiscoverablePrompt)?.seconds
            }
            return intent.getStringExtra(EXTRA_REQUEST)?.takeIf { it.length <= LaunchEntry.MAX_REQUEST_CHARS }?.let(DiscoverableRequest::parse)
        }
```

`watch/.../MainActivity.kt` — add (a URI only opens/raises this screen; nothing reaches the controller or recorder):

```kotlin
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { d -> WatchRuntime.log("opened by ${LaunchEntry.of(d.scheme, d.host, d.path, null)}") } // UI only (spec §4)
    }
```

(import `android.content.Intent`.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test :watch:testDebugUnitTest :phone:testDebugUnitTest :watch:assembleDebug :phone:assembleDebug`
Expected: PASS.

- [ ] **Step 6: Device check**

Install both debug builds. (a) Phone → Start workout: the watch opens the workout screen (remote launch) and records — the gate passes the real phone. (b) Pairing fallback: Settings → Nearby → Pair watch shows the watch's discoverable prompt. (c) Browser test: `adb -s <watch-serial> shell am start -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d "livefit://workout/start?op=start"` → the app opens on Ready, no workout starts (`adb -s <watch-serial> logcat -d -s LiveFitWatch | grep "opened by"` shows `Ignore`); `-d "livefit://discoverable?req=garbage"` → nothing happens.

- [ ] **Step 7: Commit**

```bash
git add core/model watch phone/src/main/java/com/debasish/livefit/phone/WatchListener.kt
git commit -m "Hardening: livefit:// entry guard (URI never authorises), Wearable sender capability gate on phone and watch

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 5: Phone battery-optimisation banner

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/BatteryAdvice.kt`, `phone/src/test/java/com/debasish/livefit/phone/BatteryAdviceTest.kt`, `phone/src/main/java/com/debasish/livefit/phone/ui/components/BatteryBanner.kt`
- Modify: `phone/src/main/AndroidManifest.xml:6`, `phone/.../setup/SetupScreen.kt:73-77,113`, `phone/.../ui/list/sources/PermissionSource.kt:48`, `phone/.../ui/home/HomeScreen.kt` (after the header Column, ~line 102)
- Test: `phone/src/test/java/com/debasish/livefit/phone/ManifestPolicyTest.kt` (extend)

**Interfaces:**
- Produces: `data class BatteryBanner(headline: String, steps: List<String>, samsung: Boolean)`; `object BatteryAdvice { HEADLINE; SAMSUNG_STEPS; GENERIC_STEPS; SETTINGS_ACTIONS; fun banner(ignoringOptimizations: Boolean, manufacturer: String?): BatteryBanner? }`; `@Composable fun BatteryBannerCard(modifier: Modifier = Modifier)`; `fun openBatterySettings(context: Context)`.

- [ ] **Step 1: Write the failing tests**

`phone/src/test/java/com/debasish/livefit/phone/BatteryAdviceTest.kt`:

```kotlin
package com.debasish.livefit.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryAdviceTest {
    @Test fun exemptMeansNoBanner() {
        assertNull(BatteryAdvice.banner(ignoringOptimizations = true, manufacturer = "samsung"))
        assertNull(BatteryAdvice.banner(ignoringOptimizations = true, manufacturer = "Google"))
    }

    @Test fun notExemptShowsTheSpecHeadline() {
        val b = BatteryAdvice.banner(ignoringOptimizations = false, manufacturer = "Google")!!
        assertEquals("Battery optimisation is on — LiveFit may stop tracking or lose the glasses/watch link when the screen is off.", b.headline)
        assertFalse(b.samsung)
        assertEquals(BatteryAdvice.GENERIC_STEPS, b.steps)
    }

    @Test fun samsungGetsNeverSleepingAppsStepsWhateverTheCase() {
        for (m in listOf("samsung", "SAMSUNG", " Samsung ")) {
            val b = BatteryAdvice.banner(false, m)!!
            assertTrue(b.samsung, m)
            assertEquals(listOf("Settings", "Battery", "Background usage limits", "Never sleeping apps", "Add Live AR Fit"), b.steps)
        }
    }

    @Test fun unknownManufacturerIsGeneric() {
        assertFalse(BatteryAdvice.banner(false, null)!!.samsung)
        assertFalse(BatteryAdvice.banner(false, "samsungish")!!.samsung)
    }

    @Test fun openSettingsNeedsNoPermission() =
        assertEquals("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", BatteryAdvice.SETTINGS_ACTIONS.first())
}
```

Append to `phone/src/test/java/com/debasish/livefit/phone/ManifestPolicyTest.kt` (inside the class):

```kotlin
    /** Spec §4: restricted permission removed; the banner uses the permission-free settings list. */
    @Test fun noBatteryExemptionRequest() = assertTrue("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" !in manifest)

    @Test fun noBackgroundLocation() = assertTrue("ACCESS_BACKGROUND_LOCATION" !in manifest)
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*BatteryAdviceTest' --tests '*ManifestPolicyTest'`
Expected: FAIL — unresolved `BatteryAdvice`; `noBatteryExemptionRequest` fails.

- [ ] **Step 3: Implement** — `phone/src/main/java/com/debasish/livefit/phone/BatteryAdvice.kt`:

```kotlin
package com.debasish.livefit.phone

/** What the battery-optimisation banner shows (spec §4); null = LiveFit is exempt, no banner. */
data class BatteryBanner(val headline: String, val steps: List<String>, val samsung: Boolean)

/**
 * Spec §4: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is gone; while LiveFit is not exempt every app open shows a bold banner
 * with an Open settings button (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, no permission needed). Samsung gets its
 * own steps, other OEMs generic ones. Pure: the caller passes PowerManager.isIgnoringBatteryOptimizations and Build.MANUFACTURER.
 */
object BatteryAdvice {
    const val HEADLINE = "Battery optimisation is on — LiveFit may stop tracking or lose the glasses/watch link when the screen is off."
    val SAMSUNG_STEPS = listOf("Settings", "Battery", "Background usage limits", "Never sleeping apps", "Add Live AR Fit")
    val GENERIC_STEPS = listOf("Tap Open settings", "Show all apps and find Live AR Fit", "Choose \"Don't optimise\" (or \"Unrestricted\")")
    /** Tried in order by Open settings; the plain Settings screen is the fallback when an OEM hides the first. */
    val SETTINGS_ACTIONS = listOf("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", "android.settings.SETTINGS")

    fun banner(ignoringOptimizations: Boolean, manufacturer: String?): BatteryBanner? {
        if (ignoringOptimizations) return null
        val samsung = manufacturer?.trim().equals("samsung", ignoreCase = true)
        return BatteryBanner(HEADLINE, if (samsung) SAMSUNG_STEPS else GENERIC_STEPS, samsung)
    }
}
```

`phone/src/main/java/com/debasish/livefit/phone/ui/components/BatteryBanner.kt`:

```kotlin
package com.debasish.livefit.phone.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.debasish.livefit.phone.BatteryAdvice
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** Spec §4: bold banner on every app open while LiveFit is not exempt from battery optimisation. */
@Composable
fun BatteryBannerCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isExempt(context)) }
    LifecycleResumeEffect(Unit) {
        exempt = isExempt(context) // back from Settings: the banner disappears once exempt
        onPauseOrDispose { }
    }
    val banner = BatteryAdvice.banner(exempt, Build.MANUFACTURER) ?: return
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFFF1D6)).padding(16.dp)) {
        Text(banner.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = LiveFitColors.Ink)
        Spacer(Modifier.height(10.dp))
        if (banner.samsung) SamsungSteps(banner.steps)
        else banner.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall, color = LiveFitColors.InkSoft) }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { openBatterySettings(context) }) { Text("Open settings") }
    }
}

private fun isExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

fun openBatterySettings(context: Context) {
    for (action in BatteryAdvice.SETTINGS_ACTIONS) {
        try { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) { }
    }
}

/** Screenshot-style illustration of Samsung's path: settings rows with chevrons, the final "Add" row highlighted. */
@Composable
private fun SamsungSteps(steps: List<String>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)) {
        steps.forEachIndexed { i, s ->
            val last = i == steps.lastIndex
            Row(
                Modifier.fillMaxWidth().background(if (last) LiveFitColors.Mint.copy(alpha = 0.18f) else Color.Transparent).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (last) FontWeight.Bold else FontWeight.Normal, color = LiveFitColors.Ink)
                Icon(if (last) Icons.Rounded.Add else Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = LiveFitColors.InkSoft)
            }
            if (!last) HorizontalDivider(color = LiveFitColors.SurfaceSoft)
        }
    }
}
```

Wiring:
- `phone/src/main/AndroidManifest.xml:6` — delete the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` line.
- `SetupScreen.kt:73-77` — the permissions callback keeps only `LiveFitHubService.ensureRunning(activity)` (delete the comment and the `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` line; drop the now-unused `Uri` import). Line 113's body becomes `"Allow microphone, nearby devices and notifications so the hub can run during workouts."` (the battery advice now lives on Home).
- `PermissionSource.kt:48` → `"battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)`.
- `HomeScreen.kt` — directly after the header `Column(… HeaderGradient …)` closes (the first child of the scrolling Column), add `BatteryBannerCard(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))` (import `com.debasish.livefit.phone.ui.components.BatteryBannerCard`).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest :phone:assembleDebug`
Expected: PASS.

- [ ] **Step 5: Device check**

Install on the phone (`adb -s <phone-serial> install -r --user 0 phone/build/outputs/apk/debug/phone-debug.apk`). With LiveFit optimised: Home shows the banner (Samsung: the 5-row illustration); Open settings opens the battery-optimisation list. Make LiveFit unrestricted, return: the banner is gone.

- [ ] **Step 6: Commit**

```bash
git add phone
git commit -m "Phone: battery-optimisation banner with Samsung steps; REQUEST_IGNORE_BATTERY_OPTIMIZATIONS removed

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

### Task 6: Disclosures (music, location, mic) and in-app Privacy policy

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/DisclosureKind.kt`, `phone/src/test/java/com/debasish/livefit/phone/ui/DisclosureKindTest.kt`, `phone/src/main/java/com/debasish/livefit/phone/ui/DisclosureActivity.kt`, `watch/src/main/java/com/debasish/livefit/watch/ui/WatchAbout.kt`
- Modify: `phone/src/main/AndroidManifest.xml` (add activity), `phone/.../setup/SetupScreen.kt:78-80,113,138-139`, `phone/.../ui/linked/LinkedMusicScreen.kt:56-57`, `phone/.../ui/list/sources/PermissionSource.kt:46-50`, `phone/.../ui/settings/SimpleScreens.kt:43-58`, `watch/.../ui/WatchApp.kt:120-127,146-170`
- Test: `phone/src/test/java/com/debasish/livefit/phone/ManifestPolicyTest.kt` (extend)

**Interfaces:**
- Consumes: `Disclosures.*` (Task 4), `OpenOnPhone.open(context, url): Boolean` (Task 8), `PhoneCrash.log(context): CrashLog`, `PhoneCrash.shareIntent(text): Intent` (Task 9).
- Produces: `enum class DisclosureKind(title, body, continueLabel) { Music, Location }`; `DisclosureActivity.intent(context: Context, kind: DisclosureKind): Intent`; watch `@Composable internal fun AboutPage(onClose: () -> Unit)`; `Ready(…, onAbout: () -> Unit)`.

- [ ] **Step 1: Write the failing tests**

`phone/src/test/java/com/debasish/livefit/phone/ui/DisclosureKindTest.kt`:

```kotlin
package com.debasish.livefit.phone.ui

import com.debasish.livefit.model.Disclosures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec §4: each disclosure screen shows the one shared wording (also quoted by the privacy policy). */
class DisclosureKindTest {
    @Test fun musicAndLocationUseTheSharedTexts() {
        assertEquals(Disclosures.MUSIC, DisclosureKind.Music.body)
        assertEquals(Disclosures.MUSIC_TITLE, DisclosureKind.Music.title)
        assertEquals(Disclosures.LOCATION, DisclosureKind.Location.body)
    }

    @Test fun musicSaysWhereContinueLeads() = assertTrue("notification access" in DisclosureKind.Music.continueLabel)
}
```

Append to `phone/src/test/java/com/debasish/livefit/phone/ManifestPolicyTest.kt`:

```kotlin
    @Test fun disclosureScreenIsInternal() =
        assertTrue(Regex("""android:name="\.ui\.DisclosureActivity"\s+android:exported="false"""").containsMatchIn(manifest))
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*DisclosureKindTest' --tests '*ManifestPolicyTest'`
Expected: FAIL — unresolved `DisclosureKind`; `disclosureScreenIsInternal` fails.

- [ ] **Step 3: Implement the phone disclosure**

`phone/src/main/java/com/debasish/livefit/phone/ui/DisclosureKind.kt`:

```kotlin
package com.debasish.livefit.phone.ui

import com.debasish.livefit.model.Disclosures

/** The prominent disclosures shown before LiveFit sends the user to a system permission (spec §4). */
enum class DisclosureKind(val title: String, val body: String, val continueLabel: String) {
    Music(Disclosures.MUSIC_TITLE, Disclosures.MUSIC, "Continue to notification access"),
    Location(Disclosures.LOCATION_TITLE, Disclosures.LOCATION, "Continue"),
}
```

`phone/src/main/java/com/debasish/livefit/phone/ui/DisclosureActivity.kt`:

```kotlin
package com.debasish.livefit.phone.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import com.debasish.livefit.phone.LiveFitHubService
import com.debasish.livefit.phone.ui.theme.LiveFitTheme

/**
 * Spec §4: one dialog-style screen in front of notification access and the location prompt, used by Setup, Linked
 * music and the Permissions list, so no route skips the disclosure. "Not now" changes nothing.
 */
class DisclosureActivity : ComponentActivity() {
    private val locationRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LiveFitHubService.promoteLocation(this) // granted: the visible app re-promotes the hub with `location`
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND)?.let { n -> DisclosureKind.entries.firstOrNull { it.name == n } } ?: return finish()
        setContent {
            LiveFitTheme {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(kind.title) },
                    text = { Text(kind.body, modifier = Modifier.verticalScroll(rememberScrollState())) },
                    confirmButton = { TextButton(onClick = { proceed(kind) }) { Text(kind.continueLabel) } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text("Not now") } },
                )
            }
        }
    }

    private fun proceed(kind: DisclosureKind) {
        when (kind) {
            DisclosureKind.Music -> {
                runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                finish()
            }
            DisclosureKind.Location -> locationRequest.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    companion object {
        private const val EXTRA_KIND = "kind"

        fun intent(context: Context, kind: DisclosureKind): Intent =
            Intent(context, DisclosureActivity::class.java).putExtra(EXTRA_KIND, kind.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
```

`phone/src/main/AndroidManifest.xml` — inside `<application>`, after the `AppActivity` block:

```xml
        <!-- Spec §4: prominent disclosure before notification access / the location prompt. -->
        <activity
            android:name=".ui.DisclosureActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
```

Entry points:
- `SetupScreen.kt:138` → `SetupStep.Music -> Button(onClick = { activity.startActivity(DisclosureActivity.intent(activity, DisclosureKind.Music)) }) { Text("Open notification access") }`
- `SetupScreen.kt:139` → `SetupStep.Map -> Button(onClick = { activity.startActivity(DisclosureActivity.intent(activity, DisclosureKind.Location)) }) { Text("Allow location") }`; delete the now-unused `locationPermission` launcher (lines 78-80).
- `SetupScreen.kt:113` — the Welcome body becomes `"Allow microphone, nearby devices and notifications so the hub can run during workouts.\n\n${Disclosures.MIC}"` (in-context mic rationale before the system dialog; import `com.debasish.livefit.model.Disclosures`).
- `LinkedMusicScreen.kt:56-57` — the row's click becomes `{ context.startActivity(DisclosureActivity.intent(context, DisclosureKind.Music)) }`.
- `PermissionSource.kt:46-50` — `actionFor` becomes:

```kotlin
        val intent = when (item.id) {
            "media" -> DisclosureActivity.intent(context, DisclosureKind.Music)
            "location" -> DisclosureActivity.intent(context, DisclosureKind.Location)
            "battery" -> Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
```

- [ ] **Step 4: Privacy policy and crash sharing on the phone** — `SimpleScreens.kt` `AboutScreen` (lines 43-58) becomes:

```kotlin
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Spec §6: the crash file is read off the main thread; null = nothing recorded.
    val crash by produceState<String?>(null) { value = withContext(Dispatchers.IO) { PhoneCrash.log(context).last() } }
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft), horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenHeader("About", onBack)
        Spacer(Modifier.height(24.dp))
        IconChip(Icons.Rounded.Favorite, LiveFitColors.ChipMint, size = 88.dp, shapeRadius = 28.dp)
        Spacer(Modifier.height(12.dp))
        Text("Live AR Fit", style = MaterialTheme.typography.headlineMedium)
        Text("Version ${com.debasish.livefit.phone.BuildConfig.VERSION_NAME}", color = LiveFitColors.InkSoft)
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Code, LiveFitColors.ChipSlate, "Built on", "Rokid CXR-L · Wear Health Services", {}, trailing = {})
                HorizontalDivider(color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Policy, LiveFitColors.ChipMint, "Privacy policy", "Opens in your browser", {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Disclosures.PRIVACY_POLICY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                })
                HorizontalDivider(color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.BugReport, LiveFitColors.ChipSlate, "Share last crash",
                    if (crash == null) "No crash recorded" else "Opens the share sheet — nothing is sent automatically",
                    { crash?.let { context.startActivity(PhoneCrash.shareIntent(it)) } }, enabled = crash != null)
            }
        }
    }
}
```

(imports: `android.content.Intent`, `android.net.Uri`, `androidx.compose.material.icons.rounded.BugReport`, `androidx.compose.material.icons.rounded.Policy`, `androidx.compose.material3.HorizontalDivider`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.produceState`, `androidx.compose.ui.platform.LocalContext`, `com.debasish.livefit.model.Disclosures`, `com.debasish.livefit.phone.PhoneCrash`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`.)

- [ ] **Step 5: Watch About page** — `watch/src/main/java/com/debasish/livefit/watch/ui/WatchAbout.kt`:

```kotlin
package com.debasish.livefit.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.Disclosures
import com.debasish.livefit.watch.BuildConfig
import com.debasish.livefit.watch.OpenOnPhone
import kotlinx.coroutines.launch

/** Spec §7 (review P2-8): Privacy policy opens on the paired phone; the short summary is always shown as the fallback. */
@Composable
internal fun AboutPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Live AR Fit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        item { Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = W.Dim) }
        item {
            Chip(
                onClick = { scope.launch { note = if (OpenOnPhone.open(context, Disclosures.PRIVACY_POLICY_URL)) "Opened on your phone" else "Couldn't reach the phone" } },
                label = { Text("Privacy policy") }, secondaryLabel = { Text("Open on phone") }, colors = ChipDefaults.secondaryChipColors(),
            )
        }
        note?.let { item { Text(it, fontSize = 11.sp, color = W.Mint) } }
        item { Text(Disclosures.WATCH_SUMMARY, fontSize = 11.sp, color = W.Dim, textAlign = TextAlign.Center) }
        item { CompactChip(onClick = onClose, label = { Text("Close") }) }
    }
}
```

`WatchApp.kt` — in `WatchApp` add `var about by remember { mutableStateOf(false) }` and the Idle branch (line 122) becomes `WorkoutPhase.Idle -> if (about) AboutPage { about = false } else Ready(state.phoneOnline, state.glassesOnline, onAbout = { about = true }) { onCommand(Command.StartWorkout(it)) }`. `Ready` (line 146) gains `onAbout: () -> Unit` before `onStart`, and after the type pill `Row` (line 168) add:

```kotlin
        Spacer(Modifier.height(6.dp))
        Text("ⓘ About", fontSize = 12.sp, color = W.Dim, modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onAbout).padding(horizontal = 12.dp, vertical = 8.dp))
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest :watch:testDebugUnitTest :phone:assembleDebug :watch:assembleDebug`
Expected: PASS.

- [ ] **Step 7: Device check**

Phone: Setup → Music → the disclosure shows the full music text; Continue → notification access; "Not now" → back, music stays off, the rest of setup works. Settings → Permissions → Music control and Location → same disclosure first. Settings → About → Privacy policy opens the browser (404 until Task 10's page is published and Pages is enabled — acceptable here); Share last crash is disabled ("No crash recorded"). Watch: Ready → ⓘ About → Privacy policy → the page opens on the phone ("Opened on your phone"); with the phone's Bluetooth off → "Couldn't reach the phone", summary visible.

- [ ] **Step 8: Commit**

```bash
git add phone watch
git commit -m "Prominent disclosures (music incl. Data Layer cloud relay, location, mic) and in-app Privacy policy on phone and watch

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Lane M — maps

### Task 8: MapTiler tile source and attribution everywhere

**Files:**
- Modify: `core/map/src/main/kotlin/com/debasish/livefit/map/Tiles.kt:12-27`, `core/map/src/test/kotlin/com/debasish/livefit/map/TilesTest.kt:59-63,69-74,81`
- Modify: `phone/.../ServiceGraph.kt:8,103-104`, `phone/.../map/GlassesMapRenderer.kt:27,36,68,84-88`, `phone/.../ui/history/SessionDetailScreen.kt:97-108`
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/components/MapAttributionRow.kt`, `phone/src/main/res/drawable/maptiler_logo.xml`, `watch/src/main/res/drawable/maptiler_logo.xml`
- Modify: `watch/.../map/WatchTiles.kt:10,26`, `watch/.../ui/WatchMap.kt:102`
- Create: `watch/src/main/java/com/debasish/livefit/watch/OpenOnPhone.kt`, `watch/src/main/java/com/debasish/livefit/watch/ui/MapAttributionUi.kt`

**Interfaces:**
- Consumes: `BuildConfig.TILES_KEY`, `BuildConfig.DEBUG`, `BuildConfig.VERSION_NAME` (phone/watch, Task 2); `wear-remote-interactions` on the watch (Task 2).
- Produces: `data class AttributionLink(label: String, url: String)`; `data class MapAttribution(text: String, mapTilerLogo: Boolean, links: List<AttributionLink>)`; `object Attributions { OSM_LINK; MAPTILER_LINK; OSM; MAPTILER }`; `interface TileSource { userAgent: String; attribution: MapAttribution; fun url(tile: TileId): String }`; `class MapTilerTileSource(key: String, userAgent: String)`; `OsmTileSource(userAgent: String = TileSources.userAgent("dev"))`; `object TileSources { CONTACT; fun userAgent(version: String): String; fun select(key: String, debug: Boolean, version: String): TileSource }`; `ServiceGraph.mapAttribution: MapAttribution`; `GlassesMapRenderer(tiles, attribution: MapAttribution, logo: Bitmap?, sizePx = SIZE_PX)` + `GlassesMapRenderer.logo(context): Bitmap?`; `WatchTiles.attribution: MapAttribution`; `object OpenOnPhone { suspend fun open(context: Context, url: String): Boolean }` (used by Task 6); `@Composable fun MapAttributionRow(attribution: MapAttribution, modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the failing tests** — in `TilesTest.kt`: the anonymous source (lines 59-63) becomes

```kotlin
    private val source = object : TileSource {
        override val userAgent = "LiveARFit/test"
        override val attribution = Attributions.OSM
        override fun url(tile: TileId) = "http://127.0.0.1:${server.address.port}/${tile.z}/${tile.x}/${tile.y}.png"
    }
```

line 81 expects `listOf("LiveARFit/test")`; add `import kotlin.test.assertFailsWith`; replace `osmSourceUrlAndAttribution` (lines 69-74) with these tests:

```kotlin
    @Test fun osmSourceUrlAndAttribution() {
        val osm = OsmTileSource()
        assertEquals("https://tile.openstreetmap.org/18/1/2.png", osm.url(tile))
        assertEquals("© OpenStreetMap contributors", osm.attribution.text)
        assertFalse(osm.attribution.mapTilerLogo)
        assertTrue(osm.userAgent.startsWith("LiveARFit/"), "app-specific User-Agent")
    }

    @Test fun mapTilerStreetsV2RasterWithLogoAndBothLinks() {
        val mt = MapTilerTileSource("k3y", TileSources.userAgent("1.0.0"))
        assertEquals("https://api.maptiler.com/maps/streets-v2/256/18/1/2.png?key=k3y", mt.url(tile))
        assertEquals("© MapTiler © OpenStreetMap contributors", mt.attribution.text)
        assertTrue(mt.attribution.mapTilerLogo)
        assertEquals(listOf("https://www.maptiler.com/copyright/", "https://www.openstreetmap.org/copyright"), mt.attribution.links.map { it.url })
    }

    @Test fun userAgentNamesAppVersionAndContact() =
        assertEquals("LiveARFit/1.0.0 (com.livear.fit; contact: d.kanhar@gmail.com)", TileSources.userAgent("1.0.0"))

    /** Review focus 3: the key is in the URL only — never in the User-Agent, toString or anything logged. */
    @Test fun theKeyStaysOutOfTheUserAgentAndToString() {
        val mt = TileSources.select("s3cretKey", debug = false, version = "1.0.0")
        assertFalse("s3cretKey" in mt.userAgent)
        assertFalse("s3cretKey" in mt.toString())
    }

    @Test fun selectFallsBackToOsmOnlyInDebug() {
        assertTrue(TileSources.select("", debug = true, version = "1.0.0") is OsmTileSource)
        assertTrue(TileSources.select("k", debug = true, version = "1.0.0") is MapTilerTileSource)
        assertFailsWith<IllegalStateException> { TileSources.select(" ", debug = false, version = "1.0.0") }
    }

    /** Review focus 3: a revoked key (401/403) or an exhausted Free plan (429) → route only, no retry storm. */
    @Test fun keyAndQuotaErrorsBackOffLikeAnyFailure() {
        for (code in listOf(401, 403, 429)) {
            requests.set(0); status = code
            val f = fetcher()
            assertNull(f.fetch(tile), "HTTP $code")
            assertNull(f.fetch(tile))
            assertEquals(1, requests.get(), "HTTP $code: backed off")
        }
    }
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test`
Expected: FAIL — unresolved `Attributions`, `MapTilerTileSource`, `TileSources`.

- [ ] **Step 3: Implement** — `Tiles.kt` lines 12-27 (the `TileSource` interface and `OsmTileSource`) become:

```kotlin
/** A tappable attribution link (spec §5: maptiler.com/copyright, openstreetmap.org/copyright). */
data class AttributionLink(val label: String, val url: String)

/** What every map display must show for the tiles it draws (spec §5, review P2-6). */
data class MapAttribution(val text: String, val mapTilerLogo: Boolean, val links: List<AttributionLink>)

object Attributions {
    val OSM_LINK = AttributionLink("OpenStreetMap", "https://www.openstreetmap.org/copyright")
    val MAPTILER_LINK = AttributionLink("MapTiler", "https://www.maptiler.com/copyright/")
    val OSM = MapAttribution(OSM_ATTRIBUTION, mapTilerLogo = false, links = listOf(OSM_LINK))
    val MAPTILER = MapAttribution("© MapTiler © OpenStreetMap contributors", mapTilerLogo = true, links = listOf(MAPTILER_LINK, OSM_LINK))
}

/** Where tiles come from (spec §2.4, §5): behind an interface so the provider can be swapped. */
interface TileSource {
    val userAgent: String
    val attribution: MapAttribution
    fun url(tile: TileId): String
}

/** Debug/personal fallback only (spec §5): the OSM server is not for public-scale use. */
class OsmTileSource(override val userAgent: String = TileSources.userAgent("dev")) : TileSource {
    override val attribution: MapAttribution = Attributions.OSM
    override fun url(tile: TileId): String = "https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png"
}

/** Production tiles (spec §5): MapTiler raster "streets-v2", 256 px PNG, Free plan. The key never leaves the URL. */
class MapTilerTileSource(private val key: String, override val userAgent: String) : TileSource {
    init { require(key.isNotBlank()) { "MapTiler key is blank" } }
    override val attribution: MapAttribution = Attributions.MAPTILER
    override fun url(tile: TileId): String = "https://api.maptiler.com/maps/streets-v2/256/${tile.z}/${tile.x}/${tile.y}.png?key=$key"
    override fun toString(): String = "MapTilerTileSource(streets-v2/256)"
}

object TileSources {
    const val CONTACT = "d.kanhar@gmail.com"

    fun userAgent(version: String): String = "LiveARFit/$version (com.livear.fit; contact: $CONTACT)"

    /**
     * The build's tile source: MapTiler with a key; without one, debug builds use the OSM server and release builds
     * never get here (the Gradle build fails without LIVEAR_TILES_KEY) — so a keyless release is a programming error.
     */
    fun select(key: String, debug: Boolean, version: String): TileSource = when {
        key.isNotBlank() -> MapTilerTileSource(key, userAgent(version))
        debug -> OsmTileSource(userAgent(version))
        else -> throw IllegalStateException("release build without LIVEAR_TILES_KEY")
    }
}
```

- [ ] **Step 4: Run them to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test`
Expected: PASS (17 tests).

- [ ] **Step 5: The MapTiler logo asset (phone and watch)**

Download the official logo: `curl -sSfL https://api.maptiler.com/resources/logo.svg -o "$TMPDIR/maptiler-logo.svg"` (if that URL has moved, take the logo from the MapTiler attribution guidance linked at https://www.maptiler.com/copyright/). Convert to a VectorDrawable: `npx -y svg2vectordrawable -i "$TMPDIR/maptiler-logo.svg" -o phone/src/main/res/drawable/maptiler_logo.xml` (or Android Studio → New → Vector Asset → Local file) and copy the result to `watch/src/main/res/drawable/maptiler_logo.xml`. Do not redraw or alter the logo; it is recoloured only by tint at draw time.

- [ ] **Step 6: Glasses PNG (phone renderer) and phone wiring**

`GlassesMapRenderer.kt` — constructor (line 27):

```kotlin
class GlassesMapRenderer(
    private val tiles: TileLoader<Bitmap>,
    private val attribution: MapAttribution,
    private val logo: Bitmap?,
    private val sizePx: Int = SIZE_PX,
) {
```

line 36 becomes the two paints:

```kotlin
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim; textSize = 18f; textAlign = Paint.Align.RIGHT; isFakeBoldText = true }
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(dim, PorterDuff.Mode.SRC_IN) }
```

line 68 becomes:

```kotlin
        // Spec §5: MapTiler logo + "© MapTiler © OpenStreetMap contributors" on every image, in the HUD palette, HUD-legible.
        val textY = sizePx - 12f
        c.drawText(attribution.text, sizePx - 12f, textY, attributionPaint)
        if (attribution.mapTilerLogo && logo != null) c.drawBitmap(logo, sizePx - 12f - logo.width, textY - 24f - logo.height, logoPaint)
```

and in the companion (after `MAX_PNG_BYTES`) add:

```kotlin
        const val LOGO_HEIGHT_PX = 24

        /** The MapTiler logo at HUD size (tinted at draw time); null if the drawable is missing. */
        fun logo(context: Context): Bitmap? = ContextCompat.getDrawable(context, R.drawable.maptiler_logo)?.let { d ->
            d.toBitmap(width = LOGO_HEIGHT_PX * d.intrinsicWidth / d.intrinsicHeight.coerceAtLeast(1), height = LOGO_HEIGHT_PX)
        }
```

(imports: `android.content.Context`, `android.graphics.PorterDuff`, `android.graphics.PorterDuffColorFilter`, `androidx.core.content.ContextCompat`, `androidx.core.graphics.drawable.toBitmap`, `com.debasish.livefit.map.MapAttribution`, `com.debasish.livefit.phone.R`.)

`ServiceGraph.kt` — line 8 `import com.debasish.livefit.map.OsmTileSource` becomes `import com.debasish.livefit.map.MapAttribution` + `import com.debasish.livefit.map.TileSources`; lines 103-104 become:

```kotlin
    /** Spec §5: MapTiler in every build with a key; the OSM server only in keyless debug builds. */
    private val tileSource = TileSources.select(BuildConfig.TILES_KEY, BuildConfig.DEBUG, BuildConfig.VERSION_NAME)
    val mapAttribution: MapAttribution get() = tileSource.attribution
    private val mapTiles = GlassesMapRenderer.tileLoader(scope, HttpTileFetcher(tileSource, TileDiskCache(File(app.cacheDir, "tiles"), TileDiskCache.PHONE_MAX_BYTES)))
    private val mapRenderer = GlassesMapRenderer(mapTiles, tileSource.attribution, GlassesMapRenderer.logo(app))
```

`phone/src/main/java/com/debasish/livefit/phone/ui/components/MapAttributionRow.kt`:

```kotlin
package com.debasish.livefit.phone.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.debasish.livefit.map.MapAttribution
import com.debasish.livefit.phone.R
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** Spec §5: logo + text with tappable copyright links under a phone map display. */
@Composable
fun MapAttributionRow(attribution: MapAttribution, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (attribution.mapTilerLogo) {
                Image(painterResource(R.drawable.maptiler_logo), contentDescription = "MapTiler", modifier = Modifier.height(16.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(attribution.text, style = MaterialTheme.typography.labelSmall, color = LiveFitColors.InkSoft)
        }
        Row {
            attribution.links.forEach { link ->
                TextButton(onClick = { runCatching { uri.openUri(link.url) } }) { Text("© ${link.label}", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}
```

`SessionDetailScreen.kt` — right after the route `SoftCard { … }` (closing at line 108, inside `if (route.size >= 2)`) add `MapAttributionRow(services.mapAttribution, Modifier.padding(horizontal = 20.dp, vertical = 4.dp))`.

- [ ] **Step 7: Watch wiring**

`watch/src/main/java/com/debasish/livefit/watch/OpenOnPhone.kt`:

```kotlin
package com.debasish.livefit.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Opens a web page in the paired phone's browser (spec §5 map links, §7 privacy policy). True if the phone accepted. */
object OpenOnPhone {
    suspend fun open(context: Context, url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        withTimeoutOrNull(5_000) { RemoteActivityHelper(context.applicationContext).startRemoteActivity(intent).await(); true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
```

`WatchTiles.kt` — line 10 import becomes `com.debasish.livefit.map.MapAttribution` + `com.debasish.livefit.map.TileSources` (+ `com.debasish.livefit.watch.BuildConfig`); line 26 becomes:

```kotlin
    private val source = TileSources.select(BuildConfig.TILES_KEY, BuildConfig.DEBUG, BuildConfig.VERSION_NAME)
    /** What the Map page must show for these tiles (spec §5). */
    val attribution: MapAttribution get() = source.attribution
    private val fetcher = HttpTileFetcher(source, TileDiskCache(File(context.cacheDir, "tiles"), TileDiskCache.WATCH_MAX_BYTES))
```

`watch/src/main/java/com/debasish/livefit/watch/ui/MapAttributionUi.kt`:

```kotlin
package com.debasish.livefit.watch.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import com.debasish.livefit.map.MapAttribution
import com.debasish.livefit.watch.OpenOnPhone
import com.debasish.livefit.watch.R
import kotlinx.coroutines.launch

/** Spec §5: logo + text overlay and an "ⓘ Map data" tap target on the watch Map page. */
@Composable
internal fun MapAttributionBadge(attribution: MapAttribution, onInfo: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (attribution.mapTilerLogo) Image(painterResource(R.drawable.maptiler_logo), contentDescription = "MapTiler", modifier = Modifier.height(10.dp), colorFilter = ColorFilter.tint(W.Dim))
            Text(" ${attribution.text}", fontSize = 9.sp, color = W.Dim)
        }
        Text("ⓘ Map data", fontSize = 10.sp, color = Color.White,
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onInfo).defaultMinSize(minHeight = 32.dp).padding(horizontal = 10.dp, vertical = 8.dp))
    }
}

/** The attribution sheet: the full text and each copyright link, opened on the phone. */
@Composable
internal fun MapAttributionSheet(attribution: MapAttribution, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Map data", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        item { Text(attribution.text, fontSize = 11.sp, color = W.Dim, textAlign = TextAlign.Center) }
        items(attribution.links.size) { i ->
            val link = attribution.links[i]
            Chip(
                onClick = { scope.launch { note = if (OpenOnPhone.open(context, link.url)) "Opened on your phone" else "Couldn't reach the phone" } },
                label = { Text("© ${link.label}") }, secondaryLabel = { Text("Open on phone") }, colors = ChipDefaults.secondaryChipColors(),
            )
        }
        note?.let { item { Text(it, fontSize = 11.sp, color = W.Mint) } }
        item { CompactChip(onClick = onClose, label = { Text("Close") }) }
    }
}
```

`WatchMap.kt` — add `var sheet by remember { mutableStateOf(false) }` at the top of `WatchMapPage`; line 102 becomes `MapAttributionBadge(tiles.attribution) { sheet = true }`; after the `Column` (closing at line 103), inside the `BoxWithConstraints`, add `if (sheet) MapAttributionSheet(tiles.attribution) { sheet = false }` (import `androidx.compose.runtime.mutableStateOf`).

- [ ] **Step 8: Build and check on devices**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test :phone:testDebugUnitTest :watch:testDebugUnitTest :phone:assembleDebug :watch:assembleDebug`
Expected: PASS.

With `LIVEAR_TILES_KEY=<your key>` in `local.properties`, rebuild and install phone, watch, glasses. Outdoors (or with the debug location fallback), a GPS workout: the glasses Map page shows MapTiler streets with the logo and "© MapTiler © OpenStreetMap contributors" legible at the bottom right; the watch Map page shows logo + text and "ⓘ Map data" → sheet → "© MapTiler" → opens maptiler.com/copyright on the phone; the phone's history detail shows the row with both links. Without a key in a debug build: OSM tiles and "© OpenStreetMap contributors" only. Verify no key in logs: `adb -s <phone-serial> logcat -d | grep -c "<your key>"` → `0`.

- [ ] **Step 9: Commit**

```bash
git add core/map phone watch
git commit -m "Maps: MapTiler streets-v2 raster source with LIVEAR_TILES_KEY, contact User-Agent, logo + attribution on glasses, watch and phone

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Lane R — robustness

### Task 9: Robustness — I/O off the main thread, crash log, Room schema + migration test

**Files:**
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/CrashLog.kt`, `services/sync/src/test/kotlin/com/debasish/livefit/sync/CrashLogTest.kt`, `services/sync/src/test/kotlin/com/debasish/livefit/sync/GpsPreferencesLazyTest.kt`
- Modify: `services/sync/src/main/kotlin/com/debasish/livefit/sync/GpsPreferences.kt:9-10`
- Modify: `watch/.../WatchRuntime.kt:27-31,38,41-66`, `watch/.../WatchClient.kt:85`
- Modify: `phone/.../LiveFitApp.kt:20-23`; Create: `phone/src/main/java/com/debasish/livefit/phone/PhoneCrash.kt`
- Modify: `glasses/.../MainActivity.kt:70-72,119-121`
- Modify: `services/history/build.gradle.kts:19-31`, `services/history/.../HistoryDatabase.kt:10`; Create: `services/history/schemas/com.debasish.livefit.history.HistoryDatabase/2.json` (generated), `services/history/src/test/kotlin/com/debasish/livefit/history/HistoryMigrationTest.kt`

**Interfaces:**
- Produces: `class CrashLog(file: File, version: String, nowMs: () -> Long = System::currentTimeMillis) { fun install(); fun record(thread: Thread, error: Throwable); fun last(): String?; fun clear(); companion FILE_NAME = "last-crash.txt", MAX_CHARS = 65_536 }`; `object PhoneCrash { fun log(context: Context): CrashLog; fun shareIntent(text: String): Intent }` (used by Task 6); `WatchRuntime.scope` (serial background), `WatchRuntime.uiScope` (Main).

- [ ] **Step 1: Write the failing tests**

`services/sync/src/test/kotlin/com/debasish/livefit/sync/CrashLogTest.kt`:

```kotlin
package com.debasish.livefit.sync

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CrashLogTest {
    private val dir = Files.createTempDirectory("crash").toFile()
    private lateinit var saved: Thread.UncaughtExceptionHandler
    private val seen = mutableListOf<Throwable>()

    @BeforeTest fun up() {
        saved = Thread.getDefaultUncaughtExceptionHandler() ?: Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler { _, e -> seen += e }
    }

    @AfterTest fun down() = Thread.setDefaultUncaughtExceptionHandler(saved)

    @Test fun recordsVersionThreadAndStack() {
        val log = CrashLog(dir.resolve(CrashLog.FILE_NAME), "1.0.0", nowMs = { 0 })
        assertNull(log.last())
        log.record(Thread.currentThread(), IllegalStateException("boom"))
        val text = log.last()!!
        assertTrue(text.startsWith("Live AR Fit 1.0.0\nthread: "), text)
        assertTrue("time: 1970-01-01T00:00:00Z" in text)
        assertTrue("java.lang.IllegalStateException: boom" in text)
        log.clear()
        assertNull(log.last())
    }

    @Test fun aHugeStackIsCapped() {
        val log = CrashLog(dir.resolve(CrashLog.FILE_NAME), "1.0.0")
        log.record(Thread.currentThread(), RuntimeException("x".repeat(200_000)))
        assertEquals(CrashLog.MAX_CHARS, log.last()!!.length)
    }

    @Test fun installChainsToThePreviousHandlerOnce() {
        val log = CrashLog(dir.resolve(CrashLog.FILE_NAME), "1.0.0")
        log.install()
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        log.install()
        assertSame(installed, Thread.getDefaultUncaughtExceptionHandler(), "idempotent")
        val e = RuntimeException("crash")
        installed!!.uncaughtException(Thread.currentThread(), e)
        assertEquals(listOf<Throwable>(e), seen, "the platform handler still runs (process dies as before)")
        assertTrue("RuntimeException: crash" in log.last()!!)
    }

    @Test fun aFailingWriteNeverMasksTheCrash() {
        val blocked = dir.resolve("blocked").also { it.mkdirs(); it.resolve(CrashLog.FILE_NAME + ".tmp").mkdirs() }
        val log = CrashLog(blocked.resolve(CrashLog.FILE_NAME), "1.0.0")
        log.install()
        val e = RuntimeException("crash")
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), e)
        assertEquals(listOf<Throwable>(e), seen)
    }
}
```

`services/sync/src/test/kotlin/com/debasish/livefit/sync/GpsPreferencesLazyTest.kt`:

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.WorkoutType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpsPreferencesLazyTest {
    /** Spec §6: constructing it (main thread, WatchRuntime.init) must not read the disk. */
    @Test fun theFileIsReadOnFirstUseNotInTheConstructor() {
        val f = Files.createTempDirectory("gps").toFile().resolve("gps.json")
        val prefs = GpsPreferences(f)
        f.writeText(Wire.encode(mapOf(WorkoutType.Run to true)))
        assertTrue(prefs.get(WorkoutType.Run), "read after construction")
        assertFalse(prefs.get(WorkoutType.Walk))
    }
}
```

`services/history/src/test/kotlin/com/debasish/livefit/history/HistoryMigrationTest.kt`:

```kotlin
package com.debasish.livefit.history

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Spec §6: v1 history (commit b1f1131) survives MIGRATION_1_2; the v2 schema JSON is committed. */
@RunWith(RobolectricTestRunner::class)
class HistoryMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @After fun cleanUp() { context.deleteDatabase(name) }

    @Test fun version1HistorySurvivesTheRoutePointMigration() {
        val v1 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) = V1_DDL.forEach(db::execSQL)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build(),
        )
        v1.writableDatabase.apply {
            execSQL("INSERT INTO session (id, summaryJson, status, startMs, createdAtMs, endedAtMs, endReason) VALUES ('s1', NULL, 'Active', 1000, 1000, NULL, NULL)")
            execSQL("INSERT INTO delta (sessionId, seq, json) VALUES ('s1', 0, '{}')")
            execSQL("INSERT INTO sample (sessionId, tMs, hr, steps, distanceKm, kcal, speedKmh, provenance) VALUES ('s1', 1000, 120, 10, 0.01, 1.0, NULL, 'Fake')")
        }
        v1.close()

        // Opening runs MIGRATION_1_2 and validates the result against the v2 entities (Room throws on any mismatch).
        val db = Room.databaseBuilder(context, HistoryDatabase::class.java, name).addMigrations(HistoryDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val sql = db.openHelper.writableDatabase
            fun count(table: String) = sql.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getInt(0) }
            assertEquals(1, count("session"))
            assertEquals(1, count("delta"))
            assertEquals(1, count("sample"))
            assertEquals(0, count("route_point"))
            assertEquals(2, sql.version)
        } finally {
            db.close()
        }
    }

    @Test fun theV2SchemaIsExportedAndCommitted() =
        assertTrue(File("schemas/com.debasish.livefit.history.HistoryDatabase/2.json").isFile)

    private companion object {
        /** Room 2.6.1's DDL for the v1 entities (identical to their createSql in the exported 2.json). */
        val V1_DDL = listOf(
            "CREATE TABLE IF NOT EXISTS `session` (`id` TEXT NOT NULL, `summaryJson` TEXT, `status` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER, `endReason` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `delta` (`sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `seq`))",
            "CREATE TABLE IF NOT EXISTS `sample` (`sessionId` TEXT NOT NULL, `tMs` INTEGER NOT NULL, `hr` INTEGER, `steps` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, `kcal` REAL NOT NULL, `speedKmh` REAL, `provenance` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `tMs`))",
            "CREATE INDEX IF NOT EXISTS `index_sample_sessionId` ON `sample` (`sessionId`)",
        )
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test :services:history:testDebugUnitTest`
Expected: FAIL — unresolved `CrashLog`; `theFileIsReadOnFirstUseNotInTheConstructor` fails (read in the constructor → `false`); `theV2SchemaIsExportedAndCommitted` fails.

- [ ] **Step 3: Implement the pure parts and Room**

`services/sync/src/main/kotlin/com/debasish/livefit/sync/CrashLog.kt`:

```kotlin
package com.debasish.livefit.sync

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Spec §6: the last uncaught crash (app version, thread, time, stack) in app-private storage. Nothing is sent
 * anywhere: the phone's Settings → About → "Share last crash" hands it to a share sheet. The previously installed
 * handler still runs afterwards, so the process dies exactly as before.
 */
class CrashLog(private val file: File, private val version: String, private val nowMs: () -> Long = System::currentTimeMillis) {
    /** Idempotent: a second install (another entry point of the same process) keeps the first. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(this, previous))
    }

    fun record(thread: Thread, error: Throwable) {
        val stack = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = "Live AR Fit $version\nthread: ${thread.name}\ntime: ${Instant.ofEpochMilli(nowMs())}\n\n$stack"
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(text.take(MAX_CHARS))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun last(): String? = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear() { file.delete() }

    private class Handler(private val log: CrashLog, private val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try { log.record(t, e) } catch (_: Throwable) { /* never mask the original crash */ }
            previous?.uncaughtException(t, e)
        }
    }

    companion object {
        const val FILE_NAME = "last-crash.txt"
        const val MAX_CHARS = 64 * 1024
    }
}
```

`GpsPreferences.kt:9-10` becomes:

```kotlin
    /** Read on first use, not in the constructor: the watch builds this on the main thread (spec §6). */
    private val map: MutableMap<WorkoutType, Boolean> by lazy {
        runCatching { Wire.decode<Map<WorkoutType, Boolean>>(file.readText()).toMutableMap() }.getOrDefault(mutableMapOf())
    }
```

`HistoryDatabase.kt:10` → `exportSchema = true`. `services/history/build.gradle.kts` — after the `android { … }` block add:

```kotlin
// Spec §6: the schema JSON of every version is committed (services/history/schemas/).
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
```

and in `dependencies` add `testImplementation("androidx.sqlite:sqlite-framework:2.4.0")`.

Generate and keep the schema: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:history:kspDebugKotlin && ls services/history/schemas/com.debasish.livefit.history.HistoryDatabase/2.json`. Confirm the `createSql` of `session`, `delta` and `sample` in `2.json` equals `V1_DDL` (with `${TABLE_NAME}` → the table name); if a line differs, copy Room's text into `V1_DDL`.

- [ ] **Step 4: Main-thread I/O off the main thread and the crash handlers**

`WatchRuntime.kt` — lines 27-31 and 38 become:

```kotlin
    private val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.e(TAG, "uncaught in WatchRuntime.scope", e) }
    /**
     * One serial background dispatcher (spec §6: no file I/O on the main thread). The recorder and controller assume a
     * single-threaded caller; limitedParallelism(1) runs one task at a time, in order, off the main thread.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1) + handler)
    /** Compose-facing work (the Map page's tile loader, driven from composition) stays on the main thread. */
    val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + handler)
```

```kotlin
    val tiles: WatchTiles by lazy { WatchTiles(app, uiScope) }
```

In `init` (after `app = context.applicationContext`):

```kotlin
        CrashLog(File(app.filesDir, CrashLog.FILE_NAME), BuildConfig.VERSION_NAME).install()
        if (BuildConfig.DEBUG) android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build())
```

and wrap the one-time crash-recovery scan (the documented exception): move the existing `routes = WatchRouteFile(…).also { it.sweep() }` line and the whole existing `recorder = WatchSessionRecorder(…)` statement (WatchRuntime.kt:45-51), unchanged, into the `try` block:

```kotlin
        val policy = android.os.StrictMode.allowThreadDiskReads()
        try {
            // WatchRuntime.kt:45-51 moved here verbatim: routes = WatchRouteFile(…).also { it.sweep() }; recorder = WatchSessionRecorder(…)
        } finally {
            android.os.StrictMode.setThreadPolicy(policy)
        }
```

`MainActivity`'s two `WatchRuntime.controller.recheckPermissions()` calls stay on the main thread (Lane U owns that file): they only read permission state and set a `StateFlow`, which is thread-safe.

(import `com.debasish.livefit.sync.CrashLog`.) `WatchClient.kt:85` `pages = pagesFile.load()` becomes `WatchRuntime.scope.launch { pages = pagesFile.load(); refresh() }`.

`phone/src/main/java/com/debasish/livefit/phone/PhoneCrash.kt`:

```kotlin
package com.debasish.livefit.phone

import android.content.Context
import android.content.Intent
import com.debasish.livefit.sync.CrashLog
import java.io.File

/** Spec §6: the phone's crash log and its share sheet; nothing is sent automatically. */
object PhoneCrash {
    fun log(context: Context): CrashLog = CrashLog(File(context.applicationContext.filesDir, CrashLog.FILE_NAME), BuildConfig.VERSION_NAME)

    fun shareIntent(text: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Live AR Fit crash report")
            .putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, "Share last crash").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
```

`LiveFitApp.kt:20-23` becomes:

```kotlin
    override fun onCreate() {
        super.onCreate()
        PhoneCrash.log(this).install()
        if (BuildConfig.DEBUG) android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().penaltyLog().build())
        // Spec §6: start loading these files on SharedPreferences' own thread now, so the first main-thread read
        // (SettingsStore, CompanionLinker, PermissionSource) finds them in memory.
        for (name in listOf("settings", "companion", "rokid")) getSharedPreferences(name, MODE_PRIVATE)
        try { LiveFitHubService.ensureRunning(this) } catch (e: Exception) { android.util.Log.w("LiveFitHub", "hub start at process start failed", e) }
    }
```

`glasses/.../MainActivity.kt` — first lines of `onCreate` (after `super.onCreate`):

```kotlin
        CrashLog(File(filesDir, CrashLog.FILE_NAME), packageManager.getPackageInfo(packageName, 0).versionName ?: "?").install()
```

and lines 119-121 (`val mapBitmap = remember(...) { decode }`) become a decode off the main thread:

```kotlin
            val mapBitmap by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, mapImage, sessionId) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    mapImage?.takeIf { it.sessionId == sessionId }?.let { android.graphics.BitmapFactory.decodeByteArray(it.png, 0, it.png.size)?.asImageBitmap() }
                }
            }
```

(imports: `com.debasish.livefit.sync.CrashLog`, `java.io.File`.)

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug`
Expected: PASS (all modules; `WatchSessionRecorderTest` and `WatchExerciseControllerTest` unchanged).

- [ ] **Step 6: Device check (StrictMode, crash log)**

Install debug builds on all three, run one short workout started from the phone and stopped from the watch. Then:
`adb -s <watch-serial> logcat -d -s StrictMode | grep -E "FileDeltaBuffer|GpsPreferences|PageSettingsFile|WatchSessionRecorder\.record|onAck"` → no lines;
`adb -s <phone-serial> logcat -d -s StrictMode | grep -E "SettingsStore|CompanionLinker"` → no lines;
`adb -s <glasses-serial> logcat -d -s StrictMode | grep BitmapFactory` → no lines (StrictMode is phone/watch only; on the glasses just confirm the map page renders).
Crash log: `adb -s <phone-serial> shell am crash com.livear.fit`, reopen → Settings → About → Share last crash is enabled and the share sheet contains "Live AR Fit 1.0.0".

- [ ] **Step 7: Commit**

```bash
git add services/sync services/history watch phone glasses
git commit -m "Robustness: watch recorder I/O on a serial background dispatcher, lazy GPS prefs, off-main PNG decode, crash log, Room schema export + v1→v2 migration test

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Lane D — documents

### Task 10: Documents — README, install guide, LICENSE, notices, changelog, privacy policy, Play drafts

**Files:**
- Create: `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `CHANGELOG.md`, `docs/install-glasses.md`, `docs/privacy-policy.md`, `docs/play/data-safety.md`, `docs/play/health-apps-declaration.md`, `docs/play/foreground-services.md`, `docs/play/permissions.md`, `docs/play/store-listing.md`, `docs/play/content-rating.md`, `tools/release/test_docs.py`

**Interfaces:**
- Consumes: `Disclosures.*` wording (Task 4) — quoted verbatim; secrets/variable names (Task 11); `ReleaseSigning.KEYS` names (Task 2).
- Produces: the published privacy policy at `Disclosures.PRIVACY_POLICY_URL` once the owner enables GitHub Pages (Settings → Pages → `main` / `/docs`).

- [ ] **Step 1: Write the failing test** — `tools/release/test_docs.py`:

```python
import os
import re
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DOCS = ["README.md", "THIRD_PARTY_NOTICES.md", "CHANGELOG.md", "docs/install-glasses.md", "docs/privacy-policy.md",
        "docs/play/data-safety.md", "docs/play/health-apps-declaration.md", "docs/play/foreground-services.md",
        "docs/play/permissions.md", "docs/play/store-listing.md", "docs/play/content-rating.md"]


def read(path):
    with open(os.path.join(ROOT, path)) as f:
        return f.read()


class DocsTest(unittest.TestCase):
    def test_every_document_exists(self):
        for d in DOCS + ["LICENSE"]:
            self.assertTrue(os.path.isfile(os.path.join(ROOT, d)), d)

    def test_licence_is_apache_2(self):
        self.assertIn("Apache License", read("LICENSE"))
        self.assertIn("Version 2.0, January 2004", read("LICENSE"))

    def test_privacy_policy_covers_the_spec_list(self):
        p = read("docs/privacy-policy.md")
        for needed in ("heart rate", "location", "audio", "on-device", "title, artist, playback state and up-next queue",
                       "sent to your paired watch and glasses", "Google's cloud, encrypted", "no server of its own",
                       "MapTiler", "IP address", "Clear history", "uninstall", "no account", "no analytics",
                       "d.kanhar@gmail.com"):
            self.assertIn(needed, p, needed)

    def test_data_safety_matches_the_relay_and_encryption(self):
        d = read("docs/play/data-safety.md")
        for needed in ("encrypted in transit", "Google's cloud", "Data safety", "shared"):
            self.assertIn(needed, d, needed)

    def test_readme_has_the_keystore_and_secret_setup(self):
        r = read("README.md")
        for needed in ("keytool -genkeypair", "keystore.properties", "LIVEAR_KEYSTORE", "LIVEAR_KEY_ALIAS",
                       "LIVEAR_STORE_PASSWORD", "LIVEAR_KEY_PASSWORD", "LIVEAR_TILES_KEY", "LIVEAR_KEYSTORE_BASE64",
                       "LIVEAR_UPLOAD_CERT_SHA256", "unsigned-diagnostic", "docs/install-glasses.md"):
            self.assertIn(needed, r, needed)

    def test_no_rokid_in_the_app_name(self):
        listing = read("docs/play/store-listing.md")
        self.assertRegex(listing, r"(?m)^App name: Live AR Fit$")

    def test_public_repo_hygiene_no_serials_adb_names_or_macs(self):
        """Spec §11.5: the new public documents carry placeholders (<phone-serial>), never real identifiers."""
        mac = re.compile(r"\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b")
        mdns = re.compile(r"\badb-[A-Za-z0-9]{6,}-[A-Za-z0-9]{4,}\b")  # wireless-adb service names: adb-<serial>-<suffix>
        for path in DOCS:
            text = read(path)
            self.assertIsNone(mac.search(text), "MAC address in " + path)
            self.assertIsNone(mdns.search(text), "adb mDNS name in " + path)


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python3 -m unittest discover -s tools/release -p 'test_docs.py' -v`
Expected: FAIL — `test_every_document_exists` (missing files).

- [ ] **Step 3: Write the documents** (content requirements; wording consistent with the spec and `Disclosures.kt`, quoted verbatim where named)

- `LICENSE`: `curl -sSfL https://www.apache.org/licenses/LICENSE-2.0.txt -o LICENSE` (unmodified Apache-2.0 text).
- `README.md`:
  1. What it is (one paragraph: a fitness HUD on Rokid AR glasses with a Wear OS watch for heart rate/GPS and the phone as hub), hardware (Android 10+ phone, Wear OS 3+ watch, Rokid glasses with Hi Rokid), and an ASCII architecture sketch (phone hub ↔ CXR-L/Bluetooth ↔ glasses; phone ↔ Wear Data Layer ↔ watch; module list `core/*`, `services/*`, three apps).
  2. Build: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug`; the tile key line `LIVEAR_TILES_KEY=<key>` in `local.properties` (debug works without it, using the OSM server; release fails without it).
  3. Release keystore (owner, once): `keytool -genkeypair -v -keystore ~/keys/livear-upload.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`; keep it and both passwords in a password manager with an offline backup (spec §11.3: Play can reset a lost upload key; the GitHub glasses APK cannot — users would have to uninstall); `keystore.properties` at the repo root (untracked) with the four keys `LIVEAR_KEYSTORE=/absolute/path/livear-upload.jks`, `LIVEAR_KEY_ALIAS=upload`, `LIVEAR_STORE_PASSWORD=…`, `LIVEAR_KEY_PASSWORD=…` (or the same names as env vars); certificate fingerprint: `keytool -list -v -keystore ~/keys/livear-upload.jks -alias upload | grep SHA256`. Without a keystore, release outputs are debug-signed `*-unsigned-diagnostic` and must never be distributed. Enable Play App Signing on first upload.
  4. Release builds: `./gradlew :phone:bundleRelease :watch:bundleRelease :glasses:assembleRelease` and `./gradlew check16kb`; versioning (`livefit.version` in `gradle.properties`, versionCode scheme with the table phone/watch/glasses for 1.0.0 = 1000000/1000001/1000002; a `-beta` suffix does not change the code — never upload a beta and its release with the same numbers).
  5. CI/tag release: the secrets `LIVEAR_KEYSTORE_BASE64` (`base64 -i ~/keys/livear-upload.jks | pbcopy`), `LIVEAR_KEY_ALIAS`, `LIVEAR_STORE_PASSWORD`, `LIVEAR_KEY_PASSWORD`, `LIVEAR_TILES_KEY`, and the variable `LIVEAR_UPLOAD_CERT_SHA256`; tag `v1.0.0` → draft release with the glasses APK + `SHA256SUMS.txt`; the AABs are a workflow artifact for the Play upload.
  6. Installing: phone + watch from Play (one listing); glasses → `docs/install-glasses.md`. Upgrade note: installs of the old `com.debasish.livefit` are a different app — uninstall them; local history is not migrated.
  7. Crash logs: phone Settings → About → Share last crash; watch/glasses: `adb shell run-as com.livear.fit cat files/last-crash.txt` / `run-as com.livear.fit.glasses …` (debuggable builds only).
  8. Licence (Apache-2.0), privacy policy link (`Disclosures.PRIVACY_POLICY_URL`), contact d.kanhar@gmail.com. No device serials, adb names or MACs anywhere.
- `docs/install-glasses.md`: enable developer mode/adb on the Rokid glasses (as documented by Rokid; link their developer docs), `adb devices` (placeholder `<glasses-serial>`), `adb -s <glasses-serial> install -r live-ar-fit-glasses-v1.0.0.apk`, verify the SHA-256 against `SHA256SUMS.txt` (`shasum -a 256`), grant `RECORD_AUDIO` and `BLUETOOTH_ADVERTISE` (`adb shell pm grant com.livear.fit.glasses …`), first pairing via Hi Rokid + the phone app's Authorize/Pair steps, upgrades (`install -r` over the same signed APK), and the **matching-version rule** (phone, watch and glasses must be the same `livefit.version`; a mismatch shows "update" banners).
- `THIRD_PARTY_NOTICES.md`: OpenStreetMap — "© OpenStreetMap contributors", data under the ODbL 1.0 (https://www.openstreetmap.org/copyright); MapTiler — Free plan, the current usage limit and terms copied from https://www.maptiler.com/cloud/pricing/ and https://www.maptiler.com/terms/ with the date checked, the attribution requirement (logo + text), and the note that a move to a paid plan is an owner decision; Rokid CXR SDK (`client-l`, `cxr-service-bridge`) — licence/redistribution terms as published by Rokid, with an explicit "owner to confirm redistribution in a public APK" line if Rokid publishes none; AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines/serialization, Play services Wearable, Health Services, Gson — Apache-2.0 (Play services under the Android SDK licence), listed from `./gradlew :phone:dependencies :watch:dependencies :glasses:dependencies --configuration releaseRuntimeClasspath`.
- `CHANGELOG.md` (Keep a Changelog style): `1.0.0` — production release (rename to Live AR Fit, MapTiler tiles, privacy disclosures, battery banner, OS-dependent heart-rate permission, crash log, signed R8 builds); `0.2.0-beta` — pages, live map, configurable gestures (plan `2026-10-09-livefit-pages-maps-gestures-plan.md`); `0.1.0-beta` — V1 hub, watch, glasses HUD, voice, music. Dates = the date of the merge commit of each milestone (`git log --merges --format='%ad %s' --date=short`).
- `docs/privacy-policy.md` (GitHub Pages front matter `---\ntitle: Live AR Fit privacy policy\n---`): who (Debasish Kanhar, contact d.kanhar@gmail.com), effective date; **no account, no ads, no analytics**, no third-party crash SDK; health data (heart rate, steps, distance, calories from Wear Health Services; stored on the phone and watch); location (quote `Disclosures.LOCATION` verbatim); audio (quote `Disclosures.MIC`; speech stays on-device, it must not change to cloud recognition); music metadata (quote `Disclosures.MUSIC`); how phone↔watch data travels (quote `Disclosures.DATA_LAYER`; applies to workout, heart rate, location fixes, settings and music); the tile provider (MapTiler) sees your IP address and the tile coordinates (the map area); local storage only (Room DB, app files); data deletion (Clear history on the phone, uninstall removes everything; backups are disabled); children (not directed at children); changes to the policy; contact.
- `docs/play/data-safety.md`: per data type (Location — precise; Health and fitness — heart rate, fitness info; Audio — not collected (processed on-device, never leaves the device or is stored); App activity/info — none; App info and performance — crash log only if the user shares it manually): collected? (follow current Play definitions: on-device-only processing is not "collection"), shared?, purpose, optional/required, **"data is encrypted in transit"**. Add a section "Google Play services Data Layer relay" that quotes the current Data safety guidance on what counts as "shared" (https://support.google.com/googleplay/android-developer/answer/10787469, retrieved date) and records the resulting answer and why — the spec requires the answer to follow that guidance.
- `docs/play/health-apps-declaration.md`: app category (fitness/activity tracking), the health features (live heart rate, workout tracking on the watch via Health Services, no Health Connect, no background-health permissions), permissions (`BODY_SENSORS` ≤ API 35, `android.permission.health.READ_HEART_RATE` API 36+, `ACTIVITY_RECOGNITION`), no medical claims, data handling summary pointing to the privacy policy.
- `docs/play/foreground-services.md`: for phone `connectedDevice` (keeps the glasses/watch link during a workout the user started), phone `location` (phone GPS fallback during a GPS workout while LiveFit is visible), watch `health` (exercise tracking), watch `location` (GPS workouts with "Use GPS outdoors" on) — each with the user-visible trigger, why it can't be deferred, and a **screen-recording script** (numbered shots: open app → start workout → notification visible → screen off → glasses/watch keep updating → stop → notification gone), each under 30 s.
- `docs/play/permissions.md`: one row per declared permission of phone and watch (from the merged release manifests: `aapt2 dump permissions`) with the user-facing rationale; mark the removed ones (`USE_FULL_SCREEN_INTENT`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) and the notification-listener disclosure.
- `docs/play/store-listing.md`: a line `App name: Live AR Fit`; short description (≤ 80 chars) and full description (≤ 4000 chars) for phone and for Wear; "works with Rokid AR glasses" only as a compatibility statement, never in the name; screenshots list (phone, watch, a glasses HUD capture); category Health & Fitness; contact email; privacy policy URL.
- `docs/play/content-rating.md`: IARC questionnaire answers (no violence, sexual content, profanity, drugs, gambling; no user-to-user communication or sharing; shares location with no other users; no purchases) and the expected rating.

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m unittest discover -s tools/release -p 'test_docs.py' -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add README.md LICENSE THIRD_PARTY_NOTICES.md CHANGELOG.md docs/install-glasses.md docs/privacy-policy.md docs/play tools/release/test_docs.py
git commit -m "Docs: README with keystore setup, glasses install guide, Apache-2.0, notices, changelog, privacy policy, Play Console drafts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Finish

### Task 12: Release acceptance on devices (controller-run)

**Files:** none planned. Keep-rule fixes found by the smoke test go into the matching `proguard-rules.pro` with their own commit ("R8: keep … (found by release smoke test)").

**Interfaces:** consumes everything; produces the go/no-go record for the owner.

- [ ] **Step 1: Owner-signed release builds and static gates**

Run (owner machine with `keystore.properties` and `LIVEAR_TILES_KEY`): `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew -Plivefit.requireSigning=true test check16kb :phone:assembleRelease :watch:assembleRelease`
Expected: tests PASS; `check16kb` table — **must be all PASS/SKIP for v1.0.0** (spec §3 hard gate; if it fails, stop here and report: release blocked). Record the table.
Run: `APKSIGNER=$(ls -d $ANDROID_HOME/build-tools/*/ | sort -V | tail -1)apksigner; for a in phone/build/outputs/apk/release/phone-release.apk watch/build/outputs/apk/release/watch-release.apk glasses/build/outputs/apk/release/glasses-release.apk; do $APKSIGNER verify --print-certs $a | grep "SHA-256"; done` → the same upload-key digest three times.

- [ ] **Step 2: Install release builds** — `adb -s <glasses-serial> install -r glasses/build/outputs/apk/release/glasses-release.apk`, `adb -s <watch-serial> install -r watch/build/outputs/apk/release/watch-release.apk`, `adb -s <phone-serial> install -r --user 0 phone/build/outputs/apk/release/phone-release.apk` (uninstall the old `com.debasish.livefit*` apps first).

- [ ] **Step 3: Smoke checklist (spec §9) — tick each, note failures with `adb logcat -b crash`**

- [ ] Pair (setup flow: authorise Hi Rokid, pair glasses, pair watch).
- [ ] Start/stop a workout from the phone; start/stop from the watch.
- [ ] Glasses pages and gestures (all six pages, scroll mode on Playlist/Music controls, Close app).
- [ ] Map page renders (glasses, watch) with MapTiler tiles and attribution; history thumbnail attribution row.
- [ ] Music controls (play/pause/next, queue, like) — after the music disclosure.
- [ ] Voice command (on-device pack installed).
- [ ] Remote watch launch (workout screen opens on phone start) and the pairing fallback (`livefit://discoverable`).
- [ ] The renamed glasses app opens through CXR (phone connects; glasses show the HUD).
- [ ] Privacy policy action on the phone (browser) and on the watch (opens on the phone).
- [ ] Watch workout start on API ≤ 35 (e.g. Galaxy Watch 4/6, Wear OS 4/5) and on API 36: heart rate shows.
- [ ] Battery banner: shown while optimised, gone when exempt; Samsung steps on a Samsung phone.
- [ ] A browser opening `livefit://workout` on the watch changes no state (Task 7 Step 6c).
- [ ] targetSdk 36 behaviour: the phone hub FGS starts after reboot and app update, notifications appear, Bluetooth link survives screen-off for 10 min.
- [ ] Release logs: `adb -s <phone-serial> logcat -d | grep -E "LiveFit" | grep -E " [DIV] "` → no lines (v/d/i stripped).

- [ ] **Step 4: 16 KB runtime smoke test**

Emulator: `sdkmanager "system-images;android-35;google_apis_ps16k;arm64-v8a"`, create and boot an AVD from it, `adb -s <emulator-serial> shell getconf PAGE_SIZE` → `16384`. Install the release phone APK and glasses APK there; launch both; `adb -s <emulator-serial> logcat -d | grep -iE "dlopen|UnsatisfiedLink|page size|16 ?KB"` → no errors; the phone app reaches Home and the glasses app reaches "Open Live AR Fit". The emulator has no Bluetooth to the glasses, so the spec's "pairs with the glasses through CXR and renders the HUD" part needs a 16 KB **device** (a Pixel 8 or newer with Developer options → "Boot with 16 KB page size"): `getconf PAGE_SIZE` = 16384 there, then pair with the glasses and confirm the HUD renders. Record which environment passed; both parts are required for go.

- [ ] **Step 5: Report** — the gate table, signer digests, the checklist with pass/fail, any keep rules added, and the go/no-go (no-go if anything in Steps 1 or 4 fails).
