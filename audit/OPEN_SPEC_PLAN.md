# OpenSpec Plan

Date: 2026-05-31

## Proposed Changes

- `recording-finalize-handoff-integrity`: stop handoff ordering, stale handoff guard, terminal finalize results, existing export idempotency, Starting stop cancellation, capture handoff timeout, startup finalize dedupe.
- `widget-keyboard-stop-durable-command`: durable pending keyboard stop before widget acknowledgement.
- `keyboard-inline-delivery-safety`: generation-scoped input commits and sensitive-input refusal.
- `external-voice-recognition-capture-lock`: shared capture lock for Android speech-recognition entry points.
- `model-readiness-authoritative-state`: readiness invalidation and complete-directory model validation.
- `transcription-storage-and-text-persistence`: storage constraint and processed text persistence.
- `share-audio-mime-correctness`: canonical audio share MIME types.
- `recording-recovery-orphan-cleanup-integrity`: stale nested capture artifact cleanup.
- `android-16-sdk-alignment`: API 36 baseline, AGP/Gradle alignment, and dead legacy SDK branch cleanup.

## Validation Targets

- `openspec validate <change> --strict` for each proposed change.
- Final Gradle gate: unit tests for touched modules plus `:app:assembleDebug`.
- Audit scans for old SDK values, destructive migrations, stale MIME strings, ignored tests, and stale WorkManager policies.

## Second Pass Notes

- No new behavior proposal was added in the repeat pass.
- `audit/` is no longer ignored, so audit deliverables are visible in normal worktree status.
- Existing OpenSpec changes still validate structurally. The service stop ordering and widget receiver dispatch test tasks are now complete through narrow JVM seams.

## Third Pass Notes

- Added `external-voice-recognition-capture-lock` after deeper scanning found two remaining raw external-recognition microphone paths.
- Expanded `model-readiness-authoritative-state` for invalidation racing in-flight readiness verification.
- New strict validations pass for both touched changes.
