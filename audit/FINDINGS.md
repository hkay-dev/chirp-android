# Audit Findings

Date: 2026-05-31

## Fixed In This Pass

- Critical: service stop could mark `STOPPING` before capture stop completed. Fixed by moving journal `STOPPING` after capture stop and before finalize enqueue.
- Critical: error-triggered app/widget stops could pre-release recording state before capture handoff. Fixed by routing device-loss and storage-critical stops through normal stop handoff.
- Critical: stale capture handoff could clear a newer active recording. Fixed by recording-id scoped handoff in `RecordingStateManager`.
- Critical: stop during pre-id `Starting` could release the lock while the start job kept running. Fixed by cancelling and joining the start job before stop handoff.
- High: terminal finalize outcomes could poison the shared finalize chain. Fixed by mapping handled terminal outcomes to successful worker results.
- High: startup finalize recovery could duplicate unfinished finalize work for one recording. Fixed by checking unfinished recording-tag work before enqueue.
- High: widget stop for an unbound keyboard recording could report queued before pending stop persistence completed. Fixed with an awaitable stop command and `goAsync()` receiver handling.
- High: keyboard dictation could commit stale text into a new or sensitive input field. Fixed with an input-session guard and sensitive-field refusal.
- High: model readiness could remain cached after deletion or accept files split across two model directories. Fixed with explicit invalidation and complete-directory validation.
- Medium: transcription work lacked a storage-not-low constraint. Fixed in `TranscriptionWorkRequest`.
- Medium: word-replacement processed text was not persisted at first transcript commit. Fixed in `TranscriptionWorker`.
- Medium: audio share MIME used `audio/m4a`. Fixed by deriving MIME from `RecordingOutputFormat`.
- Medium: stale nested capture artifacts under `.capture` were not cleaned. Fixed with recursive orphan capture cleanup.
- Medium: Android module SDK settings and toolchain drifted from the Android 16-only baseline. Fixed with API 36 module settings, AGP 8.10.0, Gradle 8.11.1, and low-risk legacy branch removal.

## Remaining Risks

- Full end-to-end process-death behavior still needs manual or instrumented validation on a device or emulator.
- Transcription queue ordering is still per-recording unique work, not a proven global FIFO worker. The docs now avoid claiming stronger behavior than the implementation proves.
- `RecordingService` still owns several intertwined start, stop, pause, and recovery concerns. Follow-up refactoring should preserve the new tests before splitting it.
- The two remaining OpenSpec test tasks are now implemented with narrow JVM seams: `RecordingStopHandoffTest` covers capture stop before `STOPPING`, and `WidgetReceiverDispatchTest` covers durable toggle completion before broadcast finish.

## Second Pass Validation

- Fixed: first-pass cleanup added `audit/` to `.gitignore`, hiding required audit deliverables from ordinary `rtk git status`. Removed that ignore entry so `audit/FINDINGS.md`, `audit/OPEN_SPEC_PLAN.md`, and `audit/SUBAGENT_SUMMARIES.md` are visible again.
- Fixed: stale ignored `.desloppify/` review packets from earlier audits were still present. Removed the local generated directory.
- Confirmed: static tripwire scan found no production `fallbackToDestructiveMigration()` calls, no active `audio/m4a` share MIME usage, no `PhoneStateListener`, no direct `currentInputConnection?.commitText`, no old SDK 35 module settings, no `@Ignore`, and no `assertTrue(true)` tests in the audited source set.
- Confirmed: `WidgetReceiver` uses `goAsync()` and calls the suspending durable toggle path before `PendingResult.finish()`.
- Confirmed: `RecordingService` now marks the session `STOPPING` only after capture stop handoff and before enqueueing finalize work.
- Fixed after second-pass follow-up: added explicit tests for service stop handoff ordering and widget receiver durable dispatch ordering, then marked both OpenSpec tasks complete.
- Suspicion: the service-owned capture stop timeout wraps synchronous `stopAndFinalize()` implementations, so it should be treated as a best-effort guard rather than proof that every native encoder/muxer close path is interruptible. The capture engines do bound their capture-thread joins.

## Third Pass Deep Validation

- Fixed: `ModelReadinessGate` invalidation could race an in-flight verification and return a stale Ready result to the caller that started before invalidation. Invalidation and in-flight verification now share one ownership lock, stale work is cancelled, and callers re-run against the post-invalidation generation.
- Fixed: `ChirpRecognitionService` and `VoiceRecognitionActivity` used `VoiceRecorder` directly without acquiring the shared `RecordingStateManager` lock. Added `VoiceRecognitionCaptureGate`, wired both external Android speech-recognition entry points through it, and added unit coverage for acquire, busy rejection, and error release.
- Confirmed: all active `VoiceRecorder` construction sites now route through either `QuickCaptureSessionImpl` or `VoiceRecognitionCaptureGate`, with the recorder implementation itself as the only raw constructor definition.
- Confirmed: the apparent missing `StopSnapshotWorkDataTest` is a class-name/file-name mismatch inside `RecordingFinalizeStopOutcomeApplierTest.kt`, not absent coverage.
- Confirmed: deeper static tripwire scan found no production destructive migrations, active stale `audio/m4a`, old SDK 35 settings, direct IME commit calls, `PhoneStateListener`, ignored tests, placeholder assertions, or module `isReturnDefaultValues = true`.
- Agent used: none. Current tool policy allows spawning sub-agents only when explicitly requested, so the deeper repeat pass ran serially in the main checkout.

## Verification Run

- `openspec validate recording-finalize-handoff-integrity --strict`
- `openspec validate widget-keyboard-stop-durable-command --strict`
- `openspec validate keyboard-inline-delivery-safety --strict`
- `openspec validate model-readiness-authoritative-state --strict`
- `openspec validate transcription-storage-and-text-persistence --strict`
- `openspec validate share-audio-mime-correctness --strict`
- `openspec validate recording-recovery-orphan-cleanup-integrity --strict`
- `openspec validate android-16-sdk-alignment --strict`
- `./gradlew :feature-recording:testDebugUnitTest`
- `./gradlew :feature-studio:testDebugUnitTest`
- `./gradlew :feature-keyboard:testDebugUnitTest`
- `./gradlew :app:testDebugUnitTest`
- Follow-up targeted gate: `./gradlew :feature-recording:testDebugUnitTest :feature-widget:testDebugUnitTest`
- Third-pass targeted gate: `./gradlew :app:testDebugUnitTest`
- Third-pass OpenSpec validation: `openspec validate external-voice-recognition-capture-lock --strict`
- Third-pass OpenSpec validation: `openspec validate model-readiness-authoritative-state --strict`
- Earlier broad gate in this pass: `./gradlew :core-contracts:testDebugUnitTest :feature-recording:testDebugUnitTest :feature-keyboard:testDebugUnitTest :feature-transcription:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
- Final full gate: `./gradlew :core-contracts:testDebugUnitTest :core-audio:testDebugUnitTest :core-ui:testDebugUnitTest :feature-recording:testDebugUnitTest :feature-keyboard:testDebugUnitTest :feature-widget:testDebugUnitTest :feature-studio:testDebugUnitTest :feature-transcription:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug`
