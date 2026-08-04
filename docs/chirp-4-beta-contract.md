# Chirp 4.0 beta local transcription contract

This contract is a release gate. Performance work must not weaken content preservation.

## Content integrity

- Microphone capture starts independently of model loading.
- Durable audio is the source of truth until final text is saved and delivered.
- A continuous recording is decoded as one continuous utterance whenever the backend supports it.
- File-backed continuous decoding may skip heap copies only when the source length is validated
  against its declared sample count and failure preserves the source for recovery.
- Overlapping chunks are a recovery path, not the primary transcript path.
- A proven native memory ceiling may select chunk recovery before decoding; it must never risk
  process death or lost audio for an unbounded whole-file call.
- Backend, post-processing, activity, IME, and process failures must leave recoverable audio behind.
- Quick-input activity windows must preserve the caller's IME visibility and return successful text
  immediately through the standard recognition result contract.
- Local capture checkpoints ownership after its first durable block and recovers the later complete
  file tail. Cloud capture journals ownership before opening the microphone.

## Model residency

- The selected offline recognizer stays loaded across completed dictations, IME hides, app switches, and idle time.
- Only confirmed Android memory pressure, severe thermal pressure, an explicit model switch or deletion, or process death may unload it.
- Capture, queued transcription, and native decode leases block pressure release.
- A replacement model must load successfully before its selection is committed.
- A failed model switch keeps the prior model selected and usable.

## Backend isolation

- Sherpa ONNX and transcribe.cpp GGUF implement the same `TranscriberProvider` contract.
- Model metadata, files, hashes, storage, and selection are keyed by stable model IDs.
- Model downloads carry their model ID through WorkManager and cannot change target mid-transfer.
- Streaming preview is optional and never authoritative.

## Performance gates

- Report cold-process, warm-session, and sustained results separately.
- Compare identical audio with alternating run order and matched thermal conditions.
- Keep short-dictation latency separate from long-file throughput.
- Do not accept a speed win that causes missing, duplicated, reordered, or silently replaced words.

## Beta release gates

- A beta build updates the existing 4.0 alpha package in place and keeps its app data and IME identity.
- The selected recognizer begins warming shortly after process startup, independently of recovery
  and janitorial work, with bounded retries for transient warmup failure.
- Content-free diagnostics remain bounded and must not queue one disk rewrite per recovery chunk.
- The reliability matrix, minified beta build, installed-package upgrade, and launch smoke test must pass.
- Experimental backends stay capability-gated until the shipped native binary and device benchmark prove them.
