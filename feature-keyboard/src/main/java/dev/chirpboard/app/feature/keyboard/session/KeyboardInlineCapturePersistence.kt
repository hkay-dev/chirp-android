package dev.chirpboard.app.feature.keyboard.session

import android.net.Uri
import android.util.Log
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import dev.chirpboard.app.feature.transcription.inline.buildCapturePersistencePlan
import dev.chirpboard.app.feature.transcription.inline.captureRecordingQualityPreset
import dev.chirpboard.app.feature.transcription.inline.saveCaptureRecording
import dev.chirpboard.app.feature.transcription.inline.shouldPersistCaptures
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "KeyboardCapturePersistence"

class KeyboardInlineCapturePersistence(
    private val persistenceScope: CoroutineScope,
    private val filesDirProvider: () -> File,
    private val audioEncoder: AudioEncoder,
    private val recordingRepository: RecordingRepository,
    private val keyboardPreferences: KeyboardPreferences,
    private val obsidianManager: ObsidianManager,
    private val obsidianPreferences: ObsidianPreferences,
) : InlineCapturePersistence {
    private var pendingSamples: FloatArray? = null

    fun prepareSamples(samples: FloatArray) {
        pendingSamples = samples.copyOf()
    }

    override suspend fun persist(
        samples: FloatArray?,
        rawText: String?,
        processedText: String?,
        errorMessage: String?,
    ) {
        val snapshot = samples ?: pendingSamples?.copyOf()
        if (snapshot == null) {
            pendingSamples = null
            return
        }

        if (!shouldPersistCaptures(keyboardPreferences)) {
            pendingSamples = null
            return
        }

        pendingSamples = null
        val plan = buildCapturePersistencePlan(rawText, processedText, errorMessage)
        val qualityPreset = captureRecordingQualityPreset(keyboardPreferences)

        persistenceScope.launch {
            val recording =
                saveCaptureRecording(
                    filesDir = filesDirProvider(),
                    audioEncoder = audioEncoder,
                    recordingRepository = recordingRepository,
                    plan = plan,
                    samples = snapshot,
                    recordingQualityPreset = qualityPreset,
                )
            if (recording != null && rawText != null) {
                exportToObsidianIfEnabled(recording, processedText ?: rawText)
            }
        }
    }

    override fun discardSamples() {
        pendingSamples = null
    }

    private suspend fun exportToObsidianIfEnabled(
        recording: dev.chirpboard.app.data.entity.Recording,
        transcript: String,
    ) {
        val autoExport = obsidianPreferences.autoExportEnabled.first()
        val vaultUriStr = obsidianPreferences.globalVaultUri.first()
        if (!autoExport || vaultUriStr == null) {
            return
        }
        try {
            obsidianManager.export(
                recording = recording,
                transcript = transcript,
                summary = null,
                vaultUri = Uri.parse(vaultUriStr),
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to auto-export to Obsidian", e)
        }
    }
}
