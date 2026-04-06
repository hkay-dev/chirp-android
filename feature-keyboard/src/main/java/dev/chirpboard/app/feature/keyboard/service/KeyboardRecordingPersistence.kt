package dev.chirpboard.app.feature.keyboard.service

import android.util.Log
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.keyboard.recorder.AudioEncoder
import dev.chirpboard.app.feature.keyboard.recorder.VoiceRecorder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private const val PERSISTENCE_TAG = "ChirpKeyboard"

internal data class KeyboardPersistencePlan(
    val title: String,
    val status: RecordingStatus,
    val rawText: String?,
    val processedText: String?,
    val errorMessage: String?
)

internal fun buildKeyboardPersistencePlan(
    rawText: String?,
    processedText: String?,
    errorMessage: String?
): KeyboardPersistencePlan {
    val normalizedRawText = rawText?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedError = errorMessage?.trim()?.takeIf { it.isNotEmpty() }

    val title = normalizedRawText?.take(50)
        ?: normalizedError?.take(50)
        ?: "Keyboard recording"

    return KeyboardPersistencePlan(
        title = title,
        status = if (normalizedError == null) RecordingStatus.COMPLETED else RecordingStatus.FAILED,
        rawText = normalizedRawText,
        processedText = if (normalizedRawText == null) null else processedText,
        errorMessage = normalizedError
    )
}

internal suspend fun saveKeyboardRecording(
    filesDir: File,
    audioEncoder: AudioEncoder,
    recordingRepository: RecordingRepository,
    persistencePlan: KeyboardPersistencePlan,
    samples: FloatArray
): Recording? {
    try {
        withContext(NonCancellable) {
            val filename = "keyboard_${System.currentTimeMillis()}.m4a"
            val recordingsDir = File(filesDir, "recordings")
            recordingsDir.mkdirs()
            val outputPath = File(recordingsDir, filename).absolutePath

            val success = audioEncoder.encodeToM4a(samples, VoiceRecorder.SAMPLE_RATE, outputPath)
            if (!success) {
                Log.e(PERSISTENCE_TAG, "Failed to encode keyboard recording")
                try { File(outputPath).delete() } catch (_: Exception) {}
                return@withContext null
            }

            val durationMs = (samples.size * 1000L) / VoiceRecorder.SAMPLE_RATE

            val recording = Recording(
                id = UUID.randomUUID(),
                title = persistencePlan.title,
                audioPath = outputPath,
                status = persistencePlan.status,
                source = RecordingSource.KEYBOARD,
                profileId = null,
                durationMs = durationMs,
                errorMessage = persistencePlan.errorMessage
            )

            val rawText = persistencePlan.rawText
            if (rawText != null) {
                val transcript = Transcript(
                    id = UUID.randomUUID(),
                    recordingId = recording.id,
                    rawText = rawText,
                    processedText = persistencePlan.processedText
                )
                recordingRepository.createRecordingWithTranscript(recording, transcript)
            } else {
                recordingRepository.insert(recording)
            }

            Log.i(PERSISTENCE_TAG, "Saved keyboard recording: ${recording.id}")
            recording
        }
    } catch (e: Exception) {
        Log.e(PERSISTENCE_TAG, "Failed to save keyboard recording", e)
        null
    }
}
