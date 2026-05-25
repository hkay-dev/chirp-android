package dev.chirpboard.app.feature.recording.service

sealed class SegmentRotationResult {
    data object Success : SegmentRotationResult()

    data class Failed(val reason: String) : SegmentRotationResult()
}
