# Tasks

- [ ] 1.1 Replace unchecked audio decode flow sends with backpressure-safe streaming.
- [ ] 1.2 Add decode stress tests that verify no chunk is silently dropped under slow collection.
- [ ] 1.3 Gate model warmup behind candidate workload detection.
- [ ] 1.4 Add tests for idle startup, queued transcription, keyboard dictation, and recovery warmup paths.
- [ ] 1.5 Refactor keyboard dictation handoff to use bounded chunked or file-backed audio.
- [ ] 1.6 Add long dictation memory and correctness tests.
- [ ] 1.7 Add Home transcript preview projection queries and stop loading full bodies for list rows.
- [ ] 1.8 Isolate playback tick state and add recomposition-focused tests or instrumentation.
- [ ] 1.9 Reuse per-recording transcript context in LLM enhancement phases.
- [ ] 1.10 Measure representative long recording, Home list, playback, and enhancement runs before and after implementation.
