# Play Console: Health apps declaration (draft)

Draft answers for **App content → Health apps** for Live AR Fit (`com.livear.fit`, phone and Wear OS). Owner to
review before submitting.

## App category

- **Health and fitness: Activity and fitness tracking.** Live AR Fit records walks, runs and rides on a Wear OS watch
  and shows live workout stats on the watch and on Rokid AR glasses.
- Not a medical app, not a medical device, no clinical, diagnostic or treatment features.

## Health features

- **Live heart rate during a workout:** read on the watch through Wear OS **Health Services** (`ExerciseClient`) only
  while a workout the user started is running. Shown on the watch and glasses and saved with the workout on the phone.
- **Workout tracking on the watch:** Health Services exercise types running, walking and biking; data types heart rate,
  steps, distance, speed, calories and (only with "Use GPS outdoors" on) location.
- **Workout history** on the phone: summaries, heart-rate samples and routes, stored locally.
- **No Health Connect:** the app neither reads from nor writes to Health Connect.
- **No background health access:** no `READ_HEALTH_DATA_IN_BACKGROUND` or other background-health permissions. Health
  data is read only during an active workout, inside the watch's `health` foreground service.

## Permissions (watch)

| Permission | When | Why |
|---|---|---|
| `android.permission.BODY_SENSORS` (declared with `android:maxSdkVersion="35"`) | Wear OS on API 35 and below | Heart rate during a workout |
| `android.permission.health.READ_HEART_RATE` | API 36 and above (Wear OS 6) | Heart rate during a workout (the granular replacement for `BODY_SENSORS`) |
| `android.permission.ACTIVITY_RECOGNITION` | All versions | Steps, distance and calories during a workout |

The watch requests exactly the set for its OS version (one helper, `requiredHealthPermissions(sdkInt)`, drives both
the runtime request and the check before a workout starts). The phone app declares no health permissions.

## Claims

- No medical claims. The heart-rate figure is the watch sensor's reading, shown for training feedback only.
- The store listing does not describe the app as diagnosing, treating or preventing any condition.

## Data handling summary

- Health data is stored only on the user's own phone and watch, never on a developer server (there is none), and is
  not used for advertising or sold.
- Between phone and watch it travels over the Wear OS Data Layer: Bluetooth when nearby; when Bluetooth isn't
  available, Google Play services may relay it through Google's cloud, encrypted.
- Users delete it with phone Settings → **Clear history**, or by uninstalling.
- Full details: privacy policy, https://debasishdebs.github.io/Live-AR-Fit/privacy-policy.html
  (`docs/privacy-policy.md`), and the Data safety answers (`docs/play/data-safety.md`).
