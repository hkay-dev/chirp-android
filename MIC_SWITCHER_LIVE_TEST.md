# Mic / Input-Switcher — Interactive Live-Test Methodology & Ledger

This file is BOTH the methodology for interactively hardware-testing the input-switcher work
(audit findings MIC-001…021 + the MIC-012 dedup regression fix) AND the living progress ledger.
It is committed to git so progress survives pauses, reconnects, and context loss. Claude updates
the **Status** column and the **Resume pointer** as we go.

> Scope note: unit tests, detekt, and on-device *automated* checks already pass. This document
> covers ONLY the behaviors that need real hardware and a human in the loop (audio routing you can
> hear, physical plug/unplug, Bluetooth link state, real permission grants, interruptions).

---

## 1. Roles — who does what

**Claude (automatable):** drive the app via `adb` (taps with a focus-guard, screenshots,
navigation), set up/seed state, grant/revoke permissions for setup, clear+capture `logcat`,
read `dumpsys audio` / `media.audio_policy`, pull/inspect the DB, assert routing/state/no-crash,
update + commit this ledger.

**Harsha (only-a-human-can):** physical hardware (plug/unplug USB mic, power BT headset on/off,
pair/connect), **speak into a mic and confirm what was heard/transcribed**, perform real
permission grants when we're testing that UX, trigger real interruptions (alarm/call), and give
the perceptual yes/no Claude can't observe (was the audio actually from the headset?).

The golden rule: **Claude verifies routing + state + absence-of-crash; Harsha verifies
perception (audio actually came through the right mic, transcript is right).** A test passes only
when BOTH halves are satisfied.

---

## 2. The interactive loop (one test at a time)

Every test runs as this strict handshake so it's resumable at any point:

1. **PREP (Claude):** re-confirm the device is connected (re-derive transport id), set the app to
   the required starting state, `adb logcat -c` (clear), start any capture. Mark the test
   `IN-PROGRESS` in the ledger.
2. **ASK (Claude):** give Harsha exactly **one atomic action** + what "good" looks like + what NOT
   to do, and the **reply word** to send back. Then **Claude ends the turn and waits** — no
   polling, no loops. (Harsha's reply re-invokes Claude.)
3. **DO (Harsha):** perform the action; take whatever time is needed; reply with the agreed word.
4. **OBSERVE (Claude):** capture evidence — `logcat` (key tags below), `dumpsys` routing/comm
   device, screenshot(s), DB if relevant — and read back Harsha's perceptual answer.
5. **JUDGE (Claude):** pass/fail against the test's explicit criteria. If ambiguous, ask one
   targeted follow-up.
6. **RECORD (Claude):** write Status + evidence summary + notes into the ledger, **commit it**,
   move the Resume pointer.
7. **RESET → NEXT (Claude):** restore device/app state for the next test, then PREP the next one.

### Signal words (Harsha → Claude)
- `ready` — I've done the setup action; proceed to observe.
- `done` — I finished the timed action (e.g., spoke / recorded); proceed.
- `pass` / `fail` / `weird: <note>` — my perceptual verdict for the current step.
- `pause` — stop here; we'll resume later (Claude commits the ledger and parks).
- `skip` — skip this test (e.g., no hardware for it); Claude marks it SKIPPED with reason.
- `redo` — that step was botched; re-run it.

### How long is fine
A step can take seconds or days. Claude never assumes time passed — it always waits for a signal
word. If Harsha says "let me know when you're done," that's Harsha's cue to act and Claude's cue
to wait silently until the reply.

---

## 3. Resuming after any pause (the important part)

This ledger is the single source of truth. **On every resume** (new turn, hours later, or after
context compaction) Claude does, before anything else:
1. Read this file; find the **Resume pointer** and the first test whose Status is
   `PENDING`/`IN-PROGRESS`.
2. Re-derive the adb transport id (it changes across reconnects):
   `TID=$(adb devices -l | grep -i R5CY11B8DHD | grep -o 'transport_id:[0-9]*' | cut -d: -f2)`
   If empty → ask Harsha to re-pair wireless adb / replug USB (`pause` until back).
3. Confirm app build + state: debug build installed (Dev Menu present), MANAGE_EXTERNAL_STORAGE
   state as the test needs, mic/notification perms granted. Re-seed/clear data only if a test needs it.
4. Continue the loop at the pending test. Never silently redo a `PASS` test.

Claude commits this file after every test so even total loss of the conversation loses no progress.

---

## 4. Observability — what Claude reads

- **logcat tags (clear with `logcat -c` before each test, capture after):**
  - `AudioInputDeviceSelector` — logs `Effective capture route:` (the *real* routed device — truth for which mic is live), device enumeration, dedup.
  - `RecordingService` — pause/resume, auto-stop reason (e.g. `INPUT_DEVICE_LOST`), focus pause/regain.
  - `CommunicationDeviceSession` — SCO acquire / activation / timeout / clear (MIC-006).
  - `AudioFocusManager` — focus loss/regain transitions (MIC-001).
  - Filter noise: `grep -ivE 'SemGame|Unihal|Aconfig|adbd|FlexPanel'`.
- **Routing/Comm-device state (ground truth for SCO):**
  - `adb shell dumpsys audio | grep -iE 'input|communication|sco|device'`
  - `adb shell dumpsys media.audio_policy` (active input devices + addresses).
  - After-stop check: communication device must return to default (no stranded headset routing).
- **DevMenu → Reliability Timeline** (`ReliabilityEventLogger`) — structured lifecycle events, visible on-screen.
- **Screenshots** → `/tmp/chirpboard-ondevice/live/<TESTID>-<step>.png` (downscale via PIL before reading).
- **DB** (debug build): `adb exec-out run-as dev.chirpboard.app cat databases/chirp.db > /tmp/.../chirp.db` then local `sqlite3` (no on-device sqlite3).
- **Cannot be read by Claude → Harsha confirms:** which physical mic the audio actually came from, audible playback, headset LED/connection, whether a transcript matches what was said.

### Safety rails (lessons from this session)
- **Focus guard before any blind tap:** verify `mCurrentFocus` is `dev.chirpboard.app` first; never tap into the launcher/another app. Never send input during a phone call.
- Wireless adb drops — re-derive transport id on every resume.
- Phone is set to stay awake while charging (good — keep it plugged in during sessions).
- After destructive/teardown steps, restore: re-grant permissions, reset policy to a known state.

---

## 5. Hardware & build preconditions (confirm at kickoff — "Step 0")

Before the first test, Claude asks Harsha to confirm the kit, because the run order batches by
"what's plugged in":
- **USB microphone** + how it connects to the phone (USB-C direct, or OTG/hub).
- **Bluetooth headset** — and critically whether it's a **classic (SCO) headset** vs **LE Audio**.
  MIC-006 (the headline finding) specifically needs a *classic, non-LE-audio* BT mic. Galaxy Buds
  / modern earbuds may negotiate LE Audio — note the model so we know which path we're exercising.
- **Second device for interruptions** (any phone that can call this one) — optional, for the
  cleanest transient-focus-loss test; alarms/notifications are a fallback.
- **Devices under test:** Samsung SM-S938U1 is connected. A Pixel is a *nice-to-have* second
  data point for MIC-006 (OEM-dependent) but not required to start.
- **Build state:** debug build is installed (Dev Menu available); all-files access currently
  granted. Tests that exercise the *real* grant UX (MIC-006/storage) will revoke first.

If a piece of hardware is missing, its tests are marked SKIPPED with the reason and revisited later.

---

## 6. Test matrix

Grouped by hardware so we test everything that needs a given device while it's connected. Each test
lists: the finding(s) it covers, the surface(s), Claude's setup, Harsha's action, the pass criteria,
and a Status. Surfaces: **App** = in-app Record screen; **IME** = on-screen keyboard dictation;
**Rec** = on-demand recognition dialog.

### Group A — USB microphone

| ID | Finding · Surface | Harsha action | Pass criteria (Claude verifies routing/no-crash; Harsha confirms audio) | Status |
|----|----|----|----|----|
| A1 | MIC-011 · App | Plug USB mic; in picker pick it (Manual); speak a known phrase; stop | Picker lists the USB mic exactly once; logcat `Effective capture route` = USB; transcript matches the spoken phrase | PENDING |
| A2 | MIC-011 · App | With USB **and** BT both connected, set policy = Automatic; record briefly | Automatic picks **USB** (route=USB), per USB>BT>wired>built-in | PENDING |
| A3 | MIC-009/014 · App | Start recording on USB; **unplug USB mid-recording** | Session auto-stops **and saves**, advisory names the lost device (deliberate on App surface) | PENDING |
| A4 | MIC-009 + MIC-010 · App | Record on USB → **Pause** → unplug USB → (pick built-in) → **Resume** → speak → stop | While paused, unplug does **not** auto-stop (stays Paused); Resume continues the same recording on the new mic and shows a "device changed on resume" advisory; final file has both segments | PENDING |
| A5 | MIC-012 · App | With USB + BT connected (grant BT names), open the picker | Each device appears **once** (no duplicate built-in/USB rows); a manual selection's check survives the BLUETOOTH_CONNECT grant | PENDING |

### Group B — Classic Bluetooth (SCO) headset — MIC-006 (highest priority, OEM-dependent)

| ID | Finding · Surface | Harsha action | Pass criteria | Status |
|----|----|----|----|----|
| B1 | MIC-006 · App | Pair+connect classic BT headset; Manual-select it; speak into the **headset mic** only; stop | logcat `Effective capture route` names the **SCO** device; transcript captured the headset-mic speech; after stop, `getCommunicationDevice` is back to default (Claude checks dumpsys) | PENDING |
| B2 | MIC-006 · IME | Same, but dictate via the keyboard into a text field | Route = SCO; committed text matches headset-mic speech; comm-device cleared after | PENDING |
| B3 | MIC-006 · Rec | Same, via the on-demand recognition dialog | Route = SCO; recognized text matches; comm-device cleared after | PENDING |
| B4 | MIC-006 · App | With only BT connected, policy = Automatic; record | Automatic selects the BT SCO mic; route = SCO | PENDING |
| B5 | MIC-006 (media) · App | While a BT recording is live, and after it stops, play any audio | Media playback routing is unaffected during/after capture (no app-wide MODE_IN_COMMUNICATION side effect) | PENDING |
| B6 | MIC-006 (fallback) · App | Power the headset OFF right as recording starts (force SCO activation to fail/timeout) | Within ~2s, capture falls back to a non-BT mic with a "using X instead" notice; no hang/crash | PENDING |
| B7 | MIC-014 · App vs IME | Start a recording on the BT mic, then **power the headset off mid-capture** | **App**: auto-stops with a named "input device disconnected" reason. **IME**: a transient hint appears and the device chip/route corrects — it does **not** silently keep claiming the headset | PENDING |

### Group C — Interruptions & dictation races (minimal/no special hardware)

| ID | Finding · Surface | Harsha action | Pass criteria | Status |
|----|----|----|----|----|
| C1 | MIC-001 · App | Start a longer recording (let it run past a segment rotation); trigger a **transient focus loss** (incoming notification/alarm, or a short call from a 2nd phone) | Recording auto-pauses then **auto-resumes**; it never gets **stuck Paused**; final audio is continuous | PENDING |
| C2 | MIC-008 · IME | Dictate a sentence; tap **Stop**, then tap the mic again **within ~1s** | No "microphone in use by keyboard" toast; it just waits for the previous dictation to finish, then starts | PENDING |
| C3 | MIC-017 · IME | Dictate; tap **Stop** then immediately tap **Cancel** (inside the teardown window) | Text is **not** committed to the field; with save-pref ON the cancelled capture is discarded (Claude checks DB) | PENDING |
| C4 | MIC-015 · Rec | Start a recognition capture, then swipe-away / destroy the dialog **while it's cancelling/tearing down** | No crash; rescue behaves correctly (Claude checks logcat/DB) | PENDING |

### Group D — No-speech / endpointer (MIC-018, recognition surface)

| ID | Finding · Surface | Harsha action | Pass criteria | Status |
|----|----|----|----|----|
| D1 | MIC-018 · Rec | Set mic gain to ~5×; open the recognition dialog next to steady noise (fan/AC); **say nothing** | Session still times out to the no-speech/retry state (continuous amplified noise no longer defeats the endpointer) | PENDING |
| D2 | MIC-018 · Rec | Normal-gain recognition; say nothing | Times out to no-speech as expected | PENDING |

---

## 7. Live ledger / session log

**Resume pointer:** `Step 0` (confirm hardware) — testing has NOT started yet.

**Status legend:** PENDING · IN-PROGRESS · PASS · FAIL(notes) · BLOCKED(reason) · SKIPPED(reason).

| When | Test | Result | Evidence / notes |
|------|------|--------|------------------|
| — | Step 0 | PENDING | Confirm USB mic + BT (classic vs LE) + optional 2nd phone, then Claude proposes a run order. |

(Claude appends one row per executed test and updates the matrix Status + Resume pointer, committing after each.)
