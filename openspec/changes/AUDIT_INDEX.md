# Audit backlog — OpenSpec change index

Canonical fix specs for findings in `docs/recording-lifecycle-spec.md` § Audit backlog (2026-05-25).

| Priority | Gap summary | Change folder | Status |
|----------|-------------|---------------|--------|
| P0-P1 | Stop journal ordering, stale capture handoff, terminal finalize cleanup, existing export retry, Starting-stop race, capture-stop timeout, and startup finalize dedupe | [recording-finalize-handoff-integrity](./recording-finalize-handoff-integrity/) | proposed |
| P1 | Widget stop for unbound keyboard recording can acknowledge before pending-stop persistence completes | [widget-keyboard-stop-durable-command](./widget-keyboard-stop-durable-command/) | proposed |
| P1 | Keyboard inline dictation can commit into a stale or sensitive input session | [keyboard-inline-delivery-safety](./keyboard-inline-delivery-safety/) | proposed |
| P1 | External Android speech recognition can bypass the shared microphone capture lock | [external-voice-recognition-capture-lock](./external-voice-recognition-capture-lock/) | proposed |
| P1-P2 | Model readiness can accept stale cache or split model files across directories | [model-readiness-authoritative-state](./model-readiness-authoritative-state/) | proposed |
| P2 | Saved recording enhancement ignores profile default LLM processing, auto-title, and auto-summary settings | [recording-profile-llm-processing](./recording-profile-llm-processing/) | proposed |
| P2 | Network-backed LLM enhancement is coupled to offline transcription worker execution | [split-transcription-enhancement-work](./split-transcription-enhancement-work/) | proposed |
| P2 | Transcription work lacks storage constraint and first transcript commit drops processed text | [transcription-storage-and-text-persistence](./transcription-storage-and-text-persistence/) | proposed |
| P2 | Audio sharing uses non-canonical `audio/m4a` instead of format-derived MIME types | [share-audio-mime-correctness](./share-audio-mime-correctness/) | proposed |
| P2 | Orphan cleanup misses stale nested capture directories | [recording-recovery-orphan-cleanup-integrity](./recording-recovery-orphan-cleanup-integrity/) | proposed |
| P2 | Android modules still declare API 35/26 despite Android 16-only baseline and AGP emits API 36 warnings | [android-16-sdk-alignment](./android-16-sdk-alignment/) | proposed |
| P0-P1 | Recording stop can lose recoverable artifacts or block service teardown when finalization, native stop, or keyboard persistence fails | [harden-recording-stop-durability](./harden-recording-stop-durability/) | proposed |
| P1-P2 | Offline transcription and network enhancement need independent execution state, recovery, and retry semantics | [harden-transcription-enhancement-pipeline](./harden-transcription-enhancement-pipeline/) | proposed |
| P1-P3 | Room writes, stale transitions, schema history, bulk queries, and profile default tag storage can weaken data integrity | [data-room-repository-integrity-hardening](./data-room-repository-integrity-hardening/) | proposed |
| P2-P4 | Recovery UI and Home enrichment can show stale or incomplete state after queue, transcript, tag, or diagnostic changes | [ui-polish-recovery-display-consistency](./ui-polish-recovery-display-consistency/) | proposed |
| P2-P4 | Feature modules, core UI, core contracts, and `RecordingService` have monolithic dependency boundaries | [enforce-architectural-module-boundaries](./enforce-architectural-module-boundaries/) | proposed |
| P2-P4 | Audio decode, model warmup, keyboard dictation, Home previews, playback ticks, and LLM context assembly can waste memory, CPU, or tokens | [improve-performance-resource-efficiency](./improve-performance-resource-efficiency/) | proposed |
| P0-P1 | Active M4A/MP3 live capture segment can be unrecoverable if the process dies before container finalization | [recording-crash-tolerant-live-capture](./recording-crash-tolerant-live-capture/) | proposed |
| P0–P2, P4 | recoverSession guards; keep-files RECORDING row; reconciler missing row; defer persist | [recovery-data-integrity](./archive/2026-05-25-recovery-data-integrity/) | applied |
| P0–P2 | Invalid UUID trap; missing recording skeleton; FAILED duplicate UI; Home import nav | [archive/2026-05-25-processing-studio-resilience](./archive/2026-05-25-processing-studio-resilience/) | applied |
| P1–P3 | Cancel during Starting; early Done; pending stop reconcile; widget Stopping | [archive/2026-05-25-recording-edge-case-races](./archive/2026-05-25-recording-edge-case-races/) | applied |
| P2–P4 | Orphan mp3; worker bounded wait; WAV MediaCodec | [archive/2026-05-25-transcription-pipeline-hardening](./archive/2026-05-25-transcription-pipeline-hardening/) | applied |
| P3–P4 | launchSingleTop; search RECORDING filter; mini player; motion checklist | [archive/2026-05-25-nav-search-playback-polish](./archive/2026-05-25-nav-search-playback-polish/) | applied |
| P3–P4 | Matrix accuracy; dead wrappers; gapless tests; migration gaps; outputFormat | [archive/2026-05-25-docs-test-hygiene](./archive/2026-05-25-docs-test-hygiene/) | applied |

## Suggested implementation order

1. `recording-finalize-handoff-integrity`
2. `widget-keyboard-stop-durable-command`
3. `keyboard-inline-delivery-safety`
4. `external-voice-recognition-capture-lock`
5. `model-readiness-authoritative-state`
6. `recording-profile-llm-processing`
7. `split-transcription-enhancement-work`
8. `transcription-storage-and-text-persistence`
9. `share-audio-mime-correctness`
10. `recording-recovery-orphan-cleanup-integrity`
11. `android-16-sdk-alignment`
12. `harden-recording-stop-durability`
13. `harden-transcription-enhancement-pipeline`
14. `data-room-repository-integrity-hardening`
15. `ui-polish-recovery-display-consistency`
16. `enforce-architectural-module-boundaries`
17. `improve-performance-resource-efficiency`

## Archive reference

Applied predecessor: [archive/2026-05-25-recording-lifecycle-gap-closure](../archive/2026-05-25-recording-lifecycle-gap-closure/)

Applied: [archive/2026-05-25-stop-persistence-integrity](../archive/2026-05-25-stop-persistence-integrity/)

Applied: [archive/2026-05-25-recovery-data-integrity](../archive/2026-05-25-recovery-data-integrity/)

Applied: [archive/2026-05-25-recording-edge-case-races](../archive/2026-05-25-recording-edge-case-races/)

Applied: [archive/2026-05-25-processing-studio-resilience](../archive/2026-05-25-processing-studio-resilience/)

Applied: [archive/2026-05-25-transcription-pipeline-hardening](../archive/2026-05-25-transcription-pipeline-hardening/)

Applied: [archive/2026-05-25-nav-search-playback-polish](../archive/2026-05-25-nav-search-playback-polish/)

Applied: [archive/2026-05-25-docs-test-hygiene](../archive/2026-05-25-docs-test-hygiene/)
