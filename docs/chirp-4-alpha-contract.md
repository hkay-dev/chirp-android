# Chirp 4.0 alpha local transcription contract

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
