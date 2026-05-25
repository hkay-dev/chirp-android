package dev.chirpboard.app.core.recording

data class PendingKeyboardStop(
    val requestedAtEpochMs: Long,
    val requesterOrigin: RecordingOrigin,
)
