# Capture resilience

The continuous PCM stream remains Chirp's authoritative recording. Recovery replaces only the
Android `AudioRecord` producer and appends new samples to the same durable file and trusted sample
count. It never starts a second logical recording or substitutes preview text for captured audio.

## Storage writes

File-backed capture checks for 48 MiB of usable space ahead of microphone startup and writes each
completed block directly to the capture file.

Application startup asynchronously asks Android for one content-free 4 MiB cache reserve. The
allocation never runs from microphone startup or the read/write loop, and it never extends the PCM
file. Preparation uses a partial file and atomically promotes it only after allocation and sync
succeed. Startup removes an incomplete partial or wrong-sized final reserve, which makes a process
death during preparation safe and keeps the reserve bounded to one file.

The active recorder ignores the reserve until a write reports `ENOSPC`, `EDQUOT`, or the platform's
equivalent error text. It can delete a completed reserve without waiting for in-flight preparation,
then retries the exact unchanged PCM block once. Any other failure, a missing reserve, or a failed
retry stops capture cleanly, trims the uncertain tail, and keeps every earlier trusted block for
recovery. The reserve is intentionally one-shot for the process. Rebuilding it during capture could
compete with microphone I/O or consume the space it just released.

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

At most two watchdog restarts occur in one logical capture. A stalled or empty source that cannot be
recovered ends with an explicit microphone error and keeps its durable prefix. Hardware timestamp
gaps stay diagnostic only. Restarting a recorder that is still delivering samples would discard any
frames buffered by Android and cannot recover an earlier gap. Existing one-shot `ERROR_DEAD_OBJECT`
recovery remains separate.

Every successful restart is included in `CaptureIntegrityReport`. Watchdog restarts are also counted
separately so device testing can distinguish platform death from health-triggered recovery.
