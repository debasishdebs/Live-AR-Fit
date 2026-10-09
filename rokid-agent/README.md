# LiveFit Hi Rokid agent

`livefit/` is a Rokid AIUI agent ("LiveFit"). It lets the wearer say "Hi Rokid, tell LiveFit to pause" and have the
Live AR Fit glasses app act on it.

How it works:

1. The Hi Rokid assistant picks the `pages/command/index` tool and passes `{"command": "<one of the enum>"}`.
2. The page runs on the glasses (inside the Rokid assistant). It calls `fetch("http://127.0.0.1:47123/lf?cmd=<command>&v=1")`
   with a 2 s timeout.
3. The Live AR Fit glasses app answers on loopback only (`glasses/.../agent/AgentServer.kt`). It is running whenever the
   app process is alive. It sends the command to the phone hub over the same CXR `lf_cmd` path the touchpad uses and
   replies `{"ok": true|false, "say": "<short ack>"}`.
4. The page shows `say` on a small card (or "Open Live AR Fit on your glasses first" when nothing answers) and closes
   itself after 1.5 s.

Commands: `start pause resume stop play_pause next previous stats map music playlist glance workout`.

- Stop is confirmed on the glasses: the hub shows its "End workout?" prompt, the same one voice uses.
- Page views follow the hub's page gate. A page turned off in Settings is toasted and not shown. Map only appears
  during a GPS workout.

## Pack and inspect (no device needed)

```bash
npx -p @yodaos-pkg/aix-cli aix pack rokid-agent/livefit -o /tmp/livefit.aix   # builds the package (~4 KB)
npx -p @yodaos-pkg/aix-cli aix show rokid-agent/livefit                       # effective agent definition
```

- `livefit/.aix/agent-id` holds the development agent id (`develop.rokid.agent.<uuid>`). It is committed so that
  reinstalls update the same dev agent. AIUI Studio assigns the store id when you publish.
- Do not commit `.aix` packages.

## Test on the glasses (developer mode)

Preconditions:

- Glasses firmware 1.26.011 or newer.
- The ADB / developer switch is on in the Hi Rokid app.
- The Live AR Fit glasses build with the receiver is installed and **open**.
- Pass `--serial` whenever more than one adb device is attached.

```bash
G=<glasses adb serial>
npx -p @yodaos-pkg/aix-cli aix device --serial $G                       # read-only baseline
npx -p @yodaos-pkg/aix-cli aix device set-dev --serial $G               # developer mode on (reloads widgets)
npx -p @yodaos-pkg/aix-cli aix install rokid-agent/livefit --serial $G
npx -p @yodaos-pkg/aix-cli aix launch-page rokid-agent/livefit pages/command/index --card --params '{"command":"pause"}' --serial $G
adb -s $G logcat -d | grep -E "LiveFitAgent|\[LiveFit\]"
npx -p @yodaos-pkg/aix-cli aix device unset-dev --serial $G             # undo developer mode
```

To test the receiver without the agent, forward the port and use curl from the Mac:

```bash
adb -s $G forward tcp:47123 tcp:47123
curl -s 'http://127.0.0.1:47123/lf?cmd=stats'    # {"ok":true,"say":"Showing stats"}
curl -s 'http://127.0.0.1:47123/lf?cmd=dance'    # 400 {"ok":false,"say":"I can't do that in LiveFit yet"}
adb -s $G forward --remove tcp:47123
```

Voice routing ("Hi Rokid, tell LiveFit to …") does not work for draft agents. It needs Rokid's review. Until then,
test with `launch-page`.

## Import into AIUI Studio (Global)

- **Import from GitHub URL fails on branch names that contain `/`** (for example `feat/hi-rokid-agent`).
- Import from a local folder (`rokid-agent/livefit`) instead, or push the folder to a branch without slashes (for
  example `hirokid-agent`) and import that.
- After import, fill in the Build & Review tab from `review/`.

## Store review checklist

- [ ] Name ≤ 20 characters, describes the function, no "test" or "beta": **LiveFit**.
- [ ] Custom icon. The default icon is rejected, and so are solid-colour or text-only icons. Export the Live AR Fit
      launcher icon as a 512×512 PNG (`glasses/src/main/res/drawable/ic_launcher_foreground.xml` on its background).
- [ ] Application category: Sports & fitness (or the closest match).
- [ ] Feature description ≤ 500 characters: `review/feature-description.txt`.
- [ ] Opening message (aim for ≤ 300 characters; it must not repeat the description): `review/opening-message.txt`.
- [ ] Permission reasons. Select only network: `review/permissions.txt`. No camera, microphone or account data.
- [ ] 3–5 preview files, including at least one image and at least one MP4 video ≤ 60 s (15–45 s recommended, 1:1
      recommended): `review/preview-plan.md`.
- [ ] Before submitting:
  - Check on a real device that every command works.
  - Check that the "app not open" card appears and the page closes itself.
  - Check that the declared permissions match the code (`INTERNET` only).
