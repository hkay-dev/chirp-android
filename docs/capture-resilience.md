# Capture resilience

The continuous PCM stream remains Chirp's authoritative recording. Recovery replaces only the
Android `AudioRecord` producer and appends new samples to the same durable file and trusted sample
count. It never starts a second logical recording or substitutes preview text for captured audio.

## Storage reservation

File-backed capture asks Android `StorageManager` to allocate a cache-side reservation in 4 MiB slabs. The first slab
is reserved before the microphone is presented as ready, which covers about 65 seconds of 16 kHz
mono float PCM. Longer recordings extend the allocation only when the trusted write position crosses
a slab boundary. The sidecar shrinks as trusted PCM consumes its reserved bytes and is removed at
normal teardown. Keeping reservation bytes outside the PCM file leaves the audio file's logical
length equal to its real audio content, including across process death.
A failed extension stops capture cleanly and keeps every fully written block for recovery.

## Allocation discipline

Each `VoiceRecorder` owns one 1,024-sample `AudioRecord` read buffer and one 4,096-byte little-endian
conversion buffer. Both survive across sessions. The capture loop does not allocate a new read or
conversion buffer for each block or dictation.

## Health watchdog

A separate low-frequency watchdog checks the active blocking read without running on the urgent
capture loop. It requests an in-place recorder restart for any of these signals.

- A blocking read that has not returned for 1.5 seconds
- Eight consecutive zero-length reads
- Hardware timestamp drift of at least 500 milliseconds

At most two watchdog restarts occur in one logical capture. A stalled or empty source that cannot be
recovered ends with an explicit microphone error and keeps its durable prefix. Timestamp drift stays
advisory once restart is unavailable because a device may report unreliable timestamps while still
delivering complete samples. Existing one-shot `ERROR_DEAD_OBJECT` recovery remains separate.

Every successful restart is included in `CaptureIntegrityReport`. Watchdog restarts are also counted
separately so device testing can distinguish platform death from health-triggered recovery.
