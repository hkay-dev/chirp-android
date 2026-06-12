# Microphone Input-Device Switcher — Deep Audit

Audit date: 2026-06-12 · Branch: `reliability-fixes` (HEAD `85f3936`) · Auditor scope: selection/policy/persistence layer (`core-audio`), capture engines, the three capture surfaces (Record screen via `RecordingService`, IME via `VoiceRecorder`, on-demand recognition via `VoiceRecorder`), focus management, no-speech detection, and the tests that pin them.

This is an audit only. No source files were modified.

---

## 1. Executive Summary

The switcher is structurally sound: there is a **single source of truth** for device selection (`AudioSettingsStore` → `AudioInputDeviceSelector.chooseInputDevice`, pure and matrix-tested), a single global capture lock (`RecordingStateManager`), and a deliberate, documented policy of **"selection applies at the NEXT capture start, never mid-session."** The recent dedup work (f63c626) and the BT grant/revoke identity fallback are well-tested. Most of the hard stop/start/cancel races in the engines have been hardened with generations, gates and mutexes — and the hardening mostly holds up under adversarial reading.

The defects that remain cluster in four areas:

1. **Async-callback vs. coroutine ordering in `RecordingService`** — the worst single bug found: a transient audio-focus loss whose *regain* arrives before the asynchronously-launched pause coroutine lands leaves a recording **silently stuck in Paused forever** (MIC-001).
2. **The selector's shared mutable session state is not thread-safe and is only owned by one of three surfaces** — `activeDeviceId`/`activeDevice` are written from IO/capture threads and read from the main-thread `AudioDeviceCallback` with no synchronization (MIC-002), and the IME/recognition surfaces publish into the app-wide `activeDevice` flow but never clear it (MIC-003), producing stale UI and weakening device-lost detection.
3. **Platform-correctness gaps** — no SCO/communication-device activation anywhere despite ranking Bluetooth-SCO mics above wired (MIC-006, needs on-device verification); routing is validated exactly once per engine start with no `OnRoutingChangedListener`, so every later reroute is invisible (MIC-013); capture-side selection runs on the *unfiltered* device list while the picker runs on the deduped one, letting legacy manual keys pin non-recordable endpoints (MIC-011).
4. **Surface divergence on the same events** — device loss mid-capture auto-stops the app recording, silently reroutes the IME, and errors-or-reroutes recognition (MIC-014); a device loss **while paused** force-stops a session whose mic isn't even open (MIC-009); a keyboard cancel tapped during the stop-teardown window is silently swallowed and the dictation commits anyway (MIC-017).

**Counts by severity:** Critical 0 · High 2 (MIC-001, MIC-006) · Medium 11 (MIC-002, MIC-003, MIC-007, MIC-008, MIC-009, MIC-011, MIC-013, MIC-014, MIC-016, MIC-017, MIC-018) · Low 8 (MIC-004, MIC-005, MIC-010, MIC-012, MIC-015, MIC-019, MIC-021, plus the MIC-020 test-gap catalogue).

**Overall health verdict:** Good-to-strong for a feature this hairy. No data-corruption bug is reachable under realistic timing except via the narrow windows documented below; the worst realistic user outcomes are (a) a recording stuck paused after a focus blip, (b) Bluetooth-SCO mic silence on some devices, and (c) misleading/stale device UI across surfaces. All findings are fixable without architectural surgery; 6 parallel-safe fix batches identified (§5).

---

## 2. Architecture Overview

### 2.1 Layers and ownership

```
                       ┌──────────────────────────────────────────────────────┐
 persistence           │ AudioSettingsStore (DataStore, @Singleton)           │
 (source of truth for  │   inputDevicePolicy: Automatic|PreferBuiltIn|Manual  │
 the USER's choice)    │   manualDeviceAddress (selectionKey), manualDeviceName│
                       └───────────────▲──────────────────────────────────────┘
                                       │ read at capture start (currentSettings())
                                       │ written by 4 picker call sites
                       ┌───────────────┴──────────────────────────────────────┐
 selection/policy      │ AudioInputDeviceSelector (@Singleton)                │
 (source of truth for  │   AudioDeviceCallback (registered once, never un-    │
 WHAT IS CONNECTED and │     registered — fine for a singleton) → main thread │
 WHAT THE SESSION USES)│   availableDevices: StateFlow (deduped+filtered)     │
                       │   activeDevice / activeDeviceLabel: StateFlow        │
                       │   activeDeviceId: Int?  +  onActiveDeviceLost: var   │
                       │   resolvePreferredDevice() → chooseInputDevice()     │
                       │   buildAudioRecord(): AudioRecord + setPreferredDevice│
                       │   refreshActiveDeviceFromRouting(record)             │
                       └──────▲──────────────────▲───────────────────▲────────┘
                              │                  │                   │
            ┌─────────────────┴───┐   ┌──────────┴─────────┐  ┌──────┴────────────┐
 capture    │ GaplessWavSegment-  │   │ VoiceRecorder      │  │ VoiceRecorder     │
 engines    │ Capture (AudioSource│   │ (IME, FileBacked,  │  │ (recognition,     │
            │ .MIC, quality-preset│   │ VOICE_RECOGNITION, │  │ InMemory, 16 kHz) │
            │ sample rate)        │   │ 16 kHz)            │  │                   │
            └─────────▲───────────┘   └─────────▲──────────┘  └────────▲──────────┘
                      │                         │                      │
 surfaces   ┌─────────┴───────────┐  ┌──────────┴──────────┐  ┌────────┴───────────────┐
            │ RecordingService    │  │ ChirpKeyboardService│  │ VoiceRecognitionActivity│
            │ (foreground svc;    │  │ + KeyboardSession-  │  │ + ChirpRecognition-    │
            │ start/pause/resume/ │  │   Coordinator +     │  │   Service, both via    │
            │ stop/cancel/restart;│  │   QuickCaptureSess. │  │   VoiceRecognition-    │
            │ segment rotation;   │  │ (in-process capture,│  │   SessionCoordinator + │
            │ device-lost autostop│  │ no service)         │  │   VoiceRecognitionCap- │
            │ focus pause/resume) │  │                     │  │   tureGate             │
            └─────────────────────┘  └─────────────────────┘  └────────────────────────┘
                       all three serialize through RecordingStateManager (global lock)
```

### 2.2 Data flow at capture start (identical on all three surfaces)

1. Surface acquires the global lock (`RecordingStateManager.tryStartRecording(origin)` — atomic CAS).
2. Surface requests audio focus via its **own** `AudioFocusManager` instance (one per surface; safe because the global lock guarantees only one is capturing).
3. Engine calls `AudioInputDeviceSelector.buildAudioRecord(...)` (`AudioInputDeviceSelector.kt:149-164`), which:
   - reads `AudioSettingsStore.currentSettings()`,
   - enumerates `audioManager.getDevices(GET_DEVICES_INPUTS)` (**unfiltered** — see MIC-011),
   - runs the pure `chooseInputDevice(devices, policy, manualKey)` matrix (`:277-301`),
   - publishes `_activeDevice`/`_activeDeviceLabel`/`activeDeviceId` app-wide,
   - constructs the `AudioRecord` and calls `setPreferredDevice(device)` (return value ignored — MIC-013).
4. On the **first successful read**, the engine calls `refreshActiveDeviceFromRouting(record)` to correct the optimistic label from `record.routedDevice` — once, never again (MIC-013).
5. On hot-unplug of the device whose id == `activeDeviceId`, the selector's `AudioDeviceCallback` fires `onActiveDeviceLost` — a **single listener slot registered only by `RecordingService`** (`RecordingService.kt:174-191`), which auto-stops with save *only* when the service owns the capture. The IME/recognition surfaces have no device-loss path at all (MIC-014).
6. On session end, **only `RecordingService` calls `clearActiveDevice()`** (5 call sites); the IME/recognition surfaces never do (MIC-003).

### 2.3 Where the source of truth lives

- **User preference:** `AudioSettingsStore` keys `input_device_policy` + `manual_device_address` (+ display name). All four picker surfaces (Record screen `InputDevicePickerViewModel`, keyboard `ChirpKeyboardService.selectInputDevice`, recognition dialog, `AudioSettingsViewModel`) write the same two keys — consistent, but as two non-atomic edits (MIC-005).
- **Connected-device list:** `AudioInputDeviceSelector.availableDevices` (deduped via `surfaceableInputDevices`), refreshed by `AudioDeviceCallback` and by explicit `refreshDevices()` after a BLUETOOTH_CONNECT grant. `AudioSettingsViewModel` keeps its own parallel snapshot driven by `devicesChangedTick` (duplicated mechanism, same data).
- **Live-session device:** `AudioInputDeviceSelector.activeDevice` — *intended* to be "null while no capture is live", an invariant only `RecordingService` maintains.
- **No-speech / silence:** two distinct mechanisms. (a) *Digital-silence detection* (pure zeros = client silenced by platform): per-engine, in `VoiceRecorder.collectSamples` (`VoiceRecorder.kt:482-494`) and `GaplessWavSegmentCapture.runCaptureLoopInner` (`:317-322`), advisory-only on app + IME. (b) *Speech endpointing* (`SpeechEndpointer`, app module): recognition surfaces only; terminates the session on trailing silence / no-speech timeout. The Record screen and keyboard deliberately have no endpointer (manual stop surfaces).

### 2.4 Mid-recording switching — what actually happens today

- Picking a device during a live session **persists the preference only**; the live capture is untouched (documented everywhere: "applies to the NEXT capture start"). The Record-screen sheet shows a "session live" note; the keyboard/recognition sheets do **not** (MIC-004, missing `sessionLive`).
- Pause→resume **recreates the engine and re-resolves the device** (`RecordingService.resumeRecording` → `createCaptureEngine` → `buildAudioRecord`), so a mid-session swap *is* possible across a pause — including a silent one across auto-pause/auto-resume (MIC-010).
- Active-device hot-unplug: app surface auto-stops with save (deliberate); IME/recognition silently reroute (platform default-fallback for an absent preferred device) or hit `ERROR_DEAD_OBJECT`.
- Hot-plug of a *better* device mid-recording: nothing happens, by design (the session is pinned via `setPreferredDevice`).

---

## 3. Findings

> Severity: Critical / High / Medium / Low. Confidence: Confirmed (traced end-to-end in code) / Likely / Needs verification.

---

### MIC-001 — Focus-regain processed before the async pause lands → recording stuck Paused forever

- **Severity:** High · **Confidence:** Confirmed (interleaving exists in code; trigger window is narrow but real)
- **Category:** Race / Transition
- **Affected files & lines:**
  - `feature-recording/src/main/java/dev/chirpboard/app/feature/recording/service/RecordingService.kt:161-172` (focus handlers), `:221-236` (`resumeAfterFocusRegained`), `:591-638` (`pauseRecording`, flag write at `:603`)
  - `core-audio/src/main/java/dev/chirpboard/app/core/audio/AudioFocusManager.kt:77-99` (`handleFocusChange`)

**User-visible symptom / impact:** During a recording, a short transient focus interruption (notification chime routed as `AUDIOFOCUS_LOSS_TRANSIENT`, assistant blip, SCO ring) pauses the recording; when focus returns quickly, the session stays **Paused indefinitely** with the "Paused — will auto-resume" advisory showing. The user believes they are recording; nothing is captured until they manually resume. Silent data loss.

**Root-cause analysis:** `onFocusLost(TRANSIENT)` does *not* pause synchronously — it launches a coroutine:

```kotlin
// RecordingService.kt:591
private fun pauseRecording(autoPauseReason: RecordingAutoPauseReason? = null) {
    serviceScope.launch {
        segmentTransitionMutex.withLock {
            if (recordingStateManager.state.value !is RecordingState.Recording) return@withLock
            ...
            recordingStateManager.pauseRecording()
            ...
            pausedByFocusLoss = autoPauseReason == RecordingAutoPauseReason.FOCUS_LOST_TRANSIENT  // :603
```

while `onFocusRegained` runs **synchronously** on the focus callback:

```kotlin
// RecordingService.kt:221
private fun resumeAfterFocusRegained() {
    if (!pausedByFocusLoss) return            // <-- reads the flag the pause coroutine has not set yet
```

Interleaving: (1) `AUDIOFOCUS_LOSS_TRANSIENT` → pause coroutine enqueued; (2) the coroutine suspends before `:603` — this happens whenever `segmentTransitionMutex` is contended (segment rotation holds it across `withContext(Dispatchers.IO) { capture.rotateSegment(next) }`, up to the 5 s `ROTATION_WAIT_TIMEOUT_MS`, `RecordingSegmentRotator.kt:44-93`, `GaplessWavSegmentCapture.kt:143`); (3) `AUDIOFOCUS_GAIN` arrives and `resumeAfterFocusRegained()` reads `pausedByFocusLoss == false` → returns; (4) pause coroutine acquires the mutex, pauses, sets the flag. No further `AUDIOFOCUS_GAIN` will ever arrive (the app still owns its focus request), so nothing auto-resumes. The same window exists without mutex contention if the GAIN callback message is processed between the launch being enqueued and dispatched (focus callbacks and `serviceScope` are both main-Handler FIFO, so the *uncontended* path is ordered — the contended path is the real one).

**Trigger / reproduction:** Record on the app surface long enough for segment rotation to be active. While a rotation is in flight (or artificially hold `segmentTransitionMutex`), deliver `AUDIOFOCUS_LOSS_TRANSIENT` immediately followed by `AUDIOFOCUS_GAIN` (e.g. an instrumented `AudioFocusManager.handleFocusChange` pair, or on-device: a short assistant chirp during heavy rotation). Observe state stays `Paused`, `pausedByFocusLoss == true`, no resume.

**Recommended fix:** Make the regain path resilient to the out-of-order arrival instead of trying to make the pause synchronous (it can't be — it does IO):
1. In `RecordingService`, replace the boolean read with a **pending-regain latch**: add `@Volatile private var focusRegainPending = false`. In `resumeAfterFocusRegained()`, if `!pausedByFocusLoss` **and** a transient-loss pause is in flight (track with `@Volatile private var focusPauseRequested = false`, set synchronously in the `onFocusLost` lambda *before* launching `pauseRecording`, cleared at the end of the pause coroutine and in `resumeRecording`/stop/cancel paths), set `focusRegainPending = true` and return.
2. At the end of `pauseRecording`'s `withLock` block (after `:603`, still inside the mutex), check `focusRegainPending`; if set, clear it and invoke the same `resumeAfterFocusRegained()` logic (or simply schedule `resumeRecording()` — the guards inside `resumeRecording` already re-validate state under the mutex).
3. Keep `RecordingResumeGuard.canResume` untouched — it already protects against resuming under a stop.
4. Edge cases to preserve: a *manual* pause must never auto-resume (the flag semantics at `:603` already encode this — only set the latch path for `FOCUS_LOST_TRANSIENT`); a PERMANENT loss between the transient pair must still win (it routes to `stopRecording()`; the latch must be cleared in `launchGatedStop`).
5. Do NOT move the flag write before the state transition — the comment at `:602` ("Set inside the mutex, after the pause actually landed") protects the manual-pause-races-focus-loss case; the latch approach preserves it.

**Tests to add/modify:** New `RecordingServicePauseResumeRaceTest` (or extend `RecordingResumeGuardTest`): simulate LOSS_TRANSIENT → GAIN delivered before the pause body runs (inject a held mutex / a test dispatcher that defers the launch) and assert the session ends up Recording, not Paused. Also pin: manual pause + GAIN → stays Paused; LOSS_TRANSIENT → GAIN → LOSS_PERMANENT ordering → stopped-with-save.

**Fix isolation:** Independent. Co-edits `RecordingService.kt` with MIC-003/MIC-009/MIC-010/MIC-019 — serialize those four with this one (same file), or hand the whole file-batch to one subagent.

**Fix risk:** Medium — touches the pause/resume state machine; the guards (`RecordingResumeGuard`, stop gate) must keep their current semantics. Confine changes to the two flags and one resume invocation.

---

### MIC-002 — `AudioInputDeviceSelector` mutable session state is not thread-safe

- **Severity:** Medium · **Confidence:** Confirmed (JMM-level data race; symptom probabilistic)
- **Category:** Race
- **Affected files & lines:** `core-audio/src/main/java/dev/chirpboard/app/core/audio/AudioInputDeviceSelector.kt:54-55` (fields), `:66-82` (callback reads), `:118-147` (`resolvePreferredDevice` writes), `:149-164` (`buildAudioRecord` writes), `:173-182` (`refreshActiveDeviceFromRouting` writes), `:184-186`, `:195-199` (`clearActiveDevice`)

**User-visible symptom / impact:** Missed device-lost auto-stop (recording silently continues/reroutes after the chosen mic unplugs) or, conversely, a spurious "input device disconnected — recording saved" auto-stop of a healthy session. Both are rare and timing-dependent.

**Root-cause analysis:** `activeDeviceId: Int?` and `onActiveDeviceLost` are plain `var`s with no `@Volatile`/lock. Writers and readers span threads:
- `resolvePreferredDevice()`/`buildAudioRecord()` run on `Dispatchers.IO` (both engines start capture there: `RecordingService.kt:439`, `VoiceRecorder.kt:201`),
- `refreshActiveDeviceFromRouting()` runs on the dedicated capture thread (`GaplessWavSegmentCapture.kt:308-311`) or the IO collector (`VoiceRecorder.kt:468-473`),
- `clearActiveDevice()` runs on the service main thread,
- the reader `deviceCallback.onAudioDevicesRemoved` (`:66-82`) runs on the main thread (handler `null` → main looper).

```kotlin
// AudioInputDeviceSelector.kt:54
private var activeDeviceId: Int? = null
private var onActiveDeviceLost: ((lostDeviceName: String?) -> Unit)? = null
...
// :71 (main thread)
val activeId = activeDeviceId
val lostActive = activeId != null && removedDevices.any { it.id == activeId }
```

Without a happens-before edge, the main-thread callback may read a stale `null` (write on IO not yet visible → missed loss) or a stale previous-session id (→ spurious loss if that id is in `removedDevices` — plausible right after a stop/start cycle where the old device is being unplugged while a new session starts on another device). There is also a check-then-act between resolution and publication during start (`:137` sets the id before the AudioRecord even exists at `:158-161` — a removal in that window fires "lost" for a device the session may successfully avoid, though here the deliberate auto-stop semantics make that acceptable).

**Trigger / reproduction:** Hard to reproduce deterministically; demonstrable with a stress test alternating `resolvePreferredDevice()` on IO with synthesized `onAudioDevicesRemoved` on another thread.

**Recommended fix:** Smallest sufficient change: mark `activeDeviceId` and `onActiveDeviceLost` `@Volatile`, and make the compound mutation in `resolvePreferredDevice` (`:137-139`) and `refreshActiveDeviceFromRouting` (`:177-181`) write `activeDeviceId` *after* `_activeDevice` so the callback's fallback name lookup (`:74-78`) can't see an id without a summary. A cleaner alternative: confine all mutation to a single `synchronized(stateLock)` (the callback already runs on main; the cost is negligible at these rates). Do not change the listener-survives-`clearActiveDevice` behavior — `AudioInputDeviceLossTest.listenerSurvivesClearActiveDevice_andFiresForTheNextCapture` pins it.

**Tests to add/modify:** Extend `AudioInputDeviceLossTest`: a removal delivered concurrently with `resolvePreferredDevice` (use a latch inside a mocked `audioSettingsStore.currentSettings()`) must observe either the old or the new id, never a torn state; removal of the *previous* session's device right after a new `resolvePreferredDevice` must not fire.

**Fix isolation:** Independent of everything except it co-edits `AudioInputDeviceSelector.kt` with MIC-003 (selector half), MIC-011, MIC-012, MIC-013 (selector half) — serialize within that file.

**Fix risk:** Low — visibility annotations + write ordering only.

---

### MIC-003 — App-wide `activeDevice` state is published by all surfaces but cleared only by `RecordingService` (plus a clear-after-lock-release race)

- **Severity:** Medium · **Confidence:** Confirmed
- **Category:** Ownership-conflict / Lifecycle
- **Affected files & lines:**
  - `core-audio/.../AudioInputDeviceSelector.kt:32-38` (documented invariant "Null while no capture is live"), `:195-199` (`clearActiveDevice`)
  - Publication without clear: `core-audio/.../recorder/VoiceRecorder.kt:250-260` (via `buildAudioRecord`); no `clearActiveDevice` call exists in `feature-keyboard/**` or `app/**` (verified by grep — call sites only in `RecordingService.kt:543,566,788,868,1151`)
  - Consumers of the stale value: `feature-recording/.../ui/RecordInputDevicePicker.kt:106,125` (`InputDeviceFallbackNotice(activeDevice)` not gated on `sessionLive`), `core-ui/.../components/InputDevicePicker.kt:259-290`
  - The race: `RecordingService.kt:1042` (`onCaptureStopHandoff` releases the global lock) vs `:1151` (`clearActiveDevice()` later in `finishStopLifecycle`)

**User-visible symptom / impact:**
1. After any keyboard dictation or recognition session, `activeDevice`/`activeDeviceLabel` stay populated forever (until the next capture or process death). Opening the Record screen then replays a **stale fallback notice** ("Using Built-in microphone — Buds isn't connected") for 6 s with no session live, because `InputDeviceFallbackNotice` keys only on `activeDevice` (`RecordInputDevicePicker.kt:125`), not on `sessionLive`.
2. Race: between the service stop's `onCaptureStopHandoff` (global lock released, state → Idle) and `finishStopLifecycle`'s `clearActiveDevice()` (`:1141-1151`, same coroutine but after journal/WorkManager work), a keyboard dictation can start, publish its own `activeDevice`, and then have it **wiped by the finishing service stop** — the live dictation session shows no/wrong active device.

**Root-cause analysis:** Publication happens inside `resolvePreferredDevice()` which every engine calls; clearing is a surface responsibility that only one surface implements. The selector has no notion of which session owns the published value, so a late `clearActiveDevice()` from a finished session can clobber a newer session's publication.

**Trigger / reproduction:** (1) Set a manual preference for a disconnected BT device → dictate on the keyboard (fallback fires) → open the Record screen → stale notice appears with no recording. (2) For the race: stop an app recording and start a keyboard dictation within the stop's handoff→finish window (~tens of ms; widened by finalize-enqueue IO).

**Recommended fix:**
1. Make clearing generation-aware in the selector: `resolvePreferredDevice()` returns/records a session token (a monotonically increasing `Long` stored alongside `activeDeviceId`); change `clearActiveDevice()` to `clearActiveDevice(token: Long)` that no-ops unless the token matches the latest publication. Keep a no-arg overload (deprecated) delegating to "clear if mine" for migration.
2. Make the IME and recognition surfaces clear it: `QuickCaptureSessionImpl.stopAsAudioSource()/stop()/cancelCapture()/close()` (`feature-keyboard/.../quickcapture/QuickCaptureSessionImpl.kt:125-147`) and `VoiceRecognitionSessionCoordinator.stopLocked/cancelLocked/shutdown` (`app/.../VoiceRecognitionSessionCoordinator.kt:227-263,151-158`) should call `clearActiveDevice(token)`. The cleanest plumbing: have `VoiceRecorder` capture the token from `buildAudioRecord` and clear in `stopAudioRecord()` (`VoiceRecorder.kt:594-604`) — one place covers all VoiceRecorder surfaces.
3. Independently, gate the Record-screen notice: `InputDeviceFallbackNotice` should receive `state.sessionLive` (or read `pickerState`) and render only while live — `RecordInputDevicePicker.kt:125`.

**Tests to add/modify:** `AudioInputDeviceLossTest`/new selector test: publish session A, publish session B, clear with A's token → B's `activeDevice` survives. `VoiceRecorderTest`: after `stop()`/`stopToFileBacked()`/`cancelCapture()`, `selector.activeDevice.value == null`. Compose/unit test for the notice gating.

**Fix isolation:** Co-edits `AudioInputDeviceSelector.kt` (with MIC-002/011/012/013), `VoiceRecorder.kt` (with MIC-007), `RecordingService.kt` (with MIC-001 family), `RecordInputDevicePicker.kt` (alone). Best done **after** MIC-002 (same fields). The notice gating (step 3) is fully independent and parallel-safe.

**Fix risk:** Medium — the token must thread through every `clearActiveDevice` call in `RecordingService` (5 sites); a missed site resurrects the stale-state bug. The device-lost listener behavior must not change (tests pin it).

---

### MIC-004 — Keyboard and recognition pickers never set `sessionLive` → live-session device and "applies next session" note missing there

- **Severity:** Low · **Confidence:** Confirmed
- **Category:** Cross-surface
- **Affected files & lines:**
  - `feature-keyboard/.../service/ChirpKeyboardService.kt:382-389` (builds `InputDevicePickerUiState` without `sessionLive`)
  - `app/.../VoiceRecognitionActivity.kt:358-365` (same)
  - `core-ui/.../components/InputDevicePicker.kt:95-98` (`chipDevice()` only honors `activeDevice` when `sessionLive`), `:208-215` (live note)

**User-visible symptom / impact:** Mid-dictation, the keyboard's device chip shows the *predicted next* device rather than the device actually in use (these can diverge when the session started before a hot-plug, or after a fallback), and the sheet never explains that a selection applies to the next session — the exact confusion the Record screen was given the note for. Same on the recognition dialog.

**Root-cause analysis:** `InputDevicePickerUiState.sessionLive` defaults to `false`; only `InputDevicePickerViewModel` (Record screen) wires it from `recordingStateManager.state`.

**Trigger / reproduction:** Start a keyboard dictation with a BT mic connected; unplug it (platform reroutes); chip recomputes `chooseInputDevice` → shows built-in (correct by luck). Conversely: set manual preference to built-in *during* a BT-routed live dictation → chip flips to built-in immediately even though the session still records from BT.

**Recommended fix:** In `ChirpKeyboardService.onCreateInputView` collect `recordingStateManager.state` (already injected) and pass `sessionLive = state.isActive && state.activeOrigin == RecordingOrigin.KEYBOARD` (origin-scoped so an app recording doesn't mark the keyboard picker live). In `VoiceRecognitionActivity`, derive from its `_recordingState` flow (`Recording`/`Starting`/`Stopping`). No change to core-ui.

**Tests to add/modify:** `KeyboardPanelContentTest` (exists) — add a case asserting the live note renders while recording; unit test `chipDevice()` precedence with `sessionLive=true` + diverging `activeDevice`.

**Fix isolation:** Fully independent and parallel-safe (its own files; `ChirpKeyboardService.kt` is also touched by MIC-014's keyboard half — coordinate or serialize those two).

**Fix risk:** Low.

---

### MIC-005 — Manual device key + policy persisted as two non-atomic DataStore edits (4 call sites)

- **Severity:** Low · **Confidence:** Confirmed
- **Category:** Race
- **Affected files & lines:**
  - `core-audio/.../AudioSettingsStore.kt:143-148` (`setInputDevicePolicy`), `:157-175` (`setManualDevice`)
  - Call sites: `feature-recording/.../ui/RecordInputDevicePicker.kt:81-86`, `feature-keyboard/.../service/ChirpKeyboardService.kt:529-534`, `app/.../VoiceRecognitionActivity.kt:371-375`, `app/.../ui/settings/AudioSettingsViewModel.kt:122-127`

**User-visible symptom / impact:** A capture starting in the window between the two edits reads `policy=Automatic` with the *new* manual key (key honored only under Manual → automatic ranked device used) — the user just picked device B yet the session that races the tap starts on the ranked device, with no notice. Also: every selection emits two `settings` updates → picker check-mark flicker.

**Root-cause analysis:**
```kotlin
// RecordInputDevicePicker.kt:82
audioSettingsStore.setManualDevice(device.selectionKey, device.productName)
audioSettingsStore.setInputDevicePolicy(AudioInputDevicePolicy.Manual)
```
`resolvePreferredDevice()` reads `currentSettings()` once (`AudioInputDeviceSelector.kt:119`); DataStore edits are individually atomic but the pair is not.

**Recommended fix:** Add `AudioSettingsStore.selectManualDevice(selectionKey: String, displayName: String?)` performing **one** `dataStore.edit` that writes key, name and `inputDevicePolicy=manual` together (and a matching `selectAutomatic()` that flips policy in one edit, deliberately leaving the stale manual key as the picker's `manualDevice` getter only consults it under Manual). Update the four call sites. Keep `setManualDevice`/`setInputDevicePolicy` for backup-restore code paths if referenced (`grep` `SettingsBackupDelegate` before removal).

**Tests to add/modify:** `AudioSettingsStoreTest`: `selectManualDevice` writes all three keys atomically (assert a single emission carries the complete pair); existing `manual device selection persists key and display name together` stays green.

**Fix isolation:** Independent; co-edits `ChirpKeyboardService.kt` (MIC-004/014), `VoiceRecognitionActivity.kt` (MIC-015/016/018-adjacent), `RecordInputDevicePicker.kt` (MIC-003 step 3) — the call-site edits are one-liners; low collision risk but schedule after those finishes or in the same batch.

**Fix risk:** Low.

---

### MIC-006 — No Bluetooth SCO / communication-device activation anywhere; SCO mic selection relies entirely on `setPreferredDevice`

- **Severity:** High (if it reproduces) · **Confidence:** Needs verification (on-device matrix)
- **Category:** Platform-correctness
- **Affected files & lines:**
  - `core-audio/.../AudioInputDeviceSelector.kt:149-164` (`buildAudioRecord` — only `setPreferredDevice`), `:215-223` (PRIORITY_ORDER ranks `Bluetooth` (SCO) above wired), `:254` (`TYPE_BLUETOOTH_SCO` mapped and offered)
  - Repo-wide: zero hits for `startBluetoothSco`, `stopBluetoothSco`, `setCommunicationDevice`, `OnCommunicationDeviceChangedListener`, `MODE_IN_COMMUNICATION` (verified by `git grep`)

**User-visible symptom / impact:** On devices/OEM builds where the audio policy does **not** auto-activate the SCO link for an explicitly-selected SCO input, choosing (or auto-falling-back to) a classic Bluetooth headset mic yields either capture from the built-in mic (with the routing refresh then *correcting the label* to built-in — confusing "I picked Buds, it says Built-in") or pure digital silence (the existing silence advisory naming the device — note the app *has* such an advisory with "Try a different microphone", which suggests silent capture has been observed on-device). BLE (`TYPE_BLE_HEADSET`) input is generally fine without SCO.

**Root-cause analysis:** `AudioRecord.setPreferredDevice` is best-effort and does not, by contract, manage the SCO link. Since Android 12 the `AudioDeviceBroker` activates SCO for *explicitly selected* SCO inputs in many configurations, but behavior is OEM- and version-dependent; pre-12 it essentially never works without `startBluetoothSco()`. The modern, supported recipe for deliberately capturing from a BT headset mic is `AudioManager.setCommunicationDevice(scoDevice)` (API 31+) for the session duration, with `clearCommunicationDevice()` on stop, or the deprecated `startBluetoothSco()/stopBluetoothSco()` below API 31. The app targets modern API (uses API-33+ `getParcelableExtra(Uri::class.java)` in `SharedAudioHandoffViewModel.kt:64`), so the API-31+ path suffices.

**Trigger / reproduction:** On-device: pair a classic (non-LE-audio) BT headset; select it manually; record on each of the three surfaces; verify `Effective capture route:` log (`AudioInputDeviceSelector.kt:176`) reports the SCO device AND the audio is from the headset mic. Repeat with Automatic policy. Test at least one Samsung and one Pixel.

**Recommended fix:** (Only if verification reproduces — otherwise document the verified matrix.)
1. Create `core-audio/.../audio/CommunicationDeviceSession.kt`: a small class wrapping `audioManager.setCommunicationDevice(device)` / `clearCommunicationDevice()` with refcount/idempotency, used only when the resolved device kind is `Bluetooth` (SCO). Add `OnCommunicationDeviceChangedListener` to detect activation failure and fall back (clear + re-resolve without the SCO device).
2. Engage it inside `AudioInputDeviceSelector.buildAudioRecord` (acquire before constructing the `AudioRecord`) and release from the same generation-token teardown added in MIC-003 — this guarantees SCO start/stop balance across every surface with one implementation, including all error paths (the engines already funnel teardown through `stopAudioRecord`/`releaseAudioLocked`).
3. Do **not** set `MODE_IN_COMMUNICATION` — `setCommunicationDevice` does not require it for capture routing, and forcing the mode would change playback behavior app-wide.
4. Edge cases: SCO activation is asynchronous (~1 s) — the engine's existing init-retry (`GaplessWavSegmentCapture.kt:96-129`, 3×150 ms; `VoiceRecorder.kt:247-284`) is too short for SCO link bring-up; gate the first `startRecording()` on the communication-device-changed callback with a ~2 s timeout, falling back to default routing on timeout (and surfacing the existing fallback notice).

**Tests to add/modify:** Unit-test `CommunicationDeviceSession` refcounting/failure-fallback with a mocked `AudioManager`; this is otherwise an instrumentation/on-device checklist item (add to `ONDEVICE.md`).

**Fix isolation:** Depends on MIC-003's session-token plumbing for clean release (can be done standalone with its own refcount, but pairing them avoids two teardown mechanisms). Co-edits `AudioInputDeviceSelector.kt` (serialize with MIC-002/011/012/013).

**Fix risk:** Medium-high — `setCommunicationDevice` affects process-wide routing; an unbalanced acquire leaves the phone in headset-routing after recording. The refcounted session + timed fallback bounds this.

---

### MIC-007 — `VoiceRecorder` stop paths are two-phase and not atomic against a concurrent `start()`

- **Severity:** Medium (data-corruption class; very narrow window) · **Confidence:** Confirmed (code-level; not reproduced)
- **Category:** Race
- **Affected files & lines:** `core-audio/.../recorder/VoiceRecorder.kt:500-532` (`stop()`: `stopAudioRecord()` lock block at `:501`, separate `synchronized` block at `:524`; un-locked `hasError` read at `:511`), `:534-565` (`stopToFileBacked()`: same two-phase shape, `:535` then `:541`), `:220-332` (`startInternal` — `isRecording` early-out at `:227` happens long before publication at `:302-316`)

**User-visible symptom / impact:** If a new `start()` publishes its session in the gap between a concurrent stop's two lock blocks, the stop closes/steals the **new** session's `sampleOutput`/`sampleFile` (FileBacked) or zeroes its `sampleCount` (InMemory): the new dictation loses its capture stream from t=0 (subsequent writes hit a closed stream → `StorageUnavailable` error path), and the old stop may return the new session's file as its own capture. Practically requires a thread preemption inside the microsecond gap *while* a full `start()` (permission check + DataStore read + AudioRecord init, ≥10 ms) completes — so essentially unobservable today, but the recorder's own design intent (generations for "stale collectors and aborted start attempts", `:139-144`) shows this class matters, and the stop paths simply didn't get the same treatment.

**Root-cause analysis:**
```kotlin
// VoiceRecorder.kt:534
fun stopToFileBacked(): CapturedPcmFloatFile? {
    val durationMs = stopAudioRecord()          // lock block #1: bumps generation, releases record
    ...
    return synchronized(sampleLock) {           // lock block #2: closes output, takes sampleFile
        closeSampleOutputLocked()
        val file = sampleFile                   // <-- may now belong to a NEWER session
```
Lock block #2 never checks that the session it tears down is the one block #1 ended (no generation comparison), unlike `failCollect` (`:616-628`) which does. Callers do serialize in practice (IME teardown joins; recognition holds `lifecycleMutex`) — the exposure is the keyboard's *user-tap* path where `stopAndTranscribe` schedules teardown on IO and a fresh `startRecording` is only fenced by the (still-held) global recording lock; if a future refactor releases the lock earlier, this becomes live.

**Trigger / reproduction:** Test-only: call `stop()` with an injected pause between the two blocks while a `start()` runs to completion; assert the second session's output survives.

**Recommended fix:** In `stop()`/`stopToFileBacked()`/`cancelCapture()`/`close()`, capture `val endedGeneration = sessionGeneration.get()` *inside* `stopAudioRecord()`'s lock block (return it alongside duration), and make the second block no-op (return empty/null) if `sessionGeneration.get() != endedGeneration` — exactly the discipline `failCollect` already uses. Move the `hasError` read at `:511` inside the synchronized block (or make `hasError` `@Volatile`). Behavior preserved: the normal single-caller path sees matching generations.

**Tests to add/modify:** `VoiceRecorderTest`: "stop racing a new start never tears down the new session" — drive with a generation injected via reflection or by interleaving `start()` between two halves using a subclassed/seam hook; simpler: assert `stopToFileBacked` returns null and leaves `sampleOutput` intact when the generation moved (expose a `@VisibleForTesting` seam).

**Fix isolation:** Independent. Co-edits `VoiceRecorder.kt` with MIC-003 (token clear) and MIC-013 (routing listener) — serialize that file.

**Fix risk:** Low — additive generation checks.

---

### MIC-008 — Keyboard stop→immediate mic tap yields a misleading "mic in use by the keyboard" toast

- **Severity:** Medium · **Confidence:** Confirmed
- **Category:** Transition
- **Affected files & lines:**
  - `feature-keyboard/.../session/KeyboardSessionCoordinator.kt:501-542` (`stopAndTranscribe` flips `isRecording=false` and frees the UI before any state transition), `:426-438` (`onMicTap` → falls through to `startRecording`), `:440-491` (`startRecording` → `capture.start()`)
  - `feature-keyboard/.../quickcapture/QuickCaptureSessionImpl.kt:80-97` (`tryStartRecording` → `AlreadyRecording(KEYBOARD)` → Toast "mic in use by *keyboard*")

**User-visible symptom / impact:** User taps stop, then taps the mic again quickly (very common dictation rhythm). For the whole teardown + transcription window the global lock is still held by the *previous* session (state `Recording` until `transitionToStopping` at `:565`, then `Stopping` until the pipeline completes), so the new tap shows a toast: *"Microphone in use by keyboard"* — to the user, "the keyboard" is themselves and they just stopped. There is no "still finishing the previous dictation" state; on a slow transcription this looks like the mic is wedged.

**Root-cause analysis:** Intentional serialization (a dictation's transcription must finish before the next), but the rejection surfaces through the generic cross-surface busy path with the same origin, producing a self-referential message. `onMicTap`'s dispatch (`:429-437`) has no branch for "stop pipeline in flight".

**Trigger / reproduction:** Dictate a long sentence; tap stop; tap mic within ~1 s. Toast appears.

**Recommended fix:** In `KeyboardSessionCoordinator.onMicTap`, add a branch before `else -> startRecording()`: when `recordingStateManager.state.value` is `Stopping` with `origin == KEYBOARD` (or `teardownJob?.isActive == true` / `transcriptionJob?.isActive == true`), do nothing visible except (optionally) a subtle "Finishing…" pulse via `transcription.phase` — the panel already shows the Transcribing phase, so the minimal correct fix is **suppressing the start attempt** (and therefore the toast) in that window. In `QuickCaptureSessionImpl.start` keep the toast for genuinely *other*-origin busy; for `result.currentOrigin == KEYBOARD` return `AlreadyRecording` without toasting (the coordinator decides UI).

**Tests to add/modify:** `KeyboardSessionCoordinatorTest`: mic tap during Stopping(KEYBOARD) does not call `capture.start()` and shows no error; mic tap while an APP recording is live still produces the busy result.

**Fix isolation:** Independent. Co-edits `KeyboardSessionCoordinator.kt` with MIC-017 — same file, serialize (or one subagent for both).

**Fix risk:** Low.

---

### MIC-009 — Device loss while Paused force-stops the session even though the mic is closed (defeats pause→swap→resume)

- **Severity:** Medium · **Confidence:** Confirmed
- **Category:** Transition
- **Affected files & lines:** `feature-recording/.../service/RecordingService.kt:174-191` (device-lost listener: gate is `state.value.isActive` — `RecordingState.isActive` includes `Paused`, `core-contracts/.../RecordingState.kt:67-69`); `:591-638` (`pauseRecording` releases the engine/mic via `pauseAndFinalizeSegment` and never clears `activeDeviceId`)

**User-visible symptom / impact:** User pauses a recording specifically to unplug/swap the USB mic (a flow the code *supports*: `resumeRecording` re-resolves the device, `:675-693`). The moment the USB mic is unplugged, the session is **auto-stopped and saved** with "input device disconnected", destroying the pause-swap-resume flow and surprising the user (nothing was being captured from that device — the engine was already released at `:610-615`).

**Root-cause analysis:** `pauseRecording` finalizes the segment and drops the engine but leaves `AudioInputDeviceSelector.activeDeviceId` pointing at the paused session's device (no `clearActiveDevice`/suspension on pause). The removal callback then satisfies `lostActive` (`AudioInputDeviceSelector.kt:71-81`), and the service listener only checks `isActive` + ownership — not whether capture is actually live:

```kotlin
// RecordingService.kt:182
if (!service.recordingStateManager.state.value.isActive || !service.serviceOwnsCapture()) return@launch
service.announceAutoStop(RecordingAutoStopReason.INPUT_DEVICE_LOST, lostDeviceName)
service.stopRecording()
```

**Trigger / reproduction:** Record with a USB mic → Pause → unplug the mic → session stops and saves instead of staying paused.

**Recommended fix:** In the device-lost listener, treat `Paused` as benign: replace the gate with `state.value is RecordingState.Recording || state.value is RecordingState.Starting` (plus the existing ownership check). While paused, the correct behavior is *nothing* (resume already re-resolves and shows the fallback notice if the preferred device is gone). Optionally also publish a non-stopping advisory ("Mic disconnected — will use X when you resume") via `RecordingServiceEvents`, but that is enhancement, not fix. Also consider clearing/suspending `activeDeviceId` during pause (a `suspendActiveDeviceTracking()` on the selector) so the loss callback doesn't even fire — but the listener-side gate is the smaller, safer change.

**Tests to add/modify:** New service-level test (pattern of `RecordingServiceStopRaceTest` or a unit test around the listener lambda extracted to a testable function): device-lost while `Paused` → no auto-stop event, state remains Paused; device-lost while `Recording` → auto-stop (regression pin).

**Fix isolation:** Co-edits `RecordingService.kt` (serialize with MIC-001/003/010/019).

**Fix risk:** Low — narrowing a condition; keep the `Starting` case stopping (a device that disappears mid-start should still abort deliberately).

---

### MIC-010 — Resume re-resolves the input device → silent mid-session mic swap across auto-pause/auto-resume

- **Severity:** Low · **Confidence:** Confirmed
- **Category:** Transition / Cross-surface (contract consistency)
- **Affected files & lines:** `feature-recording/.../service/RecordingService.kt:675-693` (`resumeRecording` → `createCaptureEngine` → fresh `buildAudioRecord`), `:221-236` (auto-resume), vs. the stated contract in `RecordInputDevicePicker.kt:42-43` and `RecordingService.kt:185-186` ("no silent mid-recording device swap" is the rationale for the device-lost auto-stop)

**User-visible symptom / impact:** A transient focus loss (call ends, alarm dismissed) auto-pauses and auto-resumes the session; resume re-runs device selection. If the user changed the preference mid-session, or the device set changed while paused, the *same logical recording* continues on a **different microphone with no notice** (different gain structure, noise floor, sample characteristics — audible seam in the final concatenated file). This is the very thing the device-lost handler refuses to do silently.

**Root-cause analysis:** Resume is implemented as a fresh capture start (`segmentCapture = createCaptureEngine(...); segmentCapture!!.start(nextSegment)`), inheriting capture-start selection semantics. The fallback annotation (`fallbackFromPreferredName`) only fires for a *missing preferred* device — a deliberate preference change mid-pause resumes on the new device with zero UI.

**Trigger / reproduction:** Record on built-in → pause → select USB in the picker → resume → segment N+1 is captured from USB; no notice anywhere; the live chip changes silently.

**Recommended fix (decide the contract, then enforce it):** The pragmatic choice is to **embrace** re-resolution (it is what makes pause-swap-resume work, see MIC-009) but make it *visible*: in `resumeRecording`, after the engine starts, compare the newly published `activeDevice` with the pre-pause one (capture it in `pauseRecording` into a field); if different, publish a new `RecordingServiceEvents` advisory (`RecordingAutoPauseReason`-style, e.g. `deviceChangedOnResume(from, to)`) rendered by the existing advisory banner (`RecordingSessionAdvisory.kt`) and the notification status line (`currentRecordingStatusText`, `RecordingService.kt:721-736`). Do not block or alter selection.

**Tests to add/modify:** `RecordingSessionAdvisoryTest`: new advisory resolution priority (place below PAUSED_BY_FOCUS_LOSS, above SILENCED). Service test: pause → preference change → resume publishes the event.

**Fix isolation:** Co-edits `RecordingService.kt` + `RecordingSessionAdvisory.kt` (+ `RecordingServiceEvents.kt`). Serialize with the RecordingService batch. Independent of all selector findings.

**Fix risk:** Low (additive advisory).

---

### MIC-011 — Capture-side selection runs on the unfiltered device list; legacy manual keys can pin non-recordable "Other" endpoints the picker no longer shows

- **Severity:** Medium · **Confidence:** Confirmed (by construction; needs a pre-f63c626 stored key to manifest)
- **Category:** Cross-surface / Platform-correctness
- **Affected files & lines:** `core-audio/.../AudioInputDeviceSelector.kt:118-129`:

```kotlin
suspend fun resolvePreferredDevice(): AudioDeviceInfo? {
    val settings = audioSettingsStore.currentSettings()
    val devices = inputDevices()                       // RAW list
    val summaries = devices.map(::summaryFor)          // <-- NOT surfaceableInputDevices(...)
    val choice = chooseInputDevice(devices = summaries, ...)
```

vs. `listInputDevices()`/`refreshAvailableDevices()` (`:90-91`, `:201-203`) which filter through `surfaceableInputDevices` (`:236-241`, added in f63c626). UI prediction `chipDevice()` (`core-ui/.../InputDevicePicker.kt:95-98`) also runs on the filtered list.

**User-visible symptom / impact:** Two divergences:
1. **Legacy poisoned key:** before f63c626 the picker displayed `Other`-kind rows ("SM-S938U1 / Other" — telephony/FM endpoints, per the commit message); a user who selected one persisted `device:<TYPE_TELEPHONY_RX or FM_TUNER>:<model>`. Post-fix, the picker resolves that key against the *filtered* list → "not connected" row; but `resolvePreferredDevice` resolves it against the *raw* list → **match** → `setPreferredDevice(telephonyEndpoint)` → broken/silent capture, while the UI claims the device is absent. UI and engine disagree about the same key.
2. **Ranking divergence (theoretical today):** Automatic ranking over the raw list can in principle select a row the user cannot see; `Other` ranks last so this only matters in degenerate lists, but the dedup means the engine may pin a *duplicate* built-in row the picker collapsed — harmless, yet the active-device id then refers to a row whose removal semantics differ.

**Trigger / reproduction:** Write `manual_device_address = "device:<TYPE_FM_TUNER>:SM-S938U1"` into the audio settings DataStore (simulating a pre-fix selection or a restored backup); set policy Manual; start a recording; observe `Input device selected:` log naming the Other endpoint and the picker simultaneously showing "not connected".

**Recommended fix:** In `resolvePreferredDevice` (`:121`), filter kinds only — `val summaries = devices.map(::summaryFor).filter { it.kind != AudioInputDeviceKind.Other }` — but do **not** apply `distinctBy` there (duplicate built-in rows must remain matchable so existing stored keys for the dropped sibling row still resolve to hardware; `findDeviceForSelectionKey` first-match keeps capture deterministic). Extract the filter into a named helper (`recordableInputDevices`) beside `surfaceableInputDevices` so the relationship is explicit. Resulting behavior for the poisoned key: no match → `preferredMissing=true` → ranked fallback + fallback notice — now consistent with the picker.

**Tests to add/modify:** `AudioInputDeviceSelectorTest`: `chooseInputDevice` with a manual key matching only an Other-kind summary, over the recordable-filtered list → fallback with `preferredMissing=true`; Automatic over a list of Other+BuiltIn picks BuiltIn (already implied, pin explicitly through the new helper).

**Fix isolation:** Co-edits `AudioInputDeviceSelector.kt` (serialize with MIC-002/003/012/013).

**Fix risk:** Low — only the resolution input set changes; the matrix function itself is untouched.

---

### MIC-012 — Display-name dedup collapses genuinely distinct same-name devices; hidden-name BT devices collapse to one row

- **Severity:** Low · **Confidence:** Confirmed
- **Category:** Platform-correctness / Cross-surface (UX limitation)
- **Affected files & lines:** `core-audio/.../AudioInputDeviceSelector.kt:236-241` (`distinctBy { it.kind to it.productName }`), `:426-453` (`summaryFor`: without BLUETOOTH_CONNECT every BT device's `productName` degrades to the type label "Bluetooth"/"Bluetooth LE")

**User-visible symptom / impact:** (a) Two BT headsets connected without the BLUETOOTH_CONNECT grant render as a single "Bluetooth" row — the second headset cannot be selected, and the surviving row's `selectionKey` is whichever enumerated first. (b) Two identical-model USB mics (same `productName`) collapse to one row — the second is unselectable. In both cases capture-side matching can route to the *other* physical unit than the user believes they picked.

**Root-cause analysis:** The dedup key is the **display** identity (intentionally, to collapse Samsung's duplicate built-in rows whose raw keys differ), which over-collapses when distinct hardware shares a display name. For hidden-name BT this is arguably the pre-existing precision (the user could never distinguish them anyway — mirrors `bluetoothIdentityFallbackMatches` doc, `:367-385`), but for named devices with distinct addresses it discards real choices.

**Trigger / reproduction:** Connect two BT headsets without granting BLUETOOTH_CONNECT → picker shows one "Bluetooth" row. Or two same-model USB interfaces on a hub.

**Recommended fix:** Refine the dedup key: `distinctBy { Triple(it.kind, it.productName, it.address?.takeIf { a -> a.isNotBlank() }) }` — devices with distinct non-blank addresses never collapse; blank-address duplicates (the Samsung built-in case, hidden-name BT) still collapse exactly as today. Add a disambiguating suffix in the picker row (`supporting = typeLabel` already exists; append nothing — the address-keyed rows will simply both appear with identical names, acceptable) or show the address tail for same-name pairs (optional polish).

**Tests to add/modify:** `AudioInputDeviceSelectorTest.surfaceableInputDevices_*`: two same-name USB devices with different addresses both survive; the Samsung duplicate-built-in case still collapses (existing test must stay green); two hidden-name BT (blank address) still collapse.

**Fix isolation:** Co-edits `AudioInputDeviceSelector.kt` — serialize with MIC-002/003/011/013.

**Fix risk:** Low; one regression to guard is the on-device finding f63c626 fixed (duplicate built-in rows) — the blank-address rule preserves it.

---

### MIC-013 — Routing validated once per engine start; no `OnRoutingChangedListener`; `setPreferredDevice` result ignored

- **Severity:** Medium · **Confidence:** Confirmed
- **Category:** Platform-correctness
- **Affected files & lines:**
  - `core-audio/.../AudioInputDeviceSelector.kt:159-161` (`record.setPreferredDevice(device)` — boolean discarded), `:173-182` (`refreshActiveDeviceFromRouting`, called once)
  - `feature-recording/.../service/GaplessWavSegmentCapture.kt:307-311` (`if (!routingChecked) { routingChecked = true; ... }`)
  - `core-audio/.../recorder/VoiceRecorder.kt:468-473` (same once-only pattern)
  - Repo-wide zero hits for `addOnRoutingChangedListener`/`OnRoutingChangedListener` (verified by grep)

**User-visible symptom / impact:** Any reroute *after* the first read is invisible to the app: preferred device disappears → framework silently falls back to default input → the UI keeps showing the old device for the rest of the session (especially the IME/recognition surfaces, which have no device-lost auto-stop — see MIC-014); the silence advisory can name the wrong device (`currentRecordingStatusText` reads `activeDeviceLabel`, `RecordingService.kt:727`); the device-loss detection by id (MIC-002) can diverge from reality. A `setPreferredDevice` failure (returns `false` when the record is in an invalid state) silently records from the default device while the app publishes the preferred one as active.

**Root-cause analysis:** Android's source-of-truth for live capture routing is `AudioRecord.getRoutedDevice()` + `addOnRoutingChangedListener`; the code samples it once ("`getRoutedDevice` is only populated once the stream is live", `:168-172`) and never subscribes to changes. Routing is dynamic by contract: preferred-device loss, BT link drops, and policy changes all reroute live streams.

**Trigger / reproduction:** Keyboard dictation on a BT headset; power the headset off mid-dictation. Capture continues from the built-in mic (platform fallback); keyboard chip + selector `activeDevice` keep naming the headset until the dictation ends.

**Recommended fix:**
1. In `AudioInputDeviceSelector.buildAudioRecord` (or a small helper the engines call after `startRecording()`), register `record.addOnRoutingChangedListener({ refreshActiveDeviceFromRouting(record) }, Handler(Looper.getMainLooper()))`; remove it in the engines' release paths (`GaplessWavSegmentCapture.releaseAudioLocked` `:396-400`, `VoiceRecorder.stopAudioRecord` `:594-604`) — `removeOnRoutingChangedListener` before `release()`. Keep the existing first-read refresh (the listener may not fire for the initial route on all versions).
2. Log-and-fallback on `setPreferredDevice == false` at `:160`: `Log.w` and skip setting `activeDeviceId` to the requested id (let the routing refresh establish truth).
3. Keep `refreshActiveDeviceFromRouting` as the single mutation point (it already updates id + flows and logs the effective route).

**Tests to add/modify:** Selector unit test: `refreshActiveDeviceFromRouting` invoked twice with different routed devices updates `activeDevice` both times (exists implicitly via `AudioInputDeviceLossTest.routeCaptureTo` — add an explicit re-route case). Engine tests can't exercise the platform listener on JVM; add the registration/removal symmetry to `GaplessWavSegmentCaptureTest`-style verification with a mocked record if the seams allow, otherwise on-device checklist.

**Fix isolation:** Co-edits `AudioInputDeviceSelector.kt` (selector batch), `GaplessWavSegmentCapture.kt` (with MIC-021), `VoiceRecorder.kt` (with MIC-003/007). Schedule after MIC-002 (same fields).

**Fix risk:** Low-medium — listener leaks if removal is missed on an error path; route every removal through the existing single release functions.

---

### MIC-014 — IME and recognition surfaces have no device-loss handling at all

- **Severity:** Medium · **Confidence:** Confirmed
- **Category:** Cross-surface / Ownership-conflict
- **Affected files & lines:**
  - `core-audio/.../AudioInputDeviceSelector.kt:184-186` — single-listener slot, registered only by `RecordingService.kt:174-191`
  - `feature-keyboard/.../session/KeyboardSessionCoordinator.kt:221-239` (`capture.onRecordingError` — only reached on AudioRecord read errors)
  - `app/.../ChirpRecognitionService.kt:283-285` / `app/.../VoiceRecognitionActivity.kt:260` (same: error-callback only)

**User-visible symptom / impact:** The three surfaces react to the *same physical event* (active mic unplugged/BT died) in three different ways: app recording → deliberate auto-stop with a named reason; keyboard dictation → usually a **silent reroute** to the built-in mic (because a vanished preferred device falls back to default routing without an error; `ERROR_DEAD_OBJECT` fires only when the *routed* device's stream dies before fallback) with a stale chip (MIC-013); recognition → ditto, or a hard `CaptureFailed`. The user gets a named, explained behavior on one surface and silent behavior changes on the others.

**Root-cause analysis:** `onActiveDeviceLost` is a single-listener slot consumed by `RecordingService`; the listener fires for *any* surface's active device (the selector is shared), but the service deliberately ignores events it doesn't own (`:182`), and nobody else listens. The IME's only signal is `RecordingError.DeadObject` from a read failure, which the platform often avoids by rerouting.

**Trigger / reproduction:** Dictate on the keyboard with a BT headset; turn the headset off. Observe: no toast/hint, chip unchanged, dictation continues from the phone mic.

**Recommended fix (after deciding desired UX — recommendation: *inform, don't stop* on quick-capture surfaces):**
1. Replace the single-listener slot with a `MutableSharedFlow<DeviceLostEvent>(extraBufferCapacity = 4)` on the selector (`DeviceLostEvent(deviceId, name)`), keep a thin adapter for the service's existing lambda registration to minimize churn (or migrate the service to collect the flow in `onCreate`'s scope).
2. `KeyboardSessionCoordinator` collects it while `isRecording` and surfaces a transient hint (reuse the silence-hint plumbing: a `deviceChanged` StateFlow → `mapKeyboardUiState`), and triggers an immediate `refreshActiveDeviceFromRouting`-driven chip update (comes free with MIC-013's routing listener).
3. Recognition surfaces: same advisory in the dialog (`VoiceRecognitionDialog` already renders model/error states); no auto-stop (the endpointer/user stop governs).
4. Preserve exactly the service's current auto-stop semantics (gated by ownership + the MIC-009 fix).

**Tests to add/modify:** Selector: multiple collectors each receive the loss event. `KeyboardSessionCoordinatorTest`: device-lost during recording sets the hint; after stop, no hint.

**Fix isolation:** Depends on MIC-013 (routing listener provides the chip correction) and touches `AudioInputDeviceSelector.kt` (selector batch) + `KeyboardSessionCoordinator.kt` (keyboard batch) + `ChirpKeyboardService.kt`. Schedule last among the selector-file findings.

**Fix risk:** Medium — replacing the listener slot must not break `AudioInputDeviceLossTest` (4 pinned behaviors) or the service's stop-with-save flow.

---

### MIC-015 — Recognition surfaces tear the recorder down on the main thread at destroy

- **Severity:** Low · **Confidence:** Confirmed
- **Category:** Lifecycle / Resource-mgmt (perf)
- **Affected files & lines:**
  - `app/.../VoiceRecognitionActivity.kt:745-761` — `onDestroy` calls `recorder.stop()` (AudioRecord stop/release binder calls + up to ~38 MB `samples.copyOf` for a 10-minute capture) and `recorder.close()` on the main thread
  - `app/.../ChirpRecognitionService.kt:503-510` — `onDestroy` → `sessionCoordinator.shutdown()` → `recorder.cancel()` (`VoiceRecognitionSessionCoordinator.kt:151-158`) on main

**User-visible symptom / impact:** Jank/ANR risk during activity/service destruction after long captures — the same PERF-5 class the codebase explicitly moved off-main everywhere else (see the elaborate `awaitInFlightTeardown` machinery in `KeyboardSessionCoordinator.kt:264-303` and the off-main hops in `VoiceRecognitionSessionCoordinator.kt:266-281`).

**Root-cause analysis:** The destroy paths predate the off-main teardown work; `shouldRescueOnDestroy` + `rescueScope` handle the persistence side off-main but the recorder stop itself stayed on main.

**Trigger / reproduction:** 8–10-minute recognition capture; swipe the task away; watch main-thread stalls in Perfetto during `onDestroy`.

**Recommended fix:** In `VoiceRecognitionActivity.onDestroy`, when `captureGate.isHeld()`, move the stop+rescue into `rescueScope` (already `SupervisorJob + Dispatchers.IO` and documented to survive teardown): `rescueScope.launch { val samples = recorder.stop(); captureGate.releaseCompleted(); if (shouldRescue...) persist; recorder.close(); audioFocus.abandonFocus() }` — capturing `_recordingState.value`, `secureSession` and `captureTeardownDiscardsAudio` *synchronously before* launching (they are main-confined). Mirror in `ChirpRecognitionService.onDestroy`: hop `sessionCoordinator.shutdown()`'s recorder work to a short-lived IO scope, keeping `scope.cancel()` ordering after. Edge: the gate-held check and the rescue classification must read main-thread state before the hop (they currently do, inline).

**Tests to add/modify:** `VoiceRecognitionDestroyRescueTest` (exists) — keep green; add an assertion that rescue still occurs when the stop is dispatched async (inject a test dispatcher for the rescue scope).

**Fix isolation:** Independent; co-edits `VoiceRecognitionActivity.kt` (with MIC-004 recognition half, MIC-016 call-site notes) and `ChirpRecognitionService.kt` — keep recognition-surface findings in one batch.

**Fix risk:** Medium-low — destroy-path reordering is exactly where past bugs lived here (the file's comments document three prior races); preserve `captureTeardownDiscardsAudio` semantics byte-for-byte.

---

### MIC-016 — `AudioFocusManager` is not thread-safe; recognition surfaces abandon focus on IO while requesting on main

- **Severity:** Medium · **Confidence:** Confirmed (race exists; consequence is a leaked focus hold or missed callback)
- **Category:** Race / Resource-leak
- **Affected files & lines:**
  - `core-audio/.../AudioFocusManager.kt:35-36` (`focusRequest`, `hasFocus` plain vars), `:40-75` (request/abandon unsynchronized)
  - Cross-thread call sites: `app/.../VoiceRecognitionActivity.kt:152-161` and `app/.../ChirpRecognitionService.kt:94-103` — `recorderControl.stop()/cancel()` run `audioFocus.abandonFocus()` on `ioDispatcher` (`VoiceRecognitionSessionCoordinator.kt:271-281`), while `prepare()` calls `requestFocus()` from the main-thread `lifecycleMutex` path and `handleFocusChange` arrives on main

**User-visible symptom / impact:** A torn `focusRequest`/`hasFocus` pair across threads can (a) abandon a request the main thread is mid-replacing → the *new* request never abandoned → **other apps' media stays paused** until process death; (b) `handleFocusChange` reading stale `hasFocus=false` → a real focus loss dropped → recognition keeps capturing over a phone call's audio. Both rare; both bad.

**Root-cause analysis:** The class assumes single-threaded use; the recognition coordinator deliberately moved teardown off-main (PERF-5) and took the focus abandon with it (it lives inside `RecorderControl.stop/cancel`). `RecordingService` and the keyboard use it main-only — correct today, fragile tomorrow.

**Trigger / reproduction:** Stress start/stop recognition sessions while toggling external playback; inspect `adb shell dumpsys audio` focus stack for orphaned entries after sessions end.

**Recommended fix:** Make `AudioFocusManager` internally `synchronized` (all four mutating members + `handleFocusChange`'s read of `hasFocus`); it is tiny and uncontended, a monitor is sufficient. Additionally, in `requestFocus()` abandon any existing `focusRequest` before building the new one (defense-in-depth for the restart path `RecordingService.kt:873-907` which re-requests without abandoning — currently saved only by the same-listener client-id dedup inside `AudioManager`). Alternatively (smaller): move the `abandonFocus()` calls in the two `RecorderControl` implementations out of `stop()/cancel()` and back onto the calling main path — but the class-level fix protects all current and future callers.

**Tests to add/modify:** `AudioFocusManagerTest` (exists, 8 cases): add `request → request → abandon` leaves no outstanding request (verify `abandonAudioFocusRequest` called for the first); concurrent abandon/handleFocusChange smoke test.

**Fix isolation:** Fully independent and parallel-safe (`AudioFocusManager.kt` is touched by no other finding).

**Fix risk:** Low.

---

### MIC-017 — Keyboard cancel tapped during the stop-teardown window is silently dropped; dictation commits against user intent

- **Severity:** Medium · **Confidence:** Confirmed
- **Category:** Race / Transition
- **Affected files & lines:** `feature-keyboard/.../session/KeyboardSessionCoordinator.kt:649-671`:

```kotlin
fun cancelRecording(userInitiated: Boolean) {
    val wasRecording = isRecording.value                       // false: stop already flipped it (:510)
    val wasStarting = startJob?.isActive == true               // false
    if (!wasRecording && transcriptionJob?.isActive != true) { // transcriptionJob not created until
        if (wasStarting) { ... }                               //   finishStopAfterTeardown (:573-594)
        return                                                  // <-- cancel silently dropped
    }
```

with `stopAndTranscribe` (`:501-542`) flipping `isRecording=false` on main and creating `transcriptionJob` only at the end of the IO teardown (`:516-540` → `finishStopAfterTeardown:573`).

**User-visible symptom / impact:** User taps stop, then immediately taps cancel (changed their mind / accidental stop). If the cancel lands inside the teardown window (5–50 ms typical, longer under IO pressure/GC — and the window includes the `teardownDispatcher` dispatch latency), it is a no-op: the transcription pipeline launches anyway and **commits the text into the field**, plus persists per preference. User intent violated.

**Root-cause analysis:** The cancel path keys on `isRecording` and `transcriptionJob`, but there is a third state — "teardown in flight" (`teardownJob` active, pipeline not yet started) — that matches neither branch.

**Trigger / reproduction:** Instrument `teardownDispatcher` with a 200 ms delay (test seam exists — it's constructor-injected); stop, then cancel within the delay; assert commit still happens (bug) → after fix, assert pipeline never starts.

**Recommended fix:** Introduce a `@Volatile private var cancelRequestedDuringTeardown = false` (or an `AtomicBoolean`). In `cancelRecording`, when `!wasRecording && !wasStarting && transcriptionJob?.isActive != true` **but** `teardownJob?.isActive == true`: set the flag (and `transcription.markUserCancelled()` when `userInitiated`), return. In `finishStopAfterTeardown` (`:544`), first check-and-clear the flag: if set, `sessionPersistence`-discard the audio source (`persistence.discardAudioSource(audioSource)` — respecting the user-cancel persistence preference via the existing `USER_CANCELLED` reason path), call `recordingStateManager.onRecordingCompleted()`, `transcription.resetPhase()`, `clearPendingStop()`, and skip the pipeline. Edge cases: a *non*-user-initiated cancel (service destroy) must NOT set the flag (destroy already rescues via the unmarked-cancellation path — keep `cancelRecording(userInitiated=false)` behavior for live recordings unchanged); the flag must be cleared on every session start.

**Tests to add/modify:** `KeyboardSessionCoordinatorTest`: stop → cancel-during-teardown (deferred dispatcher) → no commit, no COMPLETED persist, state machine released; stop → teardown completes → cancel → existing transcription-cancel behavior unchanged.

**Fix isolation:** Co-edits `KeyboardSessionCoordinator.kt` with MIC-008 — same file, one subagent for both.

**Fix risk:** Medium — this file's stop/cancel/destroy ordering is the most delicate in the repo (see its own comments); the flag must interact correctly with `awaitInFlightTeardown` (it does: the teardown body still completes; only the pipeline launch is skipped).

---

### MIC-018 — Speech endpointer: continuous gain-amplified ambient noise still defeats both terminals; endpointer starves if no frames arrive

- **Severity:** Medium · **Confidence:** Likely (mechanism confirmed in code; field conditions assumed)
- **Category:** No-speech
- **Affected files & lines:** `app/src/main/java/dev/chirpboard/app/SpeechEndpointer.kt:94-113` (`speechEstablished` after a 300 ms continuous above-threshold run), `:124-141` (terminals), `:168` (threshold 0.01 at gain 1.0); amplitude is **post-gain**: `core-audio/.../recorder/VoiceRecorder.kt:476-479` (`sum += abs(buffer[i] * gainMultiplier)`); frame delivery only on successful reads: `VoiceRecognitionActivity.kt:176-183` / `ChirpRecognitionService.kt:115-122` (`streamRms` ← `sampleCountFlow` ← reads)

**User-visible symptom / impact:**
1. *Continuous-noise defeat:* with microphone gain at 3–5× (user-settable to 5.0), steady ambient noise (fan, traffic, air-conditioning ≈0.003–0.005 raw → 0.015–0.025 post-gain) stays above the 0.01 threshold **continuously**, so a 300 ms run establishes `speechEstablished` → the no-speech cap is permanently disabled (`:139` `if (!speechEstablished ...)`) and trailing-silence (`< threshold` for 2 s) never occurs → the recognition session listens until the 10-minute recorder cap. This is the *same symptom* commit 7a4b3d3 fixed ("no-speech timeout defeated by ambient noise"), surviving via the continuous-noise + gain corner. The intermittent-noise case IS fixed (runs reset at `:112`); the continuous case is not.
2. *Starvation:* the endpointer is only fed per delivered amplitude frame; a capture that produces no reads (wedged BT route, HAL stall — `READ_BLOCKING` never returns) delivers no frames → no `NO_SPEECH_TIMEOUT` → the dialog/service listens forever with a frozen waveform. No wall-clock watchdog exists on either recognition surface.

**Root-cause analysis:** (1) The threshold is absolute while the signal is pre-multiplied by user gain — the classifier's operating point shifts with a settings slider it doesn't know about. (2) Event-driven-only design: time only advances when audio arrives.

**Trigger / reproduction:** (1) Set gain 5×; run the recognition dialog next to a fan; say nothing; session never times out (expected: retry state at 10 s). (2) Harder: requires a stalled HAL; simulate in a unit test by never feeding frames and asserting no terminal — then assert the fixed watchdog fires.

**Recommended fix:**
1. *Gain compensation:* divide amplitude by the session gain before thresholding — the recorder knows `gainMultiplier`; either feed raw (pre-gain) amplitude to the endpointer (preferred: compute the waveform amplitude pre-gain in `VoiceRecorder.collectSamples` and apply gain only for display… too invasive) or simpler: pass `gainMultiplier` into `recognizerSessionEndpointer(...)` and scale `speechAmplitudeThreshold = DEFAULT * max(1f, gain)` at construction (`SpeechEndpointer.kt:202-216`, both surfaces construct there). Conservative and contained.
2. *Watchdog:* alongside `armSessionTermination`/the activity's start path, launch a session-scoped wall-clock job: after `max(noSpeechTimeoutMs, 15_000)` with `endpointer` having never emitted a terminal AND `sampleCountFlow` unchanged for ≥5 s (a frames-stalled signal), route into the existing generation-gated `abortSilentSession`/`onNoSpeechTimeout` paths (idempotent by design). Cancel the job on any terminal/stop.
3. Do not raise the base threshold (slow-quiet speakers regress; 7a4b3d3's on-device tuning notes apply).

**Tests to add/modify:** `SpeechEndpointerTest` (exists): continuous above-threshold amplitude at simulated 5× gain with the compensated threshold → `NO_SPEECH_TIMEOUT` at budget; genuine speech at 5× gain still establishes. New coordinator-level test for the stall watchdog (no frames → terminal fires once, generation-gated).

**Fix isolation:** Independent of all device-switcher findings. Co-edits `SpeechEndpointer.kt`, `VoiceRecognitionActivity.kt`, `ChirpRecognitionService.kt` — same batch as MIC-015 (same files).

**Fix risk:** Medium — endpointer tuning regressions are user-facing; keep 7a4b3d3's pinned tests green and add the gain dimension to the matrix.

---

### MIC-019 — `RecordingService` pause/resume error paths leak the engine, audio focus and foreground service until the next start

- **Severity:** Low · **Confidence:** Confirmed
- **Category:** Resource-leak / Lifecycle
- **Affected files & lines:** `feature-recording/.../service/RecordingService.kt:631-637` (pause catch: only `onRecordingError`), `:703-707` (resume catch: same); contrast with the start path's exhaustive cleanup (`:559-588`)

**User-visible symptom / impact:** If `pauseAndFinalizeSegment`/journal commit throws (ENOSPC, journal IO error) or resume's engine start fails after retries, the state machine goes `Error` and the **global lock is released**, but the service keeps: a possibly half-finalized engine in `segmentCapture` (pause nulls it only on success, `:615`), the audio focus request (other apps stay paused/ducked), the foreground notification, `currentSessionId` et al. Self-heals only when the next recording start runs `startGaplessCapture`'s release of the previous engine (`:1213-1215`) and re-requests focus. Until then: stuck notification + suppressed media.

**Root-cause analysis:** Error handling in pause/resume was written for the state machine only; the resource teardown that every other error path performs is absent.

**Trigger / reproduction:** Fill the disk to near-zero; record; pause (segment finalize write fails) → Error state with the foreground notification still standing and music ducked.

**Recommended fix:** Factor the start path's failure cleanup (`:559-588`'s body minus the file deletes) into a `private suspend fun failSessionAndShutdown(message: String, cause: Exception)`: detach+`releaseWithoutSave` the engine (it never deletes committed prior segments — it deletes only `currentSegmentFile`; for pause-failure the in-flight segment is already suspect, acceptable; if preserving it is desired use `releaseAfterStopTimeout` instead, which never deletes), `abandonFocus`, `clearActiveDevice` (token-aware after MIC-003), journal `markAbandoned` **only if** no recoverable artifacts (reuse `RecordingFinalizeRecoveryPolicy.hasRecoverableArtifacts` so a multi-segment session still gets recovered at next launch rather than discarded), `onRecordingError`, `stopForeground+stopSelf`. Call it from both catches. Careful: prefer `releaseAfterStopTimeout()` over `releaseWithoutSave()` in the pause catch so already-captured audio is never deleted on an error path — the startup reconciler will recover the session.

**Tests to add/modify:** Service-level: pause failure → focus abandoned (mock), foreground stopped, journal entry left recoverable; resume failure → same + no engine leak (next start does not find a stale `segmentCapture`).

**Fix isolation:** Co-edits `RecordingService.kt` — serialize within the RecordingService batch (MIC-001/003/009/010).

**Fix risk:** Medium — must not convert a recoverable multi-segment session into an abandoned one; the recovery-policy reuse is the guard.

---

### MIC-020 — Test-gap catalogue (race/transition scenarios with no coverage)

- **Severity:** Low (meta) · **Confidence:** Confirmed (by inspection of the test tree)
- **Category:** Test-gap
- **Affected files:** `core-audio/src/test/**`, `feature-recording/src/test/**`, `feature-keyboard/src/test/**`, `app/src/test/**`

Existing coverage is strong on: the pure selection matrix + BT grant/revoke identity (`AudioInputDeviceSelectorTest`, 25 cases), device-loss listener basics (`AudioInputDeviceLossTest`), recorder error/cancellation/generation basics (`VoiceRecorderTest`, 19 cases), focus-change sequencing (`AudioFocusManagerTest`), recognition coordinator serialization (`VoiceRecognitionSessionCoordinatorTest`), and the service stop/restart races (`RecordingServiceStopRaceTest`, `RestartStopCoordinatorTest`, `StopRequestGateTest`).

**Not covered anywhere (each maps to a finding above, plus extras):**
1. Focus LOSS_TRANSIENT→GAIN reordering vs. async pause (MIC-001).
2. Concurrent `resolvePreferredDevice` / `onAudioDevicesRemoved` visibility (MIC-002).
3. Stale `activeDevice` after IME/recognition sessions; fallback-notice gating (MIC-003).
4. `sessionLive` propagation on keyboard/recognition pickers (MIC-004).
5. Capture start racing the two-edit manual-selection write (MIC-005).
6. SCO-device end-to-end routing — on-device checklist only (MIC-006).
7. `VoiceRecorder.stop*` two-phase atomicity vs. a published new session (MIC-007).
8. Mic tap during keyboard Stopping window (MIC-008).
9. Device-lost while Paused / while Starting / during a gated stop (MIC-009; only the Recording case is tested).
10. Resume-time device re-resolution + notice (MIC-010).
11. Manual key matching an Other-kind endpoint at capture start (MIC-011).
12. Same-name distinct-address dedup survival (MIC-012).
13. Mid-session reroute updates `activeDevice` (MIC-013; only the initial `routeCaptureTo` is tested).
14. Device-lost event reaching the keyboard surface (MIC-014).
15. Cancel-during-teardown on the keyboard (MIC-017).
16. Endpointer continuous-noise × gain matrix; frame-starvation watchdog (MIC-018).
17. Pause/resume failure resource teardown (MIC-019).
18. **Rapid-thrash sequence:** select device A→B→A→Automatic during a live session, then stop→start — the next session must use the final selection exactly once (no double-start, no stale key). No test exercises >1 selection change per session.
19. **Transient focus loss during `Starting`** — currently silently ignored (pause requires `Recording`); pin intended behavior.
20. `AudioFocusManager` request-without-abandon double-request (restart path) (MIC-016).

**Recommended fix:** Implement alongside each finding (listed per finding above); items 18–19 are standalone pins that can be written immediately in `feature-recording`/`core-audio` test trees without production changes.

**Fix isolation:** Test-only items (18, 19) fully parallel-safe.

---

### MIC-021 — `GaplessWavSegmentCapture` defensive gaps: `require` leaks an initialized AudioRecord; zombie capture thread after join timeout; two-phase `rotateSegment` entry check

- **Severity:** Low · **Confidence:** Confirmed (all reachable only via misuse or a wedged HAL)
- **Category:** Resource-leak / Race (defense-in-depth)
- **Affected files & lines:** `feature-recording/.../service/GaplessWavSegmentCapture.kt:73-89` (`start`: `record` built at `:73`, `require(!running.get())` at `:77` throws without releasing it), `:233-241` (`signalStopAndJoinCaptureThread`: 5 s join timeout, then `:197-199` finalizes the writer and releases the AudioRecord while the zombie thread may still loop and call `record.read`/`writer.appendPcm16` — writer nulled under lock so appends no-op, and reads on a released record return errors → `failCapture` no-notify; safe but only by luck), `:131-146` (`rotateSegment` checks `running`/`pendingRotationTarget` in one lock block and installs the latch in a second — serialized today only by the caller's `segmentTransitionMutex`)

**User-visible symptom / impact:** None today (call patterns prevent all three). These are the booby traps the next refactor steps on.

**Recommended fix:** (1) In `start`, wrap the post-build section so that any throw releases `record` (`try { synchronized... } catch { runCatching { record.release() }; throw }`) — or move the `require` before `buildInitializedAudioRecord`. (2) In `signalStopAndJoinCaptureThread`, if `join` times out, log loudly and skip `releaseAudioLocked` until a second bounded attempt (or set a `wedged` flag consulted by `releaseAfterStopTimeout`) — at minimum add the log so field wedges are diagnosable. (3) Merge `rotateSegment`'s two entry lock blocks into one.

**Tests to add/modify:** `GaplessWavSegmentCaptureTest`: start-on-running engine releases the freshly built record (mock selector returns a mock record; verify `release()`); rotation entry atomicity unit test.

**Fix isolation:** Co-edits `GaplessWavSegmentCapture.kt` with MIC-013's engine half — same batch.

**Fix risk:** Low.

---

### Verified-OK (adversarially checked, no defect — recorded so fixers don't "fix" them)

- **BT grant invalidation class:** persisted manual keys survive BLUETOOTH_CONNECT grant/revoke via `bluetoothIdentityFallbackMatches` (`AudioInputDeviceSelector.kt:386-401`), exact-match precedence enforced, MAC keys deliberately never relaxed; pinned by 6 tests. Mid-session grants don't disturb the live session (tracking is by transient id).
- **Restart double focus request** (`RecordingService.kt:873-907` → new `requestFocus` without abandon): benign because `AudioFocusManager` reuses one `OnAudioFocusChangeListener` instance → same client id → `AudioManager` replaces the stack entry. (Hardened anyway under MIC-016.)
- **`VoiceRecorder` abort/cleanup generation discipline** (`StartAttempt`, `abortStart`, `failCollect`) is correct, including cancellation-at-the-`withContext`-boundary; pinned by tests.
- **`RecordingStateManager.onCaptureStopHandoff` null/stale-id defenses** (`:373-418`) correctly refuse inconsistent handoffs.
- **Keyboard `awaitInFlightTeardown` runBlocking-on-main**: safe per the documented dispatcher confinement of the joined jobs; verified the teardown bodies never resume on Main.
- **`VoiceRecognitionCaptureGate`** non-reentrancy + release idempotence are `@Synchronized` and correct.
- **Single capture at a time** holds globally: every surface funnels through `RecordingStateManager.tryStartRecording` CAS *before* touching the mic, and focus is requested only after the lock.

---

## 4. Best-Practices Gap Analysis

Even where no concrete bug exists, the design deviates from a textbook-resilient Android input switcher in these ways:

1. **No live routing observation.** Best practice: treat `AudioRecord.getRoutedDevice()` + `addOnRoutingChangedListener` as the *only* truth about what is being captured, and derive all "active device" UI from it. Today truth is sampled once per engine start (MIC-013); everything else is optimistic publication from the selection algorithm.
2. **No communication-device management for SCO** (MIC-006). A resilient switcher owns the SCO/communication-device lifecycle explicitly on API 31+ (`setCommunicationDevice`/`clearCommunicationDevice` + `OnCommunicationDeviceChangedListener`) whenever a classic-BT input is selected, with timeout fallback.
3. **Selector mixes three responsibilities** — enumeration/dedup (pure, well-tested), per-session active-device publication (mutable, racy, ownerless: MIC-002/003), and AudioRecord construction. Target architecture: a pure `DeviceCatalog` (StateFlow of summaries), a per-session `CaptureRouteSession` object handed to the engine (owns preferred-device, routing listener, SCO session, active-device publication, and its own teardown — making every surface's clear/loss behavior identical by construction), and a thin factory.
4. **Single-listener device-lost slot** instead of a flow (MIC-014) makes the loss event un-shareable across surfaces and couples its lifetime to `RecordingService`.
5. **Three `AudioFocusManager` instances with three different transient-loss policies** (service: pause+auto-resume; keyboard: ignore; recognition: ignore). Defensible per-surface UX, but the policy lives in callback wiring scattered across `onCreate`s; a `FocusPolicy` enum on a shared manager would make the divergence explicit and testable.
6. **Event-driven-only endpointing** with no wall-clock watchdog (MIC-018) and a gain-blind threshold.
7. **Per-session settings reads are point-in-time** (`currentSettings()` once at start). Fine for the no-mid-session-swap contract, but the contract itself is violated by resume re-resolution (MIC-010) — the contract should be stated as "selection changes apply at the next *engine* start (including resume)" and surfaced in UI.
8. **Duplicated device-list plumbing** in `AudioSettingsViewModel` (tick + manual snapshot) vs. the shared `availableDevices` StateFlow used everywhere else — consolidate onto the flow.
9. **`Automatic` policy never re-evaluates on hot-plug**, even between segments. Acceptable, but plugging in a USB mic mid-recording with Automatic gives zero feedback; an advisory ("USB Mic connected — it will be used next recording / on resume") would close the loop cheaply via the existing `RecordingServiceEvents`.

---

## 5. Prioritized Fix Roadmap / Dispatch Plan

Ordering balances severity against dependency. "Files" lists *production* files each fix edits (tests omitted). Findings sharing a file must serialize; batches are mutually parallel-safe.

### Parallel-safe batches

| Batch | Findings (in order) | Files (production) | Notes |
|---|---|---|---|
| **A — RecordingService chain** | MIC-001 → MIC-009 → MIC-019 → MIC-010 → MIC-003(service half: token at 5 clear sites) | `RecordingService.kt`, `RecordingSessionAdvisory.kt`, `RecordingServiceEvents.kt` | One subagent; MIC-001 first (highest value). MIC-003 service half lands last, after Batch B defines the token API. |
| **B — Selector chain** | MIC-002 → MIC-011 → MIC-012 → MIC-003(selector half: token API + flow) → MIC-013(selector half) → MIC-014(selector half: loss flow) | `AudioInputDeviceSelector.kt` (+ `AudioInputDevicePolicy.kt` if new types) | One subagent, strictly serial within the file. Exposes the token + loss-flow APIs Batches A/C/D consume. |
| **C — VoiceRecorder** | MIC-007 → MIC-003(recorder half: clear-on-stop) → MIC-013(recorder half: routing listener) | `VoiceRecorder.kt` | Starts after Batch B publishes the token/listener API (MIC-007 alone can start immediately). |
| **D — Keyboard coordinator** | MIC-017 → MIC-008 → MIC-014(keyboard half) → MIC-004(keyboard half) | `KeyboardSessionCoordinator.kt`, `ChirpKeyboardService.kt`, `QuickCaptureSessionImpl.kt` | MIC-017/008 immediately; the -014/-004 halves wait on Batch B's flow API. |
| **E — Recognition surfaces** | MIC-015 → MIC-018 → MIC-004(recognition half) | `VoiceRecognitionActivity.kt`, `ChirpRecognitionService.kt`, `SpeechEndpointer.kt`, `VoiceRecognitionSessionCoordinator.kt` | Independent of everything else; can start immediately. |
| **F — Focus manager** | MIC-016 | `AudioFocusManager.kt` | Fully independent; start immediately. |

### Follow-on / gated work

| Item | Depends on | Files | Notes |
|---|---|---|---|
| MIC-006 (SCO) | On-device verification FIRST; then Batch B (token teardown) | new `CommunicationDeviceSession.kt`, `AudioInputDeviceSelector.kt` | Highest-risk change; gate behind the device matrix in `ONDEVICE.md`. |
| MIC-005 (atomic selection write) | none (but touches 4 picker files also edited by A/D/E) | `AudioSettingsStore.kt` + 4 call sites | Do the store API in parallel; apply call-site one-liners after batches A/D/E merge to avoid conflicts. |
| MIC-021 (engine defensive) | none | `GaplessWavSegmentCapture.kt` | Parallel-safe except MIC-013's engine half — fold into the same change. |
| MIC-020 items 18–19 (standalone test pins) | none | tests only | Parallel-safe, immediate. |
| MIC-003 step 3 (notice gating) | none | `RecordInputDevicePicker.kt` | One-liner; parallel-safe, immediate. |

**Same-file collision map (for the dispatcher):**
- `AudioInputDeviceSelector.kt`: MIC-002, 003, 006, 011, 012, 013, 014 — *all serial (Batch B + gated MIC-006)*.
- `RecordingService.kt`: MIC-001, 003, 009, 010, 019 — *Batch A serial*.
- `VoiceRecorder.kt`: MIC-003, 007, 013 — *Batch C serial*.
- `KeyboardSessionCoordinator.kt`: MIC-008, 014, 017 — *Batch D serial*.
- `ChirpKeyboardService.kt`: MIC-004, 005, 014 — *Batch D + MIC-005 follow-on*.
- `VoiceRecognitionActivity.kt`: MIC-004, 005, 015, 018 — *Batch E + MIC-005 follow-on*.
- `GaplessWavSegmentCapture.kt`: MIC-013, 021 — fold together.
- Singletons (`AudioFocusManager.kt`, `AudioSettingsStore.kt`, `RecordInputDevicePicker.kt`): one finding each — parallel-safe.

**Recommended execution order:** Launch A, B, C(MIC-007 part), D(MIC-017/008 part), E, F and the standalone test pins in parallel (6 subagents). When B merges, run the cross-file halves (A's token sites, C's clear/listener, D's flow consumers). MIC-006 only after the on-device matrix; MIC-005 call sites last.

---

## 6. Appendix

### 6.1 Platform API contracts relied upon

- **`AudioRecord.setPreferredDevice(AudioDeviceInfo)`** — best-effort; returns `false` on invalid state; routing falls back to default when the preferred device disappears; may be called before or during capture; does not manage BT links. Truth: `getRoutedDevice()` (populated only while active) + `addOnRoutingChangedListener`.
- **`AudioManager.registerAudioDeviceCallback(cb, handler=null)`** — null handler ⇒ callbacks on the main looper. Add/remove arrays may contain non-source devices; the code's `isSource` filter is correct.
- **`AudioManager.setCommunicationDevice` / `clearCommunicationDevice` (API 31+)** — the supported replacement for `startBluetoothSco()`; required (on at least some devices/versions) to capture from a classic BT (SCO) headset mic; asynchronous activation observed via `OnCommunicationDeviceChangedListener`. `startBluetoothSco`/`stopBluetoothSco` deprecated in 31.
- **Audio focus** — `requestAudioFocus(AudioFocusRequest)` entries are keyed by the listener-derived client id; re-requesting with the same listener replaces the stack entry (relied on for the restart path, hardened in MIC-016). `AUDIOFOCUS_GAIN` after a transient loss is the only regain signal; none arrives if the app never observed the corresponding loss resolution.
- **Concurrent-capture policy (Android 10+)** — a lower-priority capture client receives *silence* (zeros) rather than errors when another app holds the mic; this is the documented basis for the digital-silence advisories.
- **`RecognitionService` contract** — service must self-detect end-of-speech / `ERROR_SPEECH_TIMEOUT`; `stopListening` optional (basis for `SpeechEndpointer`).
- **IME constraints** — IMEs cannot show runtime-permission prompts (basis for the open-app affordances).

### 6.2 Files reviewed (one-line note each)

**core-audio (main):**
- `core/audio/AudioInputDeviceSelector.kt` — enumeration, dedup, selection matrix, AudioRecord build, active-device publication; subject of MIC-002/003/006/011/012/013/014.
- `core/audio/AudioInputDevicePolicy.kt` — policy enum + summary/active-device data classes; clean.
- `core/audio/AudioSettingsStore.kt` — DataStore persistence incl. manual key/name; MIC-005.
- `core/audio/AudioFocusManager.kt` — focus request/abandon + loss/regain callbacks; MIC-016.
- `core/audio/AudioGain.kt` — soft-knee gain (not re-read in depth; referenced for gain semantics).
- `core/audio/RecordingOutputFormat.kt`, `core/di/AudioSettingsModule.kt` — format enum / DI wiring; clean.
- `core/audio/recorder/VoiceRecorder.kt` — IME/recognition capture engine; generation-hardened; MIC-003/007/013.
- `core/audio/recorder/AudioEncoder.kt` — PCM→M4A/WAV/MP3 encoding; no switcher relevance; resource handling sound.
- `core/preferences/KeyboardPreferences.kt` — delegates gain/quality/format to AudioSettingsStore; consistent.

**feature-recording (service/engine):**
- `service/RecordingService.kt` — app-surface orchestration; MIC-001/003/009/010/019.
- `service/GaplessSegmentCaptureEngine.kt` — engine interface + threading contracts; well-documented.
- `service/GaplessWavSegmentCapture.kt` — WAV engine, rotation, silence; MIC-013/021.
- `service/GaplessSegmentCaptureFactory.kt` — trivial factory.
- `service/RecordingCaptureStopper.kt`, `BoundedCaptureStop.kt`, `StopRequestGate.kt`, `RecordingServiceEvents.kt` — stop handoff machinery; sound.
- `session/RecordingSegmentRotator.kt` — rotation under mutex; the mutex-hold window matters for MIC-001.
- `ui/RecordInputDevicePicker.kt` — picker VM + composable; MIC-003(step 3)/005.
- `ui/RecordingSessionAdvisory.kt` — advisory resolution; extended by MIC-010.

**core-recording-runtime / core-contracts:**
- `RecordingStateManager.kt` — global lock + state machine; handoff defenses verified OK.
- `RecordingState.kt` — `isActive` includes Paused (root of MIC-009).
- `RecordingActiveStopCommands.kt`, `KeyboardRecordingStopBridge.kt`, `RecordingPermissionGuard.kt` — stop routing/permission; clean.

**feature-keyboard:**
- `service/ChirpKeyboardService.kt` — IME lifecycle, picker wiring (no `sessionLive` — MIC-004), focus/call handlers.
- `quickcapture/QuickCaptureSessionImpl.kt` — recorder+gate+focus wrapper; MIC-008 toast origin.
- `session/KeyboardSessionCoordinator.kt` — dictation state machine; MIC-008/017.
- `service/PhoneCallHandler.kt` — telephony stop trigger; registration balanced; permission-degraded gracefully.
- `session/KeyboardUiState.kt` — silence-hint gating verified correct (`:173-175`).

**app (recognition + settings):**
- `VoiceRecognitionActivity.kt` — dialog surface; MIC-004/015/018; destroy-rescue logic verified.
- `VoiceRecognitionSessionCoordinator.kt` — generation+mutex lifecycle; verified sound; MIC-015 (shutdown on main).
- `ChirpRecognitionService.kt` — system recognition service; MIC-015/016/018.
- `VoiceRecognitionCaptureGate.kt` — synchronized gate; verified sound.
- `SpeechEndpointer.kt` — endpointing; MIC-018.
- `ui/settings/AudioSettingsViewModel.kt` / `AudioSettingsScreen.kt` — settings picker; duplicated list plumbing (§4.8); BT grant flow correct.
- `navigation/SharedAudioHandoffViewModel.kt` — shared-audio *import* (not mic capture); reviewed, out of switcher scope, no findings.

**Tests read:** `AudioInputDeviceSelectorTest`, `AudioInputDeviceLossTest`, `AudioSettingsStoreTest` (names), `AudioFocusManagerTest` (names), `VoiceRecorderTest` (names), `KeyboardSessionCoordinatorTest` (referenced), `RecordingServiceStopRaceTest` (referenced), `SpeechEndpointerTest` (referenced) — coverage assessment in MIC-020.

**Recent intent:** f63c626 (picker dedup + USB_ACCESSORY mapping — directly audited, MIC-011/012 are its edges), 9d00cb6 / 2052f58 (input-device stack + verification fixes — the `sessionLive`/notice plumbing they added is complete only on the Record screen), 7a4b3d3 (no-speech ambient-noise fix — MIC-018 is its surviving corner).
