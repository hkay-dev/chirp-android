package dev.chirpboard.app.core.playback

import java.util.UUID

data class RecordingPlaybackState(
    val recordingId: UUID? = null,
    val title: String = "",
    val audioPath: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Persisted playback speed (0.75x-2x); always one of the supported options. */
    val playbackSpeed: Float = 1f,
    /** Transient non-error hint (for example "media volume is muted"). */
    val noticeMessage: String? = null,
) {
    val isActive: Boolean
        get() = recordingId != null && errorMessage == null

    val isIdle: Boolean
        get() = recordingId == null && !isLoading && errorMessage == null

    fun isForRecording(recordingId: UUID): Boolean = this.recordingId == recordingId
}
