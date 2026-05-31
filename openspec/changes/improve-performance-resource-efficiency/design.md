# Design

## Backpressure-Safe Audio Decode

Replace unchecked `callbackFlow` sends with a decode stream that respects downstream pressure. Preferred shapes:

- a suspending `flow { decode(filePath) { emit(chunk) } }`
- or a channel flow that checks every send result and fails or retries explicitly

Decode telemetry should record dropped, retried, or failed chunks.

## Candidate-Gated Model Warmup

Warmup should run only when a likely speech workload exists:

- active or recently queued transcription
- keyboard dictation starting soon
- user opens a screen that explicitly needs readiness
- recovery flow requests model availability

Idle app startup should not eagerly warm large models without a candidate.

## Bounded Keyboard Audio Memory

Keyboard dictation should avoid full-buffer multi-copy handoff for long input. Use chunked processing or file-backed handoff so memory is bounded by chunk size and codec buffers rather than total capture length.

## Home Preview Projection

Home rows should load only preview text, summary, and metadata needed for the visible list. Full transcript bodies should load on detail or editor screens.

## Playback Tick Isolation

Playback progress ticks should update a narrow state projection for the active playback control. Parent list or studio state should not recompose on every tick unless their own displayed fields changed.

## Efficient LLM Context

LLM enhancement should avoid rebuilding the same transcript context for title, summary, cleanup, and transform phases. Context assembly can be shared per recording attempt, with phase-specific instructions layered on top.
