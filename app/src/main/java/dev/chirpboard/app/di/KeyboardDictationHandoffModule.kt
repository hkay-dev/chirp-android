package dev.chirpboard.app.di

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.chirpboard.app.core.llm.GOOGLE_CLOUD_VERTEX_PROVIDER_ID
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoff
import dev.chirpboard.app.core.transcription.KeyboardDictationLiveCapture
import dev.chirpboard.app.core.transcription.KeyboardDictationLiveCaptureRequest
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoffRequest
import dev.chirpboard.app.core.transcription.KeyboardDictationHandoffResult
import dev.chirpboard.app.core.transcription.TranscriptionEngine
import dev.chirpboard.app.core.transcription.TranscriptionRecovery
import dev.chirpboard.app.core.transcription.TranscriptionRoutingStore
import dev.chirpboard.app.core.util.DurableFiles
import dev.chirpboard.app.data.entity.Recording
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.model.RecordingStatus
import dev.chirpboard.app.data.repository.RecordingRepository
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Module
@InstallIn(SingletonComponent::class)
abstract class KeyboardDictationHandoffModule {
    @Binds
    @Singleton
    abstract fun bindKeyboardDictationHandoff(
        impl: AppKeyboardDictationHandoff,
    ): KeyboardDictationHandoff
}

@Singleton
class AppKeyboardDictationHandoff
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val recordingRepository: RecordingRepository,
        private val transcriptionRecovery: TranscriptionRecovery,
        private val routingStore: TranscriptionRoutingStore,
    ) : KeyboardDictationHandoff {
        private val gson = Gson()
        private val handoffMutex = Mutex()
        private val processInstanceId = UUID.randomUUID().toString()

        override suspend fun beginLiveCapture(
            request: KeyboardDictationLiveCaptureRequest,
        ): KeyboardDictationLiveCapture? {
            if (request.suppressHistory) {
                return KeyboardDictationLiveCapture(
                    transcriptionEngine = TranscriptionEngine.LOCAL_PARAKEET,
                )
            }
            val engine = request.transcriptionEngine ?: routingStore.getSelectedEngine()
            if (engine != TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) {
                return KeyboardDictationLiveCapture(transcriptionEngine = engine)
            }
            val recordingId = UUID.randomUUID()
            val audio = File(context.filesDir, RECORDINGS_DIRECTORY).resolve("keyboard_${recordingId}$RAW_PCM_SUFFIX")
            return KeyboardDictationLiveCapture(
                recordingId = recordingId,
                audioPath = audio.absolutePath,
                transcriptionEngine = engine,
                llmEnabled = request.llmEnabled,
                processingModeId = request.processingModeId,
                notifyWhenReady = request.notifyWhenReady,
            )
        }

        override suspend fun markLiveCaptureStarted(capture: KeyboardDictationLiveCapture) {
            if (capture.transcriptionEngine != TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) return
            withContext(NonCancellable + Dispatchers.IO) {
                handoffMutex.withLock { markLiveCaptureStartedOnIo(capture) }
            }
        }

        private fun markLiveCaptureStartedOnIo(capture: KeyboardDictationLiveCapture) {
            val recordingId = requireNotNull(capture.recordingId)
            val audio = File(requireNotNull(capture.audioPath))
            require(isValidLiveCapturePath(recordingId, audio)) { "Invalid live keyboard capture path" }
            val pending =
                PendingKeyboardLiveCapture(
                    recordingId = recordingId.toString(),
                    audioPath = audio.absolutePath,
                    ownerProcessId = processInstanceId,
                    state = LIVE_CAPTURE_STATE_RECORDING,
                    createdAtEpochMs = System.currentTimeMillis(),
                    sampleRate = dev.chirpboard.app.core.audio.recorder.VoiceRecorder.SAMPLE_RATE,
                    llmEnabled = capture.llmEnabled,
                    processingModeId = capture.processingModeId,
                    notifyWhenReady = capture.notifyWhenReady,
                )
            writePendingLiveCapture(pending)
        }

        override suspend fun abandonLiveCapture(capture: KeyboardDictationLiveCapture) {
            if (capture.transcriptionEngine != TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) return
            withContext(NonCancellable + Dispatchers.IO) {
                handoffMutex.withLock {
                    val directory = recordingsDirectory()
                    val recordingId = requireNotNull(capture.recordingId)
                    val audioPath = requireNotNull(capture.audioPath)
                    val expectedAudio = directory.resolve("keyboard_${recordingId}$RAW_PCM_SUFFIX")
                    require(File(audioPath).canonicalFile == expectedAudio.canonicalFile) {
                        "Invalid live keyboard capture path"
                    }
                    val marker = liveCaptureMarkerFile(directory, recordingId)
                    val audioDeleted = !expectedAudio.exists() || expectedAudio.delete()
                    val markerDeleted = !marker.exists() || marker.delete()
                    val partialDeleted =
                        !File(directory, "${marker.name}.partial").exists() ||
                            File(directory, "${marker.name}.partial").delete()
                    DurableFiles.syncDirectory(directory)
                    if (!audioDeleted || !markerDeleted || !partialDeleted) {
                        throw IOException("Could not discard the live keyboard capture")
                    }
                }
            }
        }

        override suspend fun releaseLiveCaptureForInline(capture: KeyboardDictationLiveCapture) {
            if (capture.transcriptionEngine != TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3) return
            withContext(NonCancellable + Dispatchers.IO) {
                handoffMutex.withLock {
                    val directory = recordingsDirectory()
                    val recordingId = requireNotNull(capture.recordingId)
                    val audioPath = requireNotNull(capture.audioPath)
                    val expectedAudio = directory.resolve("keyboard_${recordingId}$RAW_PCM_SUFFIX")
                    require(File(audioPath).canonicalFile == expectedAudio.canonicalFile) {
                        "Invalid live keyboard capture path"
                    }
                    val marker = liveCaptureMarkerFile(directory, recordingId)
                    val partial = File(directory, "${marker.name}.partial")
                    val markerDeleted = !marker.exists() || marker.delete()
                    val partialDeleted = !partial.exists() || partial.delete()
                    DurableFiles.syncDirectory(directory)
                    if (!markerDeleted || !partialDeleted) {
                        throw IOException("Could not release the live keyboard capture")
                    }
                }
            }
        }

        override suspend fun handoff(
            request: KeyboardDictationHandoffRequest,
        ): KeyboardDictationHandoffResult =
            withContext(NonCancellable + Dispatchers.IO) {
                handoffMutex.withLock { handoffOnIo(request) }
            }

        private suspend fun handoffOnIo(
            request: KeyboardDictationHandoffRequest,
        ): KeyboardDictationHandoffResult {
            val source = File(request.audioSource.path)
            if (!source.isFile) {
                return KeyboardDictationHandoffResult.Failed(
                    message = "The stopped dictation audio could not be found",
                    sourceAvailableForInlineFallback = false,
                )
            }

            val engine =
                request.transcriptionEngine ?: runCatching { routingStore.getSelectedEngine() }
                    .onFailure { Log.w(TAG, "Could not read transcription routing; using the local engine", it) }
                    .getOrDefault(TranscriptionEngine.LOCAL_PARAKEET)
            if (engine != TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3 && !request.forceDurable) {
                return KeyboardDictationHandoffResult.InlineLocal
            }

            findPendingLiveCapture(source)?.let { (marker, pending) ->
                return handoffLiveCaptureOnIo(marker, pending, source, request)
            }
            parseLiveCaptureRecordingId(source)?.takeIf {
                engine == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3 && isValidLiveCapturePath(it, source)
            }?.let { recordingId ->
                val pending =
                    PendingKeyboardLiveCapture(
                        recordingId = recordingId.toString(),
                        audioPath = source.absolutePath,
                        ownerProcessId = processInstanceId,
                        state = LIVE_CAPTURE_STATE_RECORDING,
                        createdAtEpochMs = source.lastModified(),
                        sampleRate = request.audioSource.sampleRate,
                        llmEnabled = request.llmEnabled,
                        processingModeId = request.processingModeId,
                        notifyWhenReady = request.notifyWhenReady,
                    )
                val marker = writePendingLiveCapture(pending)
                return handoffLiveCaptureOnIo(marker, pending, source, request)
            }

            val recordingId = UUID.randomUUID()
            val destination =
                File(context.filesDir, RECORDINGS_DIRECTORY)
                    .resolve("keyboard_${recordingId}$RAW_PCM_SUFFIX")
            val durationMs =
                (request.audioSource.sampleCount * 1000L) / request.audioSource.sampleRate
            val pendingHandoff =
                PendingKeyboardHandoff(
                    recordingId = recordingId.toString(),
                    sourcePath = source.absolutePath,
                    destinationPath = destination.absolutePath,
                    durationMs = durationMs,
                    llmEnabled = request.llmEnabled,
                    processingModeId = request.processingModeId,
                    notifyWhenReady = request.notifyWhenReady,
                    transcriptionEngineId = engine.id,
                )
            val handoffMarker =
                try {
                    writePendingHandoff(pendingHandoff)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Could not journal stopped keyboard audio", e)
                    return KeyboardDictationHandoffResult.Failed(
                        message = "Could not save the stopped dictation",
                        sourceAvailableForInlineFallback = source.isFile,
                    )
                }

            try {
                moveCaptureToDurableStorage(source, destination)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Could not move stopped keyboard audio into durable storage", e)
                val sourceAvailable = source.isFile
                if (sourceAvailable) {
                    deleteHandoffMarker(handoffMarker)
                }
                return KeyboardDictationHandoffResult.Failed(
                    message = "Could not save the stopped dictation",
                    sourceAvailableForInlineFallback = sourceAvailable,
                )
            }

            val recording = pendingHandoff.toRecording(recordingId, destination)

            try {
                recordingRepository.insert(recording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Keyboard audio moved, but its recording row could not be saved", e)
                val committedRow =
                    runCatching { recordingRepository.getRecording(recordingId) }
                        .getOrNull()
                        ?.takeIf { it.audioPath == destination.absolutePath }
                if (committedRow != null) {
                    deleteHandoffMarker(handoffMarker)
                    return enqueueDurableRecording(recordingId)
                }
                val sourceRestored = restoreSourceAfterInsertFailure(source, destination)
                if (sourceRestored) {
                    deleteHandoffMarker(handoffMarker)
                }
                return KeyboardDictationHandoffResult.Failed(
                    message =
                        if (sourceRestored) {
                            "Could not add the dictation to the cloud queue"
                        } else {
                            "The dictation audio was saved, but it could not be added to the queue"
                        },
                    sourceAvailableForInlineFallback = sourceRestored,
                )
            }

            deleteHandoffMarker(handoffMarker)
            return enqueueDurableRecording(recordingId)
        }

        private suspend fun handoffLiveCaptureOnIo(
            marker: File,
            pending: PendingKeyboardLiveCapture,
            source: File,
            request: KeyboardDictationHandoffRequest,
        ): KeyboardDictationHandoffResult {
            val recordingId = UUID.fromString(pending.recordingId)
            val ready =
                pending.copy(
                    state = LIVE_CAPTURE_STATE_READY,
                    durationMs =
                        (request.audioSource.sampleCount * 1000L) /
                            request.audioSource.sampleRate,
                )
            writePendingLiveCapture(ready)
            FileInputStream(source).use { input -> input.fd.sync() }
            val recording = ready.toRecording(recordingId, source)

            try {
                recordingRepository.insert(recording)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Live keyboard audio was saved, but its recording row could not be saved", e)
                val committedRow =
                    runCatching { recordingRepository.getRecording(recordingId) }
                        .getOrNull()
                        ?.takeIf { it.audioPath == source.absolutePath }
                if (committedRow == null) {
                    return KeyboardDictationHandoffResult.Failed(
                        message = "The dictation audio was saved, but it could not be added to the queue",
                        sourceAvailableForInlineFallback = false,
                    )
                }
            }

            deleteHandoffMarker(marker)
            return enqueueDurableRecording(recordingId)
        }

        private suspend fun enqueueDurableRecording(
            recordingId: UUID,
        ): KeyboardDictationHandoffResult {
            try {
                transcriptionRecovery.enqueue(
                    recordingId = recordingId,
                    correlationId = "keyboard-$recordingId",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Keyboard recording saved, but queue enqueue failed", e)
                runCatching {
                    transcriptionRecovery.markPendingForQueueRecovery(
                        recordingId = recordingId,
                        reason = QUEUE_RECOVERY_REASON,
                        cause = e,
                    )
                }.onFailure { recoveryError ->
                    Log.e(TAG, "Could not mark keyboard recording for queue recovery", recoveryError)
                }
            }

            return KeyboardDictationHandoffResult.Durable(recordingId)
        }

        override suspend fun discard(recordingId: UUID): Boolean =
            withContext(NonCancellable + Dispatchers.IO) {
                handoffMutex.withLock {
                    val original = recordingRepository.getRecording(recordingId) ?: return@withLock false
                    runCatching { transcriptionRecovery.cancelProcessing(recordingId) }
                        .onFailure { Log.w(TAG, "Could not cancel queued keyboard dictation", it) }

                    // Cancellation can race the cloud worker's raw-PCM to WAV swap. Re-read the
                    // row once WorkManager has been cancelled, delete the row first, and clean
                    // both observed paths so no replacement WAV can be stranded off-screen.
                    val winning = recordingRepository.getRecording(recordingId) ?: return@withLock true
                    recordingRepository.deleteById(recordingId)
                    var audioDeleted = true
                    setOf(original.audioPath, winning.audioPath).forEach { audioPath ->
                        val audio = File(audioPath)
                        if (audio.exists() && !audio.delete()) {
                            audioDeleted = false
                            Log.e(TAG, "Could not delete cancelled keyboard dictation audio: $audioPath")
                        } else {
                            audio.parentFile?.let(DurableFiles::syncDirectory)
                        }
                    }
                    audioDeleted
                }
            }

        override suspend fun recoverPendingHandoffs(): Int =
            withContext(NonCancellable + Dispatchers.IO) {
                handoffMutex.withLock { recoverPendingHandoffsOnIo() }
            }

        private suspend fun recoverPendingHandoffsOnIo(): Int {
            val recordingsDirectory = recordingsDirectory()
            recoverPartialMarkersOnIo(recordingsDirectory)
            val markers =
                recordingsDirectory
                    .listFiles { file ->
                        file.isFile &&
                            file.name.startsWith(HANDOFF_MARKER_PREFIX) &&
                            file.name.endsWith(HANDOFF_MARKER_SUFFIX)
                    }.orEmpty()
            var recovered =
                recoverPendingLiveCapturesOnIo(recordingsDirectory) +
                    recoverUnjournaledLiveCapturesOnIo(recordingsDirectory)
            markers.forEach { marker ->
                try {
                    val pending = readPendingHandoff(marker) ?: return@forEach
                    val recordingId = UUID.fromString(pending.recordingId)
                    val source = File(pending.sourcePath)
                    val destination = File(pending.destinationPath)
                    if (!isValidPendingPath(recordingId, source, destination)) {
                        Log.e(TAG, "Ignoring invalid keyboard handoff marker ${marker.name}")
                        return@forEach
                    }
                    val existing = recordingRepository.getRecording(recordingId)
                    if (existing != null) {
                        if (existing.audioPath != destination.absolutePath) {
                            Log.e(TAG, "Keyboard handoff marker conflicts with an existing recording: ${marker.name}")
                            return@forEach
                        }
                        if (!destination.isFile && source.isFile) {
                            moveCaptureToDurableStorage(source, destination)
                        }
                        if (destination.isFile) {
                            deleteHandoffMarker(marker)
                        } else {
                            Log.e(TAG, "Existing keyboard handoff row has no surviving audio: ${marker.name}")
                        }
                        return@forEach
                    }

                    if (!destination.isFile) {
                        if (!source.isFile) {
                            Log.e(TAG, "Keyboard handoff marker has no surviving audio: ${marker.name}")
                            return@forEach
                        }
                        moveCaptureToDurableStorage(source, destination)
                    }

                    recordingRepository.insert(
                        pending.toRecording(recordingId, destination),
                    )
                    deleteHandoffMarker(marker)
                    recovered += 1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Could not recover keyboard handoff ${marker.name}", e)
                }
            }
            return recovered
        }

        private suspend fun recoverUnjournaledLiveCapturesOnIo(directory: File): Int {
            var recovered = 0
            val now = System.currentTimeMillis()
            directory
                .listFiles { file -> file.isFile && parseLiveCaptureRecordingId(file) != null }
                .orEmpty()
                .forEach { audio ->
                    val recordingId = parseLiveCaptureRecordingId(audio) ?: return@forEach
                    if (liveCaptureMarkerFile(directory, recordingId).isFile ||
                        markerFile(directory, recordingId).isFile ||
                        now - audio.lastModified() < UNJOURNALED_CAPTURE_RECOVERY_AGE_MS
                    ) {
                        return@forEach
                    }
                    if (recordingRepository.getRecording(recordingId) != null) return@forEach
                    val completeByteLength = audio.length() - (audio.length() % Float.SIZE_BYTES)
                    val sampleCount = completeByteLength / Float.SIZE_BYTES
                    val sampleRate = dev.chirpboard.app.core.audio.recorder.VoiceRecorder.SAMPLE_RATE
                    val minimumSamples =
                        (sampleRate.toLong() * dev.chirpboard.app.core.audio.recorder.VoiceRecorder.MINIMUM_RECORDING_MS) /
                            1000L
                    if (sampleCount < minimumSamples) return@forEach
                    try {
                        if (completeByteLength != audio.length()) {
                            RandomAccessFile(audio, "rw").use { file ->
                                file.setLength(completeByteLength)
                                file.fd.sync()
                            }
                        }
                        FileInputStream(audio).use { input -> input.fd.sync() }
                        val recoveredCapture =
                            PendingKeyboardLiveCapture(
                                recordingId = recordingId.toString(),
                                audioPath = audio.absolutePath,
                                ownerProcessId = processInstanceId,
                                state = LIVE_CAPTURE_STATE_READY,
                                createdAtEpochMs = audio.lastModified(),
                                sampleRate = sampleRate,
                                durationMs = (sampleCount * 1000L) / sampleRate,
                                llmEnabled = false,
                                processingModeId = "proofread",
                                notifyWhenReady = true,
                            )
                        recordingRepository.insert(recoveredCapture.toRecording(recordingId, audio))
                        recovered += 1
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not recover unjournaled keyboard capture ${audio.name}", e)
                    }
                }
            return recovered
        }

        /**
         * A marker write is synced to a staging file before its atomic rename. If the process dies
         * inside that tiny rename window, the staging marker can be the only journal for stopped
         * cache audio. Promote valid staging markers so the ordinary recovery pass can finish them.
         */
        private fun recoverPartialMarkersOnIo(directory: File) {
            directory
                .listFiles { file ->
                    file.isFile &&
                        file.name.endsWith("$HANDOFF_MARKER_SUFFIX.partial") &&
                        (file.name.startsWith(HANDOFF_MARKER_PREFIX) ||
                            file.name.startsWith(LIVE_CAPTURE_MARKER_PREFIX))
                }.orEmpty()
                .forEach { staging ->
                    val marker = File(directory, staging.name.removeSuffix(".partial"))
                    if (marker.isFile) {
                        if (!staging.delete()) {
                            Log.w(TAG, "Could not delete a stale partial marker ${staging.name}")
                        } else {
                            DurableFiles.syncDirectory(directory)
                        }
                        return@forEach
                    }

                    val valid =
                        if (staging.name.startsWith(HANDOFF_MARKER_PREFIX)) {
                            val pending = readPendingHandoff(staging) ?: return@forEach
                            val recordingId = runCatching { UUID.fromString(pending.recordingId) }.getOrNull()
                                ?: return@forEach
                            val source = File(pending.sourcePath)
                            val destination = File(pending.destinationPath)
                            isValidPendingPath(recordingId, source, destination) &&
                                (source.isFile || destination.isFile)
                        } else {
                            val pending = readPendingLiveCapture(staging) ?: return@forEach
                            val recordingId = runCatching { UUID.fromString(pending.recordingId) }.getOrNull()
                                ?: return@forEach
                            val audio = File(pending.audioPath)
                            isValidLiveCapturePath(recordingId, audio) && audio.isFile
                        }
                    if (!valid) return@forEach

                    try {
                        try {
                            Files.move(staging.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE)
                        } catch (_: AtomicMoveNotSupportedException) {
                            Files.move(staging.toPath(), marker.toPath())
                        }
                        DurableFiles.syncDirectory(directory)
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not promote partial keyboard marker ${staging.name}", e)
                    }
                }
        }

        private suspend fun recoverPendingLiveCapturesOnIo(directory: File): Int {
            val markers =
                directory
                    .listFiles { file ->
                        file.isFile &&
                            file.name.startsWith(LIVE_CAPTURE_MARKER_PREFIX) &&
                            file.name.endsWith(LIVE_CAPTURE_MARKER_SUFFIX)
                    }.orEmpty()
            var recovered = 0
            markers.forEach { marker ->
                try {
                    val pending = readPendingLiveCapture(marker) ?: return@forEach
                    val recordingId = UUID.fromString(pending.recordingId)
                    val audio = File(pending.audioPath)
                    if (marker.canonicalFile != liveCaptureMarkerFile(directory, recordingId).canonicalFile ||
                        !isValidLiveCapturePath(recordingId, audio) ||
                        pending.state !in setOf(LIVE_CAPTURE_STATE_RECORDING, LIVE_CAPTURE_STATE_READY) ||
                        pending.sampleRate != dev.chirpboard.app.core.audio.recorder.VoiceRecorder.SAMPLE_RATE
                    ) {
                        Log.e(TAG, "Ignoring invalid live keyboard capture marker ${marker.name}")
                        return@forEach
                    }
                    if (pending.ownerProcessId == processInstanceId &&
                        pending.state == LIVE_CAPTURE_STATE_RECORDING
                    ) {
                        // This process may have started dictation during the delayed startup pass.
                        // Only a later process instance may claim a still-recording journal.
                        return@forEach
                    }

                    val existing = recordingRepository.getRecording(recordingId)
                    if (existing != null) {
                        if (existing.audioPath == audio.absolutePath && audio.isFile) {
                            deleteHandoffMarker(marker)
                        } else {
                            Log.e(TAG, "Live capture marker conflicts with an existing recording: ${marker.name}")
                        }
                        return@forEach
                    }

                    if (!audio.isFile) {
                        deleteHandoffMarker(marker)
                        return@forEach
                    }
                    val completeByteLength = audio.length() - (audio.length() % Float.SIZE_BYTES)
                    if (completeByteLength != audio.length()) {
                        RandomAccessFile(audio, "rw").use { file ->
                            file.setLength(completeByteLength)
                            file.fd.sync()
                        }
                    }
                    val sampleCount = completeByteLength / Float.SIZE_BYTES
                    val minimumSamples =
                        (pending.sampleRate.toLong() *
                            dev.chirpboard.app.core.audio.recorder.VoiceRecorder.MINIMUM_RECORDING_MS) /
                            1000L
                    if (sampleCount < minimumSamples) {
                        if (audio.exists() && !audio.delete()) {
                            Log.w(TAG, "Could not delete an incomplete live keyboard capture: ${audio.name}")
                            return@forEach
                        }
                        deleteHandoffMarker(marker)
                        return@forEach
                    }

                    FileInputStream(audio).use { input -> input.fd.sync() }
                    val ready =
                        pending.copy(
                            state = LIVE_CAPTURE_STATE_READY,
                            durationMs = (sampleCount * 1000L) / pending.sampleRate,
                        )
                    recordingRepository.insert(ready.toRecording(recordingId, audio))
                    deleteHandoffMarker(marker)
                    recovered += 1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Could not recover live keyboard capture ${marker.name}", e)
                }
            }
            return recovered
        }

        private fun writePendingHandoff(pending: PendingKeyboardHandoff): File {
            val directory = recordingsDirectory()
            val marker = markerFile(directory, UUID.fromString(pending.recordingId))
            return writeJsonMarker(marker, gson.toJson(pending))
        }

        private fun writePendingLiveCapture(pending: PendingKeyboardLiveCapture): File {
            val directory = recordingsDirectory()
            val marker = liveCaptureMarkerFile(directory, UUID.fromString(pending.recordingId))
            return writeJsonMarker(marker, gson.toJson(pending))
        }

        private fun writeJsonMarker(
            marker: File,
            json: String,
        ): File {
            // The .partial staging suffix is part of the crash-recovery contract:
            // OrphanedAudioCleaner treats it as a live reference to the capture audio.
            DurableFiles.writeTextAtomically(marker, json, stagingSuffix = ".partial")
            return marker
        }

        private fun recordingsDirectory(): File =
            File(context.filesDir, RECORDINGS_DIRECTORY).also { directory ->
                check(directory.isDirectory || directory.mkdirs()) {
                    "Could not create the recordings directory"
                }
            }

        private fun readPendingHandoff(marker: File): PendingKeyboardHandoff? =
            runCatching {
                gson.fromJson(marker.readText(Charsets.UTF_8), PendingKeyboardHandoff::class.java)
            }.onFailure { error ->
                Log.e(TAG, "Could not read keyboard handoff marker ${marker.name}", error)
            }.getOrNull()

        private fun readPendingLiveCapture(marker: File): PendingKeyboardLiveCapture? =
            runCatching {
                gson.fromJson(marker.readText(Charsets.UTF_8), PendingKeyboardLiveCapture::class.java)
            }.onFailure { error ->
                Log.e(TAG, "Could not read live keyboard capture marker ${marker.name}", error)
            }.getOrNull()

        private fun findPendingLiveCapture(
            source: File,
        ): Pair<File, PendingKeyboardLiveCapture>? {
            val recordingId = parseLiveCaptureRecordingId(source) ?: return null
            if (!isValidLiveCapturePath(recordingId, source)) return null
            val marker = liveCaptureMarkerFile(recordingsDirectory(), recordingId)
            if (!marker.isFile) return null
            val pending = readPendingLiveCapture(marker) ?: return null
            if (pending.recordingId != recordingId.toString() ||
                File(pending.audioPath).canonicalFile != source.canonicalFile ||
                pending.ownerProcessId != processInstanceId ||
                pending.state !in setOf(LIVE_CAPTURE_STATE_RECORDING, LIVE_CAPTURE_STATE_READY) ||
                pending.sampleRate != dev.chirpboard.app.core.audio.recorder.VoiceRecorder.SAMPLE_RATE
            ) {
                Log.e(TAG, "Live capture marker does not match ${source.name}")
                return null
            }
            return marker to pending
        }

        private fun parseLiveCaptureRecordingId(source: File): UUID? {
            val name = source.name
            if (!name.startsWith("keyboard_") || !name.endsWith(RAW_PCM_SUFFIX)) return null
            return runCatching {
                UUID.fromString(name.removePrefix("keyboard_").removeSuffix(RAW_PCM_SUFFIX))
            }.getOrNull()
        }

        private fun isValidLiveCapturePath(
            recordingId: UUID,
            audio: File,
        ): Boolean =
            audio.canonicalFile ==
                recordingsDirectory()
                    .resolve("keyboard_${recordingId}$RAW_PCM_SUFFIX")
                    .canonicalFile

        private fun isValidPendingPath(
            recordingId: UUID,
            source: File,
            destination: File,
        ): Boolean {
            val expectedDestination =
                File(context.filesDir, RECORDINGS_DIRECTORY)
                    .resolve("keyboard_${recordingId}$RAW_PCM_SUFFIX")
                    .canonicalFile
            val captureDirectory =
                File(
                    context.cacheDir,
                    dev.chirpboard.app.core.audio.recorder.VoiceRecorder.KEYBOARD_CAPTURE_CACHE_DIR,
                ).canonicalFile
            return destination.canonicalFile == expectedDestination &&
                source.canonicalFile.parentFile == captureDirectory
        }

        private fun deleteHandoffMarker(marker: File) {
            if (marker.exists() && !marker.delete()) {
                Log.w(TAG, "Could not delete keyboard handoff marker ${marker.name}")
            } else {
                marker.parentFile?.let(DurableFiles::syncDirectory)
            }
        }

        private companion object {
            private const val TAG = "KeyboardHandoff"
            private const val RECORDINGS_DIRECTORY = "recordings"
            private const val RAW_PCM_SUFFIX = ".f32pcm"
            private const val QUEUE_RECOVERY_REASON = "keyboard durable handoff enqueue failed"
            private const val HANDOFF_MARKER_PREFIX = ".keyboard-handoff-"
            private const val HANDOFF_MARKER_SUFFIX = ".json"
            private const val LIVE_CAPTURE_MARKER_PREFIX = ".keyboard-live-"
            private const val LIVE_CAPTURE_MARKER_SUFFIX = ".json"
            private const val LIVE_CAPTURE_STATE_RECORDING = "recording"
            private const val LIVE_CAPTURE_STATE_READY = "ready"
            private const val UNJOURNALED_CAPTURE_RECOVERY_AGE_MS = 30_000L

            private fun markerFile(
                directory: File,
                recordingId: UUID,
            ): File = File(directory, "$HANDOFF_MARKER_PREFIX$recordingId$HANDOFF_MARKER_SUFFIX")

            private fun liveCaptureMarkerFile(
                directory: File,
                recordingId: UUID,
            ): File = File(directory, "$LIVE_CAPTURE_MARKER_PREFIX$recordingId$LIVE_CAPTURE_MARKER_SUFFIX")
        }
    }

@Keep
internal data class PendingKeyboardLiveCapture(
    val recordingId: String,
    val audioPath: String,
    val ownerProcessId: String,
    val state: String,
    val createdAtEpochMs: Long,
    val sampleRate: Int,
    val durationMs: Long = 0L,
    val llmEnabled: Boolean,
    val processingModeId: String,
    val notifyWhenReady: Boolean,
)

@Keep
internal data class PendingKeyboardHandoff(
    val recordingId: String,
    val sourcePath: String,
    val destinationPath: String,
    val durationMs: Long,
    val llmEnabled: Boolean,
    val processingModeId: String,
    val notifyWhenReady: Boolean,
    val transcriptionEngineId: String = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id,
)

private fun PendingKeyboardHandoff.toRecording(
    recordingId: UUID,
    destination: File,
): Recording =
    Recording(
        id = recordingId,
        title = "Keyboard recording",
        audioPath = destination.absolutePath,
        status = RecordingStatus.PENDING_TRANSCRIPTION,
        source = RecordingSource.KEYBOARD,
        durationMs = durationMs,
        transcriptionEngineId = transcriptionEngineId,
        requestedProcessingModeId = processingModeId.takeIf { llmEnabled },
        requestedLlmProviderId =
            GOOGLE_CLOUD_VERTEX_PROVIDER_ID.takeIf {
                llmEnabled && transcriptionEngineId == TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id
            },
        requestedLlmModelId = null,
        notifyWhenReady = notifyWhenReady,
        enhancementRequestSnapshotted = true,
    )

private fun PendingKeyboardLiveCapture.toRecording(
    recordingId: UUID,
    audio: File,
): Recording =
    Recording(
        id = recordingId,
        title = "Keyboard recording",
        audioPath = audio.absolutePath,
        status = RecordingStatus.PENDING_TRANSCRIPTION,
        source = RecordingSource.KEYBOARD,
        createdAt = Date(createdAtEpochMs),
        durationMs = durationMs,
        transcriptionEngineId = TranscriptionEngine.GOOGLE_CLOUD_CHIRP_3.id,
        requestedProcessingModeId = processingModeId.takeIf { llmEnabled },
        requestedLlmProviderId = GOOGLE_CLOUD_VERTEX_PROVIDER_ID.takeIf { llmEnabled },
        requestedLlmModelId = null,
        notifyWhenReady = notifyWhenReady,
        enhancementRequestSnapshotted = true,
    )

internal fun restoreSourceAfterInsertFailure(
    source: File,
    destination: File,
): Boolean {
    if (source.isFile) {
        if (destination.exists() && !destination.delete()) {
            Log.w("KeyboardHandoff", "The original keyboard source remains with an extra durable copy")
        }
        return true
    }

    val restored =
        runCatching {
            moveCaptureToDurableStorage(destination, source)
            source.isFile
        }.onFailure { rollbackError ->
            Log.e("KeyboardHandoff", "Could not restore keyboard audio after the recording insert failed", rollbackError)
        }.getOrDefault(false)
    if (restored && destination.exists() && !destination.delete()) {
        Log.w("KeyboardHandoff", "The keyboard source was restored, but its extra durable copy remains")
    }
    return restored
}

/**
 * Moves the stopped capture by rename when possible. The copy fallback keeps the source until a
 * synced staging file has been renamed into place, so a failed move never destroys the only copy.
 */
internal fun moveCaptureToDurableStorage(
    source: File,
    destination: File,
) {
    require(source.isFile) { "Source capture does not exist" }
    destination.parentFile?.mkdirs()
    require(!destination.exists()) { "Destination recording already exists" }

    // The recorder has closed its writer by this point. Sync the inode once so the rename does
    // not make a recently buffered capture look durable before its bytes reach storage.
    FileInputStream(source).use { input -> input.fd.sync() }

    try {
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        destination.parentFile?.let(DurableFiles::syncDirectory)
        return
    } catch (_: AtomicMoveNotSupportedException) {
        // Same-device app storage normally supports the atomic rename. Keep a crash-safe copy
        // fallback for unusual filesystems and tests that place the source on another mount.
    }

    val staging = File(destination.parentFile, ".${destination.name}.partial")
    runCatching { staging.delete() }
    try {
        FileInputStream(source).use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        try {
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staging.toPath(), destination.toPath())
        }
        destination.parentFile?.let(DurableFiles::syncDirectory)
        if (!source.delete()) {
            Log.w("KeyboardHandoff", "Durable copy succeeded, but the old cache file remains")
        }
    } catch (e: Exception) {
        runCatching { staging.delete() }
        throw e
    }
}

