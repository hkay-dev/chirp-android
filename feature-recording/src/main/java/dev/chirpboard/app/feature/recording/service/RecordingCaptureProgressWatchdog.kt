package dev.chirpboard.app.feature.recording.service

/** Detects a live capture engine whose PCM byte counter has stopped advancing. */
internal class RecordingCaptureProgressWatchdog(
    private val stallTimeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
) {
    private var lastBytes: Long? = null
    private var lastProgressAtMs: Long = 0L
    private var fired = false

    fun observe(
        capturedBytes: Long,
        nowMs: Long,
    ): Boolean {
        if (fired) return false
        val previous = lastBytes
        if (previous == null || capturedBytes > previous) {
            lastBytes = capturedBytes
            lastProgressAtMs = nowMs
            return false
        }
        if (capturedBytes < previous) {
            // A fresh engine after pause/resume starts its own watchdog, but tolerate a
            // counter reset defensively so it can never look like a stall.
            lastBytes = capturedBytes
            lastProgressAtMs = nowMs
            return false
        }
        if (nowMs - lastProgressAtMs < stallTimeoutMs) return false
        fired = true
        return true
    }

    companion object {
        const val DEFAULT_STALL_TIMEOUT_MS = 15_000L
    }
}
