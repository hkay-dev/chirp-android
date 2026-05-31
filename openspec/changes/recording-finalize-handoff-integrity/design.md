## Context

App and widget recordings are service-owned, journaled, and finalized by a serial WorkManager pipeline. The service currently marks the journal `STOPPING` before `segmentCapture.stopAndFinalize()` returns. The worker applies cleanup for terminal failures, then returns `Result.failure()`. Some device-loss and low-storage paths call `onRecordingError()` before calling `stopRecording()`, which releases the global recording lock while capture is still owned by the old service job.

## Decision

Move `STOPPING` journal transition to the point after capture has stopped and before finalize work is enqueued. Keep error-triggered service stops on the same stop path without pre-releasing the lock. Make `onCaptureStopHandoff(recordingId)` a recording-id guarded transition. Return `Result.success()` for terminal per-recording cleanup outcomes after the applier has abandoned the journal or removed the in-progress row. Before concatenating segments, accept a playable existing export file recorded in the journal.

## Alternatives Considered

- Add a new journal state for stop-in-progress: rejected for this change because the simpler invariant is enough: `ACTIVE` remains recoverable until capture is ready for worker finalization.
- Replace the shared finalize chain with separate unique work per recording: rejected because it weakens FIFO ordering and adds queue semantics for a problem fixed by terminal-node success.
- Preserve pre-stop Error state and add another lock: rejected because it keeps two state owners and preserves the race trigger.

## Risks

- Returning success after terminal cleanup could hide worker failure from WorkManager history. Reliability events and journal/row cleanup remain the source of truth, and the user-visible queue is not blocked by a terminal per-recording failure.

## Fallback Policy

- Fallback added: no
- Trigger:
- Owner:
- Exit condition:
- Observability:
- Tests:

## Rollout

1. Update stop handoff and worker result semantics.
2. Add unit coverage for stale handoff, terminal worker continuation, existing export reuse, and journal ordering.
3. Run targeted unit tests and debug APK build.
