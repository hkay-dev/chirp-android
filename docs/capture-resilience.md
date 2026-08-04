# Capture resilience

The continuous PCM stream remains Chirp's authoritative recording. Recovery replaces only the
Android `AudioRecord` producer and appends new samples to the same durable file and trusted sample
count. It never starts a second logical recording or substitutes preview text for captured audio.

## Storage writes

File-backed capture checks for 48 MiB of usable space ahead of microphone startup and writes each
completed block directly to the capture file. Android's allocation API can take several seconds, so
it is deliberately kept out of both startup and the active read loop. A write failure stops capture
cleanly, trims any uncertain partial block, and keeps every earlier block for recovery.

## Allocation discipline

Each active collector owns one 1,024-sample `AudioRecord` read buffer and one 4,096-byte
little-endian conversion buffer. They are allocated once per collector, not once per audio block.
Session-local ownership also keeps a late read from a stopped recorder from overwriting a newer
session's samples.

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
