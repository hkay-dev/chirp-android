# Improve Performance and Resource Efficiency

## Why

Several hot paths can spend excess memory, CPU, or LLM tokens:

- Audio decode uses unchecked flow sends that can drop backpressure signals.
- Speech model warmup can run even when no likely speech workload needs it.
- Keyboard dictation can copy long audio buffers through multiple in-memory stages.
- Home rows can load more transcript text than the preview needs.
- Playback ticks can trigger wider recomposition than the visible timer requires.
- LLM enhancement can rebuild repeated transcript context and prompts.

These inefficiencies can reduce reliability on long recordings, lower-end devices, and repeated processing runs.

## What Changes

- Make audio decoding backpressure-safe and observable.
- Gate Sherpa model warmup on likely speech workload candidates.
- Bound keyboard dictation audio memory with chunked or file-backed handoff.
- Load transcript previews through projection queries rather than full transcript bodies.
- Isolate playback tick state to the smallest UI surface that needs it.
- Reuse or batch LLM transcript context to avoid repeated prompt assembly.

## Impact

- Affected spec: `performance-resource-efficiency`
- Affected modules: audio decode, model readiness, keyboard dictation, Home list, playback UI, LLM enhancement
- Risk: Medium. Audio and keyboard changes need stress tests with long recordings.
