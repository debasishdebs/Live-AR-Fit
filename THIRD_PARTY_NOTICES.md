# Third-party notices

Live AR Fit is licensed under the [Apache License 2.0](LICENSE). It uses the data, services and libraries below,
each under its own terms.

## Map data: OpenStreetMap

Map data © OpenStreetMap contributors. The data is available under the Open Database License (ODbL) 1.0; see
https://www.openstreetmap.org/copyright.

Debug builds without a MapTiler key download tiles directly from the OpenStreetMap tile server
(`tile.openstreetmap.org`) under the [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/).
Release builds do not use that server.

## Map tiles: MapTiler

Release builds download raster map tiles ("streets-v2", 256 px PNG) from MapTiler Cloud on the **Free plan**.

Terms recorded on 2026-10-10 from https://www.maptiler.com/cloud/pricing/ and https://www.maptiler.com/terms/:

- **Monthly allowance (Free plan):** 100,000 API requests, 5,000 map sessions (plus search, 3D and data-processing
  allowances that Live AR Fit does not use).
- **When the allowance is used up:** "On a FREE plan service will pause until the next month without an upgrade to paid
  plans." Maps then stop loading new tiles until the next month; workouts still record.
- **Permitted use:** the pricing page describes the Free plan as "suitable for testing, PoC, prototyping, personal, or
  non-commercial use". The terms add: "With a free account, you may only use the Services up to the quota allowed under
  the free tiers". Live AR Fit is free, open source, has no ads and no purchases.
- **Attribution:** the Free plan requires the **MapTiler logo** on the map, and OpenStreetMap data requires
  "© OpenStreetMap" linked to https://www.openstreetmap.org/copyright. The terms say: "The attribution must always be
  visible and readable on any screen or medium". Every Live AR Fit map display shows the MapTiler logo plus the text
  "© MapTiler © OpenStreetMap contributors"; the watch's "ⓘ Map data" sheet links to https://www.maptiler.com/copyright/
  and https://www.openstreetmap.org/copyright.

Moving to a paid MapTiler plan is an owner decision, if usage or the terms require it. Re-check both pages before each
release and update the date above.

## Rokid CXR SDK

- `com.rokid.cxr:client-l` (phone app): the phone side of the Rokid CXR-L link.
- `com.rokid.cxr:cxr-service-bridge` (glasses app): the glasses side of the link.

Both are binary libraries from Rokid's Maven repository (https://maven.rokid.com/repository/maven-public/) and are
© Rokid. Their Maven metadata (POM) declares no licence, and no redistribution terms were found published by Rokid at
the time of writing.

**Owner to confirm with Rokid that these libraries may be redistributed inside a public APK (Google Play and GitHub
Releases) before the 1.0.0 release.**

The Apache License of this repository does not cover the Rokid libraries.

## Open-source libraries

From `./gradlew :phone:dependencies :watch:dependencies :glasses:dependencies --configuration releaseRuntimeClasspath`
(checked 2026-10-10). Versions are those resolved by the build at that date.

### Apache License 2.0

The full text is in [LICENSE](LICENSE) and at https://www.apache.org/licenses/LICENSE-2.0.

- **Kotlin:** `org.jetbrains.kotlin:kotlin-stdlib` (and `-common`, `-jdk7`, `-jdk8`), `org.jetbrains:annotations`.
- **kotlinx.coroutines:** `kotlinx-coroutines-core`, `-android`, `-guava`, `-play-services`.
- **kotlinx.serialization:** `kotlinx-serialization-core`, `kotlinx-serialization-json`.
- **AndroidX core:** `activity` (`-ktx`, `-compose`), `annotation`, `arch.core`, `autofill`, `collection`,
  `concurrent-futures`, `core` (`-ktx`), `customview`, `documentfile`, `dynamicanimation`, `emoji2`, `fragment`,
  `graphics-path`, `interpolator`, `legacy-support-core-utils`, `lifecycle` (common, runtime, viewmodel, livedata,
  process, compose), `loader`, `localbroadcastmanager`, `navigation` (`-common`, `-runtime`, `-compose`), `print`,
  `profileinstaller`, `recyclerview`, `savedstate`, `startup-runtime`, `swiperefreshlayout`, `tracing`,
  `versionedparcelable`, `viewpager`.
- **Jetpack Compose:** `compose.runtime`, `compose.ui` (and `ui-graphics`, `ui-text`, `ui-unit`, `ui-geometry`,
  `ui-util`, `ui-tooling-preview`), `compose.foundation`, `compose.animation`, `compose.material3`,
  `compose.material:material-ripple`, `material-icons-core`, `material-icons-extended`.
- **Room:** `androidx.room:room-runtime`, `room-ktx`, `room-common`; `androidx.sqlite:sqlite`, `sqlite-framework`.
- **Wear OS:** `androidx.wear:wear`, `wear-ongoing`, `wear-remote-interactions`; `androidx.wear.compose:compose-material`,
  `compose-material-core`, `compose-foundation`.
- **Health Services:** `androidx.health:health-services-client` (and its `-proto`, `-external-protobuf` parts).
- **Gson:** `com.google.code.gson:gson`.
- **Guava:** `com.google.guava:guava`, `failureaccess`, `listenablefuture`.
- **Annotations:** `com.google.errorprone:error_prone_annotations`, `com.google.j2objc:j2objc-annotations`,
  `com.google.code.findbugs:jsr305`, `org.jspecify:jspecify`.

### MIT License

- `org.checkerframework:checker-qual` (annotations only), © the Checker Framework developers; see
  https://github.com/typetools/checker-framework/blob/master/LICENSE.txt.

### Google Play services

- `com.google.android.gms:play-services-wearable`, `play-services-base`, `play-services-basement`,
  `play-services-tasks`: under the Android Software Development Kit License,
  https://developer.android.com/studio/terms.
