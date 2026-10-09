# Play Console: Data safety (draft)

Draft answers for **App content → Data safety** for the Live AR Fit listing (`com.livear.fit`, phone and Wear OS).
The glasses app is not on Play, so it is not part of this form, but its data flows end on the phone and are covered
where they reach it. Owner to review before submitting.

Sources: Google's Data safety guidance, https://support.google.com/googleplay/android-developer/answer/10787469
(retrieved 2026-10-10), and the app code at the 1.0.0 release.

## Overview answers

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **Yes** (see "Google Play services Data Layer relay" and "Map tiles" for why) |
| Is all of the user data collected by your app encrypted in transit? | **Yes.** Data is encrypted in transit: map tiles over HTTPS; phone↔watch messages over the Wear OS Data Layer, which uses an encrypted Bluetooth link or, when Bluetooth isn't available, Google Play services' encrypted relay through Google's cloud |
| Do you provide a way for users to request that their data is deleted? | **Yes.** In the app: phone Settings → **Clear history** deletes finished workouts; uninstalling deletes everything. There is no server-side copy, no account and no data held by the developer |
| Account creation | The app has no accounts and no login |
| Independent security review | No |
| Families / children | Not directed at children |

## Data types

"Collected" follows Play's definition: transmitting data from the app off the user's device. On-device-only processing
is not collection. "Shared" means transferring collected data to a third party, with the exceptions quoted below.

### Location: precise location (and approximate location)

| | Answer |
|---|---|
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | No (do not tick) |
| Required or optional | **Optional.** Location permission is optional; workouts record without it |
| Purpose | **App functionality** (record the route and draw the map on phone, watch and glasses) |

Why collected: GPS fixes travel watch→phone over the Wear OS Data Layer, which may go through Google's cloud relay
(below), and the map tile requests to MapTiler carry tile coordinates that reveal the map area (precise at the zoom
levels used), together with the device's IP address. Stored only on the user's phone and watch.

Why not shared: MapTiler serves tiles to the app as a service provider (it processes the request on the developer's
behalf, under the developer's API key and terms) and the location use is shown in a prominent in-app disclosure
before the permission request. The Data Layer relay is covered below.

### Health and fitness: health info (heart rate) and fitness info (steps, distance, speed, calories, workouts)

| | Answer |
|---|---|
| Collected | **Yes** |
| Shared | **No** |
| Processed ephemerally | No |
| Required or optional | **Required** for workouts (the watch cannot start a workout without heart-rate access) |
| Purpose | **App functionality** (live stats on watch and glasses, workout history on the phone) |

Why collected: heart rate and workout stats travel watch→phone over the Data Layer, which may use Google's cloud
relay. Stored only on the user's phone and watch (the watch deletes its copy once the phone has saved the workout).
Heart rate is listed under "Health info" as well as "Fitness info" to be conservative; owner may narrow it to
"Fitness info" if Play's examples make that clearer.

### Audio: voice or sound recordings

| | Answer |
|---|---|
| Collected | **No** |

Why: speech is recognised on the phone, on-device only (`createOnDeviceSpeechRecognizer`, no cloud fallback). Audio
from the glasses' microphone reaches the phone over the Rokid Bluetooth link and is processed there. It is never sent
off the user's devices, never uploaded and never stored. Play's guidance: "User data accessed by your app that is only
processed locally on the user's device and not sent off device does not need to be disclosed."

### App activity and app info

| Data type | Answer |
|---|---|
| Other actions (music details, see below) | **Collected**, not shared, optional, App functionality |
| App interactions, in-app search history, installed apps, other user-generated content | **Not collected** |
| Web browsing, messages, contacts, calendar, files, photos/videos, financial info, personal info, device or other IDs | **Not collected** |

Music details (title, artist, playback state, up-next queue of the active YouTube Music session) are read on the phone
and sent to the user's own watch (Data Layer) and glasses (Rokid link) to show and control playback; they are not
stored. They travel over the same Data Layer relay as location and health data, so the draft treats them the same
way: **App activity → Other actions**, collected, not shared (service-provider and prominent-disclosure exceptions),
optional (notification access can be declined), purpose App functionality.

**Owner alternative:** Play has no type that clearly fits "metadata of the track playing in another app"; the owner
may choose not to declare it. The conservative answer above is the default.

### App info and performance: crash logs

| | Answer |
|---|---|
| Collected | **Yes** (only when the user shares it manually) |
| Shared | **No** |
| Processed ephemerally | No |
| Required or optional | **Optional** |
| Purpose | **Analytics** (diagnose and fix crashes) |

Why: each app writes its last crash (stack trace and app version) to app-private storage. Nothing is sent
automatically and there is no crash SDK. Phone Settings → About → **Share last crash** opens the Android share sheet;
if the user sends it to the developer, the developer receives it. Diagnostics and other performance data: **not
collected**.

## Google Play services Data Layer relay

The phone and watch talk over the Wear OS Data Layer (Google Play services). When the watch is nearby this is
Bluetooth; when Bluetooth isn't available, Google Play services may relay the message through Google's cloud,
encrypted. Live AR Fit has no server of its own. This carries workout commands and stats, heart rate, location fixes,
settings and music details.

Current guidance (https://support.google.com/googleplay/android-developer/answer/10787469, retrieved 2026-10-10):

- **Collection:** "'Collect' means transmitting data from your app off a user's device." Exception: "User data that is
  sent off device, but that is unreadable by you or anyone other than the sender and recipient as a result of
  end-to-end encryption does not need to be disclosed."
- **Sharing:** "'Sharing' refers to transferring user data collected from your app to a third party." It includes data
  transferred "Off-device, such as server to server transfers", "On-device transfer to another app" and "From your app
  libraries and SDKs". Developers must also "review how any third-party code (such as third-party libraries or SDKs)
  in your app collects and shares such data."
- **Exceptions to sharing** (do not need to be disclosed as sharing):
  - Service providers: "an entity that processes user data on behalf of the developer and based on the developer's
    instructions".
  - Legal purposes: "Transferring user data for specific legal purposes, such as in response to a legal obligation or
    government requests".
  - User-initiated action or prominent disclosure: "based on a specific user-initiated action, where the user
    reasonably expects the data to be shared, or based on a prominent in-app disclosure and consent".
  - Anonymous data: "Transferring user data that has been fully anonymized so that it can no longer be associated with
    an individual user".
- **Encryption in transit:** developers disclose whether data "collected or shared by your app using encryption in
  transit".
- The guidance has no specific text on Google Play services, system-level data flows, or transfers between the same
  user's phone and wearable.

Resulting answers, and why:

1. **Collected: Yes** for the data types the relay can carry (location, health and fitness). When Bluetooth isn't
   available the data leaves the device through Google's cloud. The relay is encrypted, but we cannot show it is
   end-to-end encrypted in the sense of the exception, so the conservative reading is that it is
   "collection".
2. **Shared: No.** Google Play services relays the messages only to deliver them to the user's own watch or phone, on
   the app's request: that is processing on the developer's behalf and instructions (service-provider exception). The
   location and music flows are also shown in prominent in-app disclosures that the user accepts first
   (prominent-disclosure exception). No data goes to a server of the developer, and the developer never receives it.
3. **Encrypted in transit: Yes.** Bluetooth between paired devices is encrypted, the cloud relay is encrypted, and map
   tiles use HTTPS.

Re-check this section against the guidance page before each submission; if Play publishes text on Data Layer or
companion-device transfers, follow it and update the answers.

## Map tiles

The phone and watch download map tiles from MapTiler over HTTPS. Each request reveals the IP address and the tile
coordinates (the map area shown); it carries the app's name/version and the developer's API key, no user ID. This is
covered by the Location answer above (collected; not shared, service provider). The privacy policy states it.

## Consistency

These answers must match the privacy policy (`docs/privacy-policy.md`) and the in-app disclosures
(`core/model/.../Disclosures.kt`). Change them together.
