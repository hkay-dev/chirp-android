package dev.chirpboard.app.di

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.R
import dev.chirpboard.app.core.audio.recorder.AudioEncoder
import dev.chirpboard.app.core.export.TranscriptExportOutcome
import dev.chirpboard.app.core.export.TranscriptExportPort
import dev.chirpboard.app.core.export.TranscriptExportRecording
import dev.chirpboard.app.core.llm.ProcessingModeListItem
import dev.chirpboard.app.core.llm.ProcessingModePort
import dev.chirpboard.app.core.llm.RecordingTextEnhancementContext
import dev.chirpboard.app.core.llm.RecordingTextEnhancementPort
import dev.chirpboard.app.core.llm.RecordingTextEnrichment
import dev.chirpboard.app.core.llm.LlmRuntimeSnapshot
import dev.chirpboard.app.core.llm.ResolvedProcessingModeSnapshot
import dev.chirpboard.app.core.preferences.KeyboardPreferences
import dev.chirpboard.app.core.transcription.InlineAudioSource
import dev.chirpboard.app.core.transcription.InlineCapturePersistReason
import dev.chirpboard.app.core.transcription.InlineCapturePersistence
import dev.chirpboard.app.core.transcription.InlineTranscriptionPort
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.llm.TextProcessor
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.TranscriptLlmContext
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import dev.chirpboard.app.feature.llm.settings.LlmProvider
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import dev.chirpboard.app.feature.obsidian.R as ObsidianR
import dev.chirpboard.app.feature.transcription.inline.InlineTranscriptionCoordinatorImpl
import dev.chirpboard.app.feature.transcription.inline.buildCapturePersistencePlan
import dev.chirpboard.app.feature.transcription.inline.captureOutputFormat
import dev.chirpboard.app.feature.transcription.inline.captureRecordingQualityPreset
import dev.chirpboard.app.feature.transcription.inline.saveCaptureRecording
import dev.chirpboard.app.feature.transcription.inline.shouldPersistCaptures
import dev.chirpboard.app.feature.transcription.audio.discardTemporaryFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import dev.chirpboard.app.core.llm.ProcessingMode as CoreProcessingMode

@Module
@InstallIn(SingletonComponent::class)
abstract class AppFeaturePortsModule {
    companion object {
        @Provides
        @Singleton
        fun provideInlineCaptureAudioEncoder(): AudioEncoder = AudioEncoder()
    }

    @Binds
    @Singleton
    abstract fun bindInlineTranscriptionPort(
        impl: InlineTranscriptionCoordinatorImpl,
    ): InlineTranscriptionPort

    @Binds
    @Singleton
    abstract fun bindInlineCapturePersistence(
        impl: AppKeyboardInlineCapturePersistence,
    ): InlineCapturePersistence

    @Binds
    @Singleton
    abstract fun bindProcessingModePort(
        impl: LlmProcessingModePort,
    ): ProcessingModePort

    @Binds
    @Singleton
    abstract fun bindRecordingTextEnhancementPort(
        impl: LlmRecordingTextEnhancementPort,
    ): RecordingTextEnhancementPort

    @Binds
    @Singleton
    abstract fun bindRecordingTextEnrichment(
        impl: LlmRecordingTextEnhancementPort,
    ): RecordingTextEnrichment

    @Binds
    @Singleton
    abstract fun bindTranscriptExportPort(
        impl: ObsidianTranscriptExportPort,
    ): TranscriptExportPort
}

@Singleton
class LlmProcessingModePort
    @Inject
    constructor(
        private val repository: ProcessingModeRepository,
    ) : ProcessingModePort {
        override val currentMode: Flow<CoreProcessingMode> =
            repository.currentMode.map { mode ->
                CoreProcessingMode(
                    id = mode.id,
                    displayName = mode.displayName,
                )
            }

        override val selectableModes: Flow<List<ProcessingModeListItem>> =
            repository.selectableModes.map { modes ->
                modes.map { mode ->
                    ProcessingModeListItem(
                        id = mode.id,
                        name = mode.name,
                    )
                }
            }

        override suspend fun setModeById(modeId: String) {
            repository.setModeById(modeId)
        }
    }

@Singleton
class LlmRecordingTextEnhancementPort
    @Inject
    constructor(
        private val textProcessor: TextProcessor,
        private val modeRepository: ProcessingModeRepository,
        private val llmClient: LlmClient,
        private val llmPreferences: LlmPreferences,
    ) : RecordingTextEnhancementPort {
        override suspend fun isEnhancementAvailable(providerId: String?): Boolean {
            val provider = LlmProvider.fromId(providerId)
            return llmPreferences.getLlmEnabled() && llmPreferences.hasApiKeyFor(provider)
        }

        override suspend fun defaultAutoTitleEnabled(): Boolean = llmPreferences.getAutoTitle()

        override suspend fun defaultAutoSummaryEnabled(): Boolean = llmPreferences.getAutoSummary()

        override suspend fun runtimeSnapshot(): LlmRuntimeSnapshot {
            val provider = llmPreferences.getActiveProvider()
            return LlmRuntimeSnapshot(
                providerId = provider.id,
                modelId = llmPreferences.getModelFor(provider),
            )
        }

        override suspend fun resolveProcessingModeSnapshot(
            text: String,
            processingModeId: String,
        ): ResolvedProcessingModeSnapshot {
            val mode = modeRepository.resolveMode(processingModeId)
            return ResolvedProcessingModeSnapshot(
                id = mode.id,
                label = mode.displayName,
                type = mode::class.simpleName,
                prompt = textProcessor.resolvePromptForSnapshot(text, mode),
            )
        }

        override suspend fun process(
            text: String,
            processingModeId: String,
        ): Result<String> {
            val mode = modeRepository.resolveMode(processingModeId)
            return textProcessor.process(TranscriptLlmContext(text), mode)
        }

        override suspend fun processResolved(
            context: RecordingTextEnhancementContext,
            prompt: String?,
            fallbackProcessingModeId: String,
        ): Result<String> {
            val transcriptContext =
                TranscriptLlmContext(context.text, context.providerId, context.modelId)
            return if (prompt.isNullOrBlank()) {
                val mode = modeRepository.resolveMode(fallbackProcessingModeId)
                textProcessor.process(transcriptContext, mode)
            } else {
                llmClient.process(transcriptContext, prompt)
            }
        }

        override suspend fun generateTitle(transcript: String): Result<String> =
            llmClient.generateTitle(TranscriptLlmContext(transcript))

        override suspend fun generateSummary(transcript: String): Result<String> =
            llmClient.generateSummary(TranscriptLlmContext(transcript))

        override suspend fun generateTitle(context: RecordingTextEnhancementContext): Result<String> =
            llmClient.generateTitle(
                TranscriptLlmContext(context.text, context.providerId, context.modelId),
            )

        override suspend fun generateSummary(context: RecordingTextEnhancementContext): Result<String> =
            llmClient.generateSummary(
                TranscriptLlmContext(context.text, context.providerId, context.modelId),
            )
    }

@Singleton
class AppKeyboardInlineCapturePersistence
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val recordingRepository: RecordingRepository,
        private val keyboardPreferences: KeyboardPreferences,
        private val transcriptExportPort: TranscriptExportPort,
        private val audioEncoder: AudioEncoder,
    ) : InlineCapturePersistence {
        private var pendingAudioSource: InlineAudioSource? = null

        override fun prepareAudioSource(audioSource: InlineAudioSource) {
            discardSamples()
            pendingAudioSource = audioSource
        }

        override fun releasePendingAudioSource() {
            // Ownership handoff only: the detached pipeline persists or discards the
            // source itself, so the backing temp file must survive this call.
            pendingAudioSource = null
        }

        override suspend fun persist(
            samples: FloatArray?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ) {
            persistAudioSource(
                audioSource = samples?.let(InlineAudioSource::InMemory),
                rawText = rawText,
                processedText = processedText,
                errorMessage = errorMessage,
                reason = reason,
            )
        }

        override suspend fun persistAudioSource(
            audioSource: InlineAudioSource?,
            rawText: String?,
            processedText: String?,
            errorMessage: String?,
            reason: InlineCapturePersistReason,
        ) {
            val source = audioSource ?: pendingAudioSource ?: return
            if (audioSource == null || pendingAudioSource == source) {
                pendingAudioSource = null
            }

            withContext(NonCancellable + Dispatchers.IO) {
                var sourceHandled = false
                try {
                    // Rescue entries are error artifacts, not normal keyboard recordings:
                    // persist them even when saveKeyboardRecordings is off so the user can
                    // retrieve undelivered transcripts from the app. An explicit user cancel
                    // is NOT a rescue and must respect the preference.
                    val isRescueEntry = reason == InlineCapturePersistReason.RESCUE
                    if (!isRescueEntry && !shouldPersistCaptures(keyboardPreferences)) {
                        source.discardTemporaryFile()
                        sourceHandled = true
                        return@withContext
                    }

                    val plan = buildCapturePersistencePlan(rawText, processedText, errorMessage)
                    val recording =
                        saveCaptureRecording(
                            filesDir = context.filesDir,
                            audioEncoder = audioEncoder,
                            recordingRepository = recordingRepository,
                            plan = plan,
                            audioSource = source,
                            recordingQualityPreset = captureRecordingQualityPreset(keyboardPreferences),
                            outputFormat = captureOutputFormat(keyboardPreferences),
                        )
                    sourceHandled = true

                    val transcript = processedText ?: rawText
                    if (recording != null && transcript != null) {
                        transcriptExportPort
                            .exportIfEnabled(
                                recording =
                                    TranscriptExportRecording(
                                        title = recording.title,
                                        createdAtEpochMs = recording.createdAt.time,
                                        durationMs = recording.durationMs,
                                        sourceName = recording.source.name.lowercase(),
                                        id = recording.id,
                                    ),
                                transcript = transcript,
                                summary = null,
                            ).onFailure { error ->
                                Log.e(TAG, "Failed to auto-export inline capture", error)
                            }
                    }
                } finally {
                    if (!sourceHandled) {
                        source.discardTemporaryFile()
                    }
                }
            }
        }

        override fun discardSamples() {
            pendingAudioSource?.discardTemporaryFile()
            pendingAudioSource = null
        }

        override fun discardAudioSource(audioSource: InlineAudioSource) {
            if (pendingAudioSource == audioSource) {
                pendingAudioSource = null
            }
            audioSource.discardTemporaryFile()
        }

        private companion object {
            private const val TAG = "KeyboardCapturePersistence"
        }
    }

@Singleton
class ObsidianTranscriptExportPort
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val obsidianManager: ObsidianManager,
        private val obsidianPreferences: ObsidianPreferences,
        private val recordingRepository: RecordingRepository,
    ) : TranscriptExportPort {
        override suspend fun exportIfEnabled(
            recording: TranscriptExportRecording,
            transcript: String,
            summary: String?,
            tags: List<String>,
            requestedByProfile: Boolean,
        ): Result<TranscriptExportOutcome> {
            // Per-profile gate (DAT-002/PLH-5): export when the global toggle is on OR the
            // recording's profile opted in. The destination is always the global vault.
            if (!obsidianPreferences.autoExportEnabledValue() && !requestedByProfile) {
                return Result.success(TranscriptExportOutcome(exportedUri = null))
            }

            val vaultUri =
                obsidianPreferences.globalVaultUriValue()
                    ?: return Result.success(TranscriptExportOutcome(exportedUri = null))
            return obsidianManager
                .export(
                    recording = recording,
                    transcript = transcript,
                    summary = summary,
                    vaultUri = Uri.parse(vaultUri),
                    tags = tags,
                ).fold(
                    onSuccess = { exportedUri ->
                        recordExportBookkeeping(recording, exportedUri)
                        clearExportFailureNotification()
                        Result.success(TranscriptExportOutcome(exportedUri = exportedUri.toString()))
                    },
                    onFailure = { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        Log.e(TAG, "Obsidian auto-export failed", error)
                        showExportFailureNotification(error)
                        Result.failure(error)
                    },
                )
        }

        /** Stamps lastExportedPath/lastExportedAt; failures here never fail the export. */
        private suspend fun recordExportBookkeeping(
            recording: TranscriptExportRecording,
            exportedUri: Uri,
        ) {
            val recordingId = recording.id ?: return
            try {
                recordingRepository.updateExportInfo(recordingId, exportedUri.toString())
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record export bookkeeping for $recordingId", e)
            }
        }

        /**
         * ERR-6: auto-export failures must not be logcat-only. A single fixed-id
         * notification (no per-recording spam) tells the user exports are failing and
         * taps through into the app. Cleared automatically on the next successful export.
         */
        private fun showExportFailureNotification(error: Throwable) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (notificationManager.getNotificationChannel(EXPORT_ERROR_CHANNEL_ID) == null) {
                notificationManager.createNotificationChannel(
                    NotificationChannel(
                        EXPORT_ERROR_CHANNEL_ID,
                        context.getString(ObsidianR.string.obsidian_export_error_channel_name),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }

            val isAccessIssue = error is SecurityException || error is IllegalArgumentException
            val message =
                if (isAccessIssue) {
                    context.getString(ObsidianR.string.obsidian_export_failed_vault_access)
                } else {
                    context.getString(ObsidianR.string.obsidian_export_failed_generic)
                }
            val contentIntent =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
                    PendingIntent.getActivity(
                        context,
                        EXPORT_ERROR_NOTIFICATION_ID,
                        launch,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                }

            val notification =
                NotificationCompat
                    .Builder(context, EXPORT_ERROR_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_chirp_parakeet)
                    .setContentTitle(context.getString(ObsidianR.string.obsidian_export_failed_title))
                    .setContentText(message)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                    .setAutoCancel(true)
                    .apply { contentIntent?.let(::setContentIntent) }
                    .build()
            notificationManager.notify(EXPORT_ERROR_NOTIFICATION_ID, notification)
        }

        private fun clearExportFailureNotification() {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(EXPORT_ERROR_NOTIFICATION_ID)
        }

        private companion object {
            private const val TAG = "ObsidianExportPort"
            private const val EXPORT_ERROR_CHANNEL_ID = "obsidian_export_errors"
            private const val EXPORT_ERROR_NOTIFICATION_ID = 2101
        }
    }

private suspend fun ObsidianPreferences.autoExportEnabledValue(): Boolean =
    autoExportEnabled.firstValue()

private suspend fun ObsidianPreferences.globalVaultUriValue(): String? =
    globalVaultUri.firstValue()

private suspend fun <T> Flow<T>.firstValue(): T = first()
