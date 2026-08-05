# Chirp 4.0 stable local transcription contract

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
- Quick-input activity windows must preserve the caller's IME visibility and return successful
  text promptly through the caller-selected activity-result or result-`PendingIntent` contract.
- Local capture checkpoints ownership after its first durable block and recovers the later complete
  file tail. Cloud capture journals ownership before opening the microphone.

## Default model

- A new install defaults to `PARAKEET_CTC_110M_Q8`, the compact 135 MB GGUF model.
- An existing explicit model selection is never overwritten during upgrade.
- The stable UI calls the model “Parakeet 110M Q8.” The persisted ID stays unchanged for upgrade
  compatibility, though the verified converted artifact uses its TDT head rather than a CTC head.
- CPU is the stable compute default. Vulkan stays capability-gated until a shipped native build
  passes the same reliability and device gates.

## Model residency

- The selected offline recognizer stays loaded across completed dictations, IME hides, app switches, and idle time.
- Only confirmed Android memory pressure, severe thermal pressure, an explicit model switch or deletion, or process death may unload it.
- Capture, queued transcription, and native decode leases block pressure release.
- A replacement model must load successfully before its selection is committed.
- A failed model switch keeps the prior model selected and usable.
- Readiness verification checks model artifacts only. It must never be described or used as native
  recognizer warmup.

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

## Stable release gates

- The release variant uses the main package ID and explicitly disables Java and JNI debugging.
- The selected recognizer begins warming shortly after process startup, independently of recovery
  and janitorial work, with bounded retries for transient load failure.
- Content-free diagnostics remain bounded and must not queue one disk rewrite per recovery chunk.
- The reliability matrix, full unit suite, lint, static checks, and minified release build must pass.
- The signed APK must report `debuggable=false`, contain no packaged native debug sections, and
  pass 16 KB zip and ELF alignment checks.
- Experimental backends stay capability-gated until the shipped native binary and device benchmark prove them.
