# Active OpenSpec changes

## Current audit reliability fixes (2026-05-31)

**Index:** [AUDIT_INDEX.md](./AUDIT_INDEX.md) — maps every P0–P4 finding to a change folder.

| Change | Priority | Focus |
|--------|----------|--------|
| `recording-finalize-handoff-integrity` | P0–P1 | Stop handoff ordering, stale handoff guard, terminal finalize results, startup dedupe |
| `widget-keyboard-stop-durable-command` | P1 | Durable pending keyboard stop before widget acknowledgement |
| `keyboard-inline-delivery-safety` | P1 | Stale input and sensitive-field dictation guard |
| `external-voice-recognition-capture-lock` | P1 | Shared capture lock for Android speech-recognition entry points |
| `model-readiness-authoritative-state` | P1–P2 | Readiness invalidation and complete-directory validation |
| `recording-profile-llm-processing` | P2 | Profile default LLM transform and metadata settings in saved recording enhancement |
| `split-transcription-enhancement-work` | P2 | Separate offline transcription from network-backed LLM enhancement work |
| `transcription-storage-and-text-persistence` | P2 | Storage constraint and processed text persistence |
| `share-audio-mime-correctness` | P2 | Format-derived audio share MIME types |
| `recording-recovery-orphan-cleanup-integrity` | P2 | Nested capture artifact cleanup |
| `android-16-sdk-alignment` | P2 | API 36 module baseline, AGP/Gradle alignment, legacy guard cleanup |
| `harden-recording-stop-durability` | P0–P1 | Stop durability, native stop bounds, service teardown, keyboard persistence |
| `harden-transcription-enhancement-pipeline` | P1–P2 | Split transcription/enhancement execution state, retries, recovery, worker testability |
| `data-room-repository-integrity-hardening` | P1–P3 | Non-destructive Room writes, guarded transitions, schemas, bounded queries, normalized profile default tags |
| `ui-polish-recovery-display-consistency` | P2–P4 | Pending enhancement recovery UI, fresh diagnostics, stale result guards, reactive enrichment |
| `enforce-architectural-module-boundaries` | P2–P4 | Ports, core module cleanup, dependency guard tests, RecordingService adapter boundary |
| `improve-performance-resource-efficiency` | P2–P4 | Backpressure-safe decode, warmup gating, bounded dictation memory, preview queries, LLM context reuse |

**Suggested order:** see AUDIT_INDEX.md.

---

## Architecture / hygiene (pre-existing queue)

Only these changes were queued before the audit. Everything else is under `archive/`.

| Change | Focus |
|--------|--------|
| `remove-codebase-slop-and-stale-docs` | Stale docs, trivial abstractions, deprecated APIs, file renames |
| `extract-recording-service-internals` | Shrink `RecordingService`; session maintenance + notifications |
| `decouple-module-dependency-graph` | Widget/recording/LLM Gradle boundaries, core intent commands |
| `split-core-module-layers` | Split contracts / UI / playback / audio modules |
| `improve-unit-test-fidelity` | Remove `isReturnDefaultValues`, strengthen tests |

**Suggested order:** slop → service extraction → module decoupling → core split → test fidelity.

Apply with `/opsx:apply <change-name>`.
