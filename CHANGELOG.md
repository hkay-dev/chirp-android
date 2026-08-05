# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [4.0.0] - 2026-08-05

Chirp 4.0 is built around one rule: once the microphone hears something, the app should not lose it.

### Compact Parakeet TDT/CTC by default

- New installs now select the 135 MB Parakeet TDT/CTC 110M Q8 GGUF model instead of the
  659 MB Parakeet 0.6B model. It loads faster, takes much less storage, and was the best
  balance of speed and accuracy in device testing.
- The Q6_K and Q4_K_M 110M models remain available for people who want smaller downloads.
  The larger Sherpa-ONNX 0.6B model remains available for streaming preview.
- The model family and filename include CTC, though the verified Q8 conversion decodes through
  its TDT head. The app calls it “Parakeet 110M Q8” rather than pretending it is pure CTC.
- An existing model choice is preserved. The new default applies when no choice has been saved.

### Recording that protects the audio

- Recording starts immediately. Model loading happens beside capture and can no longer cut off
  the beginning of a dictation.
- Quick input and app recordings write to recoverable files during capture. The first audio block
  is checkpointed, and interrupted work can be recovered after an activity, service, or process
  goes away.
- Transcription, database saving, and the finished notification keep running if you switch apps.
- Continuous transcription stays the normal path. Overlapping chunks are used only as recovery
  when a whole recording cannot be decoded safely.
- Long recordings keep durable audio, recovery journals, storage checks, and guarded stop paths.

### Faster everyday use

- The selected local recognizer stays loaded between dictations. It is released only for real
  memory pressure, severe thermal pressure, an explicit model switch or deletion, or process death.
- Home observes the recordings table once and does less list work on each database update.
- The automatic microphone order is built-in, USB, wired, then Bluetooth.
- Quick input keeps a short-lived notification with the original transcript and AI result so a
  failed app handoff never leaves the text stranded.

### Stable Android build

- Targets Android 16 and the Galaxy S25 Ultra, with an arm64-only APK and 16 KB native-page alignment.
- Uses API 36, Android Gradle Plugin 8.13.2, Gradle 8.14.5, and NDK r29.
- The release APK is R8-minified, resource-shrunk, stripped of native debug symbols, and not debuggable.
- Removed the old GGUF trial build, dead UI helpers, deprecated wrappers, and stale planning material.

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
