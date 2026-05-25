package dev.chirpboard.app.feature.transcription.inline

import android.util.Log
import dev.chirpboard.app.core.audio.RecordingOutputFormat
import dev.chirpboard.app.core.audio.RecordingQualityPreset
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.audio.recorder.VoiceRecorder
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.entity.Transcript
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private const val TAG = "KeyboardCapturePersistence"

data class CapturePersistencePlan(
    val title: String,
    val status: RecordingStatus,
    val rawText: String?,
    val processedText: String?,
    val errorMessage: String?,
)

fun buildCapturePersistencePlan(
    rawText: String?,
    processedText: String?,
    errorMessage: String?,
): CapturePersistencePlan {
    val normalizedRawText = rawText?.trim()?.takeIf { it.isNotEmpty() }
    val normalizedError = errorMessage?.trim()?.takeIf { it.isNotEmpty() }

    val title =
        normalizedRawText?.take(50)
            ?: normalizedError?.take(50)
            ?: "Keyboard recording"

    return CapturePersistencePlan(
        title = title,
        status = if (normalizedError == null) RecordingStatus.COMPLETED else RecordingStatus.FAILED,
        rawText = normalizedRawText,
        processedText = if (normalizedRawText == null) null else processedText,
        errorMessage = normalizedError,
    )
}

suspend fun saveCaptureRecording(
    filesDir: File,
    audioEncoder: AudioEncoder,
    recordingRepository: RecordingRepository,
    plan: CapturePersistencePlan,
    samples: FloatArray,
    recordingQualityPreset: RecordingQualityPreset,
    outputFormat: RecordingOutputFormat,
): Recording? {
    return try {
        withContext(NonCancellable) {
            val filename = "keyboard_${System.currentTimeMillis()}${outputFormat.fileExtension}"
            val recordingsDir = File(filesDir, "recordings")
            recordingsDir.mkdirs()
            val outputPath = File(recordingsDir, filename).absolutePath

            val success =
                audioEncoder.encode(
                    samples = samples,
                    sampleRate = VoiceRecorder.SAMPLE_RATE,
                    outputPath = outputPath,
                    format = outputFormat,
                    config = recordingQualityPreset.keyboardRecordingConfig,
                )
            if (!success) {
                Log.e(TAG, "Failed to encode keyboard recording")
                runCatching { File(outputPath).delete() }
                return@withContext null
            }

            val durationMs = (samples.size * 1000L) / VoiceRecorder.SAMPLE_RATE
            val recording =
                Recording(
                    id = UUID.randomUUID(),
                    title = plan.title,
                    audioPath = outputPath,
                    status = plan.status,
                    source = RecordingSource.KEYBOARD,
                    profileId = null,
                    durationMs = durationMs,
                    errorMessage = plan.errorMessage,
                )

            val rawText = plan.rawText
            if (rawText != null) {
                val transcript =
                    Transcript(
                        id = UUID.randomUUID(),
                        recordingId = recording.id,
                        rawText = rawText,
                        processedText = plan.processedText,
                    )
                recordingRepository.createRecordingWithTranscript(recording, transcript)
            } else {
                recordingRepository.insert(recording)
            }

            Log.i(TAG, "Saved keyboard recording: ${recording.id}")
            recording
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.e(TAG, "Failed to save keyboard recording", e)
        null
    }
}

suspend fun shouldPersistCaptures(keyboardPreferences: KeyboardPreferences): Boolean =
    keyboardPreferences.saveKeyboardRecordings.first()

suspend fun captureRecordingQualityPreset(keyboardPreferences: KeyboardPreferences): RecordingQualityPreset =
    keyboardPreferences.recordingQualityPreset.first()

suspend fun captureOutputFormat(keyboardPreferences: KeyboardPreferences): RecordingOutputFormat =
    keyboardPreferences.outputFormat.first()
