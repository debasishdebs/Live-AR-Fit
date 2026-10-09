# Permissions and rationales (draft)

Every permission declared by the phone and watch apps (`com.livear.fit`), with the user-facing reason. Listed from the
merged release manifests (`phone/build/intermediates/merged_manifest/release/.../AndroidManifest.xml` and the watch's;
`aapt2 dump permissions` on the release APKs gives the same list). Re-check against the final 1.0.0 bundles before
submitting. Owner to review.

## Phone

| Permission | Runtime prompt? | User-facing rationale |
|---|---|---|
| `INTERNET` | No | Download map tiles from MapTiler for the glasses map. |
| `RECORD_AUDIO` | Yes, after the in-app microphone rationale | Voice commands. Recognised on the phone, on-device only; audio is never uploaded or stored. |
| `BLUETOOTH_CONNECT` | Yes ("Nearby devices") | Connect to your Rokid glasses and Wear OS watch. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Yes, after the in-app location disclosure; optional | Phone GPS as a route fallback when the watch has no GPS fix, during workouts with "Use GPS outdoors" on, never in the background when no workout is running. |
| `FOREGROUND_SERVICE` | No | Run the hub as a foreground service. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | No | Keep the link with your paired glasses and watch while they are nearby or a workout runs (see `foreground-services.md`). |
| `FOREGROUND_SERVICE_LOCATION` | No | Phone GPS fallback during a GPS workout, started while Live AR Fit is on screen (see `foreground-services.md`). |
| `POST_NOTIFICATIONS` | Yes (Android 13+) | Show the ongoing hub/workout notification. |
| `RECEIVE_BOOT_COMPLETED` | No | Reconnect to your linked glasses and watch after the phone restarts. |
| `REQUEST_COMPANION_PROFILE_WATCH` | No (the system pairing dialog asks) | Pair the watch as a companion device. |
| `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE` | No | Start Live AR Fit when your paired glasses or watch come nearby ("Start LiveFit when nearby", per device). |
| `REQUEST_COMPANION_RUN_IN_BACKGROUND` | No | Keep the hub running for a paired companion device. |
| `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | No | Start the hub when a paired device comes nearby while the app is in the background. |
| `REQUEST_COMPANION_USE_DATA_IN_BACKGROUND` | No | Exchange workout data with the paired device in the background. |
| `com.livear.fit.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | No | Added by AndroidX (`core`); a signature permission that keeps the app's internal broadcast receivers private. Not user-facing. |

Feature: `android.software.companion_device_setup` (companion device pairing).

### Notification access (music control)

Not a manifest permission, but a special access the user grants in system settings. The music module declares a
`NotificationListenerService` (`com.debasish.livefit.services.music.MediaListener`, protected by
`BIND_NOTIFICATION_LISTENER_SERVICE`). Holding notification access is what lets the app read the active media session
of YouTube Music; the app does not read notifications.

A prominent disclosure (`Disclosures.MUSIC`) comes before the app sends the user to the system screen:

> Live AR Fit reads the active media session of your music app (YouTube Music): the title, artist, playback state and
> up-next queue. This is sent to your paired watch and glasses so they can show and control your music. To the glasses
> it goes over the Rokid CXR Bluetooth link. Between your phone and watch, data travels over the Wear OS Data Layer:
> Bluetooth when the watch is nearby. When Bluetooth isn't available, Google Play services may relay it through
> Google's cloud, encrypted. Live AR Fit has no server of its own and never uploads your data to one. Android calls
> this "notification access"; Live AR Fit does not read your notifications. If you decline, music features stay off and
> everything else works.

### Removed for 1.0.0

| Permission | Replacement |
|---|---|
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | **Removed.** The app opens `Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` (no permission needed) and shows the banner "Battery optimisation is on — LiveFit may stop tracking or lose the glasses/watch link when the screen is off." with **Open settings**. |

## Watch

| Permission | Runtime prompt? | User-facing rationale |
|---|---|---|
| `BODY_SENSORS` (`android:maxSdkVersion="35"`) | Yes, on API 35 and below | Heart rate during a workout. |
| `android.permission.health.READ_HEART_RATE` | Yes, on API 36+ (Wear OS 6) | Heart rate during a workout. |
| `ACTIVITY_RECOGNITION` | Yes | Steps, distance and calories during a workout. |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Yes, after the in-app location disclosure; optional | Record your route during workouts with "Use GPS outdoors" on — never in the background. Map tiles come from MapTiler, which sees your IP and the map area. |
| `FOREGROUND_SERVICE` | No | Run the workout as a foreground service. |
| `FOREGROUND_SERVICE_HEALTH` | No | Track the workout through Health Services with the screen off (see `foreground-services.md`). |
| `FOREGROUND_SERVICE_LOCATION` | No | Record the GPS route during a GPS workout (see `foreground-services.md`). |
| `INTERNET` | No | Download map tiles from MapTiler for the watch's Map page (over the phone connection, Wi-Fi or LTE). |
| `POST_NOTIFICATIONS` | Yes (API 33+) | Show the workout notification and Ongoing Activity. |
| `WAKE_LOCK` | No | Keep the workout running while the screen is off. |
| `BLUETOOTH` (`android:maxSdkVersion="30"`) | No | Make the watch discoverable for pairing with the phone (Android 11 and below). |
| `BLUETOOTH_ADVERTISE` | Yes | Make the watch discoverable when you tap Pair on the phone. |
| `com.livear.fit.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | No | Added by AndroidX (`core`); signature permission, not user-facing. |

No background-health permissions and no `ACCESS_BACKGROUND_LOCATION`.

**Note for review:** the watch's merged manifest also contains the music module's `MediaListener`
(`NotificationListenerService`), because the watch depends on `:services:music`. The watch never asks for
notification access. Consider removing it from the watch manifest (`tools:node="remove"`) before 1.0.0 so the Wear
build does not declare a notification listener.

### Removed for 1.0.0

| Permission | Replacement |
|---|---|
| `USE_FULL_SCREEN_INTENT` | **Removed.** When a workout starts from the phone, the watch posts its foreground-service notification as a Wear OS **Ongoing Activity** (the system "return to workout" chip); a tap opens Live AR Fit. |
