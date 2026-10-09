---
title: Live AR Fit privacy policy
---

# Live AR Fit privacy policy

**Effective date:** 2026-10-10
<!-- Owner: set the effective date to the day this policy is published with 1.0.0. -->

This policy covers the Live AR Fit apps for Android phones (`com.livear.fit`), Wear OS watches (`com.livear.fit`) and
Rokid AR glasses (`com.livear.fit.glasses`). Live AR Fit is a free, open-source project by Debasish Kanhar. The source
code is public at https://github.com/debasishdebs/Live-AR-Fit, so you can check everything described here.

Contact: **d.kanhar@gmail.com**

## In short

- There is **no account**, no sign-in, **no ads** and **no analytics**. There is no third-party crash-reporting SDK.
- Live AR Fit has **no server of its own**. Your workouts, heart rate and routes stay on your own phone and watch.
- Data moves only between your own phone, watch and glasses, plus map tile requests to MapTiler (see below).
- Delete everything with **Clear history** on the phone, or uninstall the apps.

## What the apps use, and why

### Health and fitness data

During a workout, the watch reads your **heart rate**, steps, distance, speed and calories from Wear OS Health
Services. It uses them to show your live stats on the watch and glasses and to save the workout.

- The watch keeps the workout only until the phone confirms it has saved it, then deletes its copy.
- The phone stores your workout history (summaries, heart-rate samples and routes) in its local database.
- Live AR Fit does not read health data outside a workout you started, does not use Health Connect, and has no
  background health access.

### Location

This is the in-app disclosure, word for word:

> Live AR Fit uses GPS only during a workout with "Use GPS outdoors" on, to record your route and show the map on your
> phone, watch and glasses. It never uses your location in the background when no workout is running. Your route is
> stored on your phone and watch. To draw the map, the phone and watch download map tiles from MapTiler, which sees your
> IP address and the map area shown. Between your phone and watch, data travels over the Wear OS Data Layer: Bluetooth
> when the watch is nearby. When Bluetooth isn't available, Google Play services may relay it through Google's cloud,
> encrypted. Live AR Fit has no server of its own and never uploads your data to one.

The watch's GPS is the main source. If the watch has no GPS fix, the phone's GPS can be used as a fallback during the
workout; the phone can only start it while Live AR Fit is open on screen. Location permission is optional; workouts
record without it, just without a route.

### Microphone and audio

The in-app disclosure, word for word:

> Voice commands are recognised on your phone, on-device only — there is no cloud speech service. Live AR Fit listens
> only after you tap Talk or when it asks you a yes/no question. Audio from the glasses' microphone travels to your
> phone over the Rokid Bluetooth link. Audio is never uploaded or stored.

Speech recognition uses Android's on-device recogniser and an offline language pack. If the pack is missing, voice
commands stay off; there is no fallback to cloud recognition. This will not change: Live AR Fit does not and will not
send your audio to a cloud speech service.

### Music details (YouTube Music control)

To show and control your music, Live AR Fit needs Android's "notification access". The in-app disclosure, word for
word:

> Live AR Fit reads the active media session of your music app (YouTube Music):
> the title, artist, playback state and up-next queue. This is sent to your paired watch and glasses so they can show
> and control your music. To the glasses it goes over the Rokid CXR Bluetooth link. Between your phone and watch, data travels over the Wear OS Data Layer:
> Bluetooth when the watch is nearby. When Bluetooth isn't available, Google Play services may relay it through
> Google's cloud, encrypted. Live AR Fit has no server of its own and never uploads your data to one. Android calls
> this "notification access"; Live AR Fit does not read your notifications. If you decline, music features stay off and
> everything else works.

Music details are not stored.

## How data travels between your devices

- **Phone and glasses:** over the Rokid CXR Bluetooth link, set up through Rokid's Hi Rokid app. The glasses receive
  your live stats, the map image, music details and settings, and send gestures and microphone audio to the phone.
- **Phone and watch:** in the words of the app:

  > Between your phone and watch, data travels over the Wear OS Data Layer: Bluetooth when the watch is nearby. When
  > Bluetooth isn't available, Google Play services may relay it through Google's cloud, encrypted. Live AR Fit has no
  > server of its own and never uploads your data to one.

  This applies to all phone↔watch data: workout commands and stats, heart rate, location fixes, settings and music.
  The relay is part of Google Play services on your devices; Google's handling of it is covered by
  [Google's privacy policy](https://policies.google.com/privacy).

## Map tiles: what MapTiler sees

To draw the map, the phone (for the glasses map and the history thumbnail) and the watch download map images
("tiles") from **MapTiler** (https://www.maptiler.com). Like any web request, each tile request shows MapTiler your
**IP address** and the **tile coordinates**, which reveal the map area being shown. The request also carries the
app's name and version and a project API key, but no account, user ID or other data about you. MapTiler's handling of
this is covered by its privacy policy, https://www.maptiler.com/privacy-policy/.

Development builds without a MapTiler key use the OpenStreetMap tile server instead, which sees the same kind of
information.

## What is stored, and where

Everything is stored **locally, in app-private storage**, on your own devices:

- **Phone:** the workout history database (summaries, heart-rate samples, routes), your settings, the Hi Rokid
  authorisation token for the glasses link, a cache of map tiles (up to 50 MB), and the last crash log.
- **Watch:** the workout in progress until the phone has saved it, page and GPS settings, a cache of map tiles (up to
  20 MB), and the last crash log.
- **Glasses:** your HUD layout and gesture settings, and the last crash log.

Android backup is turned off for all three apps, so none of this is copied to a cloud backup.

The crash log holds the technical error report of the last crash and the app version. It is never sent
automatically. On the phone, Settings → About → **Share last crash** lets you choose to send it, for example by email.

## Deleting your data

- **Clear history** (phone Settings) deletes your finished workouts: their stats, heart-rate samples and routes.
  A workout still running or syncing is kept until it finishes. For each cleared workout, the phone keeps only an
  internal ID and status (no stats, health data or location) so that late messages from the watch about it are
  ignored.
- **Uninstalling** an app deletes everything it stored on that device.
- Clearing the app's storage in Android settings has the same effect as uninstalling.

Because there is no server and no account, there is no copy of your data anywhere else for us to delete.

## Children

Live AR Fit is a fitness app for adults. It is not directed at children, and we do not knowingly process children's
data.

## Changes to this policy

If the apps' data handling changes, this policy is updated first and the effective date above changes. The full
history of this file is public in the project repository.

## Contact

Questions or requests: **d.kanhar@gmail.com**
