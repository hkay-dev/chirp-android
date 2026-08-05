package dev.chirpboard.app.data.model

/**
 * Typed classification of the machine-readable codes persisted into
 * [dev.chirpboard.app.data.entity.Recording.errorMessage] by the transcription pipeline
 * (I18N-06).
 *
 * The persisted strings are a frozen on-disk contract, NOT display copy: producers
 * (TranscriptionWorker / queue reconciler) keep writing the exact prefixes below, every
 * consumer classifies through this single function, and UI layers map the resulting kind to
 * string resources. Reworking user-facing copy therefore never changes recovery behavior,
 * and the persisted codes must never be reworded (legacy rows already carry them).
 */
enum class RecordingProcessingNoteKind {
    /** The speech/voice model is missing or failed to load; recovery waits for a download. */
    WAITING_FOR_MODEL,

    /** A stale TRANSCRIBING/ENHANCING row was recovered by the queue reconciler. */
    STALE_RECOVERED,

    /** WorkManager enqueue failed after persistence; startup recovery re-attaches the work. */
    QUEUE_HANDOFF,

    /** The user (or recovery UI) explicitly re-queued this recording. */
    MANUAL_RECOVERY,

    /** Anything else, including raw worker exception messages from legacy rows. */
    OTHER,
}

object RecordingProcessingNoteCodes {
    const val MANUAL_RECOVERY_PREFIX = "manual_recovery:"
    const val RECOVERABLE_QUEUE_HANDOFF_PREFIX = "recoverable_queue_handoff:"
    const val RECOVERABLE_STALE_TRANSCRIBING_PREFIX = "recoverable_stale_transcribing:"
    const val RECOVERABLE_STALE_ENHANCING_PREFIX = "recoverable_stale_enhancing:"

    /**
     * Frozen model-unavailable markers written by TranscriptionWorker /
     * TranscriptionWorkerSupport since v1. They read like sentences for historical reasons;
     * treat them as opaque codes (see [classifyRecordingProcessingNote]).
     */
    val WAITING_FOR_MODEL_PREFIXES =
        listOf(
            "Model not downloaded",
            "Failed to initialize",
            "Speech model unavailable",
            "Recognizer not ready",
        )
}

/** Single classification point for persisted processing notes (I18N-06). */
fun classifyRecordingProcessingNote(errorMessage: String?): RecordingProcessingNoteKind {
    if (errorMessage.isNullOrBlank()) return RecordingProcessingNoteKind.OTHER
    return when {
        RecordingProcessingNoteCodes.WAITING_FOR_MODEL_PREFIXES.any(errorMessage::startsWith) ->
            RecordingProcessingNoteKind.WAITING_FOR_MODEL

        errorMessage.startsWith(RecordingProcessingNoteCodes.RECOVERABLE_STALE_TRANSCRIBING_PREFIX) ||
            errorMessage.startsWith(RecordingProcessingNoteCodes.RECOVERABLE_STALE_ENHANCING_PREFIX) ->
            RecordingProcessingNoteKind.STALE_RECOVERED

        errorMessage.startsWith(RecordingProcessingNoteCodes.RECOVERABLE_QUEUE_HANDOFF_PREFIX) ->
            RecordingProcessingNoteKind.QUEUE_HANDOFF

        errorMessage.startsWith(RecordingProcessingNoteCodes.MANUAL_RECOVERY_PREFIX) ->
            RecordingProcessingNoteKind.MANUAL_RECOVERY

        else -> RecordingProcessingNoteKind.OTHER
    }
}

/**
 * True when a FAILED recording is waiting for the speech model to become available
 * (download/initialize) rather than having failed for an unrelated reason. Used by the
 * startup readiness coordinator and the queue manager's model-recovery pass.
 */
fun isWaitingForSpeechModel(errorMessage: String?): Boolean =
    classifyRecordingProcessingNote(errorMessage) == RecordingProcessingNoteKind.WAITING_FOR_MODEL
