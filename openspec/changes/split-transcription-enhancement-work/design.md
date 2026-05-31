## Context

The saved recording pipeline has two different execution domains:

- Local transcription decodes audio, runs Sherpa-ONNX, stores raw text, applies word replacement, and persists timings.
- LLM enhancement optionally transforms processed text and generates metadata through network-backed model calls.

Running both in `TranscriptionWorker` makes offline work wait for network-backed work and forces `PENDING_ENHANCEMENT` recovery through the transcription worker.

## Decision

Introduce a separate `RecordingEnhancementWorker` and `RecordingEnhancementWorkRequest`.

`TranscriptionWorker` remains responsible for:

- audio file validation
- model readiness
- decode and transcription
- word replacement
- transcript/timing persistence
- resolving and persisting the enhancement intent snapshot before queue handoff
- deciding whether enhancement work should be queued

`RecordingEnhancementWorker` owns:

- loading the persisted enhancement intent for the recording
- LLM availability checks at execution time
- status transition from `PENDING_ENHANCEMENT` to `ENHANCING`
- requested processing mode transform
- auto-title and auto-summary generation
- final status transition to `COMPLETED`

Enhancement work uses a separate unique work name and tag for each recording, and requires network connectivity. Transcription work keeps its current battery and storage constraints and does not require network.

The enhancement intent is persisted transactionally with the transcript commit. The queued worker consumes that snapshot, rather than re-reading profile or global settings at execution time. This prevents profile edits or settings changes from mutating already queued enhancement work.

## Status Rules

- If transcription succeeds and no enhancement is requested, the recording becomes `COMPLETED`.
- If transcription succeeds and enhancement is requested but LLM is disabled or no key exists, enhancement is logged as skipped and the recording becomes `COMPLETED`.
- If transcription succeeds and enhancement is requested and available, the recording becomes `PENDING_ENHANCEMENT` and enhancement work is enqueued.
- If enhancement work starts, the recording becomes `ENHANCING`.
- If enhancement work finds no persisted intent for an existing transcript, enhancement is logged as skipped and the recording becomes `COMPLETED`.
- If enhancement work applies any requested output, the recording becomes `COMPLETED`.
- If requested enhancement runs but every requested LLM operation fails, the event is logged as failure and the recording still becomes `COMPLETED`, preserving existing user-visible behavior.

## Recovery

Queue reconciliation inspects the unique work name for the expected phase. It re-enqueues `PENDING_TRANSCRIPTION` through `TranscriptionWorkRequest` and `PENDING_ENHANCEMENT` through `RecordingEnhancementWorkRequest`.

Stale `ENHANCING` recovery returns the recording to `PENDING_ENHANCEMENT`, then reconciliation re-enqueues enhancement work. Manual recovery can still re-run enhancement or full transcription.

## Non-goals

- Changing the existing per-recording unique work model into a global FIFO queue.
- Retrying individual LLM API failures that return `Result.failure`.
- Changing inline keyboard dictation.
