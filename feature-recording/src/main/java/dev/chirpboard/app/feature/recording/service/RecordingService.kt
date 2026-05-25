package dev.chirpboard.app.feature.recording.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.chirpboard.app.core.audio.AudioFocusManager
import dev.chirpboard.app.core.audio.AudioInputDeviceSelector
import dev.chirpboard.app.core.audio.RecordingStorageMonitor
import dev.chirpboard.app.core.audio.StorageCheckLevel
import dev.chirpboard.app.core.recording.RecordingPermissionGuard
import dev.chirpboard.app.core.recording.RecordingOrigin
import dev.chirpboard.app.core.audio.AudioSettingsStore
import dev.chirpboard.app.core.recording.RecordingStartResult
import dev.chirpboard.app.core.recording.RecordingState
import dev.chirpboard.app.core.recording.RecordingStateManager
import dev.chirpboard.app.core.reliability.ReliabilityEventLogger
import dev.chirpboard.app.core.reliability.ReliabilityOutcome
import dev.chirpboard.app.core.reliability.ReliabilityStage
import dev.chirpboard.app.data.model.RecordingSource
import dev.chirpboard.app.data.repository.RecordingRepository
import dev.chirpboard.app.feature.recording.R
import dev.chirpboard.app.feature.recording.session.RecordingCapturePaths
import dev.chirpboard.app.feature.recording.session.RecordingSessionJournal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service for audio recording.
 *
 * Coordinates with RecordingStateManager to prevent simultaneous recordings
 * from App, Keyboard, or Widget.
 */
@AndroidEntryPoint
class RecordingService : Service() {
    @Inject
    lateinit var recordingStateManager: RecordingStateManager

    @Inject
    lateinit var recordingRepository: RecordingRepository

    @Inject
    lateinit var transcriberProvider: dev.chirpboard.app.core.transcription.TranscriberProvider

    @Inject
    lateinit var stopOrchestrator: RecordingStopOrchestrator
    @Inject
    lateinit var audioSettingsStore: AudioSettingsStore

    @Inject
    lateinit var sessionJournal: RecordingSessionJournal

    @Inject
    lateinit var capturePaths: RecordingCapturePaths

    @Inject
    lateinit var recorderReleaseCoordinator: RecorderReleaseCoordinator

    @Inject
    lateinit var storageMonitor: RecordingStorageMonitor

    @Inject
    lateinit var inputDeviceSelector: AudioInputDeviceSelector

    private lateinit var audioFocusManager: AudioFocusManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var segmentCapture: GaplessSegmentCapture? = null
    private var currentRecordingFile: File? = null
    private var currentProfileId: UUID? = null
    private var currentOrigin: RecordingOrigin = RecordingOrigin.APP
    private var currentSessionId: UUID? = null
    private var currentInProgressRecordingId: UUID? = null
    private var currentFinalAudioPath: File? = null

    private var durationUpdateJob: Job? = null
    private var amplitudeJob: Job? = null
    private var heartbeatJob: Job? = null
    private var storageCheckJob: Job? = null
    private var checkpointJob: Job? = null
    private var segmentRotationJob: Job? = null
    private val segmentTransitionMutex = Mutex()
    private val stopRequestGate = StopRequestGate()
    private var currentCorrelationId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeInstanceForTest = this
        createNotificationChannel()
        audioFocusManager = AudioFocusManager(getSystemService(AudioManager::class.java))
        audioFocusManager.onFocusLost = { lossKind ->
            when (lossKind) {
                AudioFocusManager.FocusLossKind.TRANSIENT -> pauseRecording()
                AudioFocusManager.FocusLossKind.PERMANENT -> stopRecording()
            }
        }
        inputDeviceSelector.setOnActiveDeviceLostListener {
            serviceScope.launch {
                recordingStateManager.onRecordingError("Microphone disconnected during recording")
                stopRecording()
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START_RECORDING -> {
                val originName = intent.getStringExtra(EXTRA_ORIGIN) ?: RecordingOrigin.APP.name
                val origin = RecordingOrigin.valueOf(originName)
                val profileId = intent.getStringExtra(EXTRA_PROFILE_ID)?.let { UUID.fromString(it) }
                startRecording(origin, profileId)
            }

            ACTION_PAUSE_RECORDING -> {
                pauseRecording()
            }

            ACTION_RESUME_RECORDING -> {
                resumeRecording()
            }

            ACTION_STOP_RECORDING -> {
                stopRecording()
            }

            ACTION_CANCEL_RECORDING -> {
                cancelRecording()
            }

            ACTION_RESTART_RECORDING -> {
                val originName = intent?.getStringExtra(EXTRA_ORIGIN) ?: RecordingOrigin.APP.name
                val origin = RecordingOrigin.valueOf(originName)
                val profileId = intent?.getStringExtra(EXTRA_PROFILE_ID)?.let { UUID.fromString(it) }
                restartRecording(origin, profileId)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        val state = recordingStateManager.state.value
        val needsEmergencyStop =
            !stopRequestGate.isInProgress() &&
                (state is RecordingState.Recording || state is RecordingState.Paused || state is RecordingState.Starting)

        if (needsEmergencyStop) {
            runBlocking(Dispatchers.IO) {
                emergencyFinalizeActiveCapture(state is RecordingState.Paused)
            }
        }

        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()
        heartbeatJob?.cancel()
        storageCheckJob?.cancel()
        checkpointJob?.cancel()
        segmentRotationJob?.cancel()
        serviceScope.cancel()
        if (activeInstanceForTest === this) {
            activeInstanceForTest = null
        }
    }

    private fun startRecording(
        origin: RecordingOrigin,
        profileId: UUID?,
    ) {
        if (!RecordingPermissionGuard.hasRecordAudioPermission(this)) {
            recordingStateManager.onRecordingError(
                RecordingPermissionGuard.PERMISSION_DENIED_MESSAGE,
                SecurityException("RECORD_AUDIO permission missing"),
            )
            stopSelf()
            return
        }

        // Try to acquire recording lock
        when (val result = recordingStateManager.tryStartRecording(origin, profileId)) {
            is RecordingStartResult.AlreadyRecording -> {
                // Another recording is in progress
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_START,
                    outcome = ReliabilityOutcome.SKIPPED,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "already_recording",
                )
                return
            }

            is RecordingStartResult.Success -> {
                // Lock acquired, proceed with recording
            }
        }

        promoteToForegroundImmediately()

        serviceScope.launch {
            startRecordingAfterLockAcquired(origin, profileId)
        }
    }

    private fun promoteToForegroundImmediately() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            createStartingNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private suspend fun startRecordingAfterLockAcquired(
        origin: RecordingOrigin,
        profileId: UUID?,
    ) {
        currentCorrelationId = ReliabilityEventLogger.newCorrelationId("record")
        currentOrigin = origin
        currentProfileId = profileId

        val storageCheck = storageMonitor.checkAvailableStorage()
        if (storageCheck.level == StorageCheckLevel.CRITICAL) {
            recordingStateManager.onRecordingError("Not enough storage to start recording")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        when (audioFocusManager.requestFocus()) {
            is AudioFocusManager.FocusResult.Denied -> {
                recordingStateManager.onRecordingError("Could not acquire audio focus for recording")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            is AudioFocusManager.FocusResult.Granted -> Unit
        }

        ReliabilityEventLogger.log(
            stage = ReliabilityStage.RECORDING_START,
            outcome = ReliabilityOutcome.STARTED,
            correlationId = currentCorrelationId!!,
            reasonCode = "service_start",
        )

        try {
            withContext(Dispatchers.Default) {
                transcriberProvider.release()
            }

            withContext(Dispatchers.IO) {
                val recordingQualityConfig =
                    audioSettingsStore.currentRecordingQualityPreset().appRecordingConfig

                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outputDir = File(filesDir, "recordings").apply { mkdirs() }
                val finalFile = File(outputDir, "recording_$timestamp.m4a")
                val sessionId = UUID.randomUUID()
                currentSessionId = sessionId
                currentFinalAudioPath = finalFile
                val firstSegment = capturePaths.segmentFile(sessionId, 0)
                currentRecordingFile = firstSegment

                val source =
                    when (origin) {
                        RecordingOrigin.APP -> RecordingSource.APP
                        RecordingOrigin.KEYBOARD -> RecordingSource.KEYBOARD
                        RecordingOrigin.WIDGET -> RecordingSource.WIDGET
                    }
                val provisionalTitle =
                    SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date())
                val inProgressRecording =
                    recordingRepository.createInProgressRecording(
                        title = provisionalTitle,
                        audioPath = finalFile.absolutePath,
                        source = source,
                        profileId = profileId,
                    )
                currentInProgressRecordingId = inProgressRecording.id

                sessionJournal.createSession(
                    sessionId = sessionId,
                    audioPath = firstSegment.absolutePath,
                    origin = origin,
                    profileId = profileId,
                    recordingId = inProgressRecording.id,
                    correlationId = currentCorrelationId,
                    finalAudioPath = finalFile.absolutePath,
                )

                startGaplessCapture(
                    segmentFile = firstSegment,
                    bitRate = recordingQualityConfig.bitRate,
                    sampleRate = recordingQualityConfig.sampleRate,
                )
            }
            recordingStateManager.onRecordingStarted(currentRecordingFile!!.absolutePath)

            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_START,
                outcome = ReliabilityOutcome.SUCCESS,
                correlationId = currentCorrelationId!!,
                reasonCode = "recorder_started",
            )

            // Upgrade the starting notification to the live recording UI
            ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

            startDurationUpdates()
            startAmplitudeCollection()
            startSessionHeartbeat()
            startStorageMonitoring()
            startCheckpointCopies()
            startSegmentRotation()
        } catch (e: Exception) {
            currentInProgressRecordingId?.let { recordingRepository.deleteInProgressRecording(it) }
            currentInProgressRecordingId = null
            currentSessionId?.let { sessionJournal.markAbandoned(it) }
            currentSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
            currentSessionId = null
            audioFocusManager.abandonFocus()
            inputDeviceSelector.clearActiveDevice()
            segmentCapture?.releaseWithoutSave()
            segmentCapture = null
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_START,
                outcome = ReliabilityOutcome.FAILURE,
                correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                reasonCode = "recorder_start_failed",
                message = e.message,
            )
            recordingStateManager.onRecordingError("Failed to start recording: ${e.message}", e)
            // Delete the audio file if it was created during setup
            currentRecordingFile?.let { file ->
                if (file.exists()) file.delete()
            }
            currentFinalAudioPath?.let { file ->
                if (file.exists()) file.delete()
            }
            currentRecordingFile = null
            currentFinalAudioPath = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    private fun pauseRecording() {
        try {
            recordingStateManager.pauseRecording()
            amplitudeJob?.cancel()
            amplitudeJob = null
            recordingStateManager.updateAmplitude(0f)
            updateNotification()
            serviceScope.launch {
                try {
                    commitSegmentOnPause()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(TAG, "Failed to finalize segment on pause", e)
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordingStateManager.onRecordingError("Failed to pause recording: ${e.message}", e)
        }
    }

    private fun resumeRecording() {
        serviceScope.launch {
            try {
                segmentTransitionMutex.withLock {
                    val sessionId = currentSessionId ?: return@withLock
                    val entry = sessionJournal.findBySessionId(sessionId) ?: return@withLock
                    val nextSegment = capturePaths.segmentFile(sessionId, entry.segmentPaths.size)
                    val recordingQualityConfig =
                        audioSettingsStore.currentRecordingQualityPreset().appRecordingConfig

                    withContext(Dispatchers.IO) {
                        segmentCapture =
                            GaplessSegmentCapture(
                                inputDeviceSelector,
                                recordingQualityConfig.sampleRate,
                                recordingQualityConfig.bitRate,
                            )
                        segmentCapture!!.start(nextSegment)
                    }

                    currentRecordingFile = nextSegment
                    sessionJournal.beginNextSegment(sessionId, nextSegment.absolutePath)
                    recordingStateManager.resumeRecording(nextSegment.absolutePath)
                    ReliabilityEventLogger.log(
                        stage = ReliabilityStage.RECORDING_START,
                        outcome = ReliabilityOutcome.SUCCESS,
                        correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                        reasonCode = "segment_started_on_resume",
                        message = "segment=${entry.segmentPaths.size}",
                    )
                }
                startAmplitudeCollection()
                startSegmentRotation()
                updateNotification()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                recordingStateManager.onRecordingError("Failed to resume recording: ${e.message}", e)
            }
        }
    }

    private suspend fun commitSegmentOnPause() {
        segmentTransitionMutex.withLock {
            if (recordingStateManager.state.value !is RecordingState.Paused) return
            segmentRotationJob?.cancel()
            val sessionId = currentSessionId ?: return
            val completedFile =
                withContext(Dispatchers.IO) {
                    segmentCapture?.cancelPendingRotation()
                    segmentCapture?.pauseAndFinalizeSegment()
                }
            segmentCapture = null
            val finalized = completedFile ?: currentRecordingFile ?: return
            currentRecordingFile = finalized

            sessionJournal.commitPausedSegment(
                sessionId = sessionId,
                completedSegmentPath = finalized.absolutePath,
                fileBytes = finalized.length(),
            )
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_STOP,
                outcome = ReliabilityOutcome.SUCCESS,
                correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                reasonCode = "segment_saved_on_pause",
            )
        }
    }

    /**
     * Cancel the current recording — release MediaRecorder, delete the audio file,
     * do NOT save to database.
     */
    private fun cancelRecording() {
        recordingStateManager.forceCancel()
        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()
        heartbeatJob?.cancel()
        storageCheckJob?.cancel()
        checkpointJob?.cancel()
        segmentRotationJob?.cancel()
        val inProgressId = currentInProgressRecordingId
        val abandonedSessionId = currentSessionId
        currentSessionId?.let { sessionJournal.markAbandoned(it) }
        currentSessionId = null
        currentInProgressRecordingId = null

        serviceScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    segmentCapture?.releaseWithoutSave()
                }
            } catch (_: Exception) {
            } finally {
                segmentCapture = null
                inProgressId?.let { recordingRepository.deleteInProgressRecording(it) }
                abandonedSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
                currentRecordingFile?.let { file ->
                    if (file.exists()) file.delete()
                }
                currentFinalAudioPath?.let { file ->
                    if (file.exists()) file.delete()
                }
                currentRecordingFile = null
                currentFinalAudioPath = null
                audioFocusManager.abandonFocus()
                inputDeviceSelector.clearActiveDevice()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
    /**
     * Atomic restart: cancel current recording and immediately start a new one
     * without stopping the service in between.
     */
    private fun restartRecording(
        origin: RecordingOrigin,
        profileId: UUID?,
    ) {
        serviceScope.launch {
            durationUpdateJob?.cancel()
            amplitudeJob?.cancel()
            heartbeatJob?.cancel()
            storageCheckJob?.cancel()
            checkpointJob?.cancel()
            segmentRotationJob?.cancel()

            val oldSessionId = currentSessionId
            val oldRecordingId = currentInProgressRecordingId
            val oldFile = currentRecordingFile
            val oldFinalFile = currentFinalAudioPath

            withContext(Dispatchers.IO) {
                segmentCapture?.releaseWithoutSave()
            }
            segmentCapture = null

            oldSessionId?.let { sessionJournal.markAbandoned(it) }
            oldSessionId?.let { capturePaths.deleteCaptureArtifacts(it) }
            oldRecordingId?.let { recordingRepository.deleteInProgressRecording(it) }
            oldFile?.let { file ->
                if (file.exists()) file.delete()
            }
            oldFinalFile?.let { file ->
                if (file.exists()) file.delete()
            }
            currentRecordingFile = null
            currentFinalAudioPath = null
            currentSessionId = null
            currentInProgressRecordingId = null

            recordingStateManager.forceCancel()
            stopRequestGate.reset()
            startRecording(origin, profileId)
        }
    }

    private fun stopRecording() {
        if (!stopRequestGate.tryBegin()) {
            Log.d(TAG, "Ignoring duplicate stop request while stop is in progress")
            return
        }

        val snapshot = captureStopSnapshot()

        if (snapshot != null) {
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_STOP,
                outcome = ReliabilityOutcome.STARTED,
                correlationId = snapshot.correlationId,
                reasonCode = "stop_requested",
            )
        }

        if (!recordingStateManager.transitionToStopping()) {
            stopRequestGate.reset()
            return
        }

        durationUpdateJob?.cancel()
        amplitudeJob?.cancel()
        heartbeatJob?.cancel()
        storageCheckJob?.cancel()
        checkpointJob?.cancel()
        segmentRotationJob?.cancel()
        currentSessionId?.let { sessionJournal.markStopping(it) }

        val sessionId = currentSessionId
        val outputFile = currentRecordingFile

        serviceScope.launch {
            val fileSizeBytes =
                withContext(Dispatchers.IO) {
                    val capture = segmentCapture
                    if (capture != null) {
                        val finalized = capture.stopAndFinalize()
                        segmentCapture = null
                        finalized?.let { currentRecordingFile = it }
                        finalized?.length() ?: outputFile?.length() ?: 0L
                    } else {
                        outputFile?.length() ?: 0L
                    }
                }

            val timeoutJob = recordingStateManager.startStoppingTimeout(fileSizeBytes)

            withContext(kotlinx.coroutines.NonCancellable) {
                val result =
                    try {
                        if (snapshot == null) {
                            StopPersistenceResult.NoAudioFile
                        } else {
                            withContext(Dispatchers.IO) {
                                stopOrchestrator.persistAndQueueRecording(snapshot, sessionId)
                            }
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        StopPersistenceResult.PersistenceFailed("Failed to stop recording: ${e.message}", e)
                    } finally {
                        timeoutJob?.cancel()
                    }
                when (result) {
                    is StopPersistenceResult.SavedAndQueued -> {
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.RECORDING_STOP,
                            outcome = ReliabilityOutcome.SUCCESS,
                            correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                            recordingId = result.recordingId,
                            reasonCode = "saved_and_enqueued",
                        )
                        recordingStateManager.onRecordingCompleted(recordingId = result.recordingId)
                    }

                    is StopPersistenceResult.SavedPendingRecovery -> {
                        Log.w(
                            TAG,
                            "Saved recording ${result.recordingId} but queue handoff failed. " +
                                "Marked for startup recovery.",
                        )
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.QUEUE_ENQUEUE,
                            outcome = ReliabilityOutcome.FAILURE,
                            correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                            recordingId = result.recordingId,
                            reasonCode = "queue_handoff_failed",
                            message = result.message,
                        )
                        recordingStateManager.onRecordingCompleted(recordingId = result.recordingId)
                    }

                    is StopPersistenceResult.PersistenceFailed -> {
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.PERSISTENCE_SAVE,
                            outcome = ReliabilityOutcome.FAILURE,
                            correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                            reasonCode = "persistence_failed",
                            message = result.message,
                        )
                        recordingStateManager.onRecordingError(result.message, result.cause)
                    }

                    StopPersistenceResult.NoAudioFile -> {
                        ReliabilityEventLogger.log(
                            stage = ReliabilityStage.RECORDING_STOP,
                            outcome = ReliabilityOutcome.SKIPPED,
                            correlationId = snapshot?.correlationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                            reasonCode = "missing_audio_file",
                        )
                        sessionId?.let { sessionJournal.markAbandoned(it) }
                        recordingStateManager.onRecordingCompleted()
                    }
                }

                finishStopLifecycle()
            }
        }
    }

    private fun captureStopSnapshot(): StopSnapshot? {
        val state = recordingStateManager.state.value
        val isPaused = state is RecordingState.Paused
        val filePath =
            currentRecordingFile?.absolutePath ?: when (state) {
                is RecordingState.Recording -> state.audioFilePath
                is RecordingState.Paused -> state.audioFilePath
                else -> null
            }
        val profileId =
            when (state) {
                is RecordingState.Starting -> state.profileId
                is RecordingState.Recording -> state.profileId
                is RecordingState.Paused -> state.profileId
                else -> currentProfileId
            }

        return StopSnapshot(
            origin = state.activeOrigin ?: currentOrigin,
            profileId = profileId,
            recordingId = currentInProgressRecordingId,
            audioFilePath = filePath,
            durationMs = recordingStateManager.getCurrentDurationMs().coerceAtLeast(0L),
            stoppedAtEpochMs = System.currentTimeMillis(),
            wasPaused = isPaused,
            correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
        )
    }

    private suspend fun emergencyFinalizeActiveCapture(wasPaused: Boolean) {
        if (!stopRequestGate.tryBegin()) {
            withContext(Dispatchers.IO) {
                segmentCapture?.stopAndFinalize()
            }
            segmentCapture = null
            return
        }

        val snapshot = captureStopSnapshot()
        if (!recordingStateManager.transitionToStopping()) {
            stopRequestGate.reset()
            return
        }

        currentSessionId?.let { sessionJournal.markStopping(it) }
        val fileSizeBytes =
            withContext(Dispatchers.IO) {
                val capture = segmentCapture
                if (capture != null) {
                    val finalized = capture.stopAndFinalize()
                    segmentCapture = null
                    finalized?.let { currentRecordingFile = it }
                    finalized?.length() ?: currentRecordingFile?.length() ?: 0L
                } else {
                    currentRecordingFile?.length() ?: 0L
                }
            }
        val timeoutJob = recordingStateManager.startStoppingTimeout(fileSizeBytes)

        withContext(NonCancellable) {
            try {
                if (snapshot != null) {
                    stopOrchestrator.persistAndQueueRecording(snapshot, currentSessionId)
                }
                recordingStateManager.onRecordingCompleted()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                recordingStateManager.onRecordingError("Failed to finalize recording during shutdown")
            } finally {
                timeoutJob?.cancel()
                finishStopLifecycle()
            }
        }
    }

    private fun finishStopLifecycle() {
        currentRecordingFile = null
        currentFinalAudioPath = null
        currentProfileId = null
        currentSessionId = null
        currentInProgressRecordingId = null
        currentCorrelationId = null
        stopRequestGate.reset()
        audioFocusManager.abandonFocus()
        inputDeviceSelector.clearActiveDevice()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startDurationUpdates() {
        durationUpdateJob?.cancel()
        durationUpdateJob =
            serviceScope.launch {
                while (isActive) {
                    val state = recordingStateManager.state.value
                    if (state is RecordingState.Recording) {
                        updateNotification()
                    }
                    // Don't update while paused — timer is frozen and notification already set
                    delay(1000)
                }
            }
    }

    /**
     * Collect audio amplitude for waveform visualization.
     * Collect audio amplitude for waveform visualization.
     * Updates at display-friendly cadence so the UI has enough real data to interpolate smoothly.
     */
    private fun startAmplitudeCollection() {
        amplitudeJob =
            serviceScope.launch {
                while (isActive) {
                    try {
                        val maxAmplitude = segmentCapture?.maxAmplitude ?: 0
                        val normalized = (maxAmplitude / 32767f).coerceIn(0f, 1f)
                        recordingStateManager.updateAmplitude(normalized)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        recordingStateManager.updateAmplitude(0f)
                    }
                    delay(100)
                }
            }
    }

    private fun startSessionHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob =
            serviceScope.launch {
                while (isActive) {
                    delay(30_000)
                    val sessionId = currentSessionId ?: continue
                    val bytes = currentRecordingFile?.takeIf { it.exists() }?.length() ?: 0L
                    sessionJournal.updateHeartbeat(sessionId, bytes)
                }
            }
    }

    private fun startCheckpointCopies() {
        checkpointJob?.cancel()
        checkpointJob =
            serviceScope.launch {
                while (isActive) {
                    delay(RecordingSessionJournal.CHECKPOINT_INTERVAL_MS)
                    withContext(Dispatchers.IO) {
                        val file = currentRecordingFile?.takeIf { it.exists() } ?: return@withContext
                        val sessionId = currentSessionId ?: return@withContext
                        val checkpoint = File(RecordingFileValidator.checkpointPathFor(file.absolutePath))
                        runCatching {
                            file.copyTo(checkpoint, overwrite = true)
                            sessionJournal.updateCheckpoint(sessionId, checkpoint.absolutePath, file.length())
                        }.onFailure { error ->
                            Log.w(TAG, "Checkpoint copy failed", error)
                        }
                    }
                }
            }
    }

    private suspend fun startGaplessCapture(
        segmentFile: File,
        bitRate: Int,
        sampleRate: Int,
    ) {
        segmentCapture?.releaseWithoutSave()
        segmentCapture = GaplessSegmentCapture(inputDeviceSelector, sampleRate, bitRate)
        segmentCapture!!.start(segmentFile)
    }

    private fun startSegmentRotation() {
        segmentRotationJob?.cancel()
        segmentRotationJob =
            serviceScope.launch {
                while (isActive) {
                    delay(RecordingSessionJournal.SEGMENT_ROTATION_INTERVAL_MS)
                    if (recordingStateManager.state.value is RecordingState.Paused) continue
                    if (stopRequestGate.isInProgress()) continue
                    try {
                        rotateSegmentIfNeeded()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        Log.w(TAG, "Gapless segment rotation failed", e)
                    }
                }
            }
    }

    private suspend fun rotateSegmentIfNeeded() {
        if (recordingStateManager.state.value !is RecordingState.Recording) return

        segmentTransitionMutex.withLock {
            if (stopRequestGate.isInProgress()) return
            if (recordingStateManager.state.value !is RecordingState.Recording) return

            val sessionId = currentSessionId ?: return
            val entry = sessionJournal.findBySessionId(sessionId) ?: return
            val capture = segmentCapture ?: return
            val completedFile = currentRecordingFile ?: return

            val nextIndex = entry.segmentPaths.size + 1
            val nextSegment = capturePaths.segmentFile(sessionId, nextIndex)

            val rotationResult =
                withContext(Dispatchers.IO) {
                    capture.rotateSegment(nextSegment)
                }

            if (rotationResult !is SegmentRotationResult.Success) {
                val reason = (rotationResult as? SegmentRotationResult.Failed)?.reason ?: "unknown"
                Log.w(TAG, "Gapless segment rotation skipped: $reason")
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_START,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "segment_rotation_failed",
                    message = reason,
                )
                return
            }

            val completedValidation = RecordingFileValidator().validateForRecovery(completedFile)
            if (!completedValidation.isRecoverableStub) {
                Log.w(
                    TAG,
                    "Gapless segment rotation produced invalid segment: ${completedValidation.failureReason}",
                )
                ReliabilityEventLogger.log(
                    stage = ReliabilityStage.RECORDING_START,
                    outcome = ReliabilityOutcome.FAILURE,
                    correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                    reasonCode = "segment_rotation_invalid",
                    message = completedValidation.failureReason,
                )
                return
            }

            sessionJournal.appendCompletedSegment(
                sessionId = sessionId,
                completedSegmentPath = completedFile.absolutePath,
                nextSegmentPath = nextSegment.absolutePath,
                fileBytes = nextSegment.takeIf { it.exists() }?.length() ?: 0L,
            )

            currentRecordingFile = nextSegment
            recordingStateManager.rotateSegment(nextSegment.absolutePath)
            ReliabilityEventLogger.log(
                stage = ReliabilityStage.RECORDING_START,
                outcome = ReliabilityOutcome.SUCCESS,
                correlationId = currentCorrelationId ?: ReliabilityEventLogger.newCorrelationId("record"),
                reasonCode = "segment_rotated_gapless",
                message = "segment=$nextIndex",
            )
        }
    }

    private fun startStorageMonitoring() {
        storageCheckJob?.cancel()
        storageCheckJob =
            serviceScope.launch {
                while (isActive) {
                    delay(15_000)
                    when (storageMonitor.checkAvailableStorage().level) {
                        StorageCheckLevel.LOW -> updateNotification()
                        StorageCheckLevel.CRITICAL -> {
                            recordingStateManager.onRecordingError("Storage full — stopping recording to protect your audio")
                            stopRecording()
                            break
                        }
                        StorageCheckLevel.OK -> Unit
                    }
                }
            }
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createStartingNotification(): Notification {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val contentPendingIntent =
            launchIntent?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_mic)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .setColorized(true)
            .setColor(android.graphics.Color.parseColor("#D32F2F"))
            .setContentTitle(getString(R.string.rec_notification_starting_title))
            .setContentText(getString(R.string.rec_notification_starting_text))
            .build()
    }

    private fun createNotification(): Notification {
        val state = recordingStateManager.state.value
        val isPaused = state is RecordingState.Paused
        val duration = recordingStateManager.getCurrentDurationMs()
        val durationText = formatDuration(duration)

        // Tap notification → open the app
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        val contentPendingIntent =
            launchIntent?.let {
                PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        // Done action (always present)
        val doneIntent =
            Intent(this, RecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
        val donePendingIntent =
            PendingIntent.getService(
                this,
                1,
                doneIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val builder =
            NotificationCompat
                .Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_mic)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPendingIntent)
                .setColorized(true)
                .setColor(android.graphics.Color.parseColor("#D32F2F")) // Material Red 700

        if (isPaused) {
            builder.setContentTitle("Recording paused")
            builder.setContentText(durationText)

            // Resume action
            val resumeIntent =
                Intent(this, RecordingService::class.java).apply {
                    action = ACTION_RESUME_RECORDING
                }
            val resumePendingIntent =
                PendingIntent.getService(
                    this,
                    2,
                    resumeIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(R.drawable.ic_notif_resume, "Resume", resumePendingIntent)
            builder.addAction(R.drawable.ic_notif_done, "Done", donePendingIntent)
        } else {
            builder.setContentTitle("Recording")
            builder.setContentText(durationText)
            // Use chronometer for live timer — setWhen to the effective start time
            builder.setUsesChronometer(true)
            builder.setWhen(System.currentTimeMillis() - duration)

            // Pause action
            val pauseIntent =
                Intent(this, RecordingService::class.java).apply {
                    action = ACTION_PAUSE_RECORDING
                }
            val pausePendingIntent =
                PendingIntent.getService(
                    this,
                    3,
                    pauseIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(R.drawable.ic_notif_pause, "Pause", pausePendingIntent)
            builder.addAction(R.drawable.ic_notif_done, "Done", donePendingIntent)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when recording is in progress"
                setShowBadge(false)
                // No sound — this is a persistent status notification
                setSound(null, null)
                enableVibration(false)
            }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun formatDuration(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / 1000 / 60) % 60
        val hours = ms / 1000 / 3600

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel_v2"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_RECORDING = "dev.chirpboard.app.ACTION_START_RECORDING"
        const val ACTION_PAUSE_RECORDING = "dev.chirpboard.app.ACTION_PAUSE_RECORDING"
        const val ACTION_RESUME_RECORDING = "dev.chirpboard.app.ACTION_RESUME_RECORDING"
        const val ACTION_STOP_RECORDING = "dev.chirpboard.app.ACTION_STOP_RECORDING"
        const val ACTION_CANCEL_RECORDING = "dev.chirpboard.app.ACTION_CANCEL_RECORDING"
        const val ACTION_RESTART_RECORDING = "dev.chirpboard.app.ACTION_RESTART_RECORDING"
        const val EXTRA_ORIGIN = "extra_origin"
        const val EXTRA_PROFILE_ID = "extra_profile_id"

        @Volatile
        @VisibleForTesting
        var activeInstanceForTest: RecordingService? = null

        fun startRecording(
            context: Context,
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null,
        ) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_START_RECORDING
                    putExtra(EXTRA_ORIGIN, origin.name)
                    profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
                }
            context.startForegroundService(intent)
        }

        fun pauseRecording(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_PAUSE_RECORDING
                }
            context.startService(intent)
        }

        fun resumeRecording(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_RESUME_RECORDING
                }
            context.startService(intent)
        }

        fun stopRecording(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_STOP_RECORDING
                }
            context.startService(intent)
        }

        fun cancelRecording(context: Context) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_CANCEL_RECORDING
                }
            context.startService(intent)
        }

        fun restartRecording(
            context: Context,
            origin: RecordingOrigin = RecordingOrigin.APP,
            profileId: UUID? = null,
        ) {
            val intent =
                Intent(context, RecordingService::class.java).apply {
                    action = ACTION_RESTART_RECORDING
                    putExtra(EXTRA_ORIGIN, origin.name)
                    profileId?.let { putExtra(EXTRA_PROFILE_ID, it.toString()) }
                }
            context.startService(intent)
        }
    }
}
