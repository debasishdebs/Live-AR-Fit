# Changelog

All notable changes to Live AR Fit are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).
The phone, watch and glasses apps always share one version.

## [1.0.0] - Unreleased

First production release: phone and Wear OS apps on Google Play, glasses app on GitHub Releases.
<!-- Owner: set the date to the merge commit of the production-release work when tagging v1.0.0. -->

### Changed

- Renamed to **Live AR Fit**. New application IDs `com.livear.fit` (phone and watch) and `com.livear.fit.glasses`
  (glasses). Old test builds (`com.debasish.livefit`) are a different app: uninstall them; their history is not
  migrated.
- Map tiles now come from MapTiler (raster "streets-v2") with the MapTiler logo and
  "© MapTiler © OpenStreetMap contributors" on every map. Debug builds without a key still use the OSM server.
- The watch asks for the heart-rate permission that matches its OS: `BODY_SENSORS` up to Android 15 (API 35),
  `READ_HEART_RATE` from API 36. Watches on API 35 and below can start workouts again.
- The watch shows a running workout as a Wear OS Ongoing Activity instead of a full-screen intent.
- Release builds are signed with the upload key and shrunk with R8; verbose logs are stripped.
- Phone and watch target Android 16 (API 36).

### Added

- Prominent disclosures before the location, microphone and notification-access (music) requests, and a privacy
  policy reachable from phone Settings → About and the watch's settings.
- A battery-optimisation banner with **Open settings**, replacing the direct exemption request.
- A local crash log; phone Settings → About → **Share last crash** shares it manually.
- Apache-2.0 licence, third-party notices, glasses install guide.

### Removed

- The `USE_FULL_SCREEN_INTENT` (watch) and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (phone) permissions.
- App backup: nothing is copied off the device by Android backup.

## [0.2.0-beta] - 2026-10-09

Pages, live map and configurable gestures (plan `docs/superpowers/plans/2026-10-09-livefit-pages-maps-gestures-plan.md`).

### Added

- One shared page set on the glasses and the watch: Glance, Workout, Stats, Playlist, Map and Music controls.
- A live street map of the route on the glasses and the watch, from the watch's GPS, with the phone's GPS as a
  fallback when the watch has no fix.
- The route stored with each workout, and a route thumbnail in the phone's workout history.
- Every glasses touchpad gesture configurable per page and mode from the phone.
- Voice phrases for the new pages.

## [0.1.0-beta] - 2026-10-06

First beta (V1).

### Added

- Phone hub: starts, pauses and stops workouts from the phone, the watch, the glasses or by voice, with confirmations;
  stores the workout history on the phone.
- Wear OS watch app: workouts through Health Services (heart rate, steps, distance, calories), buffered offline and
  synced to the phone.
- Glasses HUD on Rokid AR glasses over the Rokid CXR link: live stats, music and status.
- Offline, on-device voice commands.
- YouTube Music control (play, skip, like) from the phone, watch and glasses.
