package dev.chirpboard.app.core.transcription

import java.util.UUID
import kotlinx.coroutines.flow.Flow

const val ACTION_OPEN_TRANSCRIPTION_RECORDING = "dev.chirpboard.app.action.OPEN_TRANSCRIPTION_RECORDING"
const val EXTRA_TRANSCRIPTION_RECORDING_ID = "transcription_recording_id"
const val GOOGLE_CLOUD_CHIRP_3_MAX_DURATION_MS = 60L * 60L * 1000L
const val GOOGLE_CLOUD_CHIRP_3_MAX_AUDIO_BYTES = 256L * 1024L * 1024L

/** File-level engine used by the durable recording pipeline. */
enum class TranscriptionEngine(
    val id: String,
) {
    LOCAL_PARAKEET("local_parakeet"),
    GOOGLE_CLOUD_CHIRP_3("google_cloud_chirp_3"),
    ;

    companion object {
        fun fromId(id: String?): TranscriptionEngine? = entries.firstOrNull { it.id == id }
    }
}

/** Global default. Each recording snapshots this value before transcription starts. */
interface TranscriptionRoutingStore {
    val selectedEngine: Flow<TranscriptionEngine>

    suspend fun getSelectedEngine(): TranscriptionEngine

    suspend fun setSelectedEngine(engine: TranscriptionEngine)
}

data class CloudFileTranscriptionRequest(
    val recordingId: UUID,
    val executionToken: String,
    val audioPath: String,
    val mimeType: String,
    val durationMs: Long,
    val languageCode: String = "en-US",
)

enum class CloudTranscriptionConfigurationStatus {
    READY,
    ENDPOINT_MISSING,
    AUTHENTICATION_MISSING,
    TEMPORARILY_UNAVAILABLE,
}

/**
 * File-level cloud recognizer. Unlike [TranscriberProvider], this contract uploads one durable
 * recording and must resume the same remote job when the worker retries.
 */
interface CloudFileTranscriptionProvider {
    suspend fun configurationStatus(): CloudTranscriptionConfigurationStatus

    suspend fun transcribeFile(request: CloudFileTranscriptionRequest): TranscriptionOutcome
}
