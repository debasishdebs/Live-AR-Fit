# Installing Live AR Fit on Rokid glasses

The glasses app (`com.livear.fit.glasses`) is not on Google Play. It is published as a signed APK on the project's
[GitHub Releases](https://github.com/debasishdebs/Live-AR-Fit/releases) page. You install it once with `adb` from a
computer; after that the phone app talks to it over the Rokid link.

You need:

- Rokid AR glasses, already set up with the **Hi Rokid** app on your phone.
- A computer with `adb` ([Android SDK Platform-Tools](https://developer.android.com/tools/releases/platform-tools)) and
  a USB cable for the glasses.
- The **Live AR Fit** phone app (and the watch app, if you use a watch) from Google Play, at the **same version** as the
  glasses APK you install.

## 1. Enable developer mode and adb on the glasses

Turn on developer mode and USB debugging on the glasses as described in Rokid's developer documentation for your
model. <!-- Owner: add the link to Rokid's official developer guide for enabling adb on the glasses. -->

Connect the glasses with USB and check that `adb` sees them:

```sh
adb devices
```

You should see a line like `<glasses-serial>    device`. If it says `unauthorized`, accept the debugging prompt on
the glasses and run `adb devices` again. In the commands below, replace `<glasses-serial>` with the value shown.

## 2. Download and verify the APK

From the release page, download both files:

- `live-ar-fit-glasses-v1.0.0.apk`
- `SHA256SUMS.txt`

Check the APK against the published checksum before installing:

```sh
shasum -a 256 live-ar-fit-glasses-v1.0.0.apk
grep live-ar-fit-glasses-v1.0.0.apk SHA256SUMS.txt
```

The two hashes must be identical. If they differ, do not install the file; download it again.

## 3. Install

```sh
adb -s <glasses-serial> install -r live-ar-fit-glasses-v1.0.0.apk
```

## 4. Grant the permissions

The glasses have no convenient permission screen, so grant them with `adb`:

```sh
adb -s <glasses-serial> shell pm grant com.livear.fit.glasses android.permission.RECORD_AUDIO
adb -s <glasses-serial> shell pm grant com.livear.fit.glasses android.permission.BLUETOOTH_ADVERTISE
```

- `RECORD_AUDIO`: the glasses microphone, for voice commands. The audio goes to your phone over the Rokid Bluetooth
  link and is recognised there, on-device only. It is never uploaded or stored.
- `BLUETOOTH_ADVERTISE`: lets the glasses become discoverable while you pair them with the phone app.

## 5. First pairing

1. On the phone, open **Live AR Fit**. The setup steps start on first launch.
2. At **Link your Rokid glasses**, tap **Authorize**. Hi Rokid opens and authorises Live AR Fit to talk to the glasses.
   The phone shows "Authorized." when it worked.
3. Tap **Pair** and pick the glasses in the system list. On Android 13 and newer, tap **Allow** on the glasses when
   asked. (On older Android versions the phone app asks you to pair from Hi Rokid instead.)
4. Finish the remaining steps (watch, music, map, voice) or skip them.

When the link is up, the phone starts the glasses app through the Rokid link and the HUD appears.

## Upgrades

Install a newer release over the existing one with the same command:

```sh
adb -s <glasses-serial> install -r live-ar-fit-glasses-v1.1.0.apk
```

This works because every release is signed with the same key. Your HUD settings are kept. If `adb` reports
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed app was signed with a different key (for example, an old test
build): uninstall it first with `adb -s <glasses-serial> uninstall com.livear.fit.glasses`, then install again.

Old test builds with the package `com.debasish.livefit.glasses` are a different app. Uninstall them:

```sh
adb -s <glasses-serial> uninstall com.debasish.livefit.glasses
```

## Matching versions

The phone, watch and glasses apps must all be the **same release** (the same `livefit.version`, for example 1.0.0).
They check each other's message format; when one side is out of date, the apps show an **Update LiveFit** banner (for
example "Update LiveFit on your glasses") and ignore that device's commands until you update it. Update the glasses APK
whenever Play updates the phone and watch apps.
