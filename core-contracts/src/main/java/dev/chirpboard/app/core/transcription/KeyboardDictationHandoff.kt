package dev.chirpboard.app.core.transcription

import java.util.UUID

/**
 * Durable boundary between the keyboard's short-lived IME session and queued transcription.
 *
 * A [KeyboardDictationHandoffResult.Durable] result transfers ownership of the exact stopped
 * capture out of the IME. The audio has moved out of cache, a recording row exists, and the
 * background queue either owns the work or can recover it from that row on the next process start.
 */
interface KeyboardDictationHandoff {
    /**
     * Snapshots the route before a keyboard recording starts. A non-null audio path reserves
     * durable cloud storage and the recorder must write directly to it. Its journal survives
     * process death and is replayed by [recoverPendingHandoffs]. A pathless result pins the
     * session to the local inline route.
     */
    suspend fun beginLiveCapture(
        request: KeyboardDictationLiveCaptureRequest,
    ): KeyboardDictationLiveCapture? = null

    /** Writes the crash-recovery journal once AudioRecord is already delivering samples. */
    suspend fun markLiveCaptureStarted(capture: KeyboardDictationLiveCapture) = Unit

    /** Drops a live-capture journal and any audio still owned by it. */
    suspend fun abandonLiveCapture(capture: KeyboardDictationLiveCapture) = Unit

    /**
     * Gives a journaled cloud capture back to the inline pipeline while keeping its audio file.
     * This is used when the field becomes incognito during a recording, so startup recovery can
     * never upload audio that the destination field says must stay local.
     */
    suspend fun releaseLiveCaptureForInline(capture: KeyboardDictationLiveCapture) = Unit

    suspend fun handoff(request: KeyboardDictationHandoffRequest): KeyboardDictationHandoffResult

    /** Explicitly discards a recording that was durably handed off during a racing cancel tap. */
    suspend fun discard(recordingId: UUID): Boolean

    /**
     * Replays file-backed handoffs interrupted between the stopped capture and its Room insert.
     * The returned count is the number of recording rows restored during this pass.
     */
    suspend fun recoverPendingHandoffs(): Int = 0
}

data class KeyboardDictationLiveCaptureRequest(
    val llmEnabled: Boolean,
    val processingModeId: String,
    val suppressHistory: Boolean,
    val notifyWhenReady: Boolean = true,
    /** In-memory route snapshot from the IME. Null keeps older callers source-compatible. */
    val transcriptionEngine: TranscriptionEngine? = null,
)

data class KeyboardDictationLiveCapture(
    val recordingId: UUID? = null,
    val audioPath: String? = null,
    val transcriptionEngine: TranscriptionEngine = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3,
    val llmEnabled: Boolean = false,
    val processingModeId: String = "proofread",
    val notifyWhenReady: Boolean = true,
) {
    init {
        val cloud = transcriptionEngine == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3
        require(!cloud || (recordingId != null && !audioPath.isNullOrBlank())) {
            "Cloud keyboard capture needs durable storage"
        }
        require(cloud || (recordingId == null && audioPath == null)) {
            "Local keyboard capture cannot own cloud storage"
        }
    }
}

data class KeyboardDictationHandoffRequest(
    val audioSource: InlineAudioSource.PcmFloatFile,
    val llmEnabled: Boolean,
    val processingModeId: String,
    val notifyWhenReady: Boolean = true,
    /** Route sampled before AudioRecord started. Null keeps older callers source-compatible. */
    val transcriptionEngine: TranscriptionEngine? = null,
    /** Queue even the local engine because the original input target has already closed. */
    val forceDurable: Boolean = false,
)

sealed interface KeyboardDictationHandoffResult {
    /** The selected engine is local, so the keyboard still owns the untouched source file. */
    data object InlineLocal : KeyboardDictationHandoffResult

    data class Durable(
        val recordingId: UUID,
    ) : KeyboardDictationHandoffResult

    /**
     * [sourceAvailableForInlineFallback] is true only when ownership never left the original
     * [InlineAudioSource.PcmFloatFile]. The keyboard may use its old local pipeline in that case.
     * Once ownership moved, the handoff implementation keeps the audio and the IME must not touch
     * the stale source path.
     */
    data class Failed(
        val message: String,
        val sourceAvailableForInlineFallback: Boolean,
    ) : KeyboardDictationHandoffResult
}
