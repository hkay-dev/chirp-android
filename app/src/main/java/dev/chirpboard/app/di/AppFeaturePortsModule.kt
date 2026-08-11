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
import dev.chirpboard.app.core.llm.GOOGLE_CLOUD_VERTEX_PROVIDER_ID
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
import dev.chirpboard.app.cloud.VertexTextGenerationClient
import dev.chirpboard.app.feature.llm.client.LlmClient
import dev.chirpboard.app.feature.llm.client.TranscriptLlmContext
import dev.chirpboard.app.feature.llm.repository.ProcessingModeRepository
import dev.chirpboard.app.feature.llm.settings.LlmProvider
import dev.chirpboard.app.feature.llm.settings.LlmPreferences
import dev.chirpboard.app.feature.obsidian.ObsidianManager
import dev.chirpboard.app.feature.obsidian.ObsidianVaultAccessException
import dev.chirpboard.app.feature.obsidian.settings.ObsidianPreferences
import dev.chirpboard.app.feature.obsidian.R as ObsidianR
import dev.chirpboard.app.feature.transcription.inline.InlineTranscriptionCoordinatorImpl
import dev.chirpboard.app.feature.transcription.inline.buildCapturePersistencePlan
import dev.chirpboard.app.feature.transcription.inline.captureOutputFormat
import dev.chirpboard.app.feature.transcription.inline.captureRecordingQualityPreset
import dev.chirpboard.app.feature.transcription.inline.saveCaptureRecording
import dev.chirpboard.app.feature.transcription.inline.COMMIT_REFUSED_MESSAGE
import dev.chirpboard.app.feature.transcription.TerminalRecordingNotificationDelivery
import dev.chirpboard.app.feature.transcription.inline.shouldPersistCaptures
import dev.chirpboard.app.feature.transcription.audio.discardTemporaryFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.Properties
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
        private val vertexTextGenerationClient: VertexTextGenerationClient,
    ) : RecordingTextEnhancementPort {
        override suspend fun isEnhancementEnabled(): Boolean = llmPreferences.getLlmEnabled()

        override suspend fun isEnhancementAvailable(providerId: String?): Boolean {
            if (providerId == GOOGLE_CLOUD_VERTEX_PROVIDER_ID) {
                return llmPreferences.getLlmEnabled() && vertexTextGenerationClient.isConfigured()
            }
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
            if (context.providerId == GOOGLE_CLOUD_VERTEX_PROVIDER_ID) {
                vertexAvailabilityFailure()?.let { return Result.failure(it) }
                val resolvedPrompt =
                    prompt ?: run {
                        val mode = modeRepository.resolveMode(fallbackProcessingModeId)
                        textProcessor.resolvePromptForSnapshot(context.text, mode)
                    } ?: return Result.failure(IllegalStateException("Processing prompt is unavailable"))
                return vertexTextGenerationClient.generate(
                    text = context.text,
                    prompt = resolvedPrompt,
                    model = context.modelId,
                    recordingId = context.recordingId,
                )
            }
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

        override suspend fun generateTitle(context: RecordingTextEnhancementContext): Result<String> {
            if (context.providerId == GOOGLE_CLOUD_VERTEX_PROVIDER_ID) {
                vertexAvailabilityFailure()?.let { return Result.failure(it) }
                return vertexTextGenerationClient.generate(
                    text = context.text,
                    prompt = VERTEX_TITLE_PROMPT,
                    model = context.modelId,
                    recordingId = context.recordingId,
                )
            }
            return llmClient.generateTitle(
                TranscriptLlmContext(context.text, context.providerId, context.modelId),
            )
        }

        override suspend fun generateSummary(context: RecordingTextEnhancementContext): Result<String> {
            if (context.providerId == GOOGLE_CLOUD_VERTEX_PROVIDER_ID) {
                vertexAvailabilityFailure()?.let { return Result.failure(it) }
                return vertexTextGenerationClient.generate(
                    text = context.text,
                    prompt = VERTEX_SUMMARY_PROMPT,
                    model = context.modelId,
                    recordingId = context.recordingId,
                )
            }
            return llmClient.generateSummary(
                TranscriptLlmContext(context.text, context.providerId, context.modelId),
            )
        }

        private suspend fun vertexAvailabilityFailure(): IllegalStateException? =
            when {
                !llmPreferences.getLlmEnabled() -> IllegalStateException("AI processing is turned off")
                !vertexTextGenerationClient.isConfigured() -> IllegalStateException("Google Cloud AI is not configured")
                else -> null
            }

        private companion object {
            const val VERTEX_TITLE_PROMPT =
                "Generate a brief, descriptive title of 5 to 8 words. Return only the title text."
            const val VERTEX_SUMMARY_PROMPT =
                "Summarize the main points and key information in 2 to 3 sentences. Return only the summary text."
        }
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
        private val terminalNotificationDelivery: dagger.Lazy<TerminalRecordingNotificationDelivery>,
    ) : InlineCapturePersistence {
        // Written from the IME thread and read-and-cleared from IO/NonCancellable coroutines,
        // so every access holds checkpointLock; an unguarded take could hand the same source
        // to two persists or drop one entirely.
        private var pendingAudioSource: InlineAudioSource? = null
        private val checkpointLock = Any()
        private val terminalCheckpointPaths = linkedSetOf<String>()

        override suspend fun checkpointAudioSource(
            audioSource: InlineAudioSource,
            trustedSampleCount: Long,
            partialTranscript: String?,
            estimatedGapMs: Long?,
        ): Boolean {
            val fileSource = audioSource as? InlineAudioSource.PcmFloatFile ?: return false
            val sourceFile = File(fileSource.path)
            if (trustedSampleCount <= 0L ||
                trustedSampleCount > fileSource.sampleCount ||
                !sourceFile.isFile ||
                trustedSampleCount > sourceFile.length() / java.lang.Float.BYTES
            ) {
                return false
            }
            return withContext(NonCancellable + Dispatchers.IO) {
                synchronized(checkpointLock) {
                    if (checkpointKey(fileSource) in terminalCheckpointPaths) return@synchronized false
                    runCatching {
                        val checkpoint = checkpointFile(fileSource)
                        val directory = checkNotNull(checkpoint.parentFile)
                        directory.mkdirs()
                        val partial = File(directory, "${checkpoint.name}.partial")
                        val properties =
                            Properties().apply {
                                setProperty("version", CHECKPOINT_VERSION)
                                setProperty("audioPath", sourceFile.canonicalPath)
                                setProperty("sampleRate", fileSource.sampleRate.toString())
                                setProperty("trustedSampleCount", trustedSampleCount.toString())
                                setProperty("estimatedGapMs", estimatedGapMs?.toString().orEmpty())
                                setProperty("partialTranscript", partialTranscript.orEmpty())
                                setProperty("updatedAtEpochMs", System.currentTimeMillis().toString())
                            }
                        FileOutputStream(partial).use { output ->
                            properties.store(output, null)
                            output.fd.sync()
                        }
                        try {
                            Files.move(
                                partial.toPath(),
                                checkpoint.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(
                                partial.toPath(),
                                checkpoint.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                        }
                        syncDirectory(directory)
                        true
                    }.onFailure { error ->
                        Log.e(TAG, "Could not write keyboard capture checkpoint", error)
                    }.getOrDefault(false)
                }
            }
        }

        override suspend fun clearCheckpoint(audioSource: InlineAudioSource) {
            val fileSource = audioSource as? InlineAudioSource.PcmFloatFile ?: return
            withContext(NonCancellable + Dispatchers.IO) {
                synchronized(checkpointLock) {
                    markCheckpointTerminalLocked(checkpointKey(fileSource))
                    deleteCheckpointFilesLocked(fileSource)
                }
            }
        }

        override suspend fun recoverCheckpoints(): Int =
            withContext(NonCancellable + Dispatchers.IO) {
                val captureDirectory = File(context.cacheDir, "keyboard-capture")
                var recovered = 0
                captureDirectory
                    .listFiles { file -> file.isFile && file.name.endsWith("$CHECKPOINT_SUFFIX.partial") }
                    .orEmpty()
                    .forEach { partial ->
                        if (!partial.delete()) Log.w(TAG, "Could not remove an incomplete keyboard checkpoint")
                    }
                captureDirectory
                    .listFiles { file -> file.isFile && file.name.endsWith(CHECKPOINT_SUFFIX) }
                    .orEmpty()
                    .forEach { checkpoint ->
                        val recoveredSource =
                            runCatching {
                                val properties = Properties().apply { checkpoint.inputStream().use(::load) }
                                require(properties.getProperty("version") == CHECKPOINT_VERSION)
                                val audio = File(requireNotNull(properties.getProperty("audioPath"))).canonicalFile
                                require(audio.parentFile == captureDirectory.canonicalFile)
                                require(audio.isFile)
                                val sampleRate = requireNotNull(properties.getProperty("sampleRate")).toInt()
                                val trustedSamples =
                                    requireNotNull(properties.getProperty("trustedSampleCount")).toLong()
                                require(sampleRate in MIN_CHECKPOINT_SAMPLE_RATE..MAX_CHECKPOINT_SAMPLE_RATE)
                                val completeSamples = audio.length() / java.lang.Float.BYTES
                                require(trustedSamples > 0L && trustedSamples <= completeSamples)
                                // The checkpoint proves ownership of this exact cache file. Once
                                // the owning process is dead, every later complete float written
                                // to that file is also recoverable. Use that crash-truncated tail
                                // rather than throwing away everything recorded after the first
                                // checkpoint. A partial final float is naturally excluded.
                                RecoveredCheckpoint(
                                    source =
                                        InlineAudioSource.PcmFloatFile(
                                            path = audio.absolutePath,
                                            sampleCount = completeSamples,
                                            sampleRate = sampleRate,
                                        ),
                                    rawText = properties.getProperty("partialTranscript")?.takeIf(String::isNotBlank),
                                )
                            }.getOrElse { error ->
                                Log.e(TAG, "Discarding an invalid keyboard capture checkpoint", error)
                                synchronized(checkpointLock) {
                                    markCheckpointTerminalLocked(
                                        runCatching { checkpoint.canonicalPath }
                                            .getOrDefault(checkpoint.absolutePath)
                                            .removeSuffix(CHECKPOINT_SUFFIX),
                                    )
                                    checkpoint.delete()
                                }
                                return@forEach
                            }
                        runCatching {
                            persistAudioSource(
                                audioSource = recoveredSource.source,
                                rawText = recoveredSource.rawText,
                                processedText = null,
                                errorMessage = CHECKPOINT_RECOVERY_MESSAGE,
                                reason = InlineCapturePersistReason.RESCUE,
                            )
                            recovered += 1
                        }.onFailure { error ->
                            Log.e(TAG, "Could not recover keyboard capture checkpoint", error)
                        }
                    }
                recovered
            }

        override fun prepareAudioSource(audioSource: InlineAudioSource) {
            discardSamples()
            synchronized(checkpointLock) {
                pendingAudioSource = audioSource
            }
        }

        override fun releasePendingAudioSource() {
            // Ownership handoff only: the detached pipeline persists or discards the
            // source itself, so the backing temp file must survive this call.
            synchronized(checkpointLock) {
                pendingAudioSource = null
            }
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
            val source =
                synchronized(checkpointLock) {
                    val chosen = audioSource ?: pendingAudioSource ?: return
                    if (audioSource == null || pendingAudioSource == chosen) {
                        pendingAudioSource = null
                    }
                    chosen
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
                        clearCheckpoint(source)
                        source.discardTemporaryFile()
                        sourceHandled = true
                        return@withContext
                    }

                    val plan = buildCapturePersistencePlan(rawText, processedText, errorMessage)
                    val notifyUndeliveredResult =
                        rawText != null && errorMessage == COMMIT_REFUSED_MESSAGE
                    val recording =
                        saveCaptureRecording(
                            filesDir = context.filesDir,
                            audioEncoder = audioEncoder,
                            recordingRepository = recordingRepository,
                            plan = plan,
                            audioSource = source,
                            recordingQualityPreset = captureRecordingQualityPreset(keyboardPreferences),
                            outputFormat = captureOutputFormat(keyboardPreferences),
                            allowTextOnlyFallback = !isRescueEntry,
                            terminalNotificationPending = notifyUndeliveredResult,
                        )
                    if (recording == null) {
                        if (isRescueEntry) {
                            throw IOException("Could not save rescued keyboard audio")
                        }
                        return@withContext
                    }
                    sourceHandled = true
                    clearCheckpoint(source)
                    if (notifyUndeliveredResult) {
                        terminalNotificationDelivery.get().deliverRequested(recording.id)
                    }

                    val transcript = processedText ?: rawText
                    if (transcript != null) {
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
                    if (!sourceHandled && reason != InlineCapturePersistReason.RESCUE) {
                        source.discardTemporaryFile()
                    }
                }
            }
        }

        override fun discardSamples() {
            val source =
                synchronized(checkpointLock) {
                    pendingAudioSource.also { pendingAudioSource = null }
                }
            source?.let {
                discardCheckpointSynchronously(it)
                it.discardTemporaryFile()
            }
        }

        override fun discardAudioSource(audioSource: InlineAudioSource) {
            synchronized(checkpointLock) {
                if (pendingAudioSource == audioSource) {
                    pendingAudioSource = null
                }
            }
            discardCheckpointSynchronously(audioSource)
            audioSource.discardTemporaryFile()
        }

        private companion object {
            private const val TAG = "KeyboardCapturePersistence"
            private const val CHECKPOINT_SUFFIX = ".chirp-checkpoint"
            private const val CHECKPOINT_VERSION = "1"
            private const val MIN_CHECKPOINT_SAMPLE_RATE = 8_000
            private const val MAX_CHECKPOINT_SAMPLE_RATE = 192_000
            private const val MAX_TERMINAL_CHECKPOINT_PATHS = 256
            private const val CHECKPOINT_RECOVERY_MESSAGE =
                "Dictation was interrupted; recovered the latest trusted audio and transcript checkpoint"
        }

        private fun checkpointFile(source: InlineAudioSource.PcmFloatFile): File =
            File("${source.path}$CHECKPOINT_SUFFIX")

        private fun checkpointKey(source: InlineAudioSource.PcmFloatFile): String =
            runCatching { File(source.path).canonicalPath }.getOrDefault(File(source.path).absolutePath)

        private fun discardCheckpointSynchronously(source: InlineAudioSource) {
            val fileSource = source as? InlineAudioSource.PcmFloatFile ?: return
            synchronized(checkpointLock) {
                markCheckpointTerminalLocked(checkpointKey(fileSource))
                deleteCheckpointFilesLocked(fileSource)
            }
        }

        private fun markCheckpointTerminalLocked(path: String) {
            terminalCheckpointPaths.remove(path)
            terminalCheckpointPaths += path
            while (terminalCheckpointPaths.size > MAX_TERMINAL_CHECKPOINT_PATHS) {
                terminalCheckpointPaths.remove(terminalCheckpointPaths.first())
            }
        }

        private fun deleteCheckpointFilesLocked(source: InlineAudioSource.PcmFloatFile) {
            val checkpoint = checkpointFile(source)
            if (checkpoint.exists() && !checkpoint.delete()) {
                Log.w(TAG, "Could not clear keyboard capture checkpoint")
            }
            File(checkpoint.parentFile, "${checkpoint.name}.partial").delete()
        }

        private data class RecoveredCheckpoint(
            val source: InlineAudioSource.PcmFloatFile,
            val rawText: String?,
        )
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

            val isAccessIssue =
                error is SecurityException ||
                    error is IllegalArgumentException ||
                    error is ObsidianVaultAccessException
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
                    .setSmallIcon(R.drawable.ic_notif_export_error)
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
