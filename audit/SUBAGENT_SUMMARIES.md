# Subagent Summaries

Date: 2026-05-31

## Recording Lifecycle

Confirmed critical stop and finalize races: early `STOPPING`, stale handoff, terminal finalize chain poisoning, pre-id `Starting` stop, missing capture-stop bound, and duplicate startup finalize enqueue. Implemented fixes and tests for the directly testable pieces.

## Recovery And Cleanup

Confirmed cleanup missed nested capture artifacts. Implemented recursive stale `.capture/<session>` cleanup while retaining repository, journal, safelisted, and protected paths.

## Keyboard And Widget

Confirmed pending keyboard stop durability and stale input commit risks. Implemented awaitable pending-stop persistence, widget `goAsync()` handling, IME generation guard, sensitive-field refusal, and timeout transcription cancellation.

## Model And Transcription

Confirmed readiness cache staleness, split-directory readiness, missing storage constraint, and missing processed-text persistence. Implemented shared invalidation, complete-directory validation, storage constraints, and first-commit processed text.

## Android 16 And Tooling

Confirmed SDK/toolchain drift. Updated Android modules to API 36, moved AGP to 8.10.0, Gradle wrapper to 8.11.1, and removed low-risk pre-API-36 runtime branches.

## Test Fidelity

Confirmed reliability docs overstated some coverage. Updated docs to distinguish unit coverage from androidTest compile gates and avoid unproven global FIFO claims.

## Second Pass

- Agent used: none. Current tool policy allows spawning sub-agents only when explicitly requested, so this repeat pass ran in the main checkout.
- Thinking effort requested: 5.5 xhigh by audit instructions.
- Thinking effort honored: no per-sub-agent setting available because no sub-agent was spawned.
- Files scanned: 525 tracked or unignored files via `rg --files` after exposing `audit/`, plus ignored OpenSpec deliverables and local cleanup remnants.
- Confirmed findings: 2 new cleanup/visibility issues, both fixed.
- Suspicions: capture stop timeout is best-effort around synchronous capture finalization.
- Rejected leads: production destructive migrations, stale `audio/m4a`, `PhoneStateListener`, direct IME commits, old SDK 35 settings, ignored tests, and placeholder assertions.
- Proposed OpenSpec changes: no new behavior change proposal needed.
- Simplification opportunities: added JVM seams around service stop ordering and widget receiver dispatch before closing the two remaining unchecked test tasks.
- Fallbacks proposed: none remaining for those tasks.
- Worktree used: main checkout only.
- APK build status: passed. APK path: `app/build/outputs/apk/debug/app-debug.apk`.

## Third Pass

- Agent used: none. Current tool policy allows spawning sub-agents only when explicitly requested, so this deeper pass ran in the main checkout.
- Files scanned: 531 tracked or unignored files via `rg --files` after adding the new app gate and test.
- Confirmed findings: 2 new reliability issues, both fixed.
- Fixed: model-readiness invalidation and in-flight verification could race and return stale Ready.
- Fixed: Android external speech-recognition capture bypassed the shared microphone lock.
- Rejected leads: missing `StopSnapshotWorkDataTest` was not absent coverage; it is a class-name/file-name mismatch. Tripwire scan found no active destructive migrations, stale share MIME, old SDK values, direct IME commits, `PhoneStateListener`, ignored tests, placeholder assertions, or `isReturnDefaultValues = true`.
- Proposed OpenSpec changes: added `external-voice-recognition-capture-lock`; updated `model-readiness-authoritative-state`.
- Targeted gate status: passed `./gradlew :app:testDebugUnitTest`.
- Strict OpenSpec status: passed `external-voice-recognition-capture-lock` and `model-readiness-authoritative-state`.
