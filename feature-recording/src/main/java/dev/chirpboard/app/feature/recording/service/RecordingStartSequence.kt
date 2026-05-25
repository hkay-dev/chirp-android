package dev.chirpboard.app.feature.recording.service

internal enum class RecordingStartStep {
    PromoteForeground,
    PrepareRecorderAsync,
}

internal object RecordingStartSequence {
    fun stepsAfterLockAcquired(): List<RecordingStartStep> =
        listOf(
            RecordingStartStep.PromoteForeground,
            RecordingStartStep.PrepareRecorderAsync,
        )
}
