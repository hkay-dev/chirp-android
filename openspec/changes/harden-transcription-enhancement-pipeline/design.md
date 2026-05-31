## Context

The pipeline now has separate transcription and enhancement workers, but both still rely on mutable recording status and incomplete intent data. A worker that started earlier can finish later and overwrite newer transcript, title, summary, or status. Enhancement failure also loses phase context because `FAILED` retry enters the transcription path unless another durable enhancement marker is consulted.

## Goals

- Make worker commits conditional on the execution that owns the current phase.
- Make enhancement retry and recovery preserve the original requested work.
- Keep queued enhancement independent from later profile or settings edits.
- Preserve recoverability for legacy pending enhancement rows.
- Remove fragile tests that mock static helper names.

## Non-Goals

- Add a global FIFO queue.
- Persist API keys or other secrets in enhancement snapshots.
- Change inline keyboard transcription behavior.
- Redesign the full recording status enum unless needed for phase-safe retry.

## Design

### Execution ownership

Each queued transcription or enhancement request receives an execution token. The token is written when the repository transitions the recording into the phase owned by that worker.

Worker commit methods update rows only when the expected recording id, phase, and execution token still match. If no row is updated, the worker logs a stale-result event and returns success without mutating transcript, title, summary, timing rows, enhancement snapshot, or status.

Transcription commit expects the recording to still be owned by the same transcription execution. Enhancement commit expects the enhancement snapshot to still reference the same enhancement execution and source transcript revision.

### Enhancement execution snapshot

Replace the minimal enhancement intent with a durable execution snapshot. The snapshot stores:

- recording id
- snapshot schema version
- source transcript revision or hash
- source processed text revision or hash
- requested subwork entries for processing mode, title, and summary
- resolved processing mode id, label, type, and prompt/config needed to execute that mode
- auto-title and auto-summary request flags
- LLM provider and model identifier selected for the queued work
- created, last attempted, and last error metadata
- active enhancement execution token
- migration state for legacy rows whose request details cannot be fully reconstructed

API keys are not persisted. Secret availability is checked at execution time. Missing credentials leave requested enhancement work recoverable rather than deleting the snapshot.

### Enhancement subwork retention

Enhancement executes only the requested subwork that is still pending or failed. Successful subwork is marked complete and is not rerun on retry unless a new enhancement request is created. Failed subwork retains its error and remains retryable.

The snapshot is deleted only after all requested subwork is complete or explicitly skipped. Partial success updates successful outputs but does not discard failed requested work.

### Phase-aware failure and retry

A failed recording with a retained enhancement snapshot is treated as an enhancement failure. `TranscriptionRecovery.retry(recordingId)` routes to enhancement work when the latest recoverable failure belongs to enhancement. Full retranscription remains an explicit action.

`PENDING_ENHANCEMENT` recovery is added to the shared `TranscriptionRecovery` contract so consumers do not need concrete `TranscriptionQueueManager` access.

### Migration

The migration creates execution snapshots for every legacy `PENDING_ENHANCEMENT` and `ENHANCING` recording.

Profile-backed rows use the profile values available in the database. Profileless rows are not skipped. If global preference details cannot be read from the database, the migration creates a recoverable legacy snapshot marked as requiring resolution. The UI can then offer enhancement recovery or full retranscription without silently completing the recording.

No destructive migration fallback is allowed.

### Work scheduling test seam

Introduce an injectable scheduler or gateway around WorkManager scheduling, cancellation, and work inspection. Work request builders remain pure and expose production unique names, tags, inputs, and constraints.

Unit tests use fake schedulers and real production name builders. Tests must not mock `WorkManager.getInstance`, `TranscriptionWorkRequest.workName`, or `RecordingEnhancementWorkRequest.workName`.
