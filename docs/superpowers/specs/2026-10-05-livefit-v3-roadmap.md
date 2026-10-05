# Rokid LiveFit — V3 Roadmap (future, not designed yet)

- **Date:** 2026-10-05
- **Status:** Input for a future brainstorming → design → plan cycle. Nothing here is approved for implementation.
- **Built on:** [V1 design](2026-10-05-livefit-v1-design.md), [V2 design](2026-10-05-livefit-v2-design.md)

How to use this doc: when V1 + V2 pass real testing, start a new brainstorm per theme below (or a group of them). Each theme lists what we already know, what's undecided, and where the code hooks in, so nothing has to be rediscovered.

---

## Theme A — Smarter voice intent (LLM fallback)

**Why:** the V1 `CommandParser` is rule-based; it handles fixed phrasings well but loose, accented or code-mixed speech ("yaar skip this one", "khatam karo workout") will fail.

**Agreed direction (from brainstorming, not yet a decision):** layered intent
1. Rules (`CommandParser[locale]`) — ~1 ms, offline. If confident → done.
2. **On-device LLM**: Gemini Nano via ML Kit GenAI (Prompt API) on Galaxy S25 — ~0.3–1 s, offline, private.
3. **Cloud LLM** (Claude Haiku via Anthropic API) only if Nano is unavailable or unsure — ~0.5–1 s, needs network.
- The LLM receives the transcript plus the **closed list of allowed commands** (and pending confirmation, if any) and must return exactly one command JSON or `none`. It can never invent actions.
- Destructive commands (stop workout) from layers 2–3 always go through a `Confirmation`.

**Open questions:** offline-only (rules + Nano) vs allowing cloud; privacy of transcripts; latency budget per layer; how to evaluate accuracy.

**Accent test corpus:** add a Developer-tools "record test phrases" mode — the owner speaks each command N times; the recogniser output is saved as fixtures for parser and LLM tests.

**Hooks:** `VoiceService` → `IntentResolver` interface (new) wrapping `CommandParser`; language registry in `:services:voice`.

---

## Theme B — Local languages

**Why:** owner wants Indian languages later.

**Known:** Android on-device recognition packs exist per locale (the Languages screen already lists 31 on the S25, e.g. `cmn-Hans-CN`, `fr-FR`; availability of `hi-IN`, `ta-IN`, `bn-IN`, etc. must be checked on device). V1 design keeps STT + parser + yes/no lexicon keyed by locale.

**Open questions:** which languages first; code-mixed (Hinglish) handling — rules per language vs Theme A LLM; UI localisation (phone strings, watch, glasses HUD labels — HUD is mostly icons, which helps); TTS/voice feedback (none today).

**Hooks:** `LanguageRegistry` in `:services:voice`; Settings → Voice; Languages list screen.

---

## Theme C — Voice launch from the Rokid home screen

**Why:** start LiveFit (and a workout) hands-free from the glasses' home screen.

**Known (verified):**
- Custom "Hi Rokid, <phrase>" commands are firmware/cloud-defined; third-party APKs cannot register phrases. Rokid's assistant replied "I can't start Live Fit directly".
- Rokid **Agent Store** items can be opened by voice → registration with Rokid's platform is the likely official path.
- The Hi Rokid transcript suggested: *"To open an app, say 'Open Phone assistant', then ask it to open Live Fit."* → may launch our **phone** app via the phone's assistant, which can then open the glasses HUD via CXR-L `CUSTOM_APP`. **Untested.**
- CXR-L `CUSTOM_APP` session already opens our glasses app from the phone in ~1 s.

**Open questions:** Agent Store developer programme requirements; whether the phone-assistant path works and how fast; whether voice launch should also auto-start a workout.

**Hooks:** phone `LiveFitHubService` (already reacts to app open / intents); deep link `livefit://workout/start?type=…` on the phone.

---

## Theme D — Other wearables (AIVELA ring, Huawei band) and sensor fusion

**Why:** use the AIVELA smart ring or a Huawei band instead of / alongside the Galaxy Watch.

**Known:**
- Architecture already isolates sources behind `MetricsSource` (approach A: phone owns the workout, sources stream samples with provenance).
- **AIVELA ring** (`com.smartring` app on the phone): protocol unknown. First step: scan with **nRF Connect** with the AIVELA app force-stopped — standard Heart Rate Service `0x180D` = easy; custom UUIDs = check for a known OEM protocol (many budget rings share the "QRing"/Colmi family protocol) or capture an **HCI snoop log**. Rings usually give HR in bursts (30–60 s) on command, not continuous; BLE allows one central at a time (vendor app must be stopped).
- **Huawei band**: no third-party apps on-band. Options: band's "HR data sharing" broadcast (standard BLE HR) during a workout; Huawei Wear Engine / Health Kit SDK (developer account + HMS Core). Exact model matters (old Band 3 likely lacks broadcast).
- Without a watch, steps/distance/speed/calories come from **phone sensors** (`TYPE_STEP_COUNTER`, fused location GPS) and calories from HR + profile (weight/age/sex — needs a profile screen).

**Open questions:** source priority and fusion rules (e.g. ring HR + phone GPS); profile data; how starting/stopping works when the source has no app (ring: phone sends BLE commands).

**Hooks:** new `:services:metrics` adapters (`StandardBleHrSource`, `AivelaRingSource`, `PhoneSensorsSource`, calorie estimator); Linked services rows per device; provenance `Live(sourceId)` already supports multiple sources.

---

## Theme E — Accounts, setup and cloud backup

**Why:** owner mentioned "create account" for later.

**Known:** V1 setup wizard is device-local; an account step can be inserted before step 1.

**Open questions:** identity provider (Google sign-in reuse from V2?), what syncs (settings, history), backend choice, privacy.

---

## Theme F — iOS app (iPhone as hub)

**Known:** V1 keeps `:core:model`, `:core:services`, workout/parser/confirm logic in plain Kotlin → candidates for **Kotlin Multiplatform**. Platform pieces needing iOS implementations: `SpeechToText` (Apple on-device Speech), `HealthDataSink` (HealthKit), music control (YouTube Music on iOS has no media-session API → likely limited to `MPRemoteCommandCenter`-style control, needs research), glasses link (CXR docs mention iPhone clients via BLE GATT / MFi — check CXR-M/CXR-L iOS SDK availability), watch (Apple Watch app or keep Galaxy Watch unsupported on iOS).

**Open questions:** whether Galaxy Watch remains the sensor (it can't pair with iPhone), Rokid iOS SDK maturity.

---

## Theme G — Publishing

**Known requirements:**
- **Play Store:** Health Connect permissions declaration form + privacy policy; foreground service type justifications (`health`, `connectedDevice`); notification-listener use justification; OAuth app **verification** for the YouTube scope (sensitive) instead of "unverified production".
- **Wear OS** listing for the watch app (paired with phone app, same applicationId).
- **Glasses:** Rokid store / Agent Store submission process (unknown) or keep sideloading.
- Release signing keystore shared by phone + watch.

---

## Theme H — Smaller deferred items
- Bundled offline STT engines (Whisper / Vosk) as alternative `SpeechToText` implementations (no Google dependency even on Android).
- Playback speed (0.5×/2×): YouTube Music media session does not support it (verified) — only possible via other players.
- Playlist management beyond "add current song".
- Reading other apps' workouts into the Activity list (owner chose LiveFit-only for V2).
- TTS / audio feedback on the glasses speaker for command confirmations.
- Glasses waking the phone hub without an open CXR session (not possible today; revisit with future Rokid SDKs).
