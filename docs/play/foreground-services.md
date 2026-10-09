# Play Console: Foreground service declarations (draft)

Draft answers for **App content → Foreground service permissions** for Live AR Fit (`com.livear.fit`). One section
per foreground service type, with the user-visible trigger, why the work can't be deferred, and a screen-recording
script for the required video. Each video should be under 30 seconds. Owner to review, record and upload.

Declared services (merged manifests):

| App | Service | `foregroundServiceType` | Permissions |
|---|---|---|---|
| Phone | `LiveFitHubService` | `connectedDevice\|location` | `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION` |
| Watch | `ExerciseService` | `health\|location` | `FOREGROUND_SERVICE_HEALTH`, `FOREGROUND_SERVICE_LOCATION` |

Recording tips: record the phone with the built-in screen recorder; record the watch and glasses with a second phone or
camera pointed at them, so the glasses HUD and the watch face are visible. A glasses HUD capture made with the Rokid
recording feature can be cut in. No real names, serials or locations should be readable in the video.

---

## Phone: `connectedDevice`

**What it does.** Keeps the hub running so the phone stays connected to the user's paired Rokid glasses (Rokid CXR
Bluetooth link) and Wear OS watch (Data Layer). The hub relays live workout stats, heart rate, the route map, music
and voice commands between the three devices.

**User-visible trigger.** The service starts when the user opens Live AR Fit, or when one of the user's
companion-paired devices (glasses or watch) comes nearby and the user has left **Start LiveFit when nearby** on for
it (Settings → Nearby devices). It is restarted after a reboot or app update only if a paired device is linked. It
shows an ongoing notification ("LiveFit ready", then the live workout status) and stops when no linked device is
present and no workout is running.

**Why it can't be deferred or done otherwise.** The glasses HUD and the watch need the phone continuously while the
user is wearing them and especially during a workout: stats, heart rate and gestures must flow every second, with the
phone screen off and the phone in a pocket. WorkManager or a deferred job would drop the link and freeze the HUD. The
connection is to the user's own paired Bluetooth devices, which is the `connectedDevice` use case.

**Recording script (under 30 s):**

1. Open Live AR Fit on the phone; the home screen shows the glasses and watch as connected.
2. Tap **Start workout** and confirm.
3. Pull down the notification shade: the "Live AR Fit" notification shows the running workout.
4. Turn the phone screen off.
5. Show the glasses HUD and the watch: time, heart rate and distance keep updating.
6. Turn the phone on and stop the workout.
7. Unlink the glasses and watch (or turn them off) and show that the notification is gone.

## Phone: `location`

**What it does.** A GPS fallback: if the watch has no GPS fix during a workout with **Use GPS outdoors** on, the
phone's GPS supplies the route so the glasses map keeps following the user.

**User-visible trigger.** The `location` type is added to the hub service only while Live AR Fit's screen is visible
on the phone and the user has granted location after the in-app location disclosure. The phone GPS itself is switched
on only during a GPS workout, and only while the watch has no fix. The ongoing hub notification stays visible the whole
time. No `ACCESS_BACKGROUND_LOCATION` is requested.

**Why it can't be deferred.** The route and the live map position are needed in real time, every second, during the
workout, and must continue when the user turns the screen off and puts the phone away.

**Recording script (under 30 s):**

1. Open Live AR Fit on the phone; the location disclosure is shown first, then the system permission prompt
   ("While using the app").
2. Settings: **Use GPS outdoors** is on. Tap **Start workout** (Run).
3. Show the watch without a GPS fix (for example indoors or with watch location off): the phone takes over the route.
4. Pull down the notification shade: the "Live AR Fit" notification is visible.
5. Turn the phone screen off; the glasses map keeps moving as you walk.
6. Stop the workout: the phone GPS stops; the notification returns to "LiveFit ready" or disappears when the devices
   are unlinked.

## Watch: `health`

**What it does.** Runs the workout on the watch through Wear OS Health Services (`ExerciseClient`): heart rate,
steps, distance, speed and calories, for the duration of a workout the user started.

**User-visible trigger.** The user starts a workout on the watch (Start button), on the phone (**Start workout**), on
the glasses or by voice. The watch then shows the workout as a Wear OS **Ongoing Activity** (the "return to workout"
chip) with an ongoing notification. The service stops when the workout ends.

**Why it can't be deferred.** Exercise tracking must run continuously for the whole workout, including with the watch
screen off or in ambient mode; Health Services requires a foreground service for an active exercise. The data is
needed live on the watch and glasses.

**Recording script (under 30 s):**

1. Open Live AR Fit on the watch.
2. Tap **Start**; the workout screen shows heart rate and time.
3. Swipe to the watch face: the Ongoing Activity chip and notification are visible.
4. Let the screen go off (or cover it for ambient mode).
5. Wake the watch: time and heart rate have kept counting; the glasses HUD shows the same values.
6. Stop the workout: the Ongoing Activity and notification are gone.

## Watch: `location`

**What it does.** Records the route with the watch's GPS during a workout with **Use GPS outdoors** on, for the route
map on the watch, glasses and phone.

**User-visible trigger.** Only a workout the user started, with **Use GPS outdoors** on for that workout type, and
only after the user granted location following the in-app location disclosure. Without that, the service runs as
`health` only. Never used when no workout is running; no `ACCESS_BACKGROUND_LOCATION`.

**Why it can't be deferred.** The route must be recorded fix by fix as the user moves; it can't be recreated later.

**Recording script (under 30 s):**

1. On the phone, Settings: **Use GPS outdoors** is on.
2. On the watch, open Live AR Fit; accept the location disclosure and the "While using the app" prompt.
3. Start a Run outdoors; the Ongoing Activity chip and notification appear.
4. Swipe to the watch's Map page: the route draws as you walk; let the screen go off.
5. Wake the watch: the route has kept growing; the glasses map shows it too.
6. Stop the workout: the notification is gone and GPS is off.
