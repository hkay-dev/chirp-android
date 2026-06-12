# Chirpboard — Morning On-Device Test Checklist

Consolidated from the 20 overnight ultra-fix reports (`/tmp/chirpboard-ultra/audit-*.md`, `v5-*.md`, endorsed `impl-*.md` lists) — ~150 raw items deduplicated into 64 distinct tests.

**Device:** Samsung SM-S938U1 (Galaxy S25 Ultra), One UI 8, Android 16. **Package:** `dev.chirpboard.app`.
Work top to bottom: Install → P0 → P1 → P2. P0 gates trust in the build; stop and triage if any P0 item fails.

---

## Step 0 — Install (do this FIRST)

- [ ] **0.1 (Optional, 30s — enables P0-8)** On the *current debug build*, record a short memo and let it sit queued/transcribing, so a WorkManager job is in flight across the update.
- [ ] **0.2 Install the release APK**

  ```
  adb install -r app/build/outputs/apk/release/app-release.apk
  ```

  > **⚠️ THIS REPLACES THE DEBUG INSTALL IN PLACE.** The release APK (27.4 MB, R8-minified, arm64-v8a only) uses the **same applicationId + debug signature**, so it installs as an update and **all data is preserved** (recordings, `chirp.db`, DataStore prefs, API keys). **Do NOT uninstall first** — that wipes everything.

  After install: app opens, existing recordings/transcripts play, API keys still present (REL-08).
- [ ] **0.3 Re-verify Chirpboard is the active IME** (updates can drop IME selection)

  ```
  adb shell settings get secure default_input_method   # expect dev.chirpboard.app/...
  adb shell ime list -s                                 # Chirpboard listed as enabled
  ```

  Also confirm it is still the device **voice input / speech recognizer** (Settings → General management → Keyboard list and default / Voice input) — needed for all RecognitionService tests below.

---

## P0 — Must verify before trusting the build (R8 + native + core path)

R8 minification + Gson keep rules + arm64-only native libs all shipped tonight. These prove the release build is not silently broken.

- [ ] **P0-1 Sherpa model load, arm64-only libs (REL-01/06)** — Open the app, confirm the Parakeet model loads, dictate one sentence via the keyboard. Then: `adb logcat -d | grep -i UnsatisfiedLinkError` → no hits. Expected: recognition works; no native link errors from the jniLibs excludes/abiFilters.
- [ ] **P0-2 Core record → transcribe → view** — Record a short memo in-app, wait for transcription, open it in Studio. Expected: status flows to COMPLETED, transcript readable, playback works.
- [ ] **P0-3 Keyboard dictation end-to-end** — (a) LLM off: raw text committed at cursor, no stray characters, keyboard returns to idle. (b) LLM on + valid key: polished text. (c) LLM on + airplane mode: **raw text still committed (never dropped)**, LlmError banner shown, banner clears on next dictation.
- [ ] **P0-4 Cloud LLM providers (Gson smoke)** — Run a Gemini post-process AND one OpenAI and one Anthropic chat turn in Studio. Expected: real parsed content from each — not empty/null (the classic R8-eats-Gson failure). Also confirm no API key (or `?key=`) appears in `adb logcat -d` under success or forced failure (SEC-3).
- [ ] **P0-5 Presets persistence (Gson smoke)** — Create/edit a custom processing preset, force-stop the app, relaunch. Expected: preset survives with all fields intact.
- [ ] **P0-6 API key backup/restore (Gson smoke)** — Export an API-key backup file, then restore it. Expected: round-trips cleanly; key works after restore.
- [ ] **P0-7 Structured outcome extraction (Gson smoke)** — On a meeting-style transcript, extract tasks/decisions/follow-ups; repeat 3–5×. Expected: parses every time; never "Couldn't parse structured outcome response".
- [ ] **P0-8 Cross-update WorkManager job** — If 0.1 was done: the transcription enqueued on the debug build completes on the release build (worker className resolves across the R8 update).
- [ ] **P0-9 External surfaces smoke** — (a) `adb shell am start -a android.speech.action.RECOGNIZE_SPEECH` (or a third-party caller): dialog renders as bottom sheet over the host, speech returns via EXTRA_RESULTS, mic green-dot only while the sheet is visible, tap-outside cancels with RESULT_CANCELED (SEC-1). (b) Obsidian: export one note from Studio → `.md` with frontmatter/tags appears in the vault.

---

## P1 — Feature verification (overnight behavior changes)

### Keyboard / IME

- [ ] **KB-1 Action key matrix + inset re-check (IME-1)** — Focus: chat field (Send), browser URL bar (Go), search box (Search), form field (Next/Done), multiline notes. Expected: new key right of Space shows the matching glyph; tap submits/advances; multiline inserts a newline (no double newline, no double-send in chat); long dictation + Send works. **Inset re-check:** bottom row not clipped by gesture-nav inset, keyboard doesn't overlap the host field — gesture and 3-button nav, portrait and landscape.
- [ ] **KB-2 Password / secure fields (IME-4)** — Focus a login password field. Expected: "Dictation is off for secure fields" lock notice (no red error, no Retry); backspace/space/cursor-drag/action key all work; Done/Next submits; nothing persisted. **Stale-error check:** first make dictation fail in a normal field (mic held by another app), then focus the password field — the lock notice shows, NOT the stale error/Retry.
- [ ] **KB-3 Incognito dictation (IME-3)** — Chrome incognito URL bar: dictate + commit works; NO history/recording row appears even with "Save keyboard recordings" ON; but kill the IME mid-transcription → the rescue row DOES still appear (never drop captured speech).
- [ ] **KB-4 Grapheme backspace + spacing (IME-8/14)** — Paste `🇺🇸 👍🏽 👨‍👩‍👧‍👦 ❤️ 1️⃣` + NFD "café": one backspace press removes exactly one glyph, no broken intermediates; space-bar cursor drag never parks inside a cluster. Spacing: cursor directly after "Hello", dictate "world" → "Hello world"; dictate before an existing period → no double space.
- [ ] **KB-5 Config-change / IME-switch matrix (LIF-07/08, IME-5/11/13)** — Mid-dictation, one at a time: rotate (keep speaking, stop → commits to same field), dark-theme flip, font-scale change, split-screen resize — all survive and commit, no "keyboard closed" rescue entry. Theme staleness: keyboard open in light → close → flip dark → reopen: palette correct (also when flipped while open). Landscape: compact keyboard (~200dp) leaves field visible. restartInput: host app rewrites the field via setText during "Transcribing…" → transcript still commits, no "input field changed". Stray-z: type "gen z", switch apps away/back → z survives; switch to Chirpboard via IME picker in a field ending "plan Z" >3s after first bind → Z survives; SwiftKey-mic stray letter still cleaned right after a real IME switch (<3s window).
- [ ] **KB-6 Mode & replacement scoping (PLH-1/8/10)** — (a) Settings → Keyboard → Default Mode = Email, global LLM mode = Proofread; dictate: output email-formatted, AI pill says "Email"; keyboard menu pick changes only keyboard scope; "Use Global Setting" restores. (b) Toggle AI off in the *recognition dialog*: master "Enable Processing" stays ON; keyboard AI pill reflects the shared dictation scope; recordings still get auto-title/summary. (c) Add replacement "chirpboard"→"Chirpboard"; dictate with AI OFF via keyboard AND via the system dialog: replacement applied.

### Recognition service & dialog

- [ ] **RC-1 Auto-endpointing, silence, threshold (IME-2/7/19/20)** — Via SwiftKey mic (Chirp as device recognizer): (a) speak a sentence, stay silent ~2s WITHOUT tapping stop → results delivered; text inserted once, no glue artifacts; beginningOfSpeech fires only on actual speech; mic-level animation shows dynamics, not pegged at max; rapid start/cancel/start doesn't hang. (b) Say nothing ~5s → ERROR_SPEECH_TIMEOUT ("didn't catch that" UX), mic released, no stuck state; with a test client sending EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS=60000, note how long the mic stays hot (currently full 60s — flag if so). (c) Threshold (DEFAULT_SPEECH_AMPLITUDE_THRESHOLD=0.01, scales with mic gain): quiet room doesn't trip speech-start; normal speech trips reliably; at 5× gain room noise doesn't defeat the no-speech timeout. Tune if needed.
- [ ] **RC-2 10-minute cap (AUD-17, PIPE-06)** — Dialog: dictate to the cap → auto-commit exactly like a stop tap. Service client: results delivered at cap (previously: guaranteed hang). Keyboard: session auto-stops, transcribes, commits — no frozen "recording" pill. During the long decode watch `adb shell dumpsys meminfo dev.chirpboard.app`: no native OOM; decode runs in ~30s chunks.
- [ ] **RC-3 Service contract matrix (IME-6/10/15, LIF-09)** — (a) EXTRA_SECURE=true (dialog and service): result returned, NO history row, dialog AI control disabled, no rescue rows even after forced process death mid-session. (b) EXTRA_LANGUAGE=de-DE: service → ERROR_LANGUAGE_NOT_SUPPORTED (no English garbage); dialog shows "English-only" hint and proceeds; checkRecognitionSupport reports [en-US]. (c) EXTRA_PROMPT="Say your search" shows in the pre-roll status instead of "Ready to listen". (d) Result codes: silence → RESULT_NO_MATCH; mic busy → RESULT_CLIENT_ERROR; model missing + dismissed → RESULT_CANCELED; swipe-away mid-recording → RESULT_CANCELED. (e) EXTRA_RESULTS has no trailing space.
- [ ] **RC-4 Audio focus on recognition (AUD-14)** — Play music; open the dialog / start a service session: music pauses for capture, resumes after stop/cancel. Phone call mid-dictation (permanent loss): session stops and still delivers the captured audio — no hang, no silent close.
- [ ] **RC-5 In-dialog errors + confirm-cancel (ERR-9/10/23/27)** — (a) Mic permission revoked → persistent "Microphone access needed" + working "Open Chirp" button; dismiss → RESULT_CLIENT_ERROR. (b) App recording active, launch dialog → "Microphone in use by app" shows ~2.4s then RESULT_CLIENT_ERROR (no flash-open-instantly-close). (c) Forced transcription failure → "Couldn't transcribe. Your audio was saved in Chirp." + rescue row exists. (d) Confirm-cancel: scrim/X during "Working on it…" → "Discard dictation?" (Keep resumes, Discard cancels); one-tap cancel while idle/recording.

### Recording service & audio

- [ ] **AU-1 Focus auto-resume matrix (AUD-05)** — (a) App recording + 10s alarm (or 30s call): auto-pauses with "Paused — another app interrupted audio. Recording will resume automatically."; auto-resumes after. (b) MANUAL pause before the alarm: must NOT auto-resume. (c) Race: stop the recording DURING the focus pause, let the interruption end mid-stop → no new capture engine starts, saved file intact. (d) Call during *keyboard* dictation: capture stops/rescues, no mic conflict, keyboard usable after the call.
- [ ] **AU-2 Silence-detection warning (AUD-02)** — Hold the mic elsewhere (WhatsApp/Telegram call), start an app recording AND separately a keyboard dictation. Expected: within ~5s, notification/screen/keyboard show "No audio detected — the microphone may be in use by another app"; clears ~1s after the mic frees; session is NEVER auto-stopped by silence. TalkBack: home live row and Record-screen banner announce the advisory.
- [ ] **AU-3 Auto-stop reason matrix (ERR-12/13, AUD-06)** — (a) Storage: fill toward the floor mid-recording → LOW warning line appears; CRITICAL auto-stops with "Recording stopped and saved — storage was full" notification + in-app snackbar (fresh <5 min only). (b) Mic device lost: unplug a USB/wired mic mid-recording → auto-stop with device-lost reason, file saved; then unplug during a *keyboard* dictation → service listener must NOT interfere (ownership-gated). (c) Clock jump: trigger an auto-stop, move clock +10 min, open app → snackbar still appears.
- [ ] **AU-4 Playback refusal + speed (AUD-10/11)** — While recording, tap play on an old recording: recording keeps running; mini bar shows "Can't play audio while a recording is in progress" until dismissed; play works after stop. Speed: full-player chip cycles 1 → 1.25 → 1.5 → 2 → 0.75, pitch preserved; kill + relaunch → last speed persists; refusal preserves the speed setting.
- [ ] **AU-5 Notification chronometer (PRF-5)** — Timer ticks every second via the system chronometer; pause freezes the duration; resume re-bases correctly; `adb shell dumpsys notification | grep -i posts` → no per-second re-posts.
- [ ] **AU-6 Lock-screen stop finalize (PRF-6)** — Record ~30 min, lock screen, tap "Done" from the lock-screen notification, leave untouched 2 min, unlock. Expected: finalized and queued for transcription without waiting for screen-on.
- [ ] **AU-7 Capture quality: gain + pause tail (AUD-01/03, pause-drain)** — (a) Record the same phrase at 1.0× and 2.0× input gain: 2.0× audibly louder, no harsh clipping (soft limiter); dictation accuracy fine at 2.0×. (b) Count "one two three", pause right after "three", resume, stop; repeat 5–10×: "three" never clipped.

### Pipeline, transcription & model download

- [ ] **PL-1 Long-recording foreground transcription (PIPE-01)** — 60+ min recording, transcribe with the app backgrounded; watch `adb logcat -s WM-WorkerWrapper TranscriptionWorker`. Expected: "Transcribing recording" dataSync notification visible, runs to completion, no ~10-min stop/restart loop.
- [ ] **PL-2 Background enhancement (PIPE-02)** — Auto-title/summary on; record ~1 min; background the app before transcription finishes. Expected: enhancement completes in background; no ForegroundServiceStartNotAllowedException, no FAILED row.
- [ ] **PL-3 Chunk-boundary duplication (PIPE-04)** — Record 2–3 min of continuous fluent reading; compare around each ~28s boundary. Expected: no duplicated/stuttered words, including after sentence-ending punctuation.
- [ ] **PL-4 Auto-transcribe OFF, park → manual (PLH-4)** — Profile with Auto Transcribe off; record. Expected: saved with a deliberate AWAITING state + visible "Transcribe" affordance; force-stop + relaunch → reconciler/"Recover stuck" does NOT re-enqueue it; tapping Transcribe completes (+ exactly one vault note if auto-export is on).
- [ ] **PL-5 Mid-transcription cancel (PIPE-07)** — Cancel a RUNNING long transcription from the Studio header and from the Home sheet. Expected: status becomes AWAITING (not FAILED), the stale transcript never appears later, retranscribe works, no error chrome.
- [ ] **PL-6 Never-lose-speech recovery** — (a) Force-stop Chirpboard mid-app-recording; relaunch: recovery prompt; Keep → playable audio of captured-so-far; Discard → file removed; journal cleaned either way. (b) Swipe-kill the host app + toggle IME away mid-transcription: rescue entry appears in-app with error note, audio playable.
- [ ] **PL-7 Model download resume matrix (ERR-1/2/3)** — Start the 652 MB download, then in turn: (a) back out of settings + home the app → "Downloading speech model" notification keeps progressing; re-entering shows live progress. (b) Force-stop at ~40%, relaunch → WorkManager resumes; `Documents/.chirpboard/models/*.download` partial grows monotonically (logcat `resumeFrom>0`), never resets to 0. (c) Reboot mid-download → resumes after unlock + network. (d) Wi-Fi off ~30s mid-transfer → bounded backoff resumes from the partial; after 5 failures, error card with Retry that resumes from the partial. (e) Cancel mid-download → status Not Downloaded, partials kept, later Download resumes; no stale progress notification if cancelled while backgrounded. End-to-end proof: after any resumed completion, SHA-256 passes and the model loads + transcribes.
- [ ] **PL-8 Download UX: airplane no-loop + storage access (ERR-3, LIF-06, PLT-07)** — (a) Airplane mode ON, Record → "Download model": single "Waiting for connection…" indeterminate state, NO error-flicker retry loop; airplane OFF → transfer starts untouched; rotate + background/restore on this screen → download does not re-trigger (nav-arg consumed once). (b) Revoke All-files access, tap Download: storage-choice dialog (not a silent bounce); "Allow access" → system toggle → back: starts without another tap; "Use app storage": lands in app-private storage and transcription works. (c) Notifications: progress percent updates; terminal failure posts "Model download failed" with an actionable reason; tapping either opens the app.

### Obsidian export

- [ ] **OB-1 Auto-export end-to-end + per-profile gates (PLH-3/5)** — Connect a vault, enable auto-export, record a short memo, wait for COMPLETED (+ enhancement). Expected: exactly one `.md` with title/transcript (+ summary when generated), no duplicate after enhancement finishes; recording row gains export bookkeeping. Gates: profile with autoExportToObsidian ON + global OFF → still exports; profile OFF → no export. Keyboard dictation with auto-export on → note appears too.
- [ ] **OB-2 Naming & dates** — (a) Collision: two recordings titled "Test" (or two dictations with identical first 50 chars) → two distinct `.md` files, no overwrite. (b) Frontmatter date: export at ~20:00 in a non-UTC TZ → `date:` shows the local date, not next-day UTC. (c) Overwrite-on-retranscribe: export, edit transcript, retranscribe → exactly one note (same filename) with updated content; rename the recording + retranscribe → note the documented duplicate-note behavior.
- [ ] **OB-3 Vault revoked (ERR)** — Delete/rename the vault folder (or revoke the SAF grant), then trigger an export and an auto-export. Expected: visible in-app error/notification (no silent logcat-only failure), no crash.

### Screens & UX

- [ ] **UX-1 Privacy Notice (PLH-9)** — Settings → About → Privacy Notice. Expected: sheet opens, survives rotation; plain-language copy covers on-device transcription, the cloud LLM path (all providers), and backup behavior matching the actual rules (settings backed up; recordings/transcripts/API keys never).
- [ ] **UX-2 Studio passage tools + promotion snackbar (PLH-6/7)** — (a) Completed recording → kebab → "Select text" → long-press select a passage → Summarize/Explain/Extract: buttons enable on selection, spinner on the running action, result card with copy, Done exits, back-gesture exits selection first; rotate mid-selection → buttons disabled or selection re-highlighted (known minor — note which). (b) Edit the transcript changing exactly one word, save: "correction saved" snackbar, then `Add "x → y" as a word replacement?` with a working Add action (visible in Settings → Word Replacements); a full rewrite shows no offer.
- [ ] **UX-3 Process-death matrix (LIF-02/04/05)** — (a) Record via Home Record button (autoStart), record 10s, home, kill the process, relaunch from recents: NO mic auto-start; recovery prompt shows first; auto-start never fires after dismissing it. (b) Studio: enter edit mode, type, switch apps, kill, return via recents: edit mode + draft + chat draft restored. (c) Share an audio file in, let import finish, kill, relaunch from recents (incl. hours later): no duplicate import.
- [ ] **UX-4 Dialog rotation persistence (LIF-03/10/12, I18N-02/11)** — Rotate with each open: record back/discard/start-over dialogs, home actions sheet, tag editor (typed name + color), word-replacement editor (typed fields), profile delete confirm, studio menus + retranscribe confirm. Expected: all survive rotation with content. Copy checks: back dialog mentions the browse-home option; profile delete shows the name in quotes (…delete "Test"?).
- [ ] **UX-5 Mic permission denied — all surfaces (ERR-7/8)** — Revoke RECORD_AUDIO, then: (a) Home Record FAB → system dialog; deny twice → "Microphone access needed" dialog with a working Open-settings deep link; same from record-screen quick-start. (b) Keyboard mic → "Open Chirp to allow microphone" overlay that launches the app; no dead Retry loop. (c) Recognition dialog already covered in RC-5(a). Also: fresh-permission prompt does not re-appear on every rotation (LIF-12).
- [ ] **UX-6 Library integrity & deletes (DAT-006/009)** — (a) Dev-menu seed >500 recordings: header shows the true total, "Showing the latest 500…" footer, search still finds older entries. (b) Delete from Home AND from Studio: row gone, audio file gone, mini player stops if the deleted item was playing (both surfaces). (c) Tag-FK guard: delete the recording from a second surface, then toggle a tag → snackbar "Recording no longer exists", no crash. (d) Delete a PENDING_TRANSCRIPTION from the Studio overflow → no transcription notification flashes afterwards (known minor — observe).
- [ ] **UX-7 Navigation & back (LIF)** — (a) While recording: home, tap the recording notification → original task foregrounded with the live Record screen (no new task / Home reset). (b) Predictive back (slow back-gesture): from Home (predictive animation), nested settings, during recording (confirmation dialog, no app exit), studio edit mode (confirm prompt, not silent discard).

### Platform

- [ ] **PT-1 Widget live-state matrix (IME-16, PLT)** — (a) Force-stop the app (don't reopen), tap the widget record button: FGS starts, notification appears, captured audio contains real signal, not silence (mic while-in-use exemption on One UI 8). (b) During a widget recording, force a launcher/appwidget refresh (change One UI theme / Good Lock / grid): widget still shows stop + a live chronometer, NOT "Tap to record"; tap stops as labeled. (c) Widget state resyncs after reboot.
- [ ] **PT-2 Adaptive/themed icon + Material You** — Launcher icon renders correctly as default, with One UI themed icons ON, and across icon shapes — no letterboxed/blurry icon in launcher/Settings/recents. "Use system colors" ON: app recolors without restart, follows a wallpaper change; OFF: brand lavender restored; fresh-install default is the brand palette.
- [ ] **PT-3 Notification permission re-prompt (PLT)** — Revoke notifications for Chirpboard, start a recording (incl. from the keyboard mic): app surfaces a re-prompt/affordance rather than silently losing the Done/Pause controls; granting restores notification flows; while denied, verify what signal remains (mic privacy indicator) and that recording still saves.
- [ ] **PT-4 Entry points + direct boot** — (a) System Settings → keyboards → Chirpboard gear → lands on keyboard settings (alias deep link), not Home. (b) Long-press launcher icon → Record shortcut → Record screen auto-starts FGS mic recording. (c) Direct boot: with Chirp as default IME, reboot; before first unlock a lock-screen text field gets Samsung Keyboard as fallback; after unlock Chirp returns automatically. (Pair with the PL-7 reboot step.)
- [ ] **PT-5 Backup/restore via bmgr (SEC-5, PLT-01)** — With >25 MB of recordings present:

  ```
  adb shell bmgr enable true
  adb shell bmgr backupnow dev.chirpboard.app
  adb shell dumpsys backup | grep -i -A3 chirpboard   # token under "Current:"
  adb shell bmgr restore <token> dev.chirpboard.app
  ```

  Expected: backup passes (no quota failure now that recordings/journals/DB are excluded); after restore, only DataStore settings travel (appearance/keyboard/audio/LLM-mode); API key absent → app prompts to re-enter without breaking; Obsidian re-prompts for the vault; NO phantom "recovered recording" from journal resurrection.
- [ ] **PT-6 DataStore corruption recovery (ERR-20, DAT-001)** — App stopped, corrupt one store:

  ```
  adb shell run-as dev.chirpboard.app truncate -s 3 files/datastore/recording_recovery.preferences_pb
  ```

  (If `run-as` is refused on the non-debuggable release build, verify on the debug build/emulator instead.) Relaunch, open the keyboard, start/stop a recording. Expected: no crash loop; the store resets to defaults (corruption logged); keyboard settings load. Repeat once with `keyboard_preferences.preferences_pb`.
- [ ] **PT-7 Media notification resume (media3 bump)** — Play a recording, home, pause from the One UI media control, wait ~1 min, resume from the control. Expected: resumes without crash; logcat shows no ForegroundServiceStartNotAllowedException (regression check for the media3 1.4.x bump).

### Accessibility (TalkBack passes)

- [ ] **AY-1 TalkBack: keyboard + recognition dialog** — Keyboard: every control (AI toggle, mic, cancel/stop/restart, backspace, space, action key) focusable inside the IME window, double-tap activates, mode dropdown gets a11y focus; Space/Backspace expose custom actions ("Move cursor left/right", "Delete previous word"); "Recording"/"Transcribing…"/"Polishing…" spoken; LlmError banner announced and persists per the a11y timeout. Dialog: scrim announces "Cancel" with the double-tap action; "Preparing speech model…"/"Ready to listen" (or EXTRA_PROMPT)/"Listening…"/"Working on it…"/errors announced via polite live regions; sheet body is no longer an unlabeled no-op node.
- [ ] **AY-2 TalkBack: record-memo flow** — Record → pause → resume → Done. Expected: pause/play announce label + state, Recording/Paused live announcements, status-label transitions polite, tag chips announce checked state, recovery dialog readable.
- [ ] **AY-3 Remove animations + font scale 2.0 (A11Y-5/9)** — Accessibility → Remove animations: all infinite animations freeze (keyboard mic glow, stop pulse, FAB breathing, processing pill, shimmer static fallbacks) in both the activity and the IME. Font scale 2.0 + display size Large: keyboard top-bar status, dialog timer + transcript area, record-screen Done button, model banner, pills — no clipping; keyboard grows or scrolls gracefully.

---

## P2 — Nice-to-verify (when P0/P1 are green)

- [ ] **Z-1 Trim-level probe (REL-09, PRF-1)** — With the recognizer warm (`adb shell dumpsys meminfo dev.chirpboard.app` ≈ 700 MB):

  ```
  adb shell am send-trim-memory dev.chirpboard.app RUNNING_CRITICAL
  adb shell am send-trim-memory dev.chirpboard.app COMPLETE
  ```

  Watch logcat for `releaseRecognizerForMemoryPressure`. Also create *real* pressure (open several heavy apps, Chirpboard backgrounded) and note whether the release ever fires naturally on Android 16 or the process is just killed — artificial delivery is NOT proof of the production path; update the residency-policy docs accordingly. Race check: send the trim at the exact moment a keyboard dictation stops and mid-RecognizerIntent transcription → completes or rescues with a surfaced error, never silent loss; the intent caller never gets ERROR_SERVER while model files exist.
- [ ] **Z-2 Idle release after 30 min (PRF-2)** — Dictate once to warm the model, leave the device idle >30 min. Expected: log "Released idle recognizer after 30 min unused"; `dumpsys meminfo` drops ~660 MB; next dictation shows the warming mask then completes. Repeat with an active app recording running: release must defer (IdleReleaseDecision EXTERNALLY_BUSY) and fire afterward; never mid-dictation/recording/transcription.
- [ ] **Z-3 Frame-stats re-profile (PRF-3)** — Open + dismiss the keyboard, leave the screen on 60s on the launcher; check `adb shell dumpsys gfxinfo dev.chirpboard.app` deltas (or perfetto). Expected: zero Choreographer frame callbacks while hidden; idle aura resumes on next show. Recomposer: keyboard hidden 10+ min, reopen → aura animates again immediately.
- [ ] **Z-4 Battery observation** — Over the morning: `adb shell dumpsys batterystats --charged dev.chirpboard.app` — notification post counts collapsed (PRF-5), no wakelock anomalies; note subjective drain.
- [ ] **Z-5 BT mic hot-plug matrix (AUD)** — (a) Classic BT headset: select as input, record with the phone across the room, play back → near/clear headset audio; `adb shell dumpsys audio | grep -A5 records` → routed device TYPE_BLUETOOTH_SCO. (b) Power off the headset mid-recording → auto-stop with device-lost reason, file saved (or fallback to built-in, per final behavior). (c) LE-Audio earbuds enumerate with a sensible label (not "Other"); selection persists across reboot. (d) Wired headset (blank address): Manual selection persists via the type+name key after reboot; checkmark + "Active input" label correct. (e) BT hardware keyboard attached: Chirp input view still appears; dictation commits while BT typing works in parallel.
- [ ] **Z-6 Stress & endurance** — (a) 60+ min recording at High in each output format (WAV/M4A/MP3): finalize completes, duration/seek correct across the whole file, player opens instantly, transcription completes. (b) Import 50 short clips quickly: all complete, sane ordering, no notification spam on induced failures. (c) 200 MB+ import from Drive: progress UI shown; a second Import tapped during the copy is handled sanely.
- [ ] **Z-7 Locale & time extras (I18N-04, DAT)** — (a) 24-hour system time: new default title shows "Jun 12, 14:32", not "2:32 PM". (b) Home open across midnight (or clock jump): "Today" pills become "Yesterday" without restart. (c) RTL smoke (Arabic locale): backspace icon mirrored, space-drag direction matches finger, back arrows/pills/mini-player transport mirror sanely, no clipped overlaps.
- [ ] **Z-8 A11y extras** — Accessibility Scanner over Home (row controls), mini player, keyboard recording state: no touch-target flags (48dp fixes landed). TalkBack sliders: mini-player seek + full-player slider adjust via volume keys in sane increments; announced value matches the time.
- [ ] **Z-9 Instrumented test sweep** — `./gradlew connectedAndroidTest` with the device attached. Expected: all green — migration chain v1→v10 (against schemas/10.json), DAO suites, RecordingRepositoryTransactionTest, RecordingServiceStopRaceTest, Compose tests, ModelDownloaderReadinessCacheTest.

---

## If something fails

- **Rollback point:** git tag `pre-overnight-audit` is the pre-ultra-fix state (`git diff pre-overnight-audit -- <path>` to inspect, or check out the tag to rebuild the old APK).
- **Each wave is a separate commit** — `git log --oneline pre-overnight-audit..HEAD` and revert just the offending wave instead of the whole night.
- Don't hot-fix from the phone: note the failing item + exact repro here, fix in the repo, rebuild, re-run only the affected section.
