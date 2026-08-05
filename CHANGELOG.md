# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [4.0.0] - 2026-08-05

Chirp 4.0 is mostly about one thing: you should never have to wonder whether it caught what you said.

### The smaller Parakeet TDT/CTC model is now the default

- New installs now use the 135 MB Parakeet 110M Q8 model instead of the old 659 MB model.
  It starts faster, takes up a lot less space, and still does a great job with everyday dictation.
- You can still pick the even smaller Q6_K and Q4_K_M versions. The big 0.6B model is still
  there too if you want its live transcription preview.
- A quick naming note: this comes from the Parakeet TDT/CTC family, though this particular GGUF
  conversion uses the TDT part of the model. Chirp just calls it “Parakeet 110M Q8.”
- Already picked a model you like? Chirp leaves it alone. This only changes the starting choice
  for new installs.

### Much harder to lose a recording

- Recording starts the moment you ask for it. Chirp does not wait around for the speech model to load,
  so the beginning of what you say should not get clipped.
- Audio is saved as you speak. If Android closes a screen, service, or even the whole app process,
  Chirp has something real to recover instead of just hoping everything stayed in memory.
- You can switch apps during transcription. Chirp keeps working, saves the result, and lets you know
  when it is done.
- Normal transcription still handles the recording as one continuous piece. Overlapping chunks are
  there as a backup if the full recording cannot be handled safely.
- Long recordings now have more checks around storage, stopping, recovery, and unfinished work.

### Quicker in day-to-day use

- Chirp keeps your speech model warmed up between dictations. It only lets go when Android really
  needs the memory, the phone gets too hot, you change models, or the app process dies.
- The recordings screen does less busywork when something changes.
- The built-in mic comes first, followed by USB, wired, and finally Bluetooth.
- Quick input keeps a short-lived notification with your original words and the AI-cleaned version.
  If another app refuses to accept the text, you can still grab it from there.

### Ready for daily use

- Built around Android 16 and the Galaxy S25 Ultra.
- The release APK is smaller, cleaned up, and has debugging turned off.
- Old experiments, unused helpers, and stale planning files have been cleared out.

## [3.1] - 2026-06-12

Overnight audit release (versionCode 31): finished half-wired features, hardened the
platform surface, and made the release build real.

### Added
- In-app Privacy Notice (About) covering on-device transcription, local storage, the
  optional cloud AI providers, and backup behavior.
- IME action key on the keyboard (Done/Search/Send/Next/Go, falls back to Enter).
- Per-keyboard Default Mode preference, honored by the keyboard session.
- Playback speed control (0.75x–2x) in the full player, persisted.
- Studio passage selection with a select toolbar.
- Snackbar to promote a manual transcript correction into a word replacement.
- "Transcribe" affordance for recordings that skipped auto-transcription.

### Changed
- Profiles now honor "auto transcribe" and "auto export to Obsidian" per profile.
- The dialog AI toggle now controls only the keyboard's AI setting, never the global one.
- English-only resources; consistent terminology ("AI Processing", "recording").
- Recognizer is released after ~30 minutes of disuse (never mid-dictation/-transcription).
- WorkManager 2.9.0 → 2.10.5 and Media3 1.2.1 → 1.4.1 (Android 15/16 service-timeout and
  media-resume fixes).

### Fixed
- Corrupted settings files can no longer crash-loop the app (all 8 preference stores
  recover to defaults).
- Gemini API key moved from the request URL to a header; clipboard copies of transcripts
  are flagged sensitive; import filenames are sanitized.
- Backups exclude recordings, journals, and the database (paths are device-specific);
  encrypted key files stay local.
- Model download: reliable resume, honest manual retry, and a storage-choice dialog when
  "All files access" is missing — including manual instructions if the system settings
  page cannot be opened.

### Release engineering
- R8 minification + resource shrinking enabled with verified keep rules (sherpa-onnx JNI,
  Hilt, Room, WorkManager, and every Gson model).
- APK ships arm64-v8a only (single personal device) and drops two never-loaded sherpa
  native libraries: release APK ~187 MB → ~31 MB.
- Release builds are signed with the local debug keystore on purpose so they update the
  installed build in place (see app/build.gradle.kts for the tradeoff).
- Removed unused Retrofit dependencies.

## [3.0] - 2026-06 (versionCode 30)

Cumulative cut covering the pre-overnight hardening sweeps on the Parakeet-era app
(sherpa-onnx Parakeet TDT 0.6B v2, Android 16, single-activity Compose):

- Reliability: stop gates, generation tokens, journal/crash recovery, and rescue
  persistence — captured speech is never dropped; interrupted recordings recover.
- Quality: performance, startup, recomposition, and data-layer fixes (99 findings).
- Visual: brand palette with optional Material You, spacing/shape tokens, shared voice
  controls and haptics, keyboard bottom-inset fix, recognition dialog parity (86 findings).

Earlier entries (Whisper-era recorder, M4A keyboard encoder, phone-call handling for
API 21+) described an architecture that no longer exists and were removed; see git
history before tag `pre-overnight-audit` if you need them.
