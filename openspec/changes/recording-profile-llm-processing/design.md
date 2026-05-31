## Context

The background worker already owns the complete saved-recording pipeline: local transcription, word replacement, optional LLM enhancement, and final status update. The UI stores profile defaults on the recording profile, but the worker previously read only global LLM title and summary preferences.

## Decision

Add a small policy resolver beside the worker. For recordings with a profile, profile settings are authoritative:

- `defaultProcessingMode` requests an LLM transform of the transcript.
- `autoTitle` controls generated title.
- `autoSummary` controls generated summary.

For recordings without a profile, global auto-title and auto-summary preferences remain the fallback.

The transform runs before title and summary generation so metadata is based on the same text the user sees as processed output. Word replacement remains the first deterministic cleanup stage, followed by optional LLM transformation.

## Non-goals

- Splitting transcription and enhancement into separate workers.
- Adding a network constraint to the transcription worker.
- Changing inline dictation behavior.

## Follow-up

Network-backed enhancement still shares a worker with offline transcription. A separate enhancement worker would let offline transcription complete immediately and defer only LLM work until network is available.
