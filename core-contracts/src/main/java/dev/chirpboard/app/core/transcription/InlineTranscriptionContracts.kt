package dev.chirpboard.app.core.transcription

import java.io.File

/**
 * Phases emitted while inline dictation transcription runs.
 */
sealed interface InlineTranscriptionPhase {
    data object Idle : InlineTranscriptionPhase

    data class LoadingModel(val progress: Float? = null) : InlineTranscriptionPhase

    data object Transcribing : InlineTranscriptionPhase

    data object Polishing : InlineTranscriptionPhase

    data class Error(val message: String) : InlineTranscriptionPhase

    data class LlmError(val message: String) : InlineTranscriptionPhase
}

data class InlineTranscriptionRequest(
    val audioSource: InlineAudioSource,
    val llmEnabled: Boolean,
    val processingModeId: String,
    val correlationPrefix: String = "keyboard",
    val latencyObserver: InlineDictationLatencyObserver? = null,
) {
    companion object {
        /**
         * Convenience constructor for the in-memory dictation path (e.g. the voice
         * recognition dialog), wrapping [samples] in an [InlineAudioSource.InMemory].
         */
        fun inMemory(
            samples: FloatArray,
            llmEnabled: Boolean,
            processingModeId: String,
            correlationPrefix: String = "keyboard",
            sampleRate: Int = 16000,
        ): InlineTranscriptionRequest =
            InlineTranscriptionRequest(
                audioSource = InlineAudioSource.InMemory(samples, sampleRate),
                llmEnabled = llmEnabled,
                processingModeId = processingModeId,
                correlationPrefix = correlationPrefix,
            )
    }
}

/** Content-free timing hooks for local performance diagnostics. */
interface InlineDictationLatencyObserver {
    fun onDecodeStarted()

    fun onRawTranscriptReady()

    fun onAiStarted()

    fun onAiCompleted()

    fun onCommitCompleted(accepted: Boolean)
}

sealed interface InlineAudioSource {
    val sampleRate: Int

    data class InMemory(
        val samples: FloatArray,
        override val sampleRate: Int = 16000,
    ) : InlineAudioSource

    data class PcmFloatFile(
        val path: String,
        val sampleCount: Long,
        override val sampleRate: Int = 16000,
    ) : InlineAudioSource
}

/**
 * Why a capture is being persisted. Drives retention: [RESCUE] entries are failure
 * artifacts the user cannot otherwise recover, so implementations persist them even
 * when the save-keyboard-recordings preference is off, while [COMPLETED] and
 * [USER_CANCELLED] captures always respect that preference.
 */
enum class InlineCapturePersistReason {
    /** Dictation finished normally and its transcript reached the target. */
    COMPLETED,

    /**
     * The user explicitly discarded the dictation (cancel tap, restart). Must respect
     * the save preference: with saving off, neither audio nor text may be retained.
     */
    USER_CANCELLED,

    /**
     * Non-user-initiated failure (commit refused, pipeline error, stop-timeout
     * rescue). Persisted regardless of the save preference so the captured speech
     * can be recovered from the recordings list.
     */
    RESCUE,
}

interface InlineCapturePersistence {
    fun prepareAudioSource(audioSource: InlineAudioSource) = Unit

    /**
     * Drops the reference staged by [prepareAudioSource] without touching its backing
     * file. Used when a detached pipeline takes over ownership of the staged source and
     * will persist or discard it itself; a later [prepareAudioSource] or [discardSamples]
     * must not delete audio it no longer owns.
     */
    fun releasePendingAudioSource() = Unit

    /**
     * Writes a recoverable, non-terminal checkpoint for a capture that is still owned by the
     * caller. A checkpoint must never consume, move, or delete [audioSource]. Implementations
     * return true only once the checkpoint is durably stored. The default keeps lightweight
     * and incognito implementations source-compatible and makes no durability claim.
     */
    suspend fun checkpointAudioSource(
        audioSource: InlineAudioSource,
        trustedSampleCount: Long,
        partialTranscript: String?,
        estimatedGapMs: Long? = null,
    ): Boolean = false

    /** Removes a checkpoint only after the capture reached a terminal durable outcome. */
    suspend fun clearCheckpoint(audioSource: InlineAudioSource) = Unit

    /** Replays valid checkpoints left by a dead process and returns durable recovery count. */
    suspend fun recoverCheckpoints(): Int = 0

    /**
     * [reason] declares the caller's intent explicitly and drives retention: see
     * [InlineCapturePersistReason]. Every caller must state why the capture is being
     * persisted so implementations never have to infer rescue intent from error text.
     */
    suspend fun persist(
        samples: FloatArray?,
        rawText: String?,
        processedText: String?,
        errorMessage: String? = null,
        reason: InlineCapturePersistReason,
    )

    suspend fun persistAudioSource(
        audioSource: InlineAudioSource?,
        rawText: String?,
        processedText: String?,
        errorMessage: String? = null,
        reason: InlineCapturePersistReason,
    ) {
        persist(
            samples = (audioSource as? InlineAudioSource.InMemory)?.samples,
            rawText = rawText,
            processedText = processedText,
            errorMessage = errorMessage,
            reason = reason,
        )
    }

    fun discardSamples()

    /**
     * Discards exactly [audioSource] and clears any staged reference only when it still
     * points at the same source. Unlike [discardSamples] this can never delete audio
     * staged by a newer dictation, so detached pipelines can call it safely. The default
     * deliberately fails closed: it drops only [audioSource]'s own backing storage and
     * never falls back to the identity-blind [discardSamples].
     */
    fun discardAudioSource(audioSource: InlineAudioSource) {
        if (audioSource is InlineAudioSource.PcmFloatFile) {
            runCatching { File(audioSource.path).delete() }
        }
    }
}

/**
 * Shared inline STT + optional LLM path for IME and voice dialog surfaces.
 */
interface InlineTranscriptionPort {
    val phase: kotlinx.coroutines.flow.StateFlow<InlineTranscriptionPhase>

    fun resetPhase()

    fun setError(message: String)

    /**
     * Marks the in-flight transcription as cancelled by an explicit user action (cancel
     * tap, restart, dialog dismissal). Owners of user-initiated cancellation must call
     * this immediately before cancelling the pipeline's job so the pipeline persists the
     * capture as [InlineCapturePersistReason.USER_CANCELLED] (respecting the save
     * preference). A cancellation that arrives without this mark — IME service
     * destruction, scope death, system kill mid-transcription — is treated as a non-user
     * interruption and the capture is rescued regardless of the preference.
     */
    fun markUserCancelled() = Unit

    suspend fun transcribe(
        request: InlineTranscriptionRequest,
        persistence: InlineCapturePersistence? = null,
        commitText: (String) -> Unit,
        onRecordingCompleted: () -> Unit = {},
        onRecordingError: (String) -> Unit = {},
    ) {
        transcribeWithCommitResult(
            request = request,
            persistence = persistence,
            commitText = { text ->
                commitText(text)
                true
            },
            onRecordingCompleted = onRecordingCompleted,
            onRecordingError = onRecordingError,
        )
    }

    /**
     * Like [transcribe] but [commitText] reports whether the text actually reached its target.
     * When the commit is refused, implementations must persist the transcript through
     * [persistence] as a rescue artifact instead of dropping it.
     */
    suspend fun transcribeWithCommitResult(
        request: InlineTranscriptionRequest,
        persistence: InlineCapturePersistence? = null,
        commitText: (String) -> Boolean,
        onRecordingCompleted: () -> Unit = {},
        onRecordingError: (String) -> Unit = {},
    )
}

interface InlineTranscriptionCoordinator : InlineTranscriptionPort
